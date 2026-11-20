package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import com.example.audio.BatchPipelineStage
import com.example.audio.LiveEngineMode
import com.example.audio.LiveStudioSessionState
import com.example.audio.PcmAudioEncoder
import com.example.audio.RecordingState
import com.example.data.local.VoiceDraftEntity
import com.example.data.prefs.VoiceTranscriptionMode
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDurationMs
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalSynapseColors

@Composable
internal fun VoiceTranscriptionStudioCard(
    liveSession: LiveStudioSessionState,
    selectedMode: VoiceTranscriptionMode,
    liveModelName: String,
    transcribeModelName: String,
    flashModelName: String,
    vaultConfigured: Boolean,
    geminiConfigured: Boolean,
    permissionDeniedNotice: Boolean,
    onSelectMode: (VoiceTranscriptionMode) -> Unit,
    onRecordOrContinueTap: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopForReview: () -> Unit,
    onRetryLive: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTranscriptChange: (String) -> Unit,
    onTranscribePending: () -> Unit,
    onRequestConfirmSend: () -> Unit,
    onNewDraft: () -> Unit,
    onRequestDeleteActiveDraft: () -> Unit
) {
    val context = LocalContext.current
    val sem = LocalSynapseColors.current
    val infinite = rememberInfiniteTransition(label = "live_rec_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    val isRecording = liveSession.recordingState == RecordingState.RECORDING
    val isPaused = liveSession.recordingState == RecordingState.PAUSED
    val isIdle = liveSession.recordingState == RecordingState.IDLE
    val hasPendingAudio = liveSession.untranscribedBytes >= (PcmAudioEncoder.BYTES_PER_SECOND / 2L)
    val pendingSeconds = (liveSession.untranscribedMs / 1000L).coerceAtLeast(if (hasPendingAudio) 1L else 0L)

    val wordCount = remember(liveSession.confirmedTranscript, liveSession.interimText) {
        val full = listOf(liveSession.confirmedTranscript, liveSession.interimText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (full.isEmpty()) 0 else full.split(Regex("""\s+""")).size
    }

    val isBatchRamblerSelected = selectedMode == VoiceTranscriptionMode.BATCH_RAMBLER

    val activeBorder = when {
        isRecording && liveSession.engineMode == LiveEngineMode.LIVE_STREAMING -> sem.emerald.copy(alpha = pulseAlpha)
        isRecording && liveSession.engineMode == LiveEngineMode.BATCH_RAMBLER_RECORDING -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
        isRecording -> sem.recordingCrimson.copy(alpha = pulseAlpha)
        isPaused || hasPendingAudio -> sem.amber.copy(alpha = 0.65f)
        else -> sem.cardBorder
    }

    val pillLabel = when (liveSession.engineMode) {
        LiveEngineMode.LIVE_STREAMING -> "Live • $liveModelName"
        LiveEngineMode.CONNECTING_LIVE -> "Connecting Live..."
        LiveEngineMode.BATCH_RAMBLER_RECORDING -> when (liveSession.batchPipelineStage) {
            BatchPipelineStage.STEP_1_TRANSCRIBING -> "Step 1/2 • Transcribing"
            BatchPipelineStage.STEP_2_RAMBLER_CLEANUP -> "Step 2/2 • Rambler Polish"
            BatchPipelineStage.IDLE -> "Batch • Rambler Mode"
        }
        LiveEngineMode.BATCH_FALLBACK -> "Batched • $transcribeModelName"
        LiveEngineMode.OFFLINE_RECORDING -> "Offline • Voice Safe"
        LiveEngineMode.PAUSED_MIC_OFF -> "Paused • Mic Off"
        LiveEngineMode.FINALIZING -> when (liveSession.batchPipelineStage) {
            BatchPipelineStage.STEP_1_TRANSCRIBING -> "Step 1/2 • Transcribing..."
            BatchPipelineStage.STEP_2_RAMBLER_CLEANUP -> "Step 2/2 • Rambler Polish..."
            BatchPipelineStage.IDLE -> "Finishing Transcription..."
        }
        LiveEngineMode.IDLE -> when {
            hasPendingAudio -> "Draft Saved • Audio Safe"
            liveSession.confirmedTranscript.isNotBlank() -> "Draft Saved • Ready"
            isBatchRamblerSelected -> "Batch • 2-Step Rambler"
            else -> "Live • Direct Output"
        }
    }

    val pillColor = when (liveSession.engineMode) {
        LiveEngineMode.LIVE_STREAMING -> sem.emerald
        LiveEngineMode.BATCH_RAMBLER_RECORDING, LiveEngineMode.FINALIZING -> MaterialTheme.colorScheme.primary
        LiveEngineMode.CONNECTING_LIVE, LiveEngineMode.BATCH_FALLBACK,
        LiveEngineMode.OFFLINE_RECORDING, LiveEngineMode.PAUSED_MIC_OFF -> sem.amber
        LiveEngineMode.IDLE -> when {
            hasPendingAudio -> sem.amber
            isBatchRamblerSelected -> MaterialTheme.colorScheme.primary
            else -> sem.emerald
        }
    }

    val pillBg = when (liveSession.engineMode) {
        LiveEngineMode.LIVE_STREAMING -> sem.emeraldContainer
        LiveEngineMode.BATCH_RAMBLER_RECORDING, LiveEngineMode.FINALIZING -> MaterialTheme.colorScheme.primaryContainer
        LiveEngineMode.CONNECTING_LIVE, LiveEngineMode.BATCH_FALLBACK,
        LiveEngineMode.OFFLINE_RECORDING, LiveEngineMode.PAUSED_MIC_OFF -> sem.amberContainer
        LiveEngineMode.IDLE -> when {
            hasPendingAudio -> sem.amberContainer
            isBatchRamblerSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> sem.emeraldContainer
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, activeBorder)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Status Pill + Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatusPill(
                        label = pillLabel,
                        color = pillColor,
                        containerColor = pillBg
                    )
                    Text(
                        text = "Screen Always On • $wordCount words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("screen_awake_label")
                    )
                }
                Text(
                    text = formatDurationMs(liveSession.elapsedMs),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Switchable Transcription Mode Selector: Live Transcribe vs Batch + Rambler (2-step)
            VoiceModeToggleBar(
                selectedMode = selectedMode,
                enabled = !isRecording && !liveSession.isBusyTranscribingPending && !liveSession.isSubmittingForDmn,
                liveModelName = liveModelName,
                transcribeModelName = transcribeModelName,
                flashModelName = flashModelName,
                activeBatchStage = liveSession.batchPipelineStage,
                onSelectMode = onSelectMode
            )

            // Live -> Batched Fallback Notice with Retry Live option
            AnimatedVisibility(visible = !liveSession.fallbackNotice.isNullOrBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = sem.amberContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, sem.amber.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = liveSession.fallbackNotice.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (liveSession.completedBatchCount > 0 || liveSession.inFlightBatchCount > 0) {
                                Text(
                                    text = "Batches: ${liveSession.completedBatchCount} done" +
                                        (if (liveSession.inFlightBatchCount > 0) " • ${liveSession.inFlightBatchCount} processing" else "") +
                                        (if (liveSession.pendingRetryBatchCount > 0) " • retrying safely" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sem.amber
                                )
                            }
                        }
                        if (isRecording && geminiConfigured) {
                            TextButton(
                                onClick = onRetryLive,
                                modifier = Modifier.testTag("retry_live_button")
                            ) {
                                Text("Retry Live")
                            }
                        }
                    }
                }
            }

            // Acoustic Waveform (expanded while recording/paused, subtle when idle)
            if (!isIdle || !liveSession.hasContent) {
                val barActiveColor = when {
                    isRecording && liveSession.engineMode == LiveEngineMode.LIVE_STREAMING -> sem.emerald
                    isRecording && liveSession.engineMode == LiveEngineMode.BATCH_RAMBLER_RECORDING -> MaterialTheme.colorScheme.primary
                    isRecording -> sem.amber
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                }
                val barTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    val count = liveSession.amplitudes.size.coerceAtLeast(1)
                    val step = size.width / count
                    val barWidth = (step * 0.58f).coerceAtLeast(3f)
                    val centerY = size.height / 2f

                    liveSession.amplitudes.forEachIndexed { idx, amp ->
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
            }

            // Status / Engine Telemetry line
            if (!liveSession.statusMessage.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (liveSession.inFlightBatchCount > 0 ||
                        liveSession.engineMode == LiveEngineMode.CONNECTING_LIVE ||
                        liveSession.engineMode == LiveEngineMode.FINALIZING ||
                        liveSession.isBusyTranscribingPending
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (hasPendingAudio) Icons.Outlined.Shield else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (hasPendingAudio) sem.amber else sem.emerald,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = liveSession.statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Safe Audio Backup Banner (when untranscribed audio remains after pause/stop/offline/error)
            AnimatedVisibility(visible = hasPendingAudio && !isRecording) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = sem.amberContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, sem.amber.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudOff,
                                contentDescription = null,
                                tint = sem.amber,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Voice Backup Safe (${pendingSeconds}s pending)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Audio stays protected on device until 100% transcribed, even if you close the app.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onTranscribePending,
                            enabled = geminiConfigured && !liveSession.isBusyTranscribingPending && !liveSession.isSubmittingForDmn,
                            modifier = Modifier.testTag("transcribe_pending_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (liveSession.isBusyTranscribingPending) "..." else "Transcribe")
                        }
                    }
                }
            }

            // Draft Title Field (when idle or paused)
            if (!isRecording) {
                OutlinedTextField(
                    value = liveSession.draftTitle,
                    onValueChange = onTitleChange,
                    label = { Text("Draft Title (optional)") },
                    placeholder = { Text("e.g., Morning Architecture Ideas") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session_label_input")
                )
            }

            // Real-Time Streaming Transcript Box (while recording) OR Editable Saved Transcript (when paused/idle)
            if (isRecording) {
                val streamScroll = rememberScrollState()
                LaunchedEffect(liveSession.confirmedTranscript.length, liveSession.interimText.length) {
                    streamScroll.animateScrollTo(streamScroll.maxValue)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp)
                        .testTag("live_transcript_stream_box"),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(streamScroll)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (liveSession.confirmedTranscript.isBlank() && liveSession.interimText.isBlank()) {
                            Text(
                                text = when (liveSession.engineMode) {
                                    LiveEngineMode.OFFLINE_RECORDING -> "Recording voice safely to device storage. Add your API key or reconnect later to transcribe."
                                    LiveEngineMode.BATCH_RAMBLER_RECORDING -> "Speak freely — Step 1 transcribes via $transcribeModelName, Step 2 applies Rambler cleanup via $flashModelName before displaying."
                                    LiveEngineMode.BATCH_FALLBACK -> "Speak freely — transcribing in progressive batches via $transcribeModelName while buffering audio safely..."
                                    else -> "Listening... Your speech will appear here in real time."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                        } else {
                            val liveAnnotated = buildAnnotatedString {
                                if (liveSession.confirmedTranscript.isNotBlank()) {
                                    append(liveSession.confirmedTranscript)
                                }
                                if (liveSession.interimText.isNotBlank()) {
                                    if (liveSession.confirmedTranscript.isNotBlank()) append(" ")
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontStyle = FontStyle.Italic
                                        )
                                    ) {
                                        append(liveSession.interimText)
                                    }
                                }
                            }
                            Text(
                                text = liveAnnotated,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else if (liveSession.hasContent || liveSession.activeDraftId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Saved Transcription (Auto-Saved & Editable)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (liveSession.confirmedTranscript.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Voice Transcript", liveSession.confirmedTranscript))
                                    Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.testTag("copy_draft_transcript_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy transcript",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = liveSession.confirmedTranscript,
                        onValueChange = onTranscriptChange,
                        placeholder = {
                            Text(
                                if (hasPendingAudio) {
                                    "Tap Transcribe above when online, or type notes here..."
                                } else {
                                    "Review or edit your transcription before confirming DMN Graph synthesis..."
                                }
                            )
                        },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("draft_transcript_editor")
                    )
                }
            }

            if (permissionDeniedNotice) {
                Text(
                    text = "Microphone permission is required to record in-app. You can also drop audio files into 00_Audio_Inbox/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Action Controls
            when {
                isRecording -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("pause_resume_recording_button")
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause (Mic Off)")
                        }
                        Button(
                            onClick = onStopForReview,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_review_recording_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = sem.emerald)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, tint = Color(0xFF062116))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop & Review", color = Color(0xFF062116), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                isPaused -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("pause_resume_recording_button")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resume Mic")
                        }
                        FilledTonalButton(
                            onClick = onStopForReview,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_review_recording_button")
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Done Recording")
                        }
                    }
                }

                else -> {
                    // IDLE: either fresh start or review/continue/confirm existing saved draft
                    if (!liveSession.hasContent && liveSession.activeDraftId == null) {
                        Button(
                            onClick = onRecordOrContinueTap,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("start_recording_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = if (isBatchRamblerSelected) "Start Batch + Rambler" else "Start Live Transcription",
                                tint = sem.amber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBatchRamblerSelected) "Start Batch + Rambler" else "Start Live Transcription",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onRecordOrContinueTap,
                                enabled = !liveSession.isSubmittingForDmn && !liveSession.isBusyTranscribingPending,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("continue_recording_button")
                            ) {
                                Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Continue")
                            }
                            OutlinedButton(
                                onClick = onNewDraft,
                                enabled = !liveSession.isSubmittingForDmn && !liveSession.isBusyTranscribingPending,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("new_draft_button")
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New")
                            }
                            IconButton(
                                onClick = onRequestDeleteActiveDraft,
                                enabled = !liveSession.isSubmittingForDmn,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("delete_active_draft_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete draft",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Button(
                            onClick = onRequestConfirmSend,
                            enabled = vaultConfigured && geminiConfigured &&
                                (liveSession.confirmedTranscript.isNotBlank() || hasPendingAudio) &&
                                !liveSession.isSubmittingForDmn && !liveSession.isBusyTranscribingPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("confirm_send_dmn_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = sem.emerald)
                        ) {
                            if (liveSession.isSubmittingForDmn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF062116)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Preparing for DMN Graph...", color = Color(0xFF062116), fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Send,
                                    contentDescription = null,
                                    tint = Color(0xFF062116),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Confirm & Send to $flashModelName",
                                    color = Color(0xFF062116),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!vaultConfigured || !geminiConfigured) {
                            Text(
                                text = "Draft is safely saved locally. Link your Vault & Gemini API key in Sync to send to DMN Graph.",
                                style = MaterialTheme.typography.labelSmall,
                                color = sem.amber
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceModeToggleBar(
    selectedMode: VoiceTranscriptionMode,
    enabled: Boolean,
    liveModelName: String,
    transcribeModelName: String,
    flashModelName: String,
    activeBatchStage: BatchPipelineStage,
    onSelectMode: (VoiceTranscriptionMode) -> Unit
) {
    val sem = LocalSynapseColors.current
    val isLive = selectedMode == VoiceTranscriptionMode.LIVE
    val isBatch = selectedMode == VoiceTranscriptionMode.BATCH_RAMBLER

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelectMode(VoiceTranscriptionMode.LIVE) }
                        .testTag("voice_mode_live"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isLive) sem.emeraldContainer else Color.Transparent,
                    border = if (isLive) BorderStroke(1.dp, sem.emerald.copy(alpha = 0.65f)) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = "Live Transcribe mode",
                            tint = if (isLive) sem.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Live Transcribe",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isLive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isLive) sem.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Direct live output",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelectMode(VoiceTranscriptionMode.BATCH_RAMBLER) }
                        .testTag("voice_mode_batch_rambler"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isBatch) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    border = if (isBatch) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Batch Rambler mode",
                            tint = if (isBatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Batch • Rambler",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isBatch) FontWeight.Bold else FontWeight.Medium,
                                color = if (isBatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "2-step clean prose",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (isBatch) {
            val step1Active = activeBatchStage == BatchPipelineStage.STEP_1_TRANSCRIBING
            val step2Active = activeBatchStage == BatchPipelineStage.STEP_2_RAMBLER_CLEANUP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("batch_rambler_pipeline_strip"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (step1Active) sem.amberContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (step1Active) sem.amber else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (step1Active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.8.dp,
                                color = sem.amber
                            )
                        }
                        Text(
                            text = "1. $transcribeModelName",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (step1Active) FontWeight.Bold else FontWeight.Medium,
                            color = if (step1Active) sem.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (step2Active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (step2Active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (step2Active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "2. Rambler ($flashModelName)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (step2Active) FontWeight.Bold else FontWeight.Medium,
                            color = if (step2Active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Direct streaming via $liveModelName (auto-falls back to $transcribeModelName if overloaded).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SavedVoiceDraftRow(
    draft: VoiceDraftEntity,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val hasPending = draft.untranscribedBytes >= (PcmAudioEncoder.BYTES_PER_SECOND / 2L)
    val pendingSec = (PcmAudioEncoder.computeDurationMs(draft.untranscribedBytes) / 1000L).coerceAtLeast(if (hasPending) 1L else 0L)
    val words = remember(draft.transcriptText) {
        if (draft.transcriptText.isBlank()) 0 else draft.transcriptText.trim().split(Regex("""\s+""")).size
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { onSelect() }
            .testTag("saved_draft_${draft.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (hasPending) sem.amberContainer else sem.emeraldContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasPending) Icons.Outlined.Shield else Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = if (hasPending) sem.amber else sem.emerald,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.title.ifBlank { "Untitled Voice Draft" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("$words words • ${formatDurationMs(draft.durationMs)}")
                        if (hasPending) append(" • ${pendingSec}s audio backup")
                        append(" • ${formatRelativeTimestamp(draft.updatedAtMs)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasPending) sem.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (draft.transcriptText.isNotBlank()) {
                    Text(
                        text = draft.transcriptText.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.testTag("delete_draft_${draft.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete saved draft",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ConfirmSendDraftDialog(
    liveSession: LiveStudioSessionState,
    flashModelName: String,
    transcribeModelName: String,
    onConfirmSend: (transcribePendingFirst: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val hasPendingAudio = liveSession.untranscribedBytes >= (PcmAudioEncoder.BYTES_PER_SECOND / 2L)
    val pendingSec = (liveSession.untranscribedMs / 1000L).coerceAtLeast(if (hasPendingAudio) 1L else 0L)
    val wordCount = remember(liveSession.confirmedTranscript) {
        if (liveSession.confirmedTranscript.isBlank()) 0 else liveSession.confirmedTranscript.trim().split(Regex("""\s+""")).size
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Send Transcript to DMN Graph?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Draft: ${liveSession.draftTitle.ifBlank { "Voice Draft" }} ($wordCount words)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "This will send your confirmed transcription to $flashModelName to extract WikiLinks, DMN insights, and update today's Daily Note + 98_Transcript_Archive/. Voice audio will not be archived.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasPendingAudio) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = sem.amberContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, sem.amber.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Note: ${pendingSec}s of recorded audio has not been transcribed yet. Recommended: transcribe remaining audio with $transcribeModelName first so zero speech is lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSend(true) },
                modifier = Modifier.testTag("dialog_confirm_send_dmn_button")
            ) {
                Text(if (hasPendingAudio) "Transcribe Pending & Send" else "Confirm & Process")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasPendingAudio && liveSession.confirmedTranscript.isNotBlank()) {
                    TextButton(
                        onClick = { onConfirmSend(false) },
                        modifier = Modifier.testTag("dialog_send_text_only_button")
                    ) {
                        Text("Text Only")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Keep Editing")
                }
            }
        }
    )
}
