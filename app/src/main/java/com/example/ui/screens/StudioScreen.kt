package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.RecordingState
import com.example.data.local.JobStatus
import com.example.data.local.ProcessingJobEntity
import com.example.data.local.VoiceDraftEntity
import com.example.data.pipeline.ActiveProcessingProgress
import com.example.data.pipeline.StepStatus
import com.example.data.prefs.VoiceTranscriptionMode
import com.example.data.vault.InboxAudioFile
import com.example.data.vault.InboxTextFile
import com.example.ui.components.SetupChecklistCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDurationMs
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.MainUiState
import kotlinx.coroutines.delay

private enum class StudioCaptureMode { VOICE, TEXT }

@Composable
fun StudioScreen(
    state: MainUiState,
    onPickVault: () -> Unit,
    onOpenSyncTab: () -> Unit,
    onStartRecording: (String) -> Unit,
    onSelectVoiceTranscriptionMode: (VoiceTranscriptionMode) -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecordingForReview: () -> Unit,
    onRetryLiveTranscription: () -> Unit,
    onUpdateDraftTitle: (String) -> Unit,
    onUpdateDraftTranscript: (String) -> Unit,
    onSelectDraft: (VoiceDraftEntity) -> Unit,
    onNewDraft: () -> Unit,
    onDeleteDraft: (String) -> Unit,
    onTranscribePendingAudio: () -> Unit,
    onConfirmSendDraftToDmn: (Boolean) -> Unit,
    onSubmitTextNote: (rawText: String, title: String, extension: String, processImmediately: Boolean) -> Unit,
    onProcessAllNow: () -> Unit,
    onProcessSingle: (String) -> Unit,
    onSwitchRetryModel: (String, Boolean) -> Unit,
    onRetryNow: () -> Unit,
    onDeleteInboxAudio: (InboxAudioFile) -> Unit,
    onDeleteInboxText: (InboxTextFile) -> Unit,
    onRefreshInbox: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDeleteSession: (ProcessingJobEntity) -> Unit,
    onClearCompletedJobs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val sem = LocalSynapseColors.current

    // Keep display awake while on the Studio / Recording screen
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    var captureMode by rememberSaveable { mutableStateOf(StudioCaptureMode.VOICE) }
    var textTitle by rememberSaveable { mutableStateOf("") }
    var textBody by rememberSaveable { mutableStateOf("") }
    var textExtension by rememberSaveable { mutableStateOf("md") }
    var permissionDeniedNotice by remember { mutableStateOf(false) }
    var jobPendingDelete by remember { mutableStateOf<ProcessingJobEntity?>(null) }
    var draftIdPendingDelete by remember { mutableStateOf<String?>(null) }
    var showConfirmSendDraftDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNotice = false
            onStartRecording(state.liveSession.draftTitle)
        } else {
            permissionDeniedNotice = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    fun runWithBackgroundNotificationConsent(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val primaryGlow = MaterialTheme.colorScheme.primary
    val amberGlow = sem.amber

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryGlow.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.85f, 90f),
                        radius = size.width * 0.75f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(amberGlow.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 220f),
                        radius = size.width * 0.5f
                    )
                )
            },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DMN Capture Studio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = if (state.settings.isVaultConfigured) state.settings.vaultDisplayName else "Vault Unlinked",
                            color = if (state.settings.isVaultConfigured) sem.emerald else sem.amber,
                            containerColor = if (state.settings.isVaultConfigured) sem.emeraldContainer else sem.amberContainer
                        )
                        StatusPill(
                            label = state.settings.geminiModel,
                            color = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }
                IconButton(
                    onClick = onRefreshInbox,
                    modifier = Modifier.testTag("refresh_inbox_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Scan Audio & Text Inbox"
                    )
                }
            }
        }

        item {
            SetupChecklistCard(
                settings = state.settings,
                onPickVault = onPickVault,
                onOpenSyncSetup = onOpenSyncTab
            )
        }

        item {
            CaptureModeSelector(
                selectedMode = captureMode,
                enabled = state.recordingState == RecordingState.IDLE,
                onSelectMode = { captureMode = it }
            )
        }

        item {
            if (captureMode == StudioCaptureMode.VOICE) {
                VoiceTranscriptionStudioCard(
                    liveSession = state.liveSession,
                    selectedMode = state.settings.voiceTranscriptionMode,
                    liveModelName = state.settings.liveTranscriptionModel,
                    transcribeModelName = state.settings.transcriptionModel,
                    flashModelName = state.settings.geminiModel,
                    vaultConfigured = state.settings.isVaultConfigured,
                    geminiConfigured = state.settings.isGeminiConfigured,
                    permissionDeniedNotice = permissionDeniedNotice,
                    onSelectMode = onSelectVoiceTranscriptionMode,
                    onRecordOrContinueTap = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartRecording(state.liveSession.draftTitle)
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onPause = onPauseRecording,
                    onResume = onResumeRecording,
                    onStopForReview = onStopRecordingForReview,
                    onRetryLive = onRetryLiveTranscription,
                    onTitleChange = onUpdateDraftTitle,
                    onTranscriptChange = onUpdateDraftTranscript,
                    onTranscribePending = onTranscribePendingAudio,
                    onRequestConfirmSend = { showConfirmSendDraftDialog = true },
                    onNewDraft = onNewDraft,
                    onRequestDeleteActiveDraft = {
                        state.liveSession.activeDraftId?.let { draftIdPendingDelete = it }
                            ?: onNewDraft()
                    }
                )
            } else {
                TextNoteComposerCard(
                    title = textTitle,
                    onTitleChange = { textTitle = it },
                    rawText = textBody,
                    onRawTextChange = { textBody = it },
                    extension = textExtension,
                    onExtensionChange = { textExtension = it },
                    vaultConfigured = state.settings.isVaultConfigured,
                    geminiConfigured = state.settings.isGeminiConfigured,
                    isProcessing = state.isProcessingAudio,
                    onSaveToQueue = {
                        onSubmitTextNote(textBody, textTitle, textExtension, false)
                        textTitle = ""
                        textBody = ""
                    },
                    onSynthesizeNow = {
                        val capturedBody = textBody
                        val capturedTitle = textTitle
                        val capturedExt = textExtension
                        textTitle = ""
                        textBody = ""
                        runWithBackgroundNotificationConsent {
                            onSubmitTextNote(capturedBody, capturedTitle, capturedExt, true)
                        }
                    }
                )
            }
        }

        if (captureMode == StudioCaptureMode.VOICE && state.voiceDrafts.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Saved Transcription Drafts (${state.voiceDrafts.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Persisted across app restarts. Tap to review, edit, continue recording, or confirm for DMN Graph.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.voiceDrafts, key = { "draft_${it.id}" }) { draft ->
                SavedVoiceDraftRow(
                    draft = draft,
                    isActive = state.liveSession.activeDraftId == draft.id,
                    enabled = state.recordingState == RecordingState.IDLE,
                    onSelect = { onSelectDraft(draft) },
                    onDelete = { draftIdPendingDelete = draft.id }
                )
            }
        }

        item {
            AnimatedVisibility(visible = state.isProcessingAudio || state.liveStatusMessage != null) {
                val activeProgress = state.activeProcessingProgress
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { showProgressDialog = true }
                        .testTag("live_processing_banner"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (activeProgress?.isRetrying == true) sem.amber else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (activeProgress?.isRetrying == true) Icons.Outlined.Loop else Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (activeProgress?.isRetrying == true) sem.amber else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = state.liveStatusMessage ?: "Processing DMN queue...",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (activeProgress?.isRetrying == true) {
                                        "Retry #${activeProgress.attemptNumber}"
                                    } else {
                                        "Details"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeProgress?.isRetrying == true) sem.amber else MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = "Inspect processing step details",
                                    tint = if (activeProgress?.isRetrying == true) sem.amber else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            val totalPending = state.inboxFiles.size + state.inboxTextFiles.size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Inbox Queue ($totalPending)",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "00_Audio_Inbox (${state.inboxFiles.size}) & 04_Text_Inbox (${state.inboxTextFiles.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { runWithBackgroundNotificationConsent(onProcessAllNow) },
                    enabled = totalPending > 0 && !state.isProcessingAudio &&
                        state.settings.isVaultConfigured && state.settings.isGeminiConfigured,
                    modifier = Modifier.testTag("process_all_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Process All")
                }
            }
        }

        if (state.inboxFiles.isEmpty() && state.inboxTextFiles.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AudioFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.settings.isVaultConfigured) {
                                "Inbox is clear. Record audio, paste/type a transcript above, or drop files into 00_Audio_Inbox / 04_Text_Inbox."
                            } else {
                                "Pick your ObsidianVault folder to inspect queued audio and text inputs."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.inboxFiles, key = { "audio_${it.uri}" }) { audio ->
                InboxAudioCard(
                    audio = audio,
                    canProcess = !state.isProcessingAudio && state.settings.isGeminiConfigured,
                    onProcess = {
                        runWithBackgroundNotificationConsent {
                            onProcessSingle(audio.uri.toString())
                        }
                    },
                    onDelete = { onDeleteInboxAudio(audio) }
                )
            }
            items(state.inboxTextFiles, key = { "text_${it.uri}" }) { textFile ->
                InboxTextCard(
                    textFile = textFile,
                    canProcess = !state.isProcessingAudio && state.settings.isGeminiConfigured,
                    onProcess = {
                        runWithBackgroundNotificationConsent {
                            onProcessSingle(textFile.uri.toString())
                        }
                    },
                    onDelete = { onDeleteInboxText(textFile) }
                )
            }
        }

        if (state.jobs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Synthesis History",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (state.jobs.any { it.status == JobStatus.COMPLETED.name }) {
                        TextButton(
                            onClick = onClearCompletedJobs,
                            modifier = Modifier.testTag("clear_completed_jobs_button")
                        ) {
                            Text("Clear Completed")
                        }
                    }
                }
            }

            items(state.jobs.take(15), key = { it.audioUri }) { job ->
                ProcessingJobCard(
                    job = job,
                    onOpenNote = onOpenNote,
                    onInspectActive = { showProgressDialog = true },
                    onRequestDelete = { jobPendingDelete = job }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showProgressDialog) {
        ActiveProcessingDialog(
            progress = state.activeProcessingProgress,
            fallbackStatusMessage = state.liveStatusMessage,
            isProcessing = state.isProcessingAudio || state.isSyncingDrive,
            availableModels = state.availableGeminiModels,
            onSwitchModel = onSwitchRetryModel,
            onRetryNow = onRetryNow,
            onDismiss = { showProgressDialog = false }
        )
    }

    jobPendingDelete?.let { targetJob ->
        AlertDialog(
            onDismissRequest = { jobPendingDelete = null },
            title = { Text("Delete DMN Session?") },
            text = {
                Text(
                    "This will permanently delete \"${targetJob.fileName}\" along with its archived audio, transcript .md, Daily Note session entry, and history record."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        jobPendingDelete = null
                        onDeleteSession(targetJob)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_job_session_button")
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { jobPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (showConfirmSendDraftDialog) {
        ConfirmSendDraftDialog(
            liveSession = state.liveSession,
            flashModelName = state.settings.geminiModel,
            transcribeModelName = state.settings.transcriptionModel,
            onConfirmSend = { transcribePendingFirst ->
                showConfirmSendDraftDialog = false
                runWithBackgroundNotificationConsent {
                    onConfirmSendDraftToDmn(transcribePendingFirst)
                }
            },
            onDismiss = { showConfirmSendDraftDialog = false }
        )
    }

    draftIdPendingDelete?.let { targetDraftId ->
        val matching = state.voiceDrafts.firstOrNull { it.id == targetDraftId }
        AlertDialog(
            onDismissRequest = { draftIdPendingDelete = null },
            title = { Text("Delete Saved Voice Draft?") },
            text = {
                Text(
                    "This will permanently delete \"${matching?.title ?: "Voice Draft"}\" and any stored pending audio backup."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        draftIdPendingDelete = null
                        onDeleteDraft(targetDraftId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_draft_button")
                ) {
                    Text("Delete Draft")
                }
            },
            dismissButton = {
                TextButton(onClick = { draftIdPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InboxAudioCard(
    audio: InboxAudioFile,
    canProcess: Boolean,
    onProcess: () -> Unit,
    onDelete: () -> Unit
) {
    val sem = LocalSynapseColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(sem.amberContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AudioFile,
                    contentDescription = null,
                    tint = sem.amber
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = audio.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(audio.sizeBytes)} • ${formatRelativeTimestamp(audio.lastModifiedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_inbox_${audio.name}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete recording",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onProcess,
                enabled = canProcess,
                modifier = Modifier.testTag("process_audio_${audio.name}")
            ) {
                Text("Process")
            }
        }
    }
}

@Composable
private fun CaptureModeSelector(
    selectedMode: StudioCaptureMode,
    enabled: Boolean,
    onSelectMode: (StudioCaptureMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val isVoice = selectedMode == StudioCaptureMode.VOICE
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = enabled) { onSelectMode(StudioCaptureMode.VOICE) }
                    .testTag("mode_voice_tab"),
                shape = MaterialTheme.shapes.medium,
                color = if (isVoice) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = null,
                        tint = if (isVoice) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Voice Session",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isVoice) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val isText = selectedMode == StudioCaptureMode.TEXT
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = enabled) { onSelectMode(StudioCaptureMode.TEXT) }
                    .testTag("mode_text_tab"),
                shape = MaterialTheme.shapes.medium,
                color = if (isText) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = null,
                        tint = if (isText) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Text / Transcript",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isText) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TextNoteComposerCard(
    title: String,
    onTitleChange: (String) -> Unit,
    rawText: String,
    onRawTextChange: (String) -> Unit,
    extension: String,
    onExtensionChange: (String) -> Unit,
    vaultConfigured: Boolean,
    geminiConfigured: Boolean,
    isProcessing: Boolean,
    onSaveToQueue: () -> Unit,
    onSynthesizeNow: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val clipboard = LocalClipboardManager.current
    val wordCount = remember(rawText) {
        if (rawText.isBlank()) 0 else rawText.trim().split(Regex("""\s+""")).size
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, sem.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = "Exact Copy Preserved in Archive",
                    color = sem.insightPurple,
                    containerColor = sem.insightPurpleContainer
                )
                Text(
                    text = "$wordCount words",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Note / Transcript Title (optional)") },
                placeholder = { Text("e.g., Morning_Walk_Thoughts") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("text_title_input")
            )

            OutlinedTextField(
                value = rawText,
                onValueChange = onRawTextChange,
                label = { Text("Paste transcript or write thoughts directly") },
                placeholder = { Text("Paste any external transcript or type raw thoughts here. Exact unmodified text is archived in 98_Transcript_Archive/ while Gemini builds your DMN graph links...") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("text_note_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = extension == "md",
                        onClick = { onExtensionChange("md") },
                        label = { Text(".md") },
                        modifier = Modifier.testTag("text_ext_md")
                    )
                    FilterChip(
                        selected = extension == "txt",
                        onClick = { onExtensionChange("txt") },
                        label = { Text(".txt") },
                        modifier = Modifier.testTag("text_ext_txt")
                    )
                }

                TextButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrEmpty()) {
                            onRawTextChange(if (rawText.isBlank()) pasted else "$rawText\n$pasted")
                        }
                    },
                    modifier = Modifier.testTag("paste_clipboard_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPaste,
                        contentDescription = "Paste from clipboard",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveToQueue,
                    enabled = vaultConfigured && rawText.isNotBlank() && !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("save_text_queue_button")
                ) {
                    Text("Save to Queue")
                }

                Button(
                    onClick = onSynthesizeNow,
                    enabled = vaultConfigured && geminiConfigured && rawText.isNotBlank() && !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("synthesize_text_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (vaultConfigured) "Synthesize" else "Select Vault")
                }
            }
        }
    }
}

@Composable
private fun InboxTextCard(
    textFile: InboxTextFile,
    canProcess: Boolean,
    onProcess: () -> Unit,
    onDelete: () -> Unit
) {
    val sem = LocalSynapseColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(sem.insightPurpleContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Article,
                    contentDescription = null,
                    tint = sem.insightPurple
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = textFile.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (textFile.previewText.isNotBlank()) {
                    Text(
                        text = textFile.previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${formatBytes(textFile.sizeBytes)} • ${formatRelativeTimestamp(textFile.lastModifiedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_text_${textFile.name}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete text note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onProcess,
                enabled = canProcess,
                modifier = Modifier.testTag("process_text_${textFile.name}")
            ) {
                Text("Process")
            }
        }
    }
}

@Composable
private fun ProcessingJobCard(
    job: ProcessingJobEntity,
    onOpenNote: (String) -> Unit,
    onInspectActive: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val status = runCatching { JobStatus.valueOf(job.status) }.getOrDefault(JobStatus.QUEUED)
    val isActiveJob = status == JobStatus.UPLOADING ||
        status == JobStatus.PROCESSING_REMOTE ||
        status == JobStatus.SYNTHESIZING
    val (color, bg, icon) = when (status) {
        JobStatus.COMPLETED -> Triple(sem.emerald, sem.emeraldContainer, Icons.Outlined.CheckCircle)
        JobStatus.FAILED -> Triple(sem.recordingCrimson, sem.recordingCrimsonContainer, Icons.Outlined.ErrorOutline)
        JobStatus.QUEUED -> Triple(sem.amber, sem.amberContainer, Icons.Outlined.Schedule)
        else -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, Icons.Outlined.AutoAwesome)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    job.outputNotePath != null -> Modifier.clickable { onOpenNote(job.outputNotePath) }
                    isActiveJob -> Modifier.clickable { onInspectActive() }
                    else -> Modifier
                }
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(label = status.displayLabel, color = color, containerColor = bg, icon = icon)
                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag("delete_job_session_${job.fileName}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete entire DMN session",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!job.errorMessage.isNullOrBlank()) {
                val context = LocalContext.current
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = job.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Gemini Error", job.errorMessage))
                                Toast.makeText(context, "Error details copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_job_error_${job.fileName}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy error details",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (!job.linkedNodesCsv.isNullOrBlank()) {
                Text(
                    text = "Linked: ${job.linkedNodesCsv}",
                    style = MaterialTheme.typography.labelSmall,
                    color = sem.insightPurple,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!job.outputNotePath.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = job.outputNotePath,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Open daily note",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveProcessingDialog(
    progress: ActiveProcessingProgress?,
    fallbackStatusMessage: String?,
    isProcessing: Boolean,
    availableModels: List<String>,
    onSwitchModel: (String, Boolean) -> Unit,
    onRetryNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sem = LocalSynapseColors.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(progress?.startedAtMs, isProcessing) {
        if (progress != null || isProcessing) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val modelSuggestions = remember(availableModels) {
        (listOf(
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-flash-lite-latest",
            "gemini-flash-latest"
        ) + availableModels).distinct()
    }

    var customModelDraft by remember(progress?.modelId, progress?.pendingOverrideModel) {
        mutableStateOf(progress?.pendingOverrideModel ?: progress?.modelId.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (progress != null) "Current Synthesis Status" else "Background Pipeline Status",
                    style = MaterialTheme.typography.titleLarge
                )
                if (progress != null) {
                    val elapsedSec = ((nowMs - progress.startedAtMs).coerceAtLeast(0L) / 1000L)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = progress.inputKind,
                            color = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                        StatusPill(
                            label = progress.modelId,
                            color = sem.insightPurple,
                            containerColor = sem.insightPurpleContainer
                        )
                        if (!progress.pendingOverrideModel.isNullOrBlank()) {
                            StatusPill(
                                label = "Next → ${progress.pendingOverrideModel}",
                                color = sem.emerald,
                                containerColor = sem.emeraldContainer
                            )
                        }
                        Text(
                            text = "${elapsedSec}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (progress == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = fallbackStatusMessage
                                    ?: if (isProcessing) "Preparing next item in queue..." else "Processing finished.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isProcessing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                } else {
                    Text(
                        text = progress.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Highlighted Current API Call & Status Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (progress.isRetrying) sem.amber else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ACTIVE API CALL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StatusPill(
                                    label = if (progress.isRetrying) {
                                        "RETRYING (#${progress.attemptNumber})"
                                    } else {
                                        "IN PROGRESS"
                                    },
                                    color = if (progress.isRetrying) sem.amber else sem.dailyBlue,
                                    containerColor = if (progress.isRetrying) sem.amberContainer else MaterialTheme.colorScheme.primaryContainer
                                )
                            }
                            SelectionContainer {
                                Text(
                                    text = progress.currentApiCall,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = progress.currentStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!progress.requestPayloadSummary.isNullOrBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = progress.requestPayloadSummary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                            if (!progress.lastHttpStatusOrError.isNullOrBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = progress.lastHttpStatusOrError,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Raw HTTP Response Payload Card
                    if (!progress.lastResponsePayload.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "LAST RESPONSE PAYLOAD",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText("Gemini Response Payload", progress.lastResponsePayload)
                                            )
                                            Toast.makeText(context, "Payload copied", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("copy_response_payload_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy response payload",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                SelectionContainer {
                                    Text(
                                        text = progress.lastResponsePayload,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("last_response_payload_text")
                                    )
                                }
                            }
                        }
                    }

                    // Interactive Model Switch & Retry Controls
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "MODEL & RETRY CONTROLS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Switch model for the next retry or interrupt a high-demand wait immediately.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                modelSuggestions.forEach { candidate ->
                                    FilterChip(
                                        selected = customModelDraft.trim() == candidate,
                                        onClick = { customModelDraft = candidate },
                                        label = { Text(candidate, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = customModelDraft,
                                onValueChange = { customModelDraft = it },
                                label = { Text("Target Gemini Model") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("retry_model_input"),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            val trimmedTarget = customModelDraft.trim()
                            val isDifferentModel = trimmedTarget.isNotBlank() && trimmedTarget != progress.modelId
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isDifferentModel) {
                                    OutlinedButton(
                                        onClick = { onSwitchModel(trimmedTarget, false) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("queue_retry_model_button"),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("Queue Next Retry", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Button(
                                        onClick = { onSwitchModel(trimmedTarget, true) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("switch_retry_now_button"),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("Switch & Retry Now", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else if (progress.isRetrying) {
                                    OutlinedButton(
                                        onClick = onRetryNow,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("retry_now_button")
                                    ) {
                                        Text("Retry Current Call Now")
                                    }
                                }
                            }
                        }
                    }

                    // Chronological Attempt History
                    if (progress.attemptHistory.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "WHAT HAPPENED SO FAR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                progress.attemptHistory.forEach { entry ->
                                    Text(
                                        text = "• $entry",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Steps for This File",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        progress.steps.forEach { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("progress_step_${step.id}"),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(18.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (step.status) {
                                        StepStatus.COMPLETED -> Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = "Completed",
                                            tint = sem.emerald,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        StepStatus.IN_PROGRESS -> CircularProgressIndicator(
                                            modifier = Modifier.size(15.dp),
                                            strokeWidth = 2.dp,
                                            color = sem.dailyBlue
                                        )
                                        StepStatus.RETRYING -> Icon(
                                            imageVector = Icons.Outlined.Loop,
                                            contentDescription = "Retrying",
                                            tint = sem.amber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        StepStatus.FAILED -> Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = "Failed",
                                            tint = sem.recordingCrimson,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        StepStatus.PENDING -> Icon(
                                            imageVector = Icons.Outlined.RadioButtonUnchecked,
                                            contentDescription = "Pending",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = step.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (step.status == StepStatus.IN_PROGRESS || step.status == StepStatus.RETRYING) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        color = when (step.status) {
                                            StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                                            StepStatus.FAILED -> sem.recordingCrimson
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    if (step.detail.isNotBlank()) {
                                        Text(
                                            text = step.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_processing_details_button")
            ) {
                Text("Close")
            }
        }
    )
}
