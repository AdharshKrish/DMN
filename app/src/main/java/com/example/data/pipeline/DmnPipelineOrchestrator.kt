package com.example.data.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.drive.DriveSyncSummary
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.drive.GoogleDriveSyncService
import com.example.data.gemini.GeminiApiException
import com.example.data.gemini.GeminiDmnService
import com.example.data.gemini.GeminiStepUpdate
import com.example.data.gemini.RetryControlChannel
import com.example.data.local.JobStatus
import com.example.data.local.ProcessingJobDao
import com.example.data.local.ProcessingJobEntity
import com.example.data.local.SyncLogDao
import com.example.data.local.SyncLogEntity
import com.example.data.local.SyncRecordDao
import com.example.data.prefs.VaultSettingsRepository
import com.example.data.vault.ArchivedAudioItem
import com.example.data.vault.ArchivedTranscript
import com.example.data.vault.InboxAudioFile
import com.example.data.vault.InboxTextFile
import com.example.data.vault.KnowledgeGraphData
import com.example.data.vault.ObsidianVaultRepository
import com.example.worker.DmnProcessWorker
import com.example.worker.DriveSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    RETRYING,
    COMPLETED,
    FAILED
}

data class PipelineStepProgress(
    val id: String,
    val title: String,
    val detail: String,
    val status: StepStatus = StepStatus.PENDING
)

data class ActiveProcessingProgress(
    val fileName: String,
    val inputKind: String,
    val modelId: String,
    val currentApiCall: String,
    val currentStatusText: String,
    val isRetrying: Boolean = false,
    val attemptNumber: Int = 1,
    val lastHttpStatusOrError: String? = null,
    val requestPayloadSummary: String? = null,
    val lastResponsePayload: String? = null,
    val pendingOverrideModel: String? = null,
    val attemptHistory: List<String> = emptyList(),
    val steps: List<PipelineStepProgress> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis()
)

