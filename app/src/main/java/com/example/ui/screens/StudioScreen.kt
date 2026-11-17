package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.RecordingState
import com.example.data.local.JobStatus
import com.example.data.local.ProcessingJobEntity
import com.example.data.vault.InboxAudioFile
import com.example.data.vault.InboxTextFile
import com.example.ui.components.SetupChecklistCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDurationMs
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.MainUiState

private enum class StudioCaptureMode { VOICE, TEXT }

@Composable
fun StudioScreen(
    state: MainUiState,
    onPickVault: () -> Unit,
    onOpenSyncTab: () -> Unit,
    onStartRecording: (String) -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopAndSaveRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onSubmitTextNote: (rawText: String, title: String, extension: String, processImmediately: Boolean) -> Unit,
    onProcessAllNow: () -> Unit,
    onProcessSingle: (String) -> Unit,
    onDeleteInboxAudio: (InboxAudioFile) -> Unit,
    onDeleteInboxText: (InboxTextFile) -> Unit,
    onRefreshInbox: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDeleteSession: (ProcessingJobEntity) -> Unit,
    onClearCompletedJobs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sem = LocalSynapseColors.current
    var captureMode by rememberSaveable { mutableStateOf(StudioCaptureMode.VOICE) }
    var sessionLabel by remember { mutableStateOf("DMN") }
    var textTitle by rememberSaveable { mutableStateOf("") }
    var textBody by rememberSaveable { mutableStateOf("") }
    var textExtension by rememberSaveable { mutableStateOf("md") }
    var permissionDeniedNotice by remember { mutableStateOf(false) }
    var jobPendingDelete by remember { mutableStateOf<ProcessingJobEntity?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNotice = false
            onStartRecording(sessionLabel)
        } else {
            permissionDeniedNotice = true
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
                VoiceRecorderHeroCard(
                    recordingState = state.recordingState,
                    elapsedMs = state.recordingElapsedMs,
                    amplitudes = state.recordingAmplitudes,
                    sessionLabel = sessionLabel,
                    onSessionLabelChange = { sessionLabel = it },
                    vaultConfigured = state.settings.isVaultConfigured,
                    permissionDeniedNotice = permissionDeniedNotice,
                    onRecordTap = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartRecording(sessionLabel)
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onPause = onPauseRecording,
                    onResume = onResumeRecording,
                    onStopAndSave = onStopAndSaveRecording,
                    onDiscard = onDiscardRecording
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
                        onSubmitTextNote(textBody, textTitle, textExtension, true)
                        textTitle = ""
                        textBody = ""
                    }
                )
            }
        }

        item {
            AnimatedVisibility(visible = state.isProcessingAudio || state.liveStatusMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = state.liveStatusMessage ?: "Processing DMN queue...",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
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
                    onClick = onProcessAllNow,
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
                    onProcess = { onProcessSingle(audio.uri.toString()) },
                    onDelete = { onDeleteInboxAudio(audio) }
                )
            }
            items(state.inboxTextFiles, key = { "text_${it.uri}" }) { textFile ->
                InboxTextCard(
                    textFile = textFile,
                    canProcess = !state.isProcessingAudio && state.settings.isGeminiConfigured,
                    onProcess = { onProcessSingle(textFile.uri.toString()) },
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
                    onRequestDelete = { jobPendingDelete = job }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
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
}

@Composable
private fun VoiceRecorderHeroCard(
    recordingState: RecordingState,
    elapsedMs: Long,
    amplitudes: List<Float>,
    sessionLabel: String,
    onSessionLabelChange: (String) -> Unit,
    vaultConfigured: Boolean,
    permissionDeniedNotice: Boolean,
    onRecordTap: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val infinite = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    val activeBorder = when (recordingState) {
        RecordingState.RECORDING -> sem.recordingCrimson.copy(alpha = pulseAlpha)
        RecordingState.PAUSED -> sem.amber.copy(alpha = 0.6f)
        RecordingState.IDLE -> sem.cardBorder
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, activeBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = when (recordingState) {
                        RecordingState.IDLE -> "Unbounded • Offline Ready"
                        RecordingState.RECORDING -> "Recording Live"
                        RecordingState.PAUSED -> "Paused"
                    },
                    color = when (recordingState) {
                        RecordingState.IDLE -> sem.amber
                        RecordingState.RECORDING -> sem.recordingCrimson
                        RecordingState.PAUSED -> sem.amber
                    },
                    containerColor = when (recordingState) {
                        RecordingState.IDLE -> sem.amberContainer
                        RecordingState.RECORDING -> sem.recordingCrimsonContainer
                        RecordingState.PAUSED -> sem.amberContainer
                    }
                )
                Text(
                    text = formatDurationMs(elapsedMs),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Live acoustic waveform Canvas
            val barActiveColor = if (recordingState == RecordingState.RECORDING) {
                sem.amber
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            }
            val barTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                val count = amplitudes.size.coerceAtLeast(1)
                val step = size.width / count
                val barWidth = (step * 0.58f).coerceAtLeast(3f)
                val centerY = size.height / 2f

                amplitudes.forEachIndexed { idx, amp ->
                    val barHeight = (amp * size.height).coerceIn(6f, size.height)
                    val x = idx * step + (step - barWidth) / 2f
                    drawRoundRect(
                        color = if (amp > 0.1f) barActiveColor else barTrackColor,
                        topLeft = Offset(x, centerY - barHeight / 2f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            if (recordingState == RecordingState.IDLE) {
                OutlinedTextField(
                    value = sessionLabel,
                    onValueChange = onSessionLabelChange,
                    label = { Text("Session Prefix (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session_label_input")
                )
            }

            if (permissionDeniedNotice) {
                Text(
                    text = "Microphone permission is required to record in-app. You can also drop recordings via Obsidian into 00_Audio_Inbox/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (recordingState == RecordingState.IDLE) {
                    Button(
                        onClick = onRecordTap,
                        enabled = vaultConfigured,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("start_recording_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = "Record",
                            tint = sem.amber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (vaultConfigured) "Start DMN Session" else "Select Vault First",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("discard_recording_button")
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Discard")
                    }
                    FilledTonalButton(
                        onClick = if (recordingState == RecordingState.RECORDING) onPause else onResume,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("pause_resume_recording_button")
                    ) {
                        Icon(
                            imageVector = if (recordingState == RecordingState.RECORDING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (recordingState == RecordingState.RECORDING) "Pause" else "Resume")
                    }
                    Button(
                        onClick = onStopAndSave,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("stop_save_recording_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = sem.emerald)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = Color(0xFF062116))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save & Queue", color = Color(0xFF062116), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
                    imageVector = Icons.Outlined.Article,
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
    onRequestDelete: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val status = runCatching { JobStatus.valueOf(job.status) }.getOrDefault(JobStatus.QUEUED)
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
                if (job.outputNotePath != null) {
                    Modifier.clickable { onOpenNote(job.outputNotePath) }
                } else {
                    Modifier
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
