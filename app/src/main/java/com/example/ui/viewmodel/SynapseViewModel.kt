package com.example.ui.viewmodel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.SynapseApplication
import com.example.audio.AudioPlaybackState
import com.example.audio.RecordingState
import com.example.data.drive.DriveAuthOutcome
import com.example.data.drive.DriveFolderItem
import com.example.data.local.ProcessingJobEntity
import com.example.data.local.SyncLogEntity
import com.example.data.pipeline.ActiveProcessingProgress
import com.example.data.prefs.ConflictStrategy
import com.example.data.prefs.SyncDirection
import com.example.data.prefs.VaultSettings
import com.example.data.vault.ArchivedAudioItem
import com.example.data.vault.ArchivedTranscript
import com.example.data.vault.InboxAudioFile
import com.example.data.vault.InboxTextFile
import com.example.data.vault.KnowledgeGraphData
import com.example.data.vault.VaultNote
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GeminiTestState {
    data object Idle : GeminiTestState
    data object Testing : GeminiTestState
    data class Success(val message: String) : GeminiTestState
    data class Error(val message: String) : GeminiTestState
}

data class MainUiState(
    val settings: VaultSettings = VaultSettings(),
    val inboxFiles: List<InboxAudioFile> = emptyList(),
    val inboxTextFiles: List<InboxTextFile> = emptyList(),
    val archivedTranscripts: List<ArchivedTranscript> = emptyList(),
    val archivedAudioFiles: List<ArchivedAudioItem> = emptyList(),
    val jobs: List<ProcessingJobEntity> = emptyList(),
    val logs: List<SyncLogEntity> = emptyList(),
    val graphData: KnowledgeGraphData = KnowledgeGraphData(),
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingElapsedMs: Long = 0L,
    val recordingAmplitudes: List<Float> = List(36) { 0.08f },
    val playbackState: AudioPlaybackState = AudioPlaybackState(),
    val isProcessingAudio: Boolean = false,
    val isSyncingDrive: Boolean = false,
    val liveStatusMessage: String? = null,
    val activeProcessingProgress: ActiveProcessingProgress? = null,
    val geminiTestState: GeminiTestState = GeminiTestState.Idle,
    val driveFolders: List<DriveFolderItem> = emptyList(),
    val isLoadingDriveFolders: Boolean = false,
    val driveAuthError: String? = null,
    val selectedNotePath: String? = null,
    val selectedTranscriptPath: String? = null,
    val availableGeminiModels: List<String> = emptyList(),
    val isLoadingGeminiModels: Boolean = false
) {
    val selectedNote: VaultNote?
        get() = selectedNotePath?.let { graphData.notesByPath[it] }

    val selectedTranscript: ArchivedTranscript?
        get() = selectedTranscriptPath?.let { target ->
            archivedTranscripts.firstOrNull {
                it.relativePath == target || it.fileName.equals(target.substringAfterLast('/'), ignoreCase = true)
            }
        }
}

class SynapseViewModel(private val app: SynapseApplication) : ViewModel() {

    private val _geminiTestState = MutableStateFlow<GeminiTestState>(GeminiTestState.Idle)
    private val _driveFolders = MutableStateFlow<List<DriveFolderItem>>(emptyList())
    private val _isLoadingDriveFolders = MutableStateFlow(false)
    private val _driveAuthError = MutableStateFlow<String?>(null)
    private val _selectedNotePath = MutableStateFlow<String?>(null)
    private val _selectedTranscriptPath = MutableStateFlow<String?>(null)

