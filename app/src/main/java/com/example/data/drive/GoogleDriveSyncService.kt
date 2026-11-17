package com.example.data.drive

import com.example.data.local.SyncLogDao
import com.example.data.local.SyncLogEntity
import com.example.data.local.SyncRecordDao
import com.example.data.local.SyncRecordEntity
import com.example.data.prefs.ConflictStrategy
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
    val errorCount: Int = 0
)

private data class DriveRemoteFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTimeMs: Long,
    val sizeBytes: Long,
    val md5Checksum: String?,
    val parentId: String
)

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
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
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
        onStatus: suspend (String) -> Unit = {}
    ): Result<DriveSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            log(
                level = "INFO",
                message = "Starting two-way Google Drive sync...",
                details = "Endpoint: $DRIVE_API\nDrive Target Folder ID: $driveRootFolderId\nConflict Strategy: ${conflictStrategy.name}"
            )
            onStatus("Indexing local Obsidian vault...")
            val localFiles = vaultRepo.listAllSyncableFiles(vaultTreeUri)
                .associateBy { it.relativePath }

            onStatus("Indexing Google Drive folder...")
            val folderIdByRelPath = mutableMapOf("" to driveRootFolderId)
            val remoteFiles = mutableMapOf<String, DriveRemoteFile>()
            fetchDriveTreeRecursive(
                accessToken = accessToken,
                folderId = driveRootFolderId,
                prefix = "",
                folderMap = folderIdByRelPath,
                fileMap = remoteFiles
            )

            val previousRecords = syncRecordDao.getAllRecords().associateBy { it.relativePath }
            val allPaths = (localFiles.keys + remoteFiles.keys).sorted()

            var uploaded = 0
            var downloaded = 0
            var conflicts = 0
            var unchanged = 0
            var errors = 0

            for (path in allPaths) {
                val local = localFiles[path]
                val remote = remoteFiles[path]
                val prev = previousRecords[path]

                try {
                    when {
                        local != null && remote == null -> {
                            // Local exists, remote missing
                            if (prev != null && !hasLocalChanged(local, prev)) {
                                // Remote was removed after last sync -> keep safe or skip? Re-upload if newly recorded, otherwise upload
                                onStatus("Uploading $path...")
                                val created = uploadNewFile(accessToken, local, driveRootFolderId, folderIdByRelPath)
                                saveRecord(local, created)
                                uploaded++
                            } else {
                                onStatus("Uploading $path...")
                                val created = uploadNewFile(accessToken, local, driveRootFolderId, folderIdByRelPath)
                                saveRecord(local, created)
                                uploaded++
                            }
                        }

                        local == null && remote != null -> {
                            onStatus("Downloading $path...")
                            val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
                            saveRecord(written, remote)
                            downloaded++
                        }

                        local != null && remote != null -> {
                            if (prev == null) {
                                // First time seeing both sides
                                val sameSize = local.sizeBytes == remote.sizeBytes
                                val closeTime = abs(local.lastModifiedMs - remote.modifiedTimeMs) <= 4000L
                                if (sameSize && closeTime) {
                                    saveRecord(local, remote)
                                    unchanged++
                                } else {
                                    when (resolveByTimestampOrCopy(
                                        accessToken, vaultTreeUri, path, local, remote, conflictStrategy
                                    )) {
                                        SyncAction.UPLOADED -> uploaded++
                                        SyncAction.DOWNLOADED -> downloaded++
                                        SyncAction.CONFLICT_COPIED -> {
                                            conflicts++
                                            uploaded++
                                        }
                                    }
                                }
                            } else {
                                val localChanged = hasLocalChanged(local, prev)
                                val remoteChanged = hasRemoteChanged(remote, prev)
                                when {
                                    !localChanged && !remoteChanged -> unchanged++
                                    localChanged && !remoteChanged -> {
                                        onStatus("Uploading update: $path")
                                        val updatedRemote = updateDriveFileContent(accessToken, remote.id, local)
                                        saveRecord(local, updatedRemote)
                                        uploaded++
                                    }
                                    !localChanged && remoteChanged -> {
                                        onStatus("Downloading update: $path")
                                        val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
                                        saveRecord(written, remote)
                                        downloaded++
                                    }
                                    else -> {
                                        onStatus("Resolving conflict: $path")
                                        when (resolveByTimestampOrCopy(
                                            accessToken, vaultTreeUri, path, local, remote, conflictStrategy
                                        )) {
                                            SyncAction.UPLOADED -> uploaded++
                                            SyncAction.DOWNLOADED -> downloaded++
                                            SyncAction.CONFLICT_COPIED -> {
                                                conflicts++
                                                uploaded++
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
                        details = "Path: $path\nDrive Folder ID: $driveRootFolderId\nEndpoint: $DRIVE_API\nError: ${e.javaClass.name}: ${e.message}"
                    )
                }
            }

            val summary = DriveSyncSummary(
                uploadedCount = uploaded,
                downloadedCount = downloaded,
                conflictsResolved = conflicts,
                skippedUnchanged = unchanged,
                errorCount = errors
            )
            log(
                level = if (errors == 0) "SUCCESS" else "INFO",
                message = "Sync complete: $uploaded uploaded, $downloaded downloaded, $conflicts conflicts, $unchanged unchanged",
                details = "Drive Root Folder ID: $driveRootFolderId\nUploaded: $uploaded\nDownloaded: $downloaded\nConflicts Resolved: $conflicts\nUnchanged: $unchanged\nErrors: $errors"
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
        strategy: ConflictStrategy
    ): SyncAction {
        return when (strategy) {
            ConflictStrategy.KEEP_NEWER -> {
                if (local.lastModifiedMs >= remote.modifiedTimeMs) {
                    val updatedRemote = updateDriveFileContent(accessToken, remote.id, local)
                    saveRecord(local, updatedRemote)
                    SyncAction.UPLOADED
                } else {
                    val written = downloadFileToVault(accessToken, vaultTreeUri, path, remote)
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
                val updatedRemote = updateDriveFileContent(accessToken, remote.id, local)
                saveRecord(local, updatedRemote)
                SyncAction.CONFLICT_COPIED
            }
        }
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

        // Step 1: Create metadata placeholder
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

        // Step 2: Stream file bytes with media upload
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
            if (!resp.isSuccessful) throw IOException("Drive upload failed (${resp.code}): $respStr")
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

    private fun hasRemoteChanged(remote: DriveRemoteFile, prev: SyncRecordEntity): Boolean {
        val md5Changed = remote.md5Checksum != null && prev.remoteMd5 != null && remote.md5Checksum != prev.remoteMd5
        val timeChanged = abs(remote.modifiedTimeMs - prev.remoteModifiedMs) > 2500L
        return md5Changed || timeChanged
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
