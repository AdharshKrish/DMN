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
import com.example.data.prefs.ConflictStrategy
import com.example.data.prefs.VaultSettings
import com.example.data.vault.InboxAudioFile
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
    val geminiTestState: GeminiTestState = GeminiTestState.Idle,
    val driveFolders: List<DriveFolderItem> = emptyList(),
    val isLoadingDriveFolders: Boolean = false,
    val driveAuthError: String? = null,
    val selectedNotePath: String? = null
) {
    val selectedNote: VaultNote?
        get() = selectedNotePath?.let { graphData.notesByPath[it] }
}

class SynapseViewModel(private val app: SynapseApplication) : ViewModel() {

    private val _geminiTestState = MutableStateFlow<GeminiTestState>(GeminiTestState.Idle)
    private val _driveFolders = MutableStateFlow<List<DriveFolderItem>>(emptyList())
    private val _isLoadingDriveFolders = MutableStateFlow(false)
    private val _driveAuthError = MutableStateFlow<String?>(null)
    private val _selectedNotePath = MutableStateFlow<String?>(null)

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    private val _driveConsentRequests = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 2)
    val driveConsentRequests = _driveConsentRequests.asSharedFlow()

    private data class AuxiliaryState(
        val geminiTest: GeminiTestState,
        val driveFolders: List<DriveFolderItem>,
        val loadingFolders: Boolean,
        val driveError: String?,
        val selectedNotePath: String?
    )

    private val auxiliaryFlow = combine(
        _geminiTestState,
        _driveFolders,
        _isLoadingDriveFolders,
        _driveAuthError,
        _selectedNotePath
    ) { testState, folders, loadingFolders, authErr, notePath ->
        AuxiliaryState(testState, folders, loadingFolders, authErr, notePath)
    }

    private data class PipelineFlowState(
        val inbox: List<InboxAudioFile>,
        val jobs: List<ProcessingJobEntity>,
        val logs: List<SyncLogEntity>,
        val graph: KnowledgeGraphData,
        val isProc: Boolean,
        val isSync: Boolean,
        val liveMsg: String?
    )

    private val pipelineFlow = combine(
        app.pipelineOrchestrator.inboxFiles,
        app.database.processingJobDao().observeAllJobs(),
        app.database.syncLogDao().observeRecentLogs(),
        app.pipelineOrchestrator.graphData,
        combine(
            app.pipelineOrchestrator.isProcessing,
            app.pipelineOrchestrator.isSyncingDrive,
            app.pipelineOrchestrator.liveStatusMessage
        ) { p, s, m -> Triple(p, s, m) }
    ) { inbox, jobs, logs, graph, statusTriple ->
        PipelineFlowState(
            inbox = inbox,
            jobs = jobs,
            logs = logs,
            graph = graph,
            isProc = statusTriple.first,
            isSync = statusTriple.second,
            liveMsg = statusTriple.third
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
            geminiTestState = aux.geminiTest,
            driveFolders = aux.driveFolders,
            isLoadingDriveFolders = aux.loadingFolders,
            driveAuthError = aux.driveError,
            selectedNotePath = aux.selectedNotePath
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainUiState()
    )

    init {
        refreshAll()
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
            }.onFailure { err ->
                _geminiTestState.value = GeminiTestState.Error(err.message ?: "Connection failed")
            }
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

    fun resumeRecording() = app.audioRecorder.resumeRecording()

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

    fun processAllInboxNow() {
        viewModelScope.launch {
            val res = app.pipelineOrchestrator.processInboxQueue()
            res.onSuccess { count ->
                if (count == 0) {
                    _snackbarMessages.emit("No audio recordings waiting in 00_Audio_Inbox.")
                } else {
                    _snackbarMessages.emit("Synthesized $count DMN session(s) into Daily Notes.")
                }
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Processing failed.")
            }
        }
    }

    fun processSingleAudioNow(audioUri: String) {
        viewModelScope.launch {
            val res = app.pipelineOrchestrator.processInboxQueue(singleTargetUri = audioUri)
            res.onSuccess { count ->
                if (count > 0) _snackbarMessages.emit("DMN session written to 01_Daily_Notes.")
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Processing failed.")
            }
        }
    }

    fun clearCompletedJobs() {
        viewModelScope.launch {
            app.database.processingJobDao().clearCompleted()
        }
    }

    fun openNoteByPath(relativePath: String?) {
        _selectedNotePath.value = relativePath
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
                    _snackbarMessages.emit(outcome.message)
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
                    _snackbarMessages.emit(outcome.message)
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
            val res = app.pipelineOrchestrator.runDriveSync()
            res.onSuccess { sum ->
                _snackbarMessages.emit(
                    "Drive Sync: ↑${sum.uploadedCount} uploaded, ↓${sum.downloadedCount} downloaded, ${sum.conflictsResolved} conflicts"
                )
            }.onFailure { err ->
                _snackbarMessages.emit(err.message ?: "Google Drive sync failed.")
            }
        }
    }

    fun updateAutomationSettings(
        autoProcess: Boolean,
        autoDriveSync: Boolean,
        wifiOnly: Boolean,
        conflictStrategy: ConflictStrategy
    ) {
        viewModelScope.launch {
            app.settingsRepo.setAutoProcessAudio(autoProcess)
            app.settingsRepo.setSyncOptions(autoDriveSync, wifiOnly, conflictStrategy)
            app.pipelineOrchestrator.schedulePeriodicDriveSync(autoDriveSync, wifiOnly)
        }
    }

    fun toggleDarkTheme(dark: Boolean) {
        viewModelScope.launch {
            app.settingsRepo.setDarkTheme(dark)
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
