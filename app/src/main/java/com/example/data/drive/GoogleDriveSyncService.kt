package com.example.data.drive

import com.example.data.local.SyncLogDao
import com.example.data.local.SyncLogEntity
import com.example.data.local.SyncRecordDao
import com.example.data.local.SyncRecordEntity
import com.example.data.prefs.ConflictStrategy
import com.example.data.prefs.SyncDirection
import com.example.data.vault.ObsidianVaultRepository
import com.example.data.vault.VaultFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class DriveFolderItem(
    val id: String,
    val name: String,
    val modifiedTime: String
)

data class DriveSyncSummary(
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val conflictsResolved: Int = 0,
    val skippedUnchanged: Int = 0,
    val errorCount: Int = 0,
    val syncMode: String = SyncDirection.PHONE_TO_DRIVE.label,
    val manifestBootstrapped: Boolean = false,
    val remoteChangesEvaluated: Int = 0
)

internal data class DriveRemoteFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTimeMs: Long,
    val sizeBytes: Long,
    val md5Checksum: String?,
    val parentId: String
)

internal data class ManifestFileEntry(
    val id: String,
    val mimeType: String,
    val localModifiedMs: Long,
    val remoteModifiedMs: Long,
    val sizeBytes: Long,
    val md5Checksum: String? = null
)

internal data class DriveSyncManifest(
    val version: Int = 1,
    var updatedAtMs: Long = 0L,
    var driveStartPageToken: String? = null,
    val folders: MutableMap<String, String> = mutableMapOf(),
    val files: MutableMap<String, ManifestFileEntry> = mutableMapOf()
)

private class DriveHttpException(
    val code: Int,
    message: String
) : IOException(message)

