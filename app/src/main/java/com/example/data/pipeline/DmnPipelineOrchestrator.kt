package com.example.data.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.drive.DriveSyncSummary
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.drive.GoogleDriveSyncService
import com.example.data.gemini.GeminiDmnService
import com.example.data.local.JobStatus
import com.example.data.local.ProcessingJobDao
import com.example.data.local.ProcessingJobEntity
import com.example.data.local.SyncLogDao
import com.example.data.local.SyncLogEntity
import com.example.data.prefs.VaultSettingsRepository
import com.example.data.vault.InboxAudioFile
import com.example.data.vault.KnowledgeGraphData
import com.example.data.vault.ObsidianVaultRepository
import com.example.worker.DmnProcessWorker
import com.example.worker.DriveSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DmnPipelineOrchestrator(
    private val context: Context,
    private val settingsRepo: VaultSettingsRepository,
    private val vaultRepo: ObsidianVaultRepository,
    private val geminiService: GeminiDmnService,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val driveSyncService: GoogleDriveSyncService,
    private val jobDao: ProcessingJobDao,
    private val logDao: SyncLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()
    private val syncMutex = Mutex()

    private val _inboxFiles = MutableStateFlow<List<InboxAudioFile>>(emptyList())
    val inboxFiles: StateFlow<List<InboxAudioFile>> = _inboxFiles.asStateFlow()

    private val _graphData = MutableStateFlow(KnowledgeGraphData())
    val graphData: StateFlow<KnowledgeGraphData> = _graphData.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isSyncingDrive = MutableStateFlow(false)
    val isSyncingDrive: StateFlow<Boolean> = _isSyncingDrive.asStateFlow()

    private val _liveStatusMessage = MutableStateFlow<String?>(null)
    val liveStatusMessage: StateFlow<String?> = _liveStatusMessage.asStateFlow()

    suspend fun refreshVaultAndInbox() {
        val settings = settingsRepo.currentSettings()
        if (!settings.isVaultConfigured) {
            _inboxFiles.value = emptyList()
            _graphData.value = KnowledgeGraphData()
            return
        }
        val files = vaultRepo.listInboxAudioFiles(settings.vaultTreeUri)
        _inboxFiles.value = files
        // Ensure queued jobs exist for any newly discovered recordings (e.g., from Obsidian Core Audio Recorder)
        files.forEach { file ->
            val existing = jobDao.getJobByUri(file.uri.toString())
            if (existing == null) {
                jobDao.upsertJob(
                    ProcessingJobEntity(
                        audioUri = file.uri.toString(),
                        fileName = file.name,
                        sizeBytes = file.sizeBytes,
                        status = JobStatus.QUEUED.name
                    )
                )
            }
        }
        _graphData.value = vaultRepo.parseKnowledgeGraph(settings.vaultTreeUri)
    }

    fun enqueueBackgroundProcessing(triggerImmediateIfOnline: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DmnProcessWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dmn_audio_processing",
            if (triggerImmediateIfOnline) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodicDriveSync(enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork("periodic_drive_sync")
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            "periodic_drive_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueOneTimeDriveSync(wifiOnly: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual_drive_sync",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun processInboxQueue(singleTargetUri: String? = null): Result<Int> = processMutex.withLock {
        val settings = settingsRepo.currentSettings()
        val treeUri = settings.vaultTreeUri
            ?: return@withLock Result.failure(IllegalStateException("Select your ObsidianVault folder first."))
        if (settings.geminiApiKey.isBlank()) {
            return@withLock Result.failure(IllegalStateException("Enter your Google AI Studio API key first."))
        }

        _isProcessing.value = true
        var processedCount = 0
        try {
            val allInbox = vaultRepo.listInboxAudioFiles(treeUri)
            val targets = if (singleTargetUri != null) {
                allInbox.filter { it.uri.toString() == singleTargetUri }
            } else {
                allInbox
            }
            if (targets.isEmpty()) {
                refreshVaultAndInbox()
                return@withLock Result.success(0)
            }

            val existingNodes = vaultRepo.scanExistingGraphNodes(treeUri)
            for (audio in targets) {
                val uriKey = audio.uri.toString()
                val nowMs = System.currentTimeMillis()
                try {
                    updateJob(audio, JobStatus.UPLOADING, null, null, null, null, nowMs)
                    _liveStatusMessage.value = "Uploading ${audio.name}..."

                    val timeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                    val generation = geminiService.processAudioToDmnMarkdown(
                        apiKey = settings.geminiApiKey,
                        modelId = settings.geminiModel,
                        audioUri = audio.uri,
                        displayName = audio.name,
                        mimeType = audio.mimeType,
                        sizeBytes = audio.sizeBytes,
                        existingNodes = existingNodes,
                        timestampStr = timeStr,
                        onStageChanged = { stageName ->
                            val status = runCatching { JobStatus.valueOf(stageName) }
                                .getOrDefault(JobStatus.SYNTHESIZING)
                            _liveStatusMessage.value = "${status.displayLabel}: ${audio.name}"
                            updateJob(audio, status, null, null, null, null, nowMs)
                        }
                    )

                    val archivedName = vaultRepo.archiveProcessedAudio(
                        treeUriString = treeUri,
                        sourceUri = audio.uri,
                        sourceName = audio.name
                    ).getOrThrow()

                    val dailyPath = vaultRepo.appendDmnEntryToDailyNote(
                        treeUriString = treeUri,
                        dateStr = dateStr,
                        entryMarkdown = generation.markdown,
                        archivedAudioName = archivedName
                    ).getOrThrow()

                    val linksCsv = generation.extractedWikiLinks.joinToString(", ")
                    updateJob(
                        audio = audio,
                        status = JobStatus.COMPLETED,
                        error = null,
                        outputNotePath = dailyPath,
                        archivedAudioName = archivedName,
                        linkedNodesCsv = linksCsv,
                        createdAtMs = nowMs
                    )
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "SUCCESS",
                            message = "Transcribed ${audio.name} -> $dailyPath (${generation.extractedWikiLinks.size} WikiLinks via ${generation.modelUsed})"
                        )
                    )
                    processedCount++
                } catch (e: Exception) {
                    val msg = e.message ?: "Failed processing ${audio.name}"
                    updateJob(audio, JobStatus.FAILED, msg, null, null, null, nowMs)
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "ERROR",
                            message = "${audio.name}: $msg"
                        )
                    )
                }
            }

            refreshVaultAndInbox()
            if (processedCount > 0 && settings.autoDriveSync && settings.isDriveConfigured) {
                scope.launch { runDriveSync() }
            }
            Result.success(processedCount)
        } finally {
            _isProcessing.value = false
            _liveStatusMessage.value = null
        }
    }

    suspend fun runDriveSync(explicitAccessToken: String? = null): Result<DriveSyncSummary> = syncMutex.withLock {
        val settings = settingsRepo.currentSettings()
        val treeUri = settings.vaultTreeUri
            ?: return@withLock Result.failure(IllegalStateException("Select your ObsidianVault folder first."))
        val folderId = settings.driveFolderId
            ?: return@withLock Result.failure(IllegalStateException("Choose a Google Drive folder in the Sync tab."))

        _isSyncingDrive.value = true
        return@withLock try {
            val token = explicitAccessToken
                ?: driveAuthManager.fetchFreshAccessTokenSilently().getOrThrow()
            val result = driveSyncService.runTwoWaySync(
                accessToken = token,
                vaultTreeUri = treeUri,
                driveRootFolderId = folderId,
                conflictStrategy = settings.conflictStrategy,
                onStatus = { status -> _liveStatusMessage.value = status }
            )
            if (result.isSuccess) {
                settingsRepo.setLastDriveSyncMs(System.currentTimeMillis())
                refreshVaultAndInbox()
            }
            result
        } catch (e: Exception) {
            logDao.insertLog(
                SyncLogEntity(
                    category = "DRIVE",
                    level = "ERROR",
                    message = e.message ?: "Drive sync failed"
                )
            )
            Result.failure(e)
        } finally {
            _isSyncingDrive.value = false
            _liveStatusMessage.value = null
        }
    }

    private suspend fun updateJob(
        audio: InboxAudioFile,
        status: JobStatus,
        error: String?,
        outputNotePath: String?,
        archivedAudioName: String?,
        linkedNodesCsv: String?,
        createdAtMs: Long
    ) {
        val prev = jobDao.getJobByUri(audio.uri.toString())
        jobDao.upsertJob(
            ProcessingJobEntity(
                audioUri = audio.uri.toString(),
                fileName = audio.name,
                sizeBytes = audio.sizeBytes,
                status = status.name,
                errorMessage = error,
                outputNotePath = outputNotePath ?: prev?.outputNotePath,
                archivedAudioName = archivedAudioName ?: prev?.archivedAudioName,
                linkedNodesCsv = linkedNodesCsv ?: prev?.linkedNodesCsv,
                createdAtMs = prev?.createdAtMs ?: createdAtMs,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }
}