    private val _availableGeminiModels = MutableStateFlow<List<String>>(emptyList())
    private val _isLoadingGeminiModels = MutableStateFlow(false)

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    private val _driveConsentRequests = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 2)
    val driveConsentRequests = _driveConsentRequests.asSharedFlow()

    private data class AuxiliaryState(
        val geminiTest: GeminiTestState,
        val driveFolders: List<DriveFolderItem>,
        val loadingFolders: Boolean,
        val driveError: String?,
        val selectedNotePath: String?,
        val selectedTranscriptPath: String?,
        val availableModels: List<String>,
        val loadingModels: Boolean
    )

    private val auxiliaryFlow = combine(
        _geminiTestState,
        _driveFolders,
        _isLoadingDriveFolders,
        _driveAuthError,
        combine(
            _selectedNotePath,
            _selectedTranscriptPath,
            _availableGeminiModels,
            _isLoadingGeminiModels
        ) { notePath, transcriptPath, models, loadingModels ->
            listOf(notePath, transcriptPath, models, loadingModels)
        }
    ) { testState, folders, loadingFolders, authErr, extra ->
        @Suppress("UNCHECKED_CAST")
        AuxiliaryState(
            geminiTest = testState,
            driveFolders = folders,
            loadingFolders = loadingFolders,
            driveError = authErr,
            selectedNotePath = extra[0] as String?,
            selectedTranscriptPath = extra[1] as String?,
            availableModels = extra[2] as List<String>,
            loadingModels = extra[3] as Boolean
        )
    }

    private data class PipelineFlowState(
        val inbox: List<InboxAudioFile>,
        val inboxText: List<InboxTextFile>,
        val archivedTranscripts: List<ArchivedTranscript>,
        val archivedAudio: List<ArchivedAudioItem>,
        val jobs: List<ProcessingJobEntity>,
        val logs: List<SyncLogEntity>,
        val graph: KnowledgeGraphData,
        val isProc: Boolean,
        val isSync: Boolean,
        val liveMsg: String?,
        val activeProgress: ActiveProcessingProgress?
    )

    private val inboxArchiveFlow = combine(
        app.pipelineOrchestrator.inboxFiles,
        app.pipelineOrchestrator.inboxTextFiles,
        app.pipelineOrchestrator.archivedTranscripts,
        app.pipelineOrchestrator.archivedAudioFiles
    ) { audio, text, transcripts, archivedAudio ->
        listOf(audio, text, transcripts, archivedAudio)
    }

    @Suppress("UNCHECKED_CAST")
    private val pipelineFlow = combine(
        inboxArchiveFlow,
        app.database.processingJobDao().observeAllJobs(),
        app.database.syncLogDao().observeRecentLogs(),
        app.pipelineOrchestrator.graphData,
        combine(
            app.pipelineOrchestrator.isProcessing,
            app.pipelineOrchestrator.isSyncingDrive,
            app.pipelineOrchestrator.liveStatusMessage,
            app.pipelineOrchestrator.activeProcessingProgress
        ) { p, s, m, prog -> listOf(p, s, m, prog) }
    ) { vaultItems, jobs, logs, graph, statusList ->
        PipelineFlowState(
            inbox = vaultItems[0] as List<InboxAudioFile>,
            inboxText = vaultItems[1] as List<InboxTextFile>,
            archivedTranscripts = vaultItems[2] as List<ArchivedTranscript>,
            archivedAudio = vaultItems[3] as List<ArchivedAudioItem>,
            jobs = jobs,
            logs = logs,
            graph = graph,
            isProc = statusList[0] as Boolean,
            isSync = statusList[1] as Boolean,
            liveMsg = statusList[2] as String?,
            activeProgress = statusList[3] as ActiveProcessingProgress?
        )
    }

    private val mediaFlow = combine(
        app.audioRecorder.state,
        app.audioRecorder.elapsedMs,
        app.audioRecorder.amplitudeHistory,
        app.audioPlayer.playbackState
    ) { recState, elapsed, amps, playState ->
        listOf(recState, elapsed, amps, playState)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MainUiState> = combine(
        app.settingsRepo.settingsFlow,
        pipelineFlow,
        mediaFlow,
        auxiliaryFlow
    ) { settings, pipe, media, aux ->
        MainUiState(
            settings = settings,
            inboxFiles = pipe.inbox,
            inboxTextFiles = pipe.inboxText,
            archivedTranscripts = pipe.archivedTranscripts,
            archivedAudioFiles = pipe.archivedAudio,
            jobs = pipe.jobs,
            logs = pipe.logs,
            graphData = pipe.graph,
            recordingState = media[0] as RecordingState,
            recordingElapsedMs = media[1] as Long,
            recordingAmplitudes = media[2] as List<Float>,
            playbackState = media[3] as AudioPlaybackState,
            isProcessingAudio = pipe.isProc,
            isSyncingDrive = pipe.isSync,
            liveStatusMessage = pipe.liveMsg,
            activeProcessingProgress = pipe.activeProgress,
            geminiTestState = aux.geminiTest,
            driveFolders = aux.driveFolders,
            isLoadingDriveFolders = aux.loadingFolders,
            driveAuthError = aux.driveError,
            selectedNotePath = aux.selectedNotePath,
            selectedTranscriptPath = aux.selectedTranscriptPath,
            availableGeminiModels = aux.availableModels,
            isLoadingGeminiModels = aux.loadingModels
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainUiState()
    )

    init {
        refreshAll()
        viewModelScope.launch {
            app.pipelineOrchestrator.completionMessages.collect { msg ->
                _snackbarMessages.emit(msg)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            app.pipelineOrchestrator.refreshVaultAndInbox()
        }
    }

    fun onVaultFolderSelected(uri: Uri) {
        viewModelScope.launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }

            val result = app.vaultRepo.ensureVaultStructure(uri.toString())
            result.onSuccess { displayName ->
                app.settingsRepo.setVaultFolder(uri.toString(), displayName)
                app.pipelineOrchestrator.refreshVaultAndInbox()
                _snackbarMessages.emit("Connected vault \"$displayName\" & verified Obsidian folders.")
                val settings = app.settingsRepo.currentSettings()
                if (settings.autoProcessAudio && settings.isGeminiConfigured) {
                    app.pipelineOrchestrator.enqueueBackgroundProcessing(triggerImmediateIfOnline = false)
                }
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Unable to initialize ObsidianVault folders.")
            }
        }
    }

    fun saveGeminiConfig(apiKey: String, model: String) {
        viewModelScope.launch {
            val cleanModel = model.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
            app.settingsRepo.setGeminiConfig(apiKey, cleanModel)
            _geminiTestState.value = GeminiTestState.Idle
            _snackbarMessages.emit("Saved Google AI Studio configuration ($cleanModel).")
        }
    }

    fun testGeminiConnection(apiKey: String, model: String) {
        viewModelScope.launch {
            _geminiTestState.value = GeminiTestState.Testing
            val cleanModel = model.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
            val result = app.geminiService.testApiKey(apiKey, cleanModel)
            result.onSuccess { msg ->
                app.settingsRepo.setGeminiConfig(apiKey, cleanModel)
                _geminiTestState.value = GeminiTestState.Success(msg)
                app.geminiService.fetchAvailableModels(apiKey).onSuccess { models ->
                    if (models.isNotEmpty()) _availableGeminiModels.value = models
                }
            }.onFailure { err ->
                _geminiTestState.value = GeminiTestState.Error(err.message ?: "Connection failed")
            }
        }
    }

    fun fetchAvailableGeminiModels(apiKey: String) {
        viewModelScope.launch {
            _isLoadingGeminiModels.value = true
            val result = app.geminiService.fetchAvailableModels(apiKey)
            result.onSuccess { models ->
                _availableGeminiModels.value = models
                if (models.isEmpty()) {
                    _snackbarMessages.emit("No generateContent models returned for this API key.")
                } else {
                    _snackbarMessages.emit("Loaded ${models.size} available model(s) for your key.")
                }
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Failed to load models")
            }
            _isLoadingGeminiModels.value = false
        }
    }

    fun startRecording(sessionLabel: String) {
        app.audioRecorder.startRecording(viewModelScope, sessionLabel.ifBlank { "DMN" })
            .onFailure { err ->
                viewModelScope.launch {
                    _snackbarMessages.emit("Could not start recorder: ${err.message}")
                }
            }
    }

    fun pauseRecording() = app.audioRecorder.pauseRecording()

    fun resumeRecording() {
        app.audioRecorder.resumeRecording().onFailure { err ->
            viewModelScope.launch {
                _snackbarMessages.emit("Could not resume microphone: ${err.message}. Recorded segments are preserved.")
            }
        }
    }

    fun discardRecording() {
        app.audioRecorder.stopAndDiscard()
    }

    fun stopAndSaveRecording() {
        viewModelScope.launch {
            val settings = app.settingsRepo.currentSettings()
            val recResult = app.audioRecorder.stopRecording()
            recResult.onSuccess { recorded ->
                val treeUri = settings.vaultTreeUri
                if (treeUri.isNullOrBlank()) {
                    recorded.tempFile.delete()
                    _snackbarMessages.emit("Select your ObsidianVault folder before saving recordings.")
                    return@launch
                }
                val saved = app.vaultRepo.saveCachedAudioToInbox(
                    treeUriString = treeUri,
                    tempFile = recorded.tempFile,
                    desiredFileName = recorded.suggestedFileName
                )
                recorded.tempFile.delete()
                saved.onSuccess { audio ->
                    app.pipelineOrchestrator.refreshVaultAndInbox()
                    _snackbarMessages.emit("Saved ${audio.name} to 00_Audio_Inbox.")
                    if (settings.autoProcessAudio && settings.isGeminiConfigured) {
                        app.pipelineOrchestrator.enqueueBackgroundProcessing(triggerImmediateIfOnline = true)
                    }
                }.onFailure { err ->
                    _snackbarMessages.emit("Failed saving to 00_Audio_Inbox: ${err.message}")
                }
            }.onFailure { err ->
                _snackbarMessages.emit("Recording error: ${err.message}")
            }
        }
    }

    fun deleteInboxAudio(audio: InboxAudioFile) {
        viewModelScope.launch {
            val deleted = app.vaultRepo.deleteInboxAudio(audio.uri)
            if (deleted) {
                app.pipelineOrchestrator.refreshVaultAndInbox()
                _snackbarMessages.emit("Removed ${audio.name} from inbox.")
            }
        }
    }

    fun submitTextToQueue(
        rawText: String,
        title: String,
        extension: String = "md",
        processImmediately: Boolean
    ) {
        viewModelScope.launch {
            val settings = app.settingsRepo.currentSettings()
            val treeUri = settings.vaultTreeUri
            if (treeUri.isNullOrBlank()) {
                _snackbarMessages.emit("Select your ObsidianVault folder before saving text notes.")
                return@launch
            }
            val saved = app.vaultRepo.saveTextToInbox(
                treeUriString = treeUri,
                rawText = rawText,
                sessionTitle = title,
                originalExtension = extension
            )
            saved.onSuccess { textItem ->
                app.pipelineOrchestrator.refreshVaultAndInbox()
                if (processImmediately) {
                    if (!settings.isGeminiConfigured) {
                        _snackbarMessages.emit("Saved ${textItem.name} to 04_Text_Inbox. Add your Gemini API key in Sync to synthesize.")
                    } else {
                        app.pipelineOrchestrator.enqueueBackgroundProcessing(
                            triggerImmediateIfOnline = true,
                            singleTargetUri = textItem.uri.toString()
                        )
                        _snackbarMessages.emit("Synthesizing ${textItem.name} in background (safe to minimize app).")
                    }
                } else {
                    _snackbarMessages.emit("Saved ${textItem.name} to 04_Text_Inbox.")
                }
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Could not save text input.")
            }
        }
    }

    fun deleteInboxText(item: InboxTextFile) {
        viewModelScope.launch {
            val deleted = app.vaultRepo.deleteInboxDocument(item.uri)
            if (deleted) {
                app.pipelineOrchestrator.refreshVaultAndInbox()
                _snackbarMessages.emit("Removed ${item.name} from text inbox.")
            }
        }
    }

    fun deleteSessionForJob(job: ProcessingJobEntity) {
        viewModelScope.launch {
            val archived = job.archivedAudioName
            val isTextArchive = archived?.endsWith(".md", ignoreCase = true) == true ||
                archived?.endsWith(".txt", ignoreCase = true) == true
            val res = app.pipelineOrchestrator.deleteCompleteSession(
                dailyNotePath = job.outputNotePath,
                transcriptFileName = if (isTextArchive) archived else archived?.let { "${it.substringBeforeLast('.')}.md" },
                audioFileName = if (!isTextArchive) archived else null,
                jobAudioUri = job.audioUri,
                jobFileName = job.fileName
            )
            res.onSuccess { artifacts ->
                _snackbarMessages.emit("Deleted session ${job.fileName} (${artifacts.deletedPaths.size} archive/note file(s) removed).")
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Failed deleting session.")
            }
        }
    }

    fun deleteSessionFromDailyNote(dailyNotePath: String, sessionHeading: String) {
        viewModelScope.launch {
            val res = app.pipelineOrchestrator.deleteCompleteSession(
                dailyNotePath = dailyNotePath,
                sessionHeading = sessionHeading
            )
            res.onSuccess { artifacts ->
                if (artifacts.dailyNoteDeletedCompletely && _selectedNotePath.value == dailyNotePath) {
                    _selectedNotePath.value = null
                }
                _snackbarMessages.emit("Deleted DMN session and linked archives (${artifacts.deletedPaths.size} file(s)).")
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Failed deleting DMN session.")
            }
        }
    }

    fun deleteSessionForArchive(transcriptFileName: String? = null, audioFileName: String? = null) {
        viewModelScope.launch {
            val res = app.pipelineOrchestrator.deleteCompleteSession(
                transcriptFileName = transcriptFileName,
                audioFileName = audioFileName
            )
            res.onSuccess { artifacts ->
                if (transcriptFileName != null && _selectedTranscriptPath.value?.endsWith(transcriptFileName, ignoreCase = true) == true) {
                    _selectedTranscriptPath.value = null
                }
                val name = transcriptFileName ?: audioFileName ?: "archive item"
                _snackbarMessages.emit("Deleted $name and all linked session data (${artifacts.deletedPaths.size} file(s)).")
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Failed deleting archive.")
            }
        }
    }

    fun processAllInboxNow() {
        viewModelScope.launch {
            val settings = app.settingsRepo.currentSettings()
            if (!settings.isVaultConfigured) {
                _snackbarMessages.emit("Select your ObsidianVault folder first.")
                return@launch
            }
            if (!settings.isGeminiConfigured) {
                _snackbarMessages.emit("Enter your Google AI Studio API key first.")
                return@launch
            }
            app.pipelineOrchestrator.enqueueBackgroundProcessing(triggerImmediateIfOnline = true)
            _snackbarMessages.emit("Processing inbox in background (safe to minimize app).")
        }
    }

    fun processSingleInboxItemNow(itemUri: String) {
        viewModelScope.launch {
            val settings = app.settingsRepo.currentSettings()
            if (!settings.isVaultConfigured) {
                _snackbarMessages.emit("Select your ObsidianVault folder first.")
                return@launch
            }
            if (!settings.isGeminiConfigured) {
                _snackbarMessages.emit("Enter your Google AI Studio API key first.")
                return@launch
            }
            app.pipelineOrchestrator.enqueueBackgroundProcessing(
                triggerImmediateIfOnline = true,
                singleTargetUri = itemUri
            )
            _snackbarMessages.emit("Synthesizing item in background (safe to minimize app).")
        }
    }

    fun switchModelForNextRetry(newModelId: String, retryImmediately: Boolean) {
        app.pipelineOrchestrator.switchModelForActiveRetry(newModelId, retryImmediately)
    }

    fun triggerImmediateRetryNow() {
        app.pipelineOrchestrator.triggerImmediateRetryNow()
    }

    fun clearCompletedJobs() {
        viewModelScope.launch {
            app.database.processingJobDao().clearCompleted()
        }
    }

    fun openNoteByPath(relativePath: String?) {
        _selectedNotePath.value = relativePath
        if (relativePath != null) {
            _selectedTranscriptPath.value = null
        }
    }

    fun openArchivedTranscript(relativePath: String?) {
        _selectedTranscriptPath.value = relativePath
    }

    fun openArchivedTranscriptByName(fileName: String) {
        val clean = fileName.substringAfterLast('/')
        val match = uiState.value.archivedTranscripts.firstOrNull {
            it.fileName.equals(clean, ignoreCase = true)
        }
        if (match != null) {
            _selectedTranscriptPath.value = match.relativePath
        } else {
            viewModelScope.launch {
                val treeUri = uiState.value.settings.vaultTreeUri
                val fetched = if (!treeUri.isNullOrBlank()) {
                    app.vaultRepo.readArchivedTranscriptByName(treeUri, clean)
                } else null
                if (fetched != null) {
                    app.pipelineOrchestrator.refreshVaultAndInbox()
                    _selectedTranscriptPath.value = fetched.relativePath
                } else {
                    _snackbarMessages.emit("Archived transcript not found: $clean")
                }
            }
        }
    }

    fun openNoteByTitle(title: String): Boolean {
        val target = uiState.value.graphData.notesByPath.values.firstOrNull {
            it.title.equals(title.trim(), ignoreCase = true)
        }
        return if (target != null) {
            _selectedNotePath.value = target.relativePath
            true
        } else {
            false
        }
    }

    fun createAtomicNodeAndOpen(title: String, subFolder: String = "Concepts") {
        viewModelScope.launch {
            val treeUri = uiState.value.settings.vaultTreeUri
            if (treeUri.isNullOrBlank()) {
                _snackbarMessages.emit("Configure your ObsidianVault folder first.")
                return@launch
            }
            app.vaultRepo.createAtomicNodeStub(treeUri, title, subFolder)
                .onSuccess { relPath ->
                    app.pipelineOrchestrator.refreshVaultAndInbox()
                    _selectedNotePath.value = relPath
                    _snackbarMessages.emit("Created atomic node [[$title]].")
                }
                .onFailure { err ->
                    _snackbarMessages.emit("Could not create node: ${err.message}")
                }
        }
    }

    fun toggleTaskInNote(relativePath: String, lineIndex: Int) {
        viewModelScope.launch {
            val treeUri = uiState.value.settings.vaultTreeUri ?: return@launch
            app.vaultRepo.toggleMarkdownTask(treeUri, relativePath, lineIndex)
                .onSuccess {
                    app.pipelineOrchestrator.refreshVaultAndInbox()
                }
                .onFailure { err ->
                    _snackbarMessages.emit("Failed updating task: ${err.message}")
                }
        }
    }

    fun togglePlayArchivedAudio(audioName: String) {
        viewModelScope.launch {
            val treeUri = uiState.value.settings.vaultTreeUri ?: return@launch
            val uri = app.vaultRepo.findArchivedAudioUri(treeUri, audioName)
            if (uri != null) {
                app.audioPlayer.togglePlay(viewModelScope, audioName, uri)
            } else {
                _snackbarMessages.emit("Archived audio not found: $audioName")
            }
        }
    }

    fun authorizeGoogleDrive(activity: Activity) {
        viewModelScope.launch {
            _driveAuthError.value = null
            when (val outcome = app.driveAuthManager.authorizeDrive(activity)) {
                is DriveAuthOutcome.Authorized -> {
                    onDriveAuthorized(outcome.accessToken, outcome.email)
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    _driveConsentRequests.emit(outcome.intentSenderRequest)
                }
                is DriveAuthOutcome.Failed -> {
                    _driveAuthError.value = outcome.message
                }
            }
        }
    }

    fun handleDriveConsentResult(data: Intent?) {
        viewModelScope.launch {
            when (val outcome = app.driveAuthManager.handleConsentResult(data)) {
                is DriveAuthOutcome.Authorized -> {
                    onDriveAuthorized(outcome.accessToken, outcome.email)
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    _driveConsentRequests.emit(outcome.intentSenderRequest)
                }
                is DriveAuthOutcome.Failed -> {
                    _driveAuthError.value = outcome.message
                }
            }
        }
    }

    private suspend fun onDriveAuthorized(token: String, email: String?) {
        _driveAuthError.value = null
        app.settingsRepo.setDriveAccount(email ?: "Google Drive Connected")
        _snackbarMessages.emit("Connected Google Drive${if (!email.isNullOrBlank()) " ($email)" else ""}.")
        fetchDriveFoldersWithToken(token)
    }

    fun loadDriveFolders() {
        viewModelScope.launch {
            _isLoadingDriveFolders.value = true
            app.driveAuthManager.fetchFreshAccessTokenSilently()
                .onSuccess { token -> fetchDriveFoldersWithToken(token) }
                .onFailure { err ->
                    _isLoadingDriveFolders.value = false
                    _driveAuthError.value = err.message
                }
        }
    }

    private suspend fun fetchDriveFoldersWithToken(token: String) {
        _isLoadingDriveFolders.value = true
        app.driveSyncService.listRootFolders(token)
            .onSuccess { folders ->
                _driveFolders.value = folders
                // Auto-select existing "ObsidianVault" folder if none selected yet
                val current = app.settingsRepo.currentSettings()
                if (current.driveFolderId.isNullOrBlank()) {
                    val match = folders.firstOrNull { it.name.equals("ObsidianVault", ignoreCase = true) }
                    if (match != null) {
                        app.settingsRepo.setDriveFolder(match.id, match.name)
                    }
                }
            }
            .onFailure { err ->
                _driveAuthError.value = err.message
            }
        _isLoadingDriveFolders.value = false
    }

    fun selectDriveFolder(folder: DriveFolderItem) {
        viewModelScope.launch {
            app.settingsRepo.setDriveFolder(folder.id, folder.name)
            _snackbarMessages.emit("Sync target set to Google Drive / ${folder.name}")
        }
    }

    fun createAndSelectDriveFolder(folderName: String) {
        viewModelScope.launch {
            val clean = folderName.trim().ifBlank { "ObsidianVault" }
            val token = app.driveAuthManager.fetchFreshAccessTokenSilently().getOrElse {
                _snackbarMessages.emit(it.message ?: "Authorize Google Drive first.")
                return@launch
            }
            app.driveSyncService.createDriveFolder(token, clean)
                .onSuccess { folder ->
                    app.settingsRepo.setDriveFolder(folder.id, folder.name)
                    _driveFolders.value = listOf(folder) + _driveFolders.value
                    _snackbarMessages.emit("Created Google Drive folder \"$clean\".")
                }
                .onFailure { err ->
                    _snackbarMessages.emit(err.message ?: "Could not create Drive folder.")
                }
        }
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            app.driveAuthManager.signOut()
            app.settingsRepo.setDriveAccount(null)
            app.settingsRepo.setDriveFolder(null, "ObsidianVault")
            _driveFolders.value = emptyList()
            _snackbarMessages.emit("Disconnected Google Drive account.")
        }
    }

    fun syncDriveNow() {
        viewModelScope.launch {
            val settings = app.settingsRepo.currentSettings()
            if (!settings.isVaultConfigured) {
                _snackbarMessages.emit("Select your ObsidianVault folder first.")
                return@launch
            }
            if (!settings.isDriveConfigured) {
                _snackbarMessages.emit("Choose a Google Drive folder in Sync first.")
                return@launch
            }
            app.pipelineOrchestrator.enqueueOneTimeDriveSync(wifiOnly = false)
            _snackbarMessages.emit("Running ${settings.syncDirection.label} in background (safe to minimize app).")
        }
    }

    fun updateAutomationSettings(
        autoProcess: Boolean,
        autoDriveSync: Boolean,
        wifiOnly: Boolean,
        syncDirection: SyncDirection,
        conflictStrategy: ConflictStrategy
    ) {
        viewModelScope.launch {
            app.settingsRepo.setAutoProcessAudio(autoProcess)
            app.settingsRepo.setSyncOptions(autoDriveSync, wifiOnly, syncDirection, conflictStrategy)
            app.pipelineOrchestrator.schedulePeriodicDriveSync(autoDriveSync, wifiOnly)
        }
    }

    fun toggleDarkTheme(dark: Boolean) {
        viewModelScope.launch {
            app.settingsRepo.setDarkTheme(dark)
        }
    }

    fun updateAudioRetentionDays(days: Int) {
        viewModelScope.launch {
            val clean = days.coerceAtLeast(0)
            app.settingsRepo.setAudioRetentionDays(clean)
            app.pipelineOrchestrator.refreshVaultAndInbox()
            val label = if (clean == 0) "Keep Forever" else "$clean day(s)"
            _snackbarMessages.emit("Archived audio retention set to $label.")
        }
    }

    fun pruneExpiredAudioNow() {
        viewModelScope.launch {
            val res = app.pipelineOrchestrator.pruneExpiredAudioNow()
            res.onSuccess { count ->
                if (count == 0) {
                    _snackbarMessages.emit("No expired audio files found in 99_Audio_Archive.")
                } else {
                    _snackbarMessages.emit("Removed $count expired audio recording(s) from 99_Audio_Archive.")
                }
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Could not prune audio archive.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        app.audioPlayer.stop()
    }

    class Factory(private val app: SynapseApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SynapseViewModel(app) as T
        }
    }
}