class DmnPipelineOrchestrator(
    private val context: Context,
    private val settingsRepo: VaultSettingsRepository,
    private val vaultRepo: ObsidianVaultRepository,
    private val geminiService: GeminiDmnService,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val driveSyncService: GoogleDriveSyncService,
    private val jobDao: ProcessingJobDao,
    private val syncRecordDao: SyncRecordDao,
    private val logDao: SyncLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()
    private val syncMutex = Mutex()

    private val _inboxFiles = MutableStateFlow<List<InboxAudioFile>>(emptyList())
    val inboxFiles: StateFlow<List<InboxAudioFile>> = _inboxFiles.asStateFlow()

    private val _inboxTextFiles = MutableStateFlow<List<InboxTextFile>>(emptyList())
    val inboxTextFiles: StateFlow<List<InboxTextFile>> = _inboxTextFiles.asStateFlow()

    private val _archivedTranscripts = MutableStateFlow<List<ArchivedTranscript>>(emptyList())
    val archivedTranscripts: StateFlow<List<ArchivedTranscript>> = _archivedTranscripts.asStateFlow()

    private val _archivedAudioFiles = MutableStateFlow<List<ArchivedAudioItem>>(emptyList())
    val archivedAudioFiles: StateFlow<List<ArchivedAudioItem>> = _archivedAudioFiles.asStateFlow()

    private val _graphData = MutableStateFlow(KnowledgeGraphData())
    val graphData: StateFlow<KnowledgeGraphData> = _graphData.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isSyncingDrive = MutableStateFlow(false)
    val isSyncingDrive: StateFlow<Boolean> = _isSyncingDrive.asStateFlow()

    private val _liveStatusMessage = MutableStateFlow<String?>(null)
    val liveStatusMessage: StateFlow<String?> = _liveStatusMessage.asStateFlow()

    private val _activeProcessingProgress = MutableStateFlow<ActiveProcessingProgress?>(null)
    val activeProcessingProgress: StateFlow<ActiveProcessingProgress?> = _activeProcessingProgress.asStateFlow()

    private val _completionMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val completionMessages: SharedFlow<String> = _completionMessages.asSharedFlow()

    private val retryControlRef = AtomicReference<RetryControlChannel?>(null)
    @Volatile
    private var batchOverrideModel: String? = null

    fun switchModelForActiveRetry(newModelId: String, retryImmediately: Boolean = true) {
        val clean = newModelId.trim()
        if (clean.isBlank()) return
        batchOverrideModel = clean
        retryControlRef.get()?.requestModelSwitch(clean, interruptActiveCall = retryImmediately)
        val timeTag = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val current = _activeProcessingProgress.value ?: return
        val note = if (retryImmediately) {
            "[$timeTag] Switched model to $clean (retrying immediately)"
        } else {
            "[$timeTag] Queued model $clean for next retry"
        }
        _activeProcessingProgress.value = current.copy(
            pendingOverrideModel = clean,
            attemptHistory = (current.attemptHistory + note).takeLast(25)
        )
    }

    fun triggerImmediateRetryNow() {
        val timeTag = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        retryControlRef.get()?.requestImmediateRetry()
        val current = _activeProcessingProgress.value ?: return
        _activeProcessingProgress.value = current.copy(
            attemptHistory = (current.attemptHistory + "[$timeTag] Manual immediate retry requested").takeLast(25)
        )
    }

    suspend fun refreshVaultAndInbox() {
        val settings = settingsRepo.currentSettings()
        val treeUri = settings.vaultTreeUri
        if (!settings.isVaultConfigured || treeUri.isNullOrBlank()) {
            _inboxFiles.value = emptyList()
            _inboxTextFiles.value = emptyList()
            _archivedTranscripts.value = emptyList()
            _archivedAudioFiles.value = emptyList()
            _graphData.value = KnowledgeGraphData()
            return
        }

        // Automatically provision any newly added vault folders (e.g. 04_Text_Inbox, 98_Transcript_Archive)
        vaultRepo.ensureRequiredFolders(treeUri)

        if (settings.audioRetentionDays > 0) {
            val pruned = vaultRepo.pruneExpiredArchivedAudio(treeUri, settings.audioRetentionDays).getOrDefault(0)
            if (pruned > 0) {
                logDao.insertLog(
                    SyncLogEntity(
                        category = "ARCHIVE",
                        level = "INFO",
                        message = "Pruned $pruned expired audio file(s) older than ${settings.audioRetentionDays} day(s) from 99_Audio_Archive.",
                        details = "Retention Window: ${settings.audioRetentionDays} day(s)\nFolder: ${ObsidianVaultRepository.DIR_AUDIO_ARCHIVE}\nFiles Deleted: $pruned"
                    )
                )
            }
        }

        val audioFiles = vaultRepo.listInboxAudioFiles(treeUri)
        _inboxFiles.value = audioFiles
        audioFiles.forEach { file ->
            if (jobDao.getJobByUri(file.uri.toString()) == null) {
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

        val textFiles = vaultRepo.listInboxTextFiles(treeUri)
        _inboxTextFiles.value = textFiles
        textFiles.forEach { file ->
            if (jobDao.getJobByUri(file.uri.toString()) == null) {
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

        _archivedTranscripts.value = vaultRepo.listArchivedTranscripts(treeUri)
        _archivedAudioFiles.value = vaultRepo.listArchivedAudioFiles(treeUri)
        _graphData.value = vaultRepo.parseKnowledgeGraph(treeUri)
    }

    suspend fun pruneExpiredAudioNow(): Result<Int> {
        val settings = settingsRepo.currentSettings()
        val treeUri = settings.vaultTreeUri
            ?: return Result.failure(IllegalStateException("Select your ObsidianVault folder first."))
        if (settings.audioRetentionDays <= 0) {
            return Result.success(0)
        }
        val res = vaultRepo.pruneExpiredArchivedAudio(treeUri, settings.audioRetentionDays)
        res.onSuccess { count ->
            if (count > 0) {
                logDao.insertLog(
                    SyncLogEntity(
                        category = "ARCHIVE",
                        level = "SUCCESS",
                        message = "Manually pruned $count expired audio recording(s) older than ${settings.audioRetentionDays} day(s).",
                        details = "Retention Window: ${settings.audioRetentionDays} day(s)\nFolder: ${ObsidianVaultRepository.DIR_AUDIO_ARCHIVE}\nFiles Deleted: $count"
                    )
                )
            }
            refreshVaultAndInbox()
        }
        return res
    }

    suspend fun deleteCompleteSession(
        dailyNotePath: String? = null,
        sessionHeading: String? = null,
        transcriptFileName: String? = null,
        audioFileName: String? = null,
        jobAudioUri: String? = null,
        jobFileName: String? = null
    ): Result<ObsidianVaultRepository.DeletedSessionArtifacts> = processMutex.withLock {
        val settings = settingsRepo.currentSettings()
        val treeUri = settings.vaultTreeUri
            ?: return@withLock Result.failure(IllegalStateException("Select your ObsidianVault folder first."))

        val result = vaultRepo.deleteCompleteDmnSession(
            treeUriString = treeUri,
            dailyNotePath = dailyNotePath,
            sessionHeading = sessionHeading,
            transcriptFileName = transcriptFileName,
            audioFileName = audioFileName
        )

        result.onSuccess { artifacts ->
            if (!jobAudioUri.isNullOrBlank()) {
                jobDao.deleteJobByUri(jobAudioUri)
            }
            val namesToMatch = (artifacts.audioNamesRemoved + artifacts.transcriptNamesRemoved +
                listOfNotNull(audioFileName, transcriptFileName, jobFileName)).distinct()
            namesToMatch.forEach { name ->
                jobDao.deleteJobsByFileOrArchive(archivedName = name, fileName = name)
            }
            artifacts.deletedPaths.forEach { relPath ->
                syncRecordDao.deleteByPath(relPath)
            }
            if (!dailyNotePath.isNullOrBlank() && artifacts.dailyNoteDeletedCompletely) {
                syncRecordDao.deleteByPath(dailyNotePath)
            }
            val label = sessionHeading?.removePrefix("##")?.trim()
                ?: transcriptFileName
                ?: audioFileName
                ?: jobFileName
                ?: "DMN Session"
            val summaryDetails = buildString {
                appendLine("Session Target: $label")
                if (!dailyNotePath.isNullOrBlank()) appendLine("Daily Note: $dailyNotePath (deletedCompletely=${artifacts.dailyNoteDeletedCompletely})")
                appendLine("Deleted Vault Files (${artifacts.deletedPaths.size}):")
                if (artifacts.deletedPaths.isEmpty()) {
                    appendLine("  (session entry removed from daily note; no separate archive files matched)")
                } else {
                    artifacts.deletedPaths.forEach { appendLine("  - $it") }
                }
            }
            logDao.insertLog(
                SyncLogEntity(
                    category = "VAULT",
                    level = "INFO",
                    message = "Deleted DMN session \"$label\" (${artifacts.deletedPaths.size} archive/note file(s) removed).",
                    details = summaryDetails.trim()
                )
            )
            refreshVaultAndInbox()
        }.onFailure { err ->
            logDao.insertLog(
                SyncLogEntity(
                    category = "VAULT",
                    level = "ERROR",
                    message = "Failed deleting DMN session: ${err.message}",
                    details = err.stackTraceToString().take(2000)
                )
            )
        }
        result
    }

    fun enqueueBackgroundProcessing(
        triggerImmediateIfOnline: Boolean = true,
        singleTargetUri: String? = null
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<DmnProcessWorker>()
            .setConstraints(constraints)
        if (triggerImmediateIfOnline) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        if (!singleTargetUri.isNullOrBlank()) {
            builder.setInputData(workDataOf(DmnProcessWorker.KEY_SINGLE_TARGET_URI to singleTargetUri))
        }
        val policy = when {
            !singleTargetUri.isNullOrBlank() -> ExistingWorkPolicy.APPEND_OR_REPLACE
            triggerImmediateIfOnline -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.KEEP
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dmn_audio_processing",
            policy,
            builder.build()
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
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual_drive_sync",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun processInboxQueue(
        singleTargetUri: String? = null,
        awaitAutoSync: Boolean = false
    ): Result<Int> {
        val settings = settingsRepo.currentSettings()
        var processedCount = 0
        var shouldAutoSync = false
        val queueResult = processMutex.withLock {
            val treeUri = settings.vaultTreeUri
                ?: return@withLock Result.failure<Int>(IllegalStateException("Select your ObsidianVault folder first."))
            if (settings.geminiApiKey.isBlank()) {
                return@withLock Result.failure<Int>(IllegalStateException("Enter your Google AI Studio API key first."))
            }

            _isProcessing.value = true
            batchOverrideModel = null
            val retryControl = RetryControlChannel()
            retryControlRef.set(retryControl)
            try {
            vaultRepo.ensureRequiredFolders(treeUri)
            val allAudio = vaultRepo.listInboxAudioFiles(treeUri)
            val allText = vaultRepo.listInboxTextFiles(treeUri)
            val audioTargets = if (singleTargetUri != null) {
                allAudio.filter { it.uri.toString() == singleTargetUri }
            } else {
                allAudio
            }
            val textTargets = if (singleTargetUri != null) {
                allText.filter { it.uri.toString() == singleTargetUri }
            } else {
                allText
            }
            if (audioTargets.isEmpty() && textTargets.isEmpty()) {
                refreshVaultAndInbox()
                return@withLock Result.success(0)
            }

            val existingNodes = vaultRepo.scanExistingGraphNodes(treeUri)

            // 1. Process Audio Inbox Items
            for (audio in audioTargets) {
                val nowMs = System.currentTimeMillis()
                val startModel = batchOverrideModel ?: settings.geminiModel
                val timeTag = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
                try {
                    updateJob(audio.uri.toString(), audio.name, audio.sizeBytes, JobStatus.UPLOADING, null, null, null, null, nowMs)
                    _liveStatusMessage.value = "Uploading ${audio.name}..."
                    _activeProcessingProgress.value = ActiveProcessingProgress(
                        fileName = audio.name,
                        inputKind = "Voice Audio",
                        modelId = startModel,
                        currentApiCall = "Local SAF Scan (${existingNodes.size} graph nodes indexed)",
                        currentStatusText = "Preparing audio upload to Gemini Files API...",
                        requestPayloadSummary = "Local File: ${audio.name} (${audio.sizeBytes / 1024} KB, ${audio.mimeType})",
                        attemptHistory = listOf("[$timeTag] Indexed ${existingNodes.size} graph hub(s); starting pipeline with $startModel"),
                        steps = listOf(
                            PipelineStepProgress("scan_graph", "Index Knowledge Graph", "Loaded ${existingNodes.size} existing [[WikiLink]] hubs", StepStatus.COMPLETED),
                            PipelineStepProgress("upload_start", "Initiate Resumable Upload", "POST /upload/v1beta/files (start)", StepStatus.PENDING),
                            PipelineStepProgress("upload_bytes", "Stream Audio Payload", "Streaming ${audio.sizeBytes / 1024} KB (${audio.mimeType})", StepStatus.PENDING),
                            PipelineStepProgress("file_active", "Index Audio Remotely", "Waiting for Gemini Files state=ACTIVE", StepStatus.PENDING),
                            PipelineStepProgress("generate", "Gemini DMN Synthesis", "POST /v1beta/models/$startModel:generateContent", StepStatus.PENDING),
                            PipelineStepProgress("cleanup_remote", "Clean Temporary Remote File", "DELETE /v1beta/files/{id}", StepStatus.PENDING),
                            PipelineStepProgress("save_vault", "Save Daily Note & Archives", "Write 99_Audio_Archive, 98_Transcript_Archive (.md), 01_Daily_Notes", StepStatus.PENDING)
                        ),
                        startedAtMs = nowMs
                    )

                    val timeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                    val generation = geminiService.processAudioToDmnMarkdown(
                        apiKey = settings.geminiApiKey,
                        modelId = startModel,
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
                            updateJob(audio.uri.toString(), audio.name, audio.sizeBytes, status, null, null, null, null, nowMs)
                        },
                        onStatusDetail = { detail ->
                            _liveStatusMessage.value = "${audio.name}: $detail"
                        },
                        onStepUpdate = { update ->
                            applyGeminiStepUpdate(update)
                        },
                        retryControl = retryControl
                    )

                    applyVaultStep(
                        stepId = "save_vault",
                        apiCall = "SAF DocumentFile Write (Local ObsidianVault)",
                        statusText = "Archiving audio, writing .md transcript & Daily Note...",
                        status = StepStatus.IN_PROGRESS
                    )

                    val archivedAudioName = vaultRepo.archiveProcessedAudio(
                        treeUriString = treeUri,
                        sourceUri = audio.uri,
                        sourceName = audio.name
                    ).getOrThrow()

                    val transcriptFileName = "${archivedAudioName.substringBeforeLast('.')}.md"
                    val archivedTranscriptName = vaultRepo.archiveRawTranscript(
                        treeUriString = treeUri,
                        rawText = generation.rawTranscript,
                        preferredFileName = transcriptFileName
                    ).getOrThrow()

                    val dailyPath = vaultRepo.appendDmnEntryToDailyNote(
                        treeUriString = treeUri,
                        dateStr = dateStr,
                        entryMarkdown = generation.markdown,
                        archivedAudioName = archivedAudioName,
                        archivedTranscriptName = archivedTranscriptName
                    ).getOrThrow()

                    applyVaultStep(
                        stepId = "save_vault",
                        apiCall = "Saved $dailyPath + $archivedTranscriptName",
                        statusText = "Completed ($archivedTranscriptName & $dailyPath)",
                        status = StepStatus.COMPLETED
                    )

                    val linksCsv = generation.extractedWikiLinks.joinToString(", ")
                    updateJob(
                        itemUri = audio.uri.toString(),
                        fileName = audio.name,
                        sizeBytes = audio.sizeBytes,
                        status = JobStatus.COMPLETED,
                        error = null,
                        outputNotePath = dailyPath,
                        archivedAudioName = archivedAudioName,
                        linkedNodesCsv = linksCsv,
                        createdAtMs = nowMs
                    )
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "SUCCESS",
                            message = "Transcribed ${audio.name} -> $dailyPath + $archivedTranscriptName (${generation.extractedWikiLinks.size} WikiLinks via ${generation.modelUsed})",
                            details = buildString {
                                appendLine("Input Audio: ${audio.name} (${audio.sizeBytes} bytes, ${audio.mimeType})")
                                appendLine("Daily Note: $dailyPath")
                                appendLine("Archived Audio: ${ObsidianVaultRepository.DIR_AUDIO_ARCHIVE}/$archivedAudioName")
                                appendLine("Archived Transcript: ${ObsidianVaultRepository.DIR_TRANSCRIPT_ARCHIVE}/$archivedTranscriptName")
                                appendLine("WikiLinks (${generation.extractedWikiLinks.size}): $linksCsv")
                                if (generation.debugDetails.isNotBlank()) {
                                    appendLine("---")
                                    append(generation.debugDetails)
                                }
                            }.trim()
                        )
                    )
                    processedCount++
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: "${e.javaClass.simpleName}: Failed processing ${audio.name}"
                    val debug = (e as? GeminiApiException)?.debugDetails ?: buildString {
                        appendLine("Input Audio: ${audio.name} (${audio.sizeBytes} bytes)")
                        appendLine("Model: ${settings.geminiModel}")
                        appendLine("Exception: ${e.javaClass.name}")
                        append(e.stackTraceToString().take(2500))
                    }.trim()
                    updateJob(audio.uri.toString(), audio.name, audio.sizeBytes, JobStatus.FAILED, msg, null, null, null, nowMs)
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "ERROR",
                            message = "${audio.name}: $msg",
                            details = debug
                        )
                    )
                }
            }

            // 2. Process Text Inbox Items
            for (textFile in textTargets) {
                val nowMs = System.currentTimeMillis()
                val startModel = batchOverrideModel ?: settings.geminiModel
                val timeTag = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
                try {
                    updateJob(textFile.uri.toString(), textFile.name, textFile.sizeBytes, JobStatus.SYNTHESIZING, null, null, null, null, nowMs)
                    _liveStatusMessage.value = "Synthesizing ${textFile.name}..."
                    _activeProcessingProgress.value = ActiveProcessingProgress(
                        fileName = textFile.name,
                        inputKind = "Text Note",
                        modelId = startModel,
                        currentApiCall = "Reading local note ${textFile.name}",
                        currentStatusText = "Loading text input from 04_Text_Inbox...",
                        requestPayloadSummary = "Local File: ${textFile.name} (${textFile.sizeBytes} bytes)",
                        attemptHistory = listOf("[$timeTag] Indexed ${existingNodes.size} graph hub(s); starting text synthesis with $startModel"),
                        steps = listOf(
                            PipelineStepProgress("scan_graph", "Index Knowledge Graph", "Loaded ${existingNodes.size} existing [[WikiLink]] hubs", StepStatus.COMPLETED),
                            PipelineStepProgress("read_input", "Load Text Input", "Reading ${textFile.name} (${textFile.sizeBytes} bytes)", StepStatus.IN_PROGRESS),
                            PipelineStepProgress("generate", "Gemini DMN Synthesis", "POST /v1beta/models/$startModel:generateContent", StepStatus.PENDING),
                            PipelineStepProgress("save_vault", "Save Daily Note & Archive .md", "Write 98_Transcript_Archive (.md) & 01_Daily_Notes", StepStatus.PENDING)
                        ),
                        startedAtMs = nowMs
                    )

                    val rawText = vaultRepo.readTextByUri(textFile.uri)
                    require(rawText.isNotBlank()) { "${textFile.name} is empty." }
                    applyVaultStep(
                        stepId = "read_input",
                        apiCall = "SAF DocumentFile Read (${textFile.name})",
                        statusText = "Loaded ${rawText.length} characters",
                        status = StepStatus.COMPLETED
                    )

                    val timeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                    val generation = geminiService.processTextToDmnMarkdown(
                        apiKey = settings.geminiApiKey,
                        modelId = startModel,
                        rawText = rawText,
                        existingNodes = existingNodes,
                        timestampStr = timeStr,
                        onStageChanged = { stageName ->
                            val status = runCatching { JobStatus.valueOf(stageName) }
                                .getOrDefault(JobStatus.SYNTHESIZING)
                            _liveStatusMessage.value = "${status.displayLabel}: ${textFile.name}"
                            updateJob(textFile.uri.toString(), textFile.name, textFile.sizeBytes, status, null, null, null, null, nowMs)
                        },
                        onStatusDetail = { detail ->
                            _liveStatusMessage.value = "${textFile.name}: $detail"
                        },
                        onStepUpdate = { update ->
                            applyGeminiStepUpdate(update)
                        },
                        retryControl = retryControl
                    )

                    applyVaultStep(
                        stepId = "save_vault",
                        apiCall = "SAF DocumentFile Write (Local ObsidianVault)",
                        statusText = "Archiving .md transcript & updating Daily Note...",
                        status = StepStatus.IN_PROGRESS
                    )

                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val stemWithStamp = if (Regex("""^\d{8}_\d{6}_.*""").matches(textFile.name)) {
                        textFile.name.substringBeforeLast('.')
                    } else {
                        "${stamp}_${textFile.name.substringBeforeLast('.')}"
                    }
                    val preferredTranscriptName = "$stemWithStamp.md"
                    val archivedTranscriptName = vaultRepo.archiveRawTranscript(
                        treeUriString = treeUri,
                        rawText = rawText,
                        preferredFileName = preferredTranscriptName,
                        sourceUriToDelete = textFile.uri
                    ).getOrThrow()

                    val dailyPath = vaultRepo.appendDmnEntryToDailyNote(
                        treeUriString = treeUri,
                        dateStr = dateStr,
                        entryMarkdown = generation.markdown,
                        archivedAudioName = null,
                        archivedTranscriptName = archivedTranscriptName
                    ).getOrThrow()

                    applyVaultStep(
                        stepId = "save_vault",
                        apiCall = "Saved $dailyPath + $archivedTranscriptName",
                        statusText = "Completed ($archivedTranscriptName & $dailyPath)",
                        status = StepStatus.COMPLETED
                    )

                    val linksCsv = generation.extractedWikiLinks.joinToString(", ")
                    updateJob(
                        itemUri = textFile.uri.toString(),
                        fileName = textFile.name,
                        sizeBytes = textFile.sizeBytes,
                        status = JobStatus.COMPLETED,
                        error = null,
                        outputNotePath = dailyPath,
                        archivedAudioName = archivedTranscriptName,
                        linkedNodesCsv = linksCsv,
                        createdAtMs = nowMs
                    )
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "SUCCESS",
                            message = "Synthesized text ${textFile.name} -> $dailyPath + $archivedTranscriptName (${generation.extractedWikiLinks.size} WikiLinks via ${generation.modelUsed})",
                            details = buildString {
                                appendLine("Input Text Note: ${textFile.name} (${textFile.sizeBytes} bytes)")
                                appendLine("Daily Note: $dailyPath")
                                appendLine("Archived Markdown: ${ObsidianVaultRepository.DIR_TRANSCRIPT_ARCHIVE}/$archivedTranscriptName")
                                appendLine("WikiLinks (${generation.extractedWikiLinks.size}): $linksCsv")
                                if (generation.debugDetails.isNotBlank()) {
                                    appendLine("---")
                                    append(generation.debugDetails)
                                }
                            }.trim()
                        )
                    )
                    processedCount++
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: "${e.javaClass.simpleName}: Failed processing ${textFile.name}"
                    val debug = (e as? GeminiApiException)?.debugDetails ?: buildString {
                        appendLine("Input Text Note: ${textFile.name} (${textFile.sizeBytes} bytes)")
                        appendLine("Model: ${settings.geminiModel}")
                        appendLine("Exception: ${e.javaClass.name}")
                        append(e.stackTraceToString().take(2500))
                    }.trim()
                    updateJob(textFile.uri.toString(), textFile.name, textFile.sizeBytes, JobStatus.FAILED, msg, null, null, null, nowMs)
                    logDao.insertLog(
                        SyncLogEntity(
                            category = "GEMINI",
                            level = "ERROR",
                            message = "${textFile.name}: $msg",
                            details = debug
                        )
                    )
                }
            }

            refreshVaultAndInbox()
            shouldAutoSync = processedCount > 0 && settings.autoDriveSync && settings.isDriveConfigured
            if (processedCount > 0) {
                _completionMessages.tryEmit("Synthesized $processedCount DMN session(s) into Daily Notes & Archives.")
            } else if (audioTargets.isEmpty() && textTargets.isEmpty()) {
                _completionMessages.tryEmit("No audio or text items waiting in inbox.")
            } else {
                _completionMessages.tryEmit("DMN processing finished with errors. Check Activity & Sync Log.")
            }
            Result.success(processedCount)
            } finally {
                retryControlRef.set(null)
                batchOverrideModel = null
                _isProcessing.value = false
                _liveStatusMessage.value = null
                _activeProcessingProgress.value = null
            }
        }

        if (shouldAutoSync) {
            if (awaitAutoSync) {
                runDriveSync()
            } else {
                enqueueOneTimeDriveSync(wifiOnly = settings.syncWifiOnly)
            }
        }
        return queueResult
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
            val result = driveSyncService.syncVault(
                accessToken = token,
                vaultTreeUri = treeUri,
                driveRootFolderId = folderId,
                syncDirection = settings.syncDirection,
                conflictStrategy = settings.conflictStrategy,
                onStatus = { status -> _liveStatusMessage.value = status }
            )
            result.onSuccess { sum ->
                settingsRepo.setLastDriveSyncMs(System.currentTimeMillis())
                refreshVaultAndInbox()
                val deletedPart = if (sum.deletedRemoteCount > 0) ", ${sum.deletedRemoteCount} deleted" else ""
                _completionMessages.tryEmit(
                    "${sum.syncMode}: ${sum.uploadedCount} uploaded, ${sum.downloadedCount} downloaded$deletedPart, ${sum.skippedUnchanged} unchanged"
                )
            }.onFailure { err ->
                _completionMessages.tryEmit(err.message ?: "Google Drive sync failed.")
            }
            result
        } catch (e: Exception) {
            val msg = e.message ?: "Drive sync failed"
            logDao.insertLog(
                SyncLogEntity(
                    category = "DRIVE",
                    level = "ERROR",
                    message = msg,
                    details = buildString {
                        appendLine("Drive Root Folder ID: $folderId")
                        appendLine("Sync Direction: ${settings.syncDirection.name}")
                        appendLine("Exception: ${e.javaClass.name}")
                        append(e.stackTraceToString().take(2000))
                    }.trim()
                )
            )
            _completionMessages.tryEmit(msg)
            Result.failure(e)
        } finally {
            _isSyncingDrive.value = false
            _liveStatusMessage.value = null
        }
    }

    private fun applyGeminiStepUpdate(update: GeminiStepUpdate) {
        val current = _activeProcessingProgress.value ?: return
        val newStepStatus = runCatching { StepStatus.valueOf(update.stepStatus) }.getOrDefault(StepStatus.IN_PROGRESS)
        val updatedSteps = current.steps.map { step ->
            if (step.id == update.stepId) {
                step.copy(detail = update.statusText, status = newStepStatus)
            } else {
                step
            }
        }
        val activeModel = update.modelId?.takeIf { it.isNotBlank() } ?: current.modelId
        val nextPendingOverride = if (current.pendingOverrideModel != null && activeModel == current.pendingOverrideModel) {
            null
        } else {
            current.pendingOverrideModel
        }
        val updatedHistory = if (!update.historyEvent.isNullOrBlank()) {
            val timeTag = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            (current.attemptHistory + "[$timeTag] ${update.historyEvent}").takeLast(25)
        } else {
            current.attemptHistory
        }

        _activeProcessingProgress.value = current.copy(
            modelId = activeModel,
            currentApiCall = update.apiCall,
            currentStatusText = update.statusText,
            isRetrying = newStepStatus == StepStatus.RETRYING,
            attemptNumber = update.attemptNumber,
            lastHttpStatusOrError = update.errorDetail ?: if (newStepStatus == StepStatus.COMPLETED) null else current.lastHttpStatusOrError,
            requestPayloadSummary = update.requestSummary ?: current.requestPayloadSummary,
            lastResponsePayload = update.responsePayload ?: current.lastResponsePayload,
            pendingOverrideModel = nextPendingOverride,
            attemptHistory = updatedHistory,
            steps = updatedSteps
        )
    }

    private fun applyVaultStep(
        stepId: String,
        apiCall: String,
        statusText: String,
        status: StepStatus
    ) {
        val current = _activeProcessingProgress.value ?: return
        val updatedSteps = current.steps.map { step ->
            if (step.id == stepId) {
                step.copy(detail = statusText, status = status)
            } else {
                step
            }
        }
        _activeProcessingProgress.value = current.copy(
            currentApiCall = apiCall,
            currentStatusText = statusText,
            isRetrying = false,
            steps = updatedSteps
        )
    }

    private suspend fun updateJob(
        itemUri: String,
        fileName: String,
        sizeBytes: Long,
        status: JobStatus,
        error: String?,
        outputNotePath: String?,
        archivedAudioName: String?,
        linkedNodesCsv: String?,
        createdAtMs: Long
    ) {
        val prev = jobDao.getJobByUri(itemUri)
        jobDao.upsertJob(
            ProcessingJobEntity(
                audioUri = itemUri,
                fileName = fileName,
                sizeBytes = sizeBytes,
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