class GoogleDriveSyncService(
    private val vaultRepo: ObsidianVaultRepository,
    private val syncRecordDao: SyncRecordDao,
    private val syncLogDao: SyncLogDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
        private const val DRIVE_CHANGES_API = "https://www.googleapis.com/drive/v3/changes"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val MANIFEST_FILE_NAME = ".synapse_sync_manifest.json"

        internal fun parseManifestJson(rawJson: String): DriveSyncManifest? {
            if (rawJson.isBlank()) return null
            return runCatching {
                val root = JSONObject(rawJson)
                val folders = mutableMapOf<String, String>()
                root.optJSONObject("folders")?.let { fObj ->
                    fObj.keys().forEach { key ->
                        val id = fObj.optString(key, "").trim()
                        if (id.isNotBlank()) folders[key] = id
                    }
                }
                val files = mutableMapOf<String, ManifestFileEntry>()
                root.optJSONObject("files")?.let { filesObj ->
                    filesObj.keys().forEach { path ->
                        val item = filesObj.optJSONObject(path) ?: return@forEach
                        val id = item.optString("id", "").trim()
                        if (id.isBlank()) return@forEach
                        files[path] = ManifestFileEntry(
                            id = id,
                            mimeType = item.optString("mimeType", "application/octet-stream"),
                            localModifiedMs = item.optLong("localModifiedMs", 0L),
                            remoteModifiedMs = item.optLong("remoteModifiedMs", 0L),
                            sizeBytes = item.optLong("sizeBytes", 0L),
                            md5Checksum = item.optString("md5").takeIf { it.isNotBlank() }
                        )
                    }
                }
                DriveSyncManifest(
                    version = root.optInt("version", 1),
                    updatedAtMs = root.optLong("updatedAtMs", 0L),
                    driveStartPageToken = root.optString("driveStartPageToken").takeIf { it.isNotBlank() },
                    folders = folders,
                    files = files
                )
            }.getOrNull()
        }

        internal fun serializeManifestJson(manifest: DriveSyncManifest): String {
            val root = JSONObject()
                .put("version", manifest.version)
                .put("updatedAtMs", manifest.updatedAtMs)
            if (!manifest.driveStartPageToken.isNullOrBlank()) {
                root.put("driveStartPageToken", manifest.driveStartPageToken)
            }
            val foldersObj = JSONObject()
            manifest.folders.toSortedMap().forEach { (relPath, id) ->
                foldersObj.put(relPath, id)
            }
            root.put("folders", foldersObj)

            val filesObj = JSONObject()
            manifest.files.toSortedMap().forEach { (relPath, entry) ->
                val item = JSONObject()
                    .put("id", entry.id)
                    .put("mimeType", entry.mimeType)
                    .put("localModifiedMs", entry.localModifiedMs)
                    .put("remoteModifiedMs", entry.remoteModifiedMs)
                    .put("sizeBytes", entry.sizeBytes)
                if (!entry.md5Checksum.isNullOrBlank()) {
                    item.put("md5", entry.md5Checksum)
                }
                filesObj.put(relPath, item)
            }
            root.put("files", filesObj)
            return root.toString()
        }

        internal fun hasLocalChangedAgainstManifest(
            localSizeBytes: Long,
            localModifiedMs: Long,
            manifestEntry: ManifestFileEntry?,
            prevRecord: SyncRecordEntity?
        ): Boolean {
            if (manifestEntry == null) return true
            val sizeChanged = localSizeBytes != manifestEntry.sizeBytes
            if (sizeChanged) return true

            val baselineLocalModMs = when {
                manifestEntry.localModifiedMs > 0L -> manifestEntry.localModifiedMs
                prevRecord != null && prevRecord.localModifiedMs > 0L -> prevRecord.localModifiedMs
                else -> 0L
            }
            return if (baselineLocalModMs > 0L) {
                abs(localModifiedMs - baselineLocalModMs) > 2500L
            } else {
                abs(localModifiedMs - manifestEntry.remoteModifiedMs) > 4000L
            }
        }

        internal fun hasRemoteChangedAgainstBaseline(
            remote: ManifestFileEntry,
            prevRecord: SyncRecordEntity
        ): Boolean {
            val md5Changed = remote.md5Checksum != null &&
                prevRecord.remoteMd5 != null &&
                remote.md5Checksum != prevRecord.remoteMd5
            val timeChanged = abs(remote.remoteModifiedMs - prevRecord.remoteModifiedMs) > 2500L
            val idChanged = remote.id != prevRecord.driveFileId
            return md5Changed || timeChanged || idChanged
        }
    }

    suspend fun listRootFolders(accessToken: String): Result<List<DriveFolderItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val query = "mimeType='$FOLDER_MIME' and trashed=false and 'root' in parents"
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val url = "$DRIVE_API?q=$encodedQ&fields=files(id,name,modifiedTime)&orderBy=modifiedTime%20desc&pageSize=50"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Drive folder list failed (${resp.code}): $body")
                val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            DriveFolderItem(
                                id = obj.getString("id"),
                                name = obj.getString("name"),
                                modifiedTime = obj.optString("modifiedTime", "")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun createDriveFolder(
        accessToken: String,
        folderName: String,
        parentId: String = "root"
    ): Result<DriveFolderItem> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = JSONObject()
                .put("name", folderName)
                .put("mimeType", FOLDER_MIME)
                .put("parents", JSONArray().put(parentId))

            val req = Request.Builder()
                .url("$DRIVE_API?fields=id,name,modifiedTime")
                .header("Authorization", "Bearer $accessToken")
                .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed creating Drive folder (${resp.code}): $body")
                val obj = JSONObject(body)
                DriveFolderItem(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    modifiedTime = obj.optString("modifiedTime", "")
                )
            }
        }
    }

    suspend fun runTwoWaySync(
        accessToken: String,
        vaultTreeUri: String,
        driveRootFolderId: String,
        conflictStrategy: ConflictStrategy,
        syncDirection: SyncDirection = SyncDirection.TWO_WAY,
        onStatus: suspend (String) -> Unit = {}
    ): Result<DriveSyncSummary> = syncVault(
        accessToken = accessToken,
        vaultTreeUri = vaultTreeUri,
        driveRootFolderId = driveRootFolderId,
        syncDirection = syncDirection,
        conflictStrategy = conflictStrategy,
        onStatus = onStatus
    )

    suspend fun syncVault(
        accessToken: String,
        vaultTreeUri: String,
        driveRootFolderId: String,
        syncDirection: SyncDirection,
        conflictStrategy: ConflictStrategy,
        onStatus: suspend (String) -> Unit = {}
    ): Result<DriveSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            log(
                level = "INFO",
                message = "Starting ${syncDirection.label} sync (manifest delta)...",
                details = buildString {
                    appendLine("Endpoint: $DRIVE_API")
                    appendLine("Sync Mode: ${syncDirection.name} (${syncDirection.label})")
                    appendLine("Drive Target Folder ID: $driveRootFolderId")
                    append("Conflict Strategy: ${conflictStrategy.name}")
                }
            )

            onStatus("Indexing local vault timestamps...")
            val localFiles = vaultRepo.listAllSyncableFiles(vaultTreeUri)
                .associateBy { it.relativePath }
            val previousRecords = syncRecordDao.getAllRecords().associateBy { it.relativePath }

            onStatus("Reading Drive metadata manifest...")
            var manifestFileId = findManifestFileId(accessToken, driveRootFolderId)
            var manifest = manifestFileId?.let { id ->
                runCatching { downloadManifest(accessToken, id) }.getOrNull()
            }

            var bootstrapped = false
            var manifestDirty = false
            var deltaChangesCount = 0

            if (manifest == null) {
                bootstrapped = true
                onStatus("Bootstrapping Drive metadata manifest (one-time scan)...")
                val folderMap = mutableMapOf<String, String>()
                val remoteMap = mutableMapOf<String, DriveRemoteFile>()
                fetchDriveTreeRecursive(
                    accessToken = accessToken,
                    folderId = driveRootFolderId,
                    prefix = "",
                    folderMap = folderMap,
                    fileMap = remoteMap
                )
                val token = runCatching { fetchStartPageToken(accessToken) }.getOrNull()
                manifest = DriveSyncManifest(
                    version = 1,
                    updatedAtMs = System.currentTimeMillis(),
                    driveStartPageToken = token,
                    folders = folderMap,
                    files = remoteMap.mapValuesTo(mutableMapOf()) { (path, remote) ->
                        val prev = previousRecords[path]
                        val knownLocalMod = if (prev != null && prev.driveFileId == remote.id) {
                            prev.localModifiedMs
                        } else {
                            0L
                        }
                        ManifestFileEntry(
                            id = remote.id,
                            mimeType = remote.mimeType,
                            localModifiedMs = knownLocalMod,
                            remoteModifiedMs = remote.modifiedTimeMs,
                            sizeBytes = remote.sizeBytes,
                            md5Checksum = remote.md5Checksum
                        )
                    }
                )
                manifestDirty = true
            } else if (syncDirection == SyncDirection.TWO_WAY) {
                onStatus("Checking remote Drive delta changes...")
                val existingToken = manifest.driveStartPageToken
                if (existingToken != null) {
                    val deltaResult = applyDriveChangesDelta(
                        accessToken = accessToken,
                        driveRootFolderId = driveRootFolderId,
                        manifest = manifest
                    )
                    if (deltaResult == null) {
                        // Page token expired (HTTP 410): refresh full remote tree once
                        manifest.folders.clear()
                        manifest.files.clear()
                        val remoteMap = mutableMapOf<String, DriveRemoteFile>()
                        fetchDriveTreeRecursive(
                            accessToken = accessToken,
                            folderId = driveRootFolderId,
                            prefix = "",
                            folderMap = manifest.folders,
                            fileMap = remoteMap
                        )
                        remoteMap.forEach { (path, remote) ->
                            val prev = previousRecords[path]
                            manifest.files[path] = ManifestFileEntry(
                                id = remote.id,
                                mimeType = remote.mimeType,
                                localModifiedMs = if (prev?.driveFileId == remote.id) prev.localModifiedMs else 0L,
                                remoteModifiedMs = remote.modifiedTimeMs,
                                sizeBytes = remote.sizeBytes,
                                md5Checksum = remote.md5Checksum
                            )
                        }
                        manifest.driveStartPageToken = runCatching { fetchStartPageToken(accessToken) }.getOrNull()
                        manifestDirty = true
                    } else {
                        deltaChangesCount = deltaResult
                        if (deltaChangesCount > 0 || manifest.driveStartPageToken != existingToken) {
                            manifestDirty = true
                        }
                    }
                } else {
                    manifest.driveStartPageToken = runCatching { fetchStartPageToken(accessToken) }.getOrNull()
                    manifestDirty = true
                }
            }

            var uploaded = 0
            var downloaded = 0
            var conflicts = 0
            var unchanged = 0
            var errors = 0
            val changedPathsLog = mutableListOf<String>()

            if (syncDirection == SyncDirection.PHONE_TO_DRIVE) {
                // Fast Phone -> Drive path: only evaluate local files against manifest timestamps/sizes
                for (path in localFiles.keys.sorted()) {
                    val local = localFiles[path]!!
                    val manifestEntry = manifest.files[path]
                    val prev = previousRecords[path]

                    try {
                        if (manifestEntry == null) {
                            onStatus("Uploading new file: $path")
                            val created = uploadNewFile(accessToken, local, driveRootFolderId, manifest.folders)
                            val updatedEntry = created.toManifestEntry(local.lastModifiedMs)
                            manifest.files[path] = updatedEntry
                            saveRecord(local, created)
                            uploaded++
                            manifestDirty = true
                            changedPathsLog.add("UPLOAD (new): $path")
                        } else {
                            val localChanged = hasLocalChangedAgainstManifest(
                                localSizeBytes = local.sizeBytes,
                                localModifiedMs = local.lastModifiedMs,
                                manifestEntry = manifestEntry,
                                prevRecord = prev
                            )
                            if (!localChanged) {
                                if (prev == null) {
                                    // Record locally so Room mirrors manifest without uploading
                                    saveRecord(
                                        local = local,
                                        remote = manifestEntry.toRemoteFile(path)
                                    )
                                }
                                unchanged++
                            } else {
                                onStatus("Uploading modified file: $path")
                                val updatedRemote = uploadUpdatedOrRecreate(
                                    accessToken = accessToken,
                                    driveFileId = manifestEntry.id,
                                    local = local,
                                    rootDriveFolderId = driveRootFolderId,
                                    folderMap = manifest.folders
                                )
                                manifest.files[path] = updatedRemote.toManifestEntry(local.lastModifiedMs)
                                saveRecord(local, updatedRemote)
                                uploaded++
                                manifestDirty = true
                                changedPathsLog.add("UPLOAD (updated): $path")
                            }
                        }
                    } catch (e: Exception) {
                        errors++
                        log(
                            level = "ERROR",
                            message = "Sync failed for $path: ${e.message}",
                            details = "Mode: ${syncDirection.name}\nPath: $path\nDrive Folder ID: $driveRootFolderId\nError: ${e.javaClass.name}: ${e.message}"
                        )
                    }
                }
            } else {
                // Two-Way Sync path using manifest + delta changes
                val allPaths = (localFiles.keys + manifest.files.keys).sorted()
                for (path in allPaths) {
                    val local = localFiles[path]
                    val remoteEntry = manifest.files[path]
                    val prev = previousRecords[path]

                    try {
                        when {
                            local != null && remoteEntry == null -> {
                                onStatus("Uploading new file: $path")
                                val created = uploadNewFile(accessToken, local, driveRootFolderId, manifest.folders)
                                manifest.files[path] = created.toManifestEntry(local.lastModifiedMs)
                                saveRecord(local, created)
                                uploaded++
                                manifestDirty = true
                                changedPathsLog.add("UPLOAD (new): $path")
                            }

                            local == null && remoteEntry != null -> {
                                onStatus("Downloading remote file: $path")
                                val remote = remoteEntry.toRemoteFile(path)
                                val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
                                manifest.files[path] = remoteEntry.copy(localModifiedMs = written.lastModifiedMs)
                                saveRecord(written, remote)
                                downloaded++
                                manifestDirty = true
                                changedPathsLog.add("DOWNLOAD (new): $path")
                            }

                            local != null && remoteEntry != null -> {
                                val remote = remoteEntry.toRemoteFile(path)
                                if (prev == null) {
                                    val sameSize = local.sizeBytes == remote.sizeBytes
                                    val closeTime = abs(local.lastModifiedMs - remote.modifiedTimeMs) <= 4000L
                                    if (sameSize && closeTime) {
                                        manifest.files[path] = remoteEntry.copy(localModifiedMs = local.lastModifiedMs)
                                        saveRecord(local, remote)
                                        unchanged++
                                        manifestDirty = true
                                    } else {
                                        when (resolveByTimestampOrCopy(
                                            accessToken, vaultTreeUri, path, local, remote, conflictStrategy, manifest.folders, manifest.files
                                        )) {
                                            SyncAction.UPLOADED -> {
                                                uploaded++
                                                manifestDirty = true
                                                changedPathsLog.add("UPLOAD (baseline): $path")
                                            }
                                            SyncAction.DOWNLOADED -> {
                                                downloaded++
                                                manifestDirty = true
                                                changedPathsLog.add("DOWNLOAD (baseline): $path")
                                            }
                                            SyncAction.CONFLICT_COPIED -> {
                                                conflicts++
                                                uploaded++
                                                manifestDirty = true
                                                changedPathsLog.add("CONFLICT (copied both): $path")
                                            }
                                        }
                                    }
                                } else {
                                    val localChanged = hasLocalChanged(local, prev)
                                    val remoteChanged = hasRemoteChangedAgainstBaseline(remoteEntry, prev)
                                    when {
                                        !localChanged && !remoteChanged -> unchanged++
                                        localChanged && !remoteChanged -> {
                                            onStatus("Uploading update: $path")
                                            val updatedRemote = uploadUpdatedOrRecreate(
                                                accessToken, remote.id, local, driveRootFolderId, manifest.folders
                                            )
                                            manifest.files[path] = updatedRemote.toManifestEntry(local.lastModifiedMs)
                                            saveRecord(local, updatedRemote)
                                            uploaded++
                                            manifestDirty = true
                                            changedPathsLog.add("UPLOAD (updated): $path")
                                        }
                                        !localChanged && remoteChanged -> {
                                            onStatus("Downloading update: $path")
                                            val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
                                            manifest.files[path] = remoteEntry.copy(localModifiedMs = written.lastModifiedMs)
                                            saveRecord(written, remote)
                                            downloaded++
                                            manifestDirty = true
                                            changedPathsLog.add("DOWNLOAD (updated): $path")
                                        }
                                        else -> {
                                            onStatus("Resolving conflict: $path")
                                            when (resolveByTimestampOrCopy(
                                                accessToken, vaultTreeUri, path, local, remote, conflictStrategy, manifest.folders, manifest.files
                                            )) {
                                                SyncAction.UPLOADED -> {
                                                    uploaded++
                                                    manifestDirty = true
                                                    changedPathsLog.add("CONFLICT -> UPLOADED: $path")
                                                }
                                                SyncAction.DOWNLOADED -> {
                                                    downloaded++
                                                    manifestDirty = true
                                                    changedPathsLog.add("CONFLICT -> DOWNLOADED: $path")
                                                }
                                                SyncAction.CONFLICT_COPIED -> {
                                                    conflicts++
                                                    uploaded++
                                                    manifestDirty = true
                                                    changedPathsLog.add("CONFLICT -> KEPT BOTH: $path")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errors++
                        log(
                            level = "ERROR",
                            message = "Sync failed for $path: ${e.message}",
                            details = "Mode: ${syncDirection.name}\nPath: $path\nDrive Folder ID: $driveRootFolderId\nError: ${e.javaClass.name}: ${e.message}"
                        )
                    }
                }
            }

            if (manifestDirty) {
                onStatus("Updating Drive metadata manifest...")
                manifest.updatedAtMs = System.currentTimeMillis()
                if (syncDirection == SyncDirection.TWO_WAY && manifest.driveStartPageToken.isNullOrBlank()) {
                    manifest.driveStartPageToken = runCatching { fetchStartPageToken(accessToken) }.getOrNull()
                }
                manifestFileId = uploadOrUpdateManifest(
                    accessToken = accessToken,
                    driveRootFolderId = driveRootFolderId,
                    existingManifestFileId = manifestFileId,
                    manifest = manifest
                )
            }

            val summary = DriveSyncSummary(
                uploadedCount = uploaded,
                downloadedCount = downloaded,
                conflictsResolved = conflicts,
                skippedUnchanged = unchanged,
                errorCount = errors,
                syncMode = syncDirection.label,
                manifestBootstrapped = bootstrapped,
                remoteChangesEvaluated = deltaChangesCount
            )
            log(
                level = if (errors == 0) "SUCCESS" else "INFO",
                message = "${syncDirection.label} complete: $uploaded uploaded, $downloaded downloaded, $unchanged unchanged",
                details = buildString {
                    appendLine("Mode: ${syncDirection.label} (${syncDirection.name})")
                    appendLine("Manifest: $MANIFEST_FILE_NAME (${manifest.files.size} tracked files)")
                    appendLine("Manifest Action: ${if (bootstrapped) "Bootstrapped from Drive folder" else if (manifestDirty) "Updated with delta changes" else "Cache hit (no remote manifest write needed)"}")
                    if (syncDirection == SyncDirection.TWO_WAY) {
                        appendLine("Drive Delta Changes Evaluated: $deltaChangesCount")
                    }
                    appendLine("Uploaded: $uploaded | Downloaded: $downloaded | Conflicts: $conflicts | Unchanged: $unchanged | Errors: $errors")
                    if (changedPathsLog.isNotEmpty()) {
                        appendLine("Transferred Files:")
                        changedPathsLog.take(40).forEach { appendLine("  - $it") }
                        if (changedPathsLog.size > 40) {
                            appendLine("  ... and ${changedPathsLog.size - 40} more")
                        }
                    }
                }.trim()
            )
            summary
        }
    }

    private enum class SyncAction { UPLOADED, DOWNLOADED, CONFLICT_COPIED }

    private suspend fun resolveByTimestampOrCopy(
        accessToken: String,
        vaultTreeUri: String,
        path: String,
        local: VaultFileEntry,
        remote: DriveRemoteFile,
        strategy: ConflictStrategy,
        folderMap: MutableMap<String, String>,
        manifestFiles: MutableMap<String, ManifestFileEntry>
    ): SyncAction {
        return when (strategy) {
            ConflictStrategy.KEEP_NEWER -> {
                if (local.lastModifiedMs >= remote.modifiedTimeMs) {
                    val updatedRemote = uploadUpdatedOrRecreate(
                        accessToken, remote.id, local, folderMap[""] ?: "", folderMap
                    )
                    manifestFiles[path] = updatedRemote.toManifestEntry(local.lastModifiedMs)
                    saveRecord(local, updatedRemote)
                    SyncAction.UPLOADED
                } else {
                    val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
                    manifestFiles[path] = remote.toManifestEntry(written.lastModifiedMs)
                    saveRecord(written, remote)
                    SyncAction.DOWNLOADED
                }
            }
            ConflictStrategy.KEEP_BOTH -> {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val ext = path.substringAfterLast('.', "")
                val stem = if (ext.isNotEmpty()) path.removeSuffix(".$ext") else path
                val conflictPath = if (ext.isNotEmpty()) "$stem.conflict-$stamp.$ext" else "$stem.conflict-$stamp"
                downloadFileToVault(accessToken, vaultTreeUri, conflictPath, remote)
                val updatedRemote = uploadUpdatedOrRecreate(
                    accessToken, remote.id, local, folderMap[""] ?: "", folderMap
                )
                manifestFiles[path] = updatedRemote.toManifestEntry(local.lastModifiedMs)
                saveRecord(local, updatedRemote)
                SyncAction.CONFLICT_COPIED
            }
        }
    }

    private fun findManifestFileId(accessToken: String, driveRootFolderId: String): String? {
        val query = "'$driveRootFolderId' in parents and name='$MANIFEST_FILE_NAME' and trashed=false"
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val url = "$DRIVE_API?q=$encodedQ&fields=files(id)&pageSize=1"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files") ?: return null
            if (arr.length() == 0) null else arr.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        }
    }

    private fun downloadManifest(accessToken: String, manifestFileId: String): DriveSyncManifest? {
        val req = Request.Builder()
            .url("$DRIVE_API/$manifestFileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parseManifestJson(resp.body?.string().orEmpty())
        }
    }

    private fun uploadOrUpdateManifest(
        accessToken: String,
        driveRootFolderId: String,
        existingManifestFileId: String?,
        manifest: DriveSyncManifest
    ): String {
        val jsonBytes = serializeManifestJson(manifest).toByteArray(Charsets.UTF_8)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        if (!existingManifestFileId.isNullOrBlank()) {
            val patchReq = Request.Builder()
                .url("$DRIVE_UPLOAD_API/$existingManifestFileId?uploadType=media&fields=id")
                .header("Authorization", "Bearer $accessToken")
                .patch(jsonBytes.toRequestBody(mediaType))
                .build()
            client.newCall(patchReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    return existingManifestFileId
                } else if (resp.code != 404) {
                    throw IOException("Manifest update failed (${resp.code}): ${resp.body?.string().orEmpty()}")
                }
            }
        }

        val metadata = JSONObject()
            .put("name", MANIFEST_FILE_NAME)
            .put("mimeType", "application/json")
            .put("parents", JSONArray().put(driveRootFolderId))
        val createReq = Request.Builder()
            .url("$DRIVE_API?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody(mediaType))
            .build()
        val createdId = client.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Manifest create failed (${resp.code}): $body")
            JSONObject(body).getString("id")
        }

        val uploadReq = Request.Builder()
            .url("$DRIVE_UPLOAD_API/$createdId?uploadType=media&fields=id")
            .header("Authorization", "Bearer $accessToken")
            .patch(jsonBytes.toRequestBody(mediaType))
            .build()
        client.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Manifest content upload failed (${resp.code}): ${resp.body?.string().orEmpty()}")
            }
        }
        return createdId
    }

    private fun fetchStartPageToken(accessToken: String): String? {
        val req = Request.Builder()
            .url("$DRIVE_CHANGES_API/startPageToken")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty()).optString("startPageToken").takeIf { it.isNotBlank() }
        }
    }

    /**
     * Fetches ONLY changed Drive items since manifest.driveStartPageToken.
     * Returns count of relevant vault items evaluated, or null if token expired (HTTP 410).
     */
    private fun applyDriveChangesDelta(
        accessToken: String,
        driveRootFolderId: String,
        manifest: DriveSyncManifest
    ): Int? {
        var pageToken = manifest.driveStartPageToken ?: return null
        val folderIdToPath = mutableMapOf(driveRootFolderId to "").apply {
            manifest.folders.forEach { (path, id) -> put(id, path) }
        }
        val fileIdToPath = manifest.files.entries.associate { it.value.id to it.key }.toMutableMap()

        val rawChanges = mutableListOf<JSONObject>()
        var nextStartToken: String? = null

        while (true) {
            val encodedToken = URLEncoder.encode(pageToken, "UTF-8")
            val url = "$DRIVE_CHANGES_API?pageToken=$encodedToken&includeItemsFromAllDrives=false" +
                "&fields=nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,mimeType,modifiedTime,size,md5Checksum,parents,trashed))&pageSize=200"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 410 || resp.code == 400) return null
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Drive changes check failed (${resp.code}): $body")
                val json = JSONObject(body)
                val arr = json.optJSONArray("changes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    rawChanges.add(arr.getJSONObject(i))
                }
                val nextPage = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                val newStart = json.optString("newStartPageToken").takeIf { it.isNotBlank() }
                if (nextPage != null) {
                    pageToken = nextPage
                } else {
                    nextStartToken = newStart
                    break
                }
            }
        }

        if (nextStartToken != null) {
            manifest.driveStartPageToken = nextStartToken
        }

        var evaluated = 0
        val pendingFolders = mutableListOf<JSONObject>()
        val fileChanges = mutableListOf<JSONObject>()

        for (change in rawChanges) {
            val fileObj = change.optJSONObject("file")
            val mime = fileObj?.optString("mimeType", "").orEmpty()
            if (mime == FOLDER_MIME && !change.optBoolean("removed", false) && !fileObj!!.optBoolean("trashed", false)) {
                pendingFolders.add(change)
            } else {
                fileChanges.add(change)
            }
        }

        // Resolve newly created nested subfolders inside driveRootFolderId iteratively
        var madeProgress = true
        while (pendingFolders.isNotEmpty() && madeProgress) {
            madeProgress = false
            val iter = pendingFolders.iterator()
            while (iter.hasNext()) {
                val ch = iter.next()
                val f = ch.optJSONObject("file") ?: continue
                val id = f.optString("id")
                val name = f.optString("name")
                if (name.startsWith(".")) {
                    iter.remove()
                    continue
                }
                val parents = f.optJSONArray("parents")
                val parentPath = findParentPath(parents, folderIdToPath)
                if (parentPath != null) {
                    val rel = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (!manifest.folders.containsKey(rel)) {
                        manifest.folders[rel] = id
                        folderIdToPath[id] = rel
                        // Also index any files created immediately inside this new folder
                        val subFiles = mutableMapOf<String, DriveRemoteFile>()
                        fetchDriveTreeRecursive(accessToken, id, rel, manifest.folders, subFiles)
                        manifest.folders.forEach { (p, fid) -> folderIdToPath[fid] = p }
                        subFiles.forEach { (p, rf) ->
                            manifest.files[p] = rf.toManifestEntry(manifest.files[p]?.localModifiedMs ?: 0L)
                            fileIdToPath[rf.id] = p
                            evaluated++
                        }
                    }
                    iter.remove()
                    madeProgress = true
                }
            }
        }

        // Apply individual file additions, edits, renames, or deletions
        for (change in fileChanges) {
            val fileId = change.optString("fileId", "").ifBlank {
                change.optJSONObject("file")?.optString("id").orEmpty()
            }
            if (fileId.isBlank()) continue
            val removed = change.optBoolean("removed", false)
            val fileObj = change.optJSONObject("file")
            val trashed = fileObj?.optBoolean("trashed", false) ?: false
            val name = fileObj?.optString("name").orEmpty()

            if (name == MANIFEST_FILE_NAME || name.startsWith(".")) continue

            val existingPath = fileIdToPath[fileId]
            if (removed || trashed || fileObj == null) {
                if (existingPath != null) {
                    manifest.files.remove(existingPath)
                    fileIdToPath.remove(fileId)
                    evaluated++
                }
                continue
            }

            val mime = fileObj.optString("mimeType", "application/octet-stream")
            if (mime.startsWith("application/vnd.google-apps.")) continue

            val parentPath = findParentPath(fileObj.optJSONArray("parents"), folderIdToPath)
            if (parentPath == null) {
                if (existingPath != null) {
                    // File was moved out of synced vault root
                    manifest.files.remove(existingPath)
                    fileIdToPath.remove(fileId)
                    evaluated++
                }
                continue
            }

            val newRelPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            if (existingPath != null && existingPath != newRelPath) {
                manifest.files.remove(existingPath)
            }
            val prevLocalMod = manifest.files[newRelPath]?.localModifiedMs
                ?: existingPath?.let { manifest.files[it]?.localModifiedMs }
                ?: 0L
            manifest.files[newRelPath] = ManifestFileEntry(
                id = fileId,
                mimeType = mime,
                localModifiedMs = prevLocalMod,
                remoteModifiedMs = parseIsoTime(fileObj.optString("modifiedTime")),
                sizeBytes = fileObj.optString("size", "0").toLongOrNull() ?: 0L,
                md5Checksum = fileObj.optString("md5Checksum").takeIf { it.isNotBlank() }
            )
            fileIdToPath[fileId] = newRelPath
            evaluated++
        }

        return evaluated
    }

    private fun findParentPath(parents: JSONArray?, folderIdToPath: Map<String, String>): String? {
        if (parents == null) return null
        for (i in 0 until parents.length()) {
            val pId = parents.optString(i)
            val path = folderIdToPath[pId]
            if (path != null) return path
        }
        return null
    }

    private fun fetchDriveTreeRecursive(
        accessToken: String,
        folderId: String,
        prefix: String,
        folderMap: MutableMap<String, String>,
        fileMap: MutableMap<String, DriveRemoteFile>
    ) {
        var pageToken: String? = null
        do {
            val query = "'$folderId' in parents and trashed=false"
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val tokenPart = if (pageToken != null) "&pageToken=$pageToken" else ""
            val url = "$DRIVE_API?q=$encodedQ&fields=nextPageToken,files(id,name,mimeType,modifiedTime,size,md5Checksum)&pageSize=100$tokenPart"

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Failed listing Drive folder $folderId: $body")
                val json = JSONObject(body)
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                val files = json.optJSONArray("files") ?: JSONArray()
                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    val name = obj.getString("name")
                    val id = obj.getString("id")
                    val mime = obj.optString("mimeType", "application/octet-stream")
                    if (name.startsWith(".")) continue
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == FOLDER_MIME) {
                        folderMap[rel] = id
                        fetchDriveTreeRecursive(accessToken, id, rel, folderMap, fileMap)
                    } else if (!mime.startsWith("application/vnd.google-apps.")) {
                        fileMap[rel] = DriveRemoteFile(
                            id = id,
                            name = name,
                            mimeType = mime,
                            modifiedTimeMs = parseIsoTime(obj.optString("modifiedTime")),
                            sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L,
                            md5Checksum = obj.optString("md5Checksum").takeIf { it.isNotBlank() },
                            parentId = folderId
                        )
                    }
                }
            }
        } while (pageToken != null)
    }

    private suspend fun ensureDriveParentFolder(
        accessToken: String,
        relativePath: String,
        rootDriveFolderId: String,
        folderMap: MutableMap<String, String>
    ): String {
        val parentRel = relativePath.substringBeforeLast('/', "")
        if (parentRel.isEmpty()) return rootDriveFolderId
        folderMap[parentRel]?.let { return it }

        val segments = parentRel.split('/').filter { it.isNotBlank() }
        var currentId = rootDriveFolderId
        var currentPath = ""
        for (segment in segments) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val existing = folderMap[currentPath]
            if (existing != null) {
                currentId = existing
            } else {
                val created = createDriveFolder(accessToken, segment, currentId).getOrThrow()
                folderMap[currentPath] = created.id
                currentId = created.id
            }
        }
        return currentId
    }

    private suspend fun uploadUpdatedOrRecreate(
        accessToken: String,
        driveFileId: String,
        local: VaultFileEntry,
        rootDriveFolderId: String,
        folderMap: MutableMap<String, String>
    ): DriveRemoteFile {
        return try {
            updateDriveFileContent(accessToken, driveFileId, local)
        } catch (httpEx: DriveHttpException) {
            if (httpEx.code == 404 && rootDriveFolderId.isNotBlank()) {
                uploadNewFile(accessToken, local, rootDriveFolderId, folderMap)
            } else {
                throw httpEx
            }
        }
    }

    private suspend fun uploadNewFile(
        accessToken: String,
        local: VaultFileEntry,
        rootDriveFolderId: String,
        folderMap: MutableMap<String, String>
    ): DriveRemoteFile {
        val parentId = ensureDriveParentFolder(accessToken, local.relativePath, rootDriveFolderId, folderMap)
        val fileName = local.relativePath.substringAfterLast('/')
        val metadata = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put(parentId))

        val createReq = Request.Builder()
            .url("$DRIVE_API?fields=id,name,mimeType,modifiedTime,size,md5Checksum")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val createdId = client.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive file metadata creation failed: $body")
            JSONObject(body).getString("id")
        }

        return updateDriveFileContent(accessToken, createdId, local)
    }

    private fun updateDriveFileContent(
        accessToken: String,
        driveFileId: String,
        local: VaultFileEntry
    ): DriveRemoteFile {
        val body = object : RequestBody() {
            override fun contentType() = local.mimeType.toMediaType()
            override fun contentLength() = if (local.sizeBytes > 0) local.sizeBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                val input = vaultRepo.openInputStream(local.uri)
                    ?: throw IOException("Unable to read local file ${local.relativePath}")
                input.use { sink.writeAll(it.source()) }
            }
        }

        val req = Request.Builder()
            .url("$DRIVE_UPLOAD_API/$driveFileId?uploadType=media&fields=id,name,mimeType,modifiedTime,size,md5Checksum,parents")
            .header("Authorization", "Bearer $accessToken")
            .patch(body)
            .build()

        return client.newCall(req).execute().use { resp ->
            val respStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw DriveHttpException(resp.code, "Drive upload failed (${resp.code}): $respStr")
            }
            val obj = JSONObject(respStr)
            DriveRemoteFile(
                id = obj.getString("id"),
                name = obj.getString("name"),
                mimeType = obj.optString("mimeType", local.mimeType),
                modifiedTimeMs = parseIsoTime(obj.optString("modifiedTime")),
                sizeBytes = obj.optString("size", local.sizeBytes.toString()).toLongOrNull() ?: local.sizeBytes,
                md5Checksum = obj.optString("md5Checksum").takeIf { it.isNotBlank() },
                parentId = obj.optJSONArray("parents")?.optString(0).orEmpty()
            )
        }
    }

    private suspend fun downloadFileToVault(
        accessToken: String,
        vaultTreeUri: String,
        relativePath: String,
        remote: DriveRemoteFile
    ): VaultFileEntry {
        val req = Request.Builder()
            .url("$DRIVE_API/${remote.id}?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive download failed (${resp.code}) for $relativePath")
            }
            val stream = resp.body?.byteStream()
                ?: throw IOException("Empty response body downloading $relativePath")
            vaultRepo.writeRelativeBinaryFile(vaultTreeUri, relativePath, remote.mimeType) { out ->
                stream.use { it.copyTo(out) }
            }.getOrThrow()
        }
    }

    private fun hasLocalChanged(local: VaultFileEntry, prev: SyncRecordEntity): Boolean {
        return local.sizeBytes != prev.localSizeBytes || abs(local.lastModifiedMs - prev.localModifiedMs) > 2500L
    }

    private suspend fun saveRecord(local: VaultFileEntry, remote: DriveRemoteFile) {
        syncRecordDao.upsertRecord(
            SyncRecordEntity(
                relativePath = local.relativePath,
                driveFileId = remote.id,
                mimeType = local.mimeType,
                localModifiedMs = local.lastModifiedMs,
                localSizeBytes = local.sizeBytes,
                remoteModifiedMs = remote.modifiedTimeMs,
                remoteMd5 = remote.md5Checksum,
                syncState = "SYNCED",
                lastSyncedMs = System.currentTimeMillis()
            )
        )
    }

    private fun DriveRemoteFile.toManifestEntry(localModifiedMs: Long): ManifestFileEntry {
        return ManifestFileEntry(
            id = id,
            mimeType = mimeType,
            localModifiedMs = localModifiedMs,
            remoteModifiedMs = modifiedTimeMs,
            sizeBytes = sizeBytes,
            md5Checksum = md5Checksum
        )
    }

    private fun ManifestFileEntry.toRemoteFile(relativePath: String): DriveRemoteFile {
        return DriveRemoteFile(
            id = id,
            name = relativePath.substringAfterLast('/'),
            mimeType = mimeType,
            modifiedTimeMs = remoteModifiedMs,
            sizeBytes = sizeBytes,
            md5Checksum = md5Checksum,
            parentId = ""
        )
    }

    private suspend fun log(level: String, message: String, details: String? = null) {
        syncLogDao.insertLog(
            SyncLogEntity(
                category = "DRIVE",
                level = level,
                message = message,
                details = details
            )
        )
    }

    private fun parseIsoTime(iso: String): Long {
        if (iso.isBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(iso)?.time?.let { return it }
            }
        }
        return 0L
    }
}
