package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.data.gemini.GeminiDmnService
import com.example.data.gemini.GeminiLiveTranscribeClient
import com.example.data.local.VoiceDraftDao
import com.example.data.local.VoiceDraftEntity
import com.example.data.prefs.VaultSettings
import com.example.data.prefs.VoiceTranscriptionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

enum class LiveEngineMode(val label: String) {
    IDLE("Draft Ready"),
    CONNECTING_LIVE("Connecting Live"),
    LIVE_STREAMING("Live Streaming"),
    BATCH_RAMBLER_RECORDING("Batch • Rambler"),
    BATCH_FALLBACK("Batched Fallback"),
    OFFLINE_RECORDING("Offline Safe Audio"),
    PAUSED_MIC_OFF("Paused • Mic Off"),
    FINALIZING("Finishing Transcription")
}

enum class BatchPipelineStage {
    IDLE,
    STEP_1_TRANSCRIBING,
    STEP_2_RAMBLER_CLEANUP
}

data class LiveStudioSessionState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val activeDraftId: String? = null,
    val draftTitle: String = "",
    val elapsedMs: Long = 0L,
    val amplitudes: List<Float> = List(36) { 0.08f },
    val engineMode: LiveEngineMode = LiveEngineMode.IDLE,
    val batchPipelineStage: BatchPipelineStage = BatchPipelineStage.IDLE,
    val confirmedTranscript: String = "",
    val interimText: String = "",
    val statusMessage: String? = null,
    val fallbackNotice: String? = null,
    val completedBatchCount: Int = 0,
    val inFlightBatchCount: Int = 0,
    val pendingRetryBatchCount: Int = 0,
    val totalPcmBytes: Long = 0L,
    val transcribedByteOffset: Long = 0L,
    val hasSafeAudioBackup: Boolean = false,
    val isBusyTranscribingPending: Boolean = false,
    val isSubmittingForDmn: Boolean = false
) {
    val untranscribedBytes: Long
        get() = (totalPcmBytes - transcribedByteOffset).coerceAtLeast(0L)

    val untranscribedMs: Long
        get() = PcmAudioEncoder.computeDurationMs(untranscribedBytes)

    val hasContent: Boolean
        get() = confirmedTranscript.isNotBlank() || interimText.isNotBlank() || totalPcmBytes > 0L || elapsedMs > 0L
}

/**
 * Unified durable speech-to-transcription capture engine:
 * - Records 16kHz 16-bit mono PCM continuously to internal durable storage (`filesDir/voice_drafts/<id>/session.pcm`)
 *   so zero speech is lost across Live drops, high-demand API errors, offline mode, or app restarts.
 * - Connects to `gemini-3.5-transcribe-live` first for real-time streaming; seamlessly falls back to
 *   progressive batched `gemini-3.5-transcribe` slices on any Live error/overload without stopping the mic.
 * - Releases the microphone completely (`Paused • Mic Off`) when paused; resuming appends seamlessly.
 * - Automatically deletes temporary PCM voice audio once 100% of audio is transcribed and safely persisted
 *   in Room (`voice_drafts`), while keeping audio safely preserved whenever any untranscribed audio remains.
 */
class LiveTranscriptionSessionManager(
    private val context: Context,
    private val geminiService: GeminiDmnService,
    private val draftDao: VoiceDraftDao
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Mutex()
    private val batchTranscribeLock = Mutex()

    private val _sessionState = MutableStateFlow(LiveStudioSessionState())
    val sessionState: StateFlow<LiveStudioSessionState> = _sessionState.asStateFlow()

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var pcmOutputStream: FileOutputStream? = null
    private val isCaptureActive = AtomicBoolean(false)
    private val isLiveStreamingActive = AtomicBoolean(false)
    private val lastQuietDurationMs = AtomicLong(0L)

    private var captureJob: Job? = null
    private var liveConnectJob: Job? = null
    private var batchLoopJob: Job? = null
    private var liveClient: GeminiLiveTranscribeClient? = null

    private var activeDraftCreatedAtMs: Long = System.currentTimeMillis()
    private var activePcmFile: File? = null
    private var basePriorDurationMs: Long = 0L
    private var totalPcmBytesWritten: Long = 0L
    private var transcribedByteOffset: Long = 0L
    private var liveSegmentStartByte: Long = 0L
    private var liveReceivedFinalSinceConnect: Boolean = false
    private var liveFailedInCurrentSession: Boolean = false

    private var currentApiKey: String = ""
    private var currentLiveModel: String = GeminiDmnService.DEFAULT_LIVE_TRANSCRIBE_MODEL
    private var currentTranscribeModel: String = GeminiDmnService.DEFAULT_TRANSCRIBE_MODEL
    private var currentFlashModel: String = VaultSettings.DEFAULT_GEMINI_MODEL
    private var currentMode: VoiceTranscriptionMode = VoiceTranscriptionMode.LIVE

    companion object {
        private const val BATCH_TARGET_BYTES = 12L * PcmAudioEncoder.BYTES_PER_SECOND // 12s chunks
        private const val BATCH_MIN_SILENCE_BYTES = 6L * PcmAudioEncoder.BYTES_PER_SECOND // 6s min before silence split
        private const val MIN_TAIL_FLUSH_BYTES = PcmAudioEncoder.BYTES_PER_SECOND / 2L // 0.5s
        private const val MAX_INLINE_CHUNK_BYTES = 45L * PcmAudioEncoder.BYTES_PER_SECOND // 45s safe inline WAV cap
        private const val LIVE_OVERLAP_SAFETY_BYTES = 3L * PcmAudioEncoder.BYTES_PER_SECOND / 2L // 1.5s safety overlap
        private const val QUIET_RMS_THRESHOLD = 0.045f
        private const val QUIET_SPLIT_MS = 750L

        /**
         * Merges an incoming transcript segment into existing transcript text cleanly,
         * removing minor overlapping words at the boundary when falling back from Live to Batched.
         */
        fun mergeTranscriptText(existing: String, incoming: String): String {
            val cleanIncoming = incoming.trim()
            if (cleanIncoming.isEmpty()) return existing
            val cleanExisting = existing.trimEnd()
            if (cleanExisting.isEmpty()) return cleanIncoming

            val existingWords = cleanExisting.split(Regex("""\s+"""))
            val incomingWords = cleanIncoming.split(Regex("""\s+"""))
            val maxOverlap = minOf(5, existingWords.size, incomingWords.size)
            var overlapCount = 0
            for (k in maxOverlap downTo 1) {
                val tail = existingWords.takeLast(k).map { normalizeOverlapToken(it) }
                val head = incomingWords.take(k).map { normalizeOverlapToken(it) }
                if (tail == head && tail.all { it.isNotEmpty() }) {
                    overlapCount = k
                    break
                }
            }

            val dedupedIncoming = if (overlapCount > 0) {
                incomingWords.drop(overlapCount).joinToString(" ").trim()
            } else {
                cleanIncoming
            }
            if (dedupedIncoming.isEmpty()) return cleanExisting

            val needsSpace = !cleanExisting.endsWith("\n") && !cleanExisting.endsWith(" ")
            return if (needsSpace) "$cleanExisting $dedupedIncoming" else "$cleanExisting$dedupedIncoming"
        }

        private fun normalizeOverlapToken(word: String): String {
            return word.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9']"""), "")
        }

        fun formatDefaultDraftTitle(nowMs: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(nowMs))
            return "Voice Draft $stamp"
        }
    }

    private fun draftFolder(draftId: String): File {
        return File(File(context.filesDir, "voice_drafts"), draftId)
    }

    private fun draftPcmFile(draftId: String): File {
        return File(draftFolder(draftId), "session.pcm")
    }

    suspend fun loadDraft(draft: VoiceDraftEntity) = stateLock.withLock {
        if (_sessionState.value.recordingState != RecordingState.IDLE) return@withLock
        val pcmFile = File(draft.pcmFilePath)
        val actualBytes = if (pcmFile.exists()) pcmFile.length() else 0L
        val safeOffset = draft.transcribedByteOffset.coerceIn(0L, actualBytes)
        val pcmDurationMs = PcmAudioEncoder.computeDurationMs(actualBytes)
        basePriorDurationMs = (draft.durationMs - pcmDurationMs).coerceAtLeast(0L)
        activeDraftCreatedAtMs = draft.createdAtMs
        activePcmFile = pcmFile
        totalPcmBytesWritten = actualBytes
        transcribedByteOffset = safeOffset
        liveSegmentStartByte = safeOffset
        liveFailedInCurrentSession = false

        val pendingBytes = (actualBytes - safeOffset).coerceAtLeast(0L)
        _sessionState.value = LiveStudioSessionState(
            recordingState = RecordingState.IDLE,
            activeDraftId = draft.id,
            draftTitle = draft.title,
            elapsedMs = draft.durationMs.coerceAtLeast(pcmDurationMs),
            engineMode = LiveEngineMode.IDLE,
            confirmedTranscript = draft.transcriptText,
            interimText = "",
            statusMessage = when {
                pendingBytes > 0L -> "Audio safely stored (${PcmAudioEncoder.computeDurationMs(pendingBytes) / 1000L}s pending transcription)"
                draft.transcriptText.isNotBlank() -> "Transcription saved • Temp audio cleared"
                else -> null
            },
            fallbackNotice = null,
            totalPcmBytes = actualBytes,
            transcribedByteOffset = safeOffset,
            hasSafeAudioBackup = actualBytes > 0L
        )
    }

    suspend fun startNewEmptyDraft(initialTitle: String = "") = stateLock.withLock {
        if (_sessionState.value.recordingState != RecordingState.IDLE) return@withLock
        activePcmFile = null
        basePriorDurationMs = 0L
        totalPcmBytesWritten = 0L
        transcribedByteOffset = 0L
        liveSegmentStartByte = 0L
        liveFailedInCurrentSession = false
        activeDraftCreatedAtMs = System.currentTimeMillis()
        _sessionState.value = LiveStudioSessionState(
            draftTitle = initialTitle.trim()
        )
    }

    fun updateDraftTitle(newTitle: String) {
        _sessionState.update { it.copy(draftTitle = newTitle) }
        val activeId = _sessionState.value.activeDraftId
        if (!activeId.isNullOrBlank()) {
            managerScope.launch { persistActiveDraftLocked() }
        }
    }

    fun updateDraftTranscript(newText: String) {
        _sessionState.update { it.copy(confirmedTranscript = newText) }
        managerScope.launch {
            stateLock.withLock {
                if (_sessionState.value.activeDraftId == null && newText.isNotBlank()) {
                    val newId = UUID.randomUUID().toString()
                    activeDraftCreatedAtMs = System.currentTimeMillis()
                    activePcmFile = draftPcmFile(newId)
                    val title = _sessionState.value.draftTitle.trim().ifBlank { formatDefaultDraftTitle(activeDraftCreatedAtMs) }
                    _sessionState.update { it.copy(activeDraftId = newId, draftTitle = title) }
                }
                if (_sessionState.value.activeDraftId != null) {
                    persistActiveDraftInternal()
                }
            }
        }
    }

    suspend fun startOrContinueRecording(
        apiKey: String,
        liveModelId: String,
        transcribeModelId: String,
        flashModelId: String = VaultSettings.DEFAULT_GEMINI_MODEL,
        mode: VoiceTranscriptionMode = VoiceTranscriptionMode.LIVE,
        requestedTitle: String
    ): Result<Unit> = stateLock.withLock {
        if (_sessionState.value.recordingState == RecordingState.RECORDING) {
            return@withLock Result.success(Unit)
        }
        currentApiKey = apiKey.trim()
        currentLiveModel = liveModelId.trim().ifBlank { GeminiDmnService.DEFAULT_LIVE_TRANSCRIBE_MODEL }
        currentTranscribeModel = transcribeModelId.trim().ifBlank { GeminiDmnService.DEFAULT_TRANSCRIBE_MODEL }
        currentFlashModel = flashModelId.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
        currentMode = mode
        if (mode == VoiceTranscriptionMode.BATCH_RAMBLER) {
            liveFailedInCurrentSession = false
        }

        val current = _sessionState.value
        val draftId = current.activeDraftId ?: UUID.randomUUID().toString()
        val isBrandNew = current.activeDraftId == null
        if (isBrandNew) {
            activeDraftCreatedAtMs = System.currentTimeMillis()
            basePriorDurationMs = 0L
        }
        val title = requestedTitle.trim()
            .ifBlank { current.draftTitle.trim() }
            .ifBlank { formatDefaultDraftTitle(activeDraftCreatedAtMs) }

        val pcmFile = draftPcmFile(draftId)
        pcmFile.parentFile?.mkdirs()
        val existingPcmBytes = if (pcmFile.exists()) pcmFile.length() else 0L
        if (existingPcmBytes == 0L) {
            // Continuing after previous audio was completely transcribed and purged
            basePriorDurationMs = current.elapsedMs
            totalPcmBytesWritten = 0L
            transcribedByteOffset = 0L
            liveSegmentStartByte = 0L
        } else {
            totalPcmBytesWritten = existingPcmBytes
            transcribedByteOffset = current.transcribedByteOffset.coerceIn(0L, existingPcmBytes)
            liveSegmentStartByte = transcribedByteOffset
        }
        activePcmFile = pcmFile

        val startMicResult = startAudioRecordCapture(pcmFile)
        if (startMicResult.isFailure) {
            return@withLock startMicResult
        }

        val hasApiKey = currentApiKey.isNotBlank()
        val initialEngineMode = when {
            !hasApiKey -> LiveEngineMode.OFFLINE_RECORDING
            currentMode == VoiceTranscriptionMode.BATCH_RAMBLER -> LiveEngineMode.BATCH_RAMBLER_RECORDING
            liveFailedInCurrentSession -> LiveEngineMode.BATCH_FALLBACK
            else -> LiveEngineMode.CONNECTING_LIVE
        }

        _sessionState.update {
            it.copy(
                recordingState = RecordingState.RECORDING,
                activeDraftId = draftId,
                draftTitle = title,
                engineMode = initialEngineMode,
                batchPipelineStage = BatchPipelineStage.IDLE,
                interimText = "",
                fallbackNotice = if (currentMode == VoiceTranscriptionMode.BATCH_RAMBLER) null else it.fallbackNotice,
                statusMessage = when (initialEngineMode) {
                    LiveEngineMode.OFFLINE_RECORDING -> "Recording offline — voice safely saved on device until API key is added"
                    LiveEngineMode.BATCH_RAMBLER_RECORDING -> "Batch Rambler: Step 1 ($currentTranscribeModel) → Step 2 ($currentFlashModel)"
                    LiveEngineMode.BATCH_FALLBACK -> "Recording with Batched $currentTranscribeModel — voice safely backed up"
                    else -> "Connecting to $currentLiveModel (voice safely recording)..."
                },
                totalPcmBytes = totalPcmBytesWritten,
                transcribedByteOffset = transcribedByteOffset,
                hasSafeAudioBackup = true
            )
        }
        persistActiveDraftInternal()

        if (hasApiKey) {
            when (initialEngineMode) {
                LiveEngineMode.BATCH_RAMBLER_RECORDING,
                LiveEngineMode.BATCH_FALLBACK -> launchBatchTranscribeLoop()
                else -> launchLiveConnection()
            }
        }
        Result.success(Unit)
    }

    suspend fun pauseRecording() {
        val wasRecording = stateLock.withLock {
            if (_sessionState.value.recordingState != RecordingState.RECORDING) return
            stopAudioRecordHardware()
            _sessionState.update {
                it.copy(
                    recordingState = RecordingState.PAUSED,
                    engineMode = LiveEngineMode.PAUSED_MIC_OFF,
                    amplitudes = List(36) { 0.08f },
                    statusMessage = if (currentMode == VoiceTranscriptionMode.BATCH_RAMBLER) {
                        "Paused • Mic released. Running 2-step transcribe + Rambler polish on remaining speech..."
                    } else {
                        "Paused • Mic released. Flushing remaining speech..."
                    }
                )
            }
            true
        }
        if (!wasRecording) return

        // Let live model emit final trailing words briefly, then close WS
        closeLiveClientGracefully()
        batchLoopJob?.cancelAndJoin()
        batchLoopJob = null

        // Flush any untranscribed tail via batched gemini-3.5-transcribe (+ Step 2 Flash Rambler in Batch mode)
        if (currentApiKey.isNotBlank()) {
            flushPendingPcmSlices(
                minBytes = MIN_TAIL_FLUSH_BYTES,
                applyRamblerCleanup = currentMode == VoiceTranscriptionMode.BATCH_RAMBLER
            )
        }

        stateLock.withLock {
            if (_sessionState.value.recordingState == RecordingState.PAUSED) {
                commitInterimIfAny()
                persistActiveDraftInternal()
                maybePurgeAudioIfCompletelySafe(allowPurgeWhilePaused = true)
                val pendingSec = _sessionState.value.untranscribedMs / 1000L
                _sessionState.update {
                    it.copy(
                        batchPipelineStage = BatchPipelineStage.IDLE,
                        statusMessage = when {
                            pendingSec > 0L -> "Paused • Mic Off (${pendingSec}s voice safely backed up on device)"
                            it.confirmedTranscript.isNotBlank() && currentMode == VoiceTranscriptionMode.BATCH_RAMBLER ->
                                "Paused • Mic Off (Rambler-cleaned & saved)"
                            it.confirmedTranscript.isNotBlank() -> "Paused • Mic Off (100% transcribed & saved)"
                            else -> "Paused • Mic Off"
                        }
                    )
                }
            }
        }
    }

    suspend fun resumeRecording(
        apiKey: String,
        liveModelId: String,
        transcribeModelId: String,
        flashModelId: String = VaultSettings.DEFAULT_GEMINI_MODEL,
        mode: VoiceTranscriptionMode = VoiceTranscriptionMode.LIVE
    ): Result<Unit> {
        val title = _sessionState.value.draftTitle
        return startOrContinueRecording(
            apiKey = apiKey,
            liveModelId = liveModelId,
            transcribeModelId = transcribeModelId,
            flashModelId = flashModelId,
            mode = mode,
            requestedTitle = title
        )
    }

    /**
     * Stops recording, releases the mic, finishes transcribing any remaining audio segments
     * (applying Step 2 Rambler Flash cleanup when in Batch mode), saves the draft persistently
     * in Room (and purges temporary PCM audio ONLY if 100% transcribed & safe).
     * NEVER triggers Flash DMN Graph processing automatically.
     */
    suspend fun stopRecordingForReview(
        apiKey: String,
        transcribeModelId: String,
        flashModelId: String = VaultSettings.DEFAULT_GEMINI_MODEL,
        mode: VoiceTranscriptionMode = currentMode
    ) {
        currentApiKey = apiKey.trim()
        currentTranscribeModel = transcribeModelId.trim().ifBlank { GeminiDmnService.DEFAULT_TRANSCRIBE_MODEL }
        currentFlashModel = flashModelId.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
        currentMode = mode

        val shouldProceed = stateLock.withLock {
            if (_sessionState.value.recordingState == RecordingState.IDLE) return@withLock false
            stopAudioRecordHardware()
            _sessionState.update {
                it.copy(
                    recordingState = RecordingState.IDLE,
                    engineMode = LiveEngineMode.FINALIZING,
                    amplitudes = List(36) { 0.08f },
                    statusMessage = if (currentMode == VoiceTranscriptionMode.BATCH_RAMBLER) {
                        "Finishing 2-step transcription & Rambler polish..."
                    } else {
                        "Finishing transcription & verifying saved draft..."
                    }
                )
            }
            true
        }
        if (!shouldProceed) return

        closeLiveClientGracefully()
        batchLoopJob?.cancelAndJoin()
        batchLoopJob = null

        if (currentApiKey.isNotBlank()) {
            flushPendingPcmSlices(
                minBytes = MIN_TAIL_FLUSH_BYTES,
                applyRamblerCleanup = currentMode == VoiceTranscriptionMode.BATCH_RAMBLER
            )
        }

        stateLock.withLock {
            commitInterimIfAny()
            persistActiveDraftInternal()
            maybePurgeAudioIfCompletelySafe(allowPurgeWhilePaused = true)
            val st = _sessionState.value
            val pendingSec = st.untranscribedMs / 1000L
            _sessionState.update {
                it.copy(
                    engineMode = LiveEngineMode.IDLE,
                    batchPipelineStage = BatchPipelineStage.IDLE,
                    statusMessage = when {
                        pendingSec > 0L -> "Draft saved • ${pendingSec}s voice kept safely on device for later transcription"
                        st.confirmedTranscript.isNotBlank() && currentMode == VoiceTranscriptionMode.BATCH_RAMBLER ->
                            "Rambler-polished draft saved • Temp voice audio cleared"
                        st.confirmedTranscript.isNotBlank() -> "Transcription complete & saved • Temp voice audio cleared"
                        else -> "Draft ready"
                    }
                )
            }
        }
    }

    fun retryLiveConnectionWhileRecording(
        apiKey: String,
        liveModelId: String,
        transcribeModelId: String
    ) {
        if (_sessionState.value.recordingState != RecordingState.RECORDING) return
        currentApiKey = apiKey.trim()
        currentLiveModel = liveModelId.trim().ifBlank { GeminiDmnService.DEFAULT_LIVE_TRANSCRIBE_MODEL }
        currentTranscribeModel = transcribeModelId.trim().ifBlank { GeminiDmnService.DEFAULT_TRANSCRIBE_MODEL }
        if (currentApiKey.isBlank()) return

        managerScope.launch {
            batchLoopJob?.cancelAndJoin()
            batchLoopJob = null
            liveFailedInCurrentSession = false
            _sessionState.update {
                it.copy(
                    engineMode = LiveEngineMode.CONNECTING_LIVE,
                    fallbackNotice = null,
                    statusMessage = "Reconnecting to $currentLiveModel..."
                )
            }
            launchLiveConnection()
        }
    }

    /**
     * Transcribes any remaining untranscribed audio in the active draft using
     * `gemini-3.5-transcribe` in <=45s WAV chunks, followed by Flash Rambler cleanup when in
     * `VoiceTranscriptionMode.BATCH_RAMBLER`. Purges temporary PCM audio once 100% safe.
     */
    suspend fun transcribeRemainingForActiveDraft(
        apiKey: String,
        transcribeModelId: String,
        flashModelId: String = VaultSettings.DEFAULT_GEMINI_MODEL,
        mode: VoiceTranscriptionMode = currentMode
    ): Result<Int> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return Result.failure(IllegalStateException("Add your Google AI Studio API key in Sync first. Your audio remains safely stored."))
        }
        currentApiKey = cleanKey
        currentTranscribeModel = transcribeModelId.trim().ifBlank { GeminiDmnService.DEFAULT_TRANSCRIBE_MODEL }
        currentFlashModel = flashModelId.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
        currentMode = mode
        val useRambler = mode == VoiceTranscriptionMode.BATCH_RAMBLER

        if (_sessionState.value.recordingState == RecordingState.RECORDING) {
            return Result.failure(IllegalStateException("Pause or stop recording before running manual catch-up."))
        }

        _sessionState.update {
            it.copy(
                isBusyTranscribingPending = true,
                statusMessage = if (useRambler) {
                    "Step 1/2: Transcribing saved audio with $currentTranscribeModel → Step 2/2: Rambler polish ($currentFlashModel)..."
                } else {
                    "Transcribing saved audio backup with $currentTranscribeModel..."
                }
            )
        }

        return try {
            val result = flushPendingPcmSlices(
                minBytes = 1_600L,
                applyRamblerCleanup = useRambler
            )
            stateLock.withLock {
                persistActiveDraftInternal()
                maybePurgeAudioIfCompletelySafe(allowPurgeWhilePaused = true)
                val remainingSec = _sessionState.value.untranscribedMs / 1000L
                _sessionState.update {
                    it.copy(
                        isBusyTranscribingPending = false,
                        batchPipelineStage = BatchPipelineStage.IDLE,
                        statusMessage = when {
                            result.isFailure -> "Audio kept safely on device (${remainingSec}s pending): ${result.exceptionOrNull()?.message}"
                            remainingSec > 0L -> "Audio kept safely on device (${remainingSec}s pending)"
                            useRambler -> "All speech transcribed & Rambler-polished • Temp voice audio cleared"
                            else -> "All speech transcribed & saved • Temp voice audio cleared"
                        }
                    )
                }
            }
            result
        } catch (e: Exception) {
            _sessionState.update {
                it.copy(
                    isBusyTranscribingPending = false,
                    batchPipelineStage = BatchPipelineStage.IDLE,
                    statusMessage = "Audio kept safely on device: ${e.message}"
                )
            }
            Result.failure(e)
        }
    }

    suspend fun deleteDraft(draftId: String) {
        val isDeletingActive = _sessionState.value.activeDraftId == draftId
        if (isDeletingActive) {
            stateLock.withLock {
                stopAudioRecordHardware()
            }
            liveConnectJob?.cancel()
            liveConnectJob = null
            liveClient?.close()
            liveClient = null
            batchLoopJob?.cancelAndJoin()
            batchLoopJob = null
        }

        withContext(Dispatchers.IO) {
            draftDao.deleteDraftById(draftId)
            draftFolder(draftId).deleteRecursively()
        }

        if (isDeletingActive) {
            stateLock.withLock {
                activePcmFile = null
                basePriorDurationMs = 0L
                totalPcmBytesWritten = 0L
                transcribedByteOffset = 0L
                liveSegmentStartByte = 0L
                liveFailedInCurrentSession = false
                _sessionState.value = LiveStudioSessionState()
            }
        }
    }

    fun setSubmittingForDmn(submitting: Boolean) {
        _sessionState.update { it.copy(isSubmittingForDmn = submitting) }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecordCapture(pcmFile: File): Result<Unit> {
        val minBuf = AudioRecord.getMinBufferSize(
            PcmAudioEncoder.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(6400)
        val bufferSize = maxOf(minBuf * 2, 16_000)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmAudioEncoder.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    PcmAudioEncoder.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Unable to initialize microphone: ${e.message}", e))
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return Result.failure(IllegalStateException("Microphone initialization failed."))
        }

        val fos = try {
            FileOutputStream(pcmFile, true)
        } catch (e: Exception) {
            runCatching { record.release() }
            return Result.failure(e)
        }

        return try {
            record.startRecording()
            audioRecord = record
            pcmOutputStream = fos
            isCaptureActive.set(true)
            lastQuietDurationMs.set(0L)

            captureJob = managerScope.launch(Dispatchers.IO) {
                val readChunk = ByteArray(3200) // 100ms of 16kHz 16-bit mono PCM
                var lastPersistMs = System.currentTimeMillis()
                while (isActive && isCaptureActive.get()) {
                    val rec = audioRecord ?: break
                    val read = try {
                        rec.read(readChunk, 0, readChunk.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (read > 0) {
                        try {
                            pcmOutputStream?.write(readChunk, 0, read)
                        } catch (_: Exception) {
                            break
                        }
                        totalPcmBytesWritten += read

                        // Forward directly to Live WebSocket when connected
                        if (isLiveStreamingActive.get()) {
                            val sent = liveClient?.sendPcmChunk(readChunk, read) == true
                            if (!sent && isLiveStreamingActive.get()) {
                                switchToBatchFallback("Live stream send interrupted — switched to batched transcription.")
                            }
                        }

                        val rms = computeNormalizedRms(readChunk, read)
                        if (rms < QUIET_RMS_THRESHOLD) {
                            lastQuietDurationMs.addAndGet(100L)
                        } else {
                            lastQuietDurationMs.set(0L)
                        }

                        val totalElapsed = basePriorDurationMs + PcmAudioEncoder.computeDurationMs(totalPcmBytesWritten)
                        _sessionState.update { st ->
                            st.copy(
                                elapsedMs = totalElapsed,
                                amplitudes = (st.amplitudes.drop(1) + rms.coerceIn(0.06f, 1.0f)),
                                totalPcmBytes = totalPcmBytesWritten,
                                hasSafeAudioBackup = true
                            )
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastPersistMs >= 4_000L) {
                            lastPersistMs = now
                            runCatching { pcmOutputStream?.flush() }
                            persistActiveDraftLocked()
                        }
                    } else if (read < 0) {
                        delay(20L)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            isCaptureActive.set(false)
            runCatching { fos.close() }
            runCatching { record.release() }
            audioRecord = null
            pcmOutputStream = null
            Result.failure(e)
        }
    }

    private fun stopAudioRecordHardware() {
        isCaptureActive.set(false)
        captureJob?.cancel()
        captureJob = null
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        val out = pcmOutputStream
        pcmOutputStream = null
        if (out != null) {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    private fun launchLiveConnection() {
        liveConnectJob?.cancel()
        liveClient?.close()
        isLiveStreamingActive.set(false)
        liveReceivedFinalSinceConnect = false
        liveSegmentStartByte = transcribedByteOffset

        val client = GeminiLiveTranscribeClient()
        liveClient = client

        liveConnectJob = managerScope.launch(Dispatchers.IO) {
            val connectResult = client.connect(
                apiKey = currentApiKey,
                liveModelId = currentLiveModel,
                onFinalTranscript = { text ->
                    handleLiveFinalTranscript(text)
                },
                onInterimTranscript = { interim ->
                    _sessionState.update { st ->
                        if (st.recordingState == RecordingState.RECORDING) {
                            st.copy(interimText = interim)
                        } else st
                    }
                },
                onDisconnectedOrError = { reason, isHighDemand ->
                    if (isCaptureActive.get()) {
                        val notice = if (isHighDemand) {
                            "$currentLiveModel busy/high demand ($reason) — switched to Batched $currentTranscribeModel. No speech lost."
                        } else {
                            "Live stream interrupted ($reason) — switched to Batched $currentTranscribeModel. No speech lost."
                        }
                        switchToBatchFallback(notice)
                    }
                }
            )

            if (!isCaptureActive.get()) {
                client.close()
                return@launch
            }

            if (connectResult.isFailure) {
                val reason = connectResult.exceptionOrNull()?.message ?: "Unavailable"
                val notice = "$currentLiveModel unavailable ($reason) — switched to Batched $currentTranscribeModel. Voice safely backed up."
                switchToBatchFallback(notice)
                return@launch
            }

            // Stream any audio spooled while WebSocket was handshaking
            val pcmFile = activePcmFile
            val backlogEnd = totalPcmBytesWritten
            if (pcmFile != null && pcmFile.exists() && backlogEnd > liveSegmentStartByte) {
                val backlogBytes = backlogEnd - liveSegmentStartByte
                if (backlogBytes <= 8L * PcmAudioEncoder.BYTES_PER_SECOND) {
                    runCatching {
                        pcmFile.inputStream().use { fis ->
                            fis.skip(liveSegmentStartByte)
                            val buf = ByteArray(3200)
                            var remaining = backlogBytes
                            while (remaining > 0 && isCaptureActive.get()) {
                                val r = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (r <= 0) break
                                client.sendPcmChunk(buf, r)
                                remaining -= r
                            }
                        }
                    }
                }
            }

            isLiveStreamingActive.set(true)
            _sessionState.update { st ->
                if (st.recordingState == RecordingState.RECORDING) {
                    st.copy(
                        engineMode = LiveEngineMode.LIVE_STREAMING,
                        fallbackNotice = null,
                        statusMessage = "Live streaming via $currentLiveModel (voice safely buffered)"
                    )
                } else st
            }
        }
    }

    private fun handleLiveFinalTranscript(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        liveReceivedFinalSinceConnect = true
        // Advance transcribedByteOffset conservatively with a 1.5s overlap margin so an abrupt drop never loses words
        val conservativeOffset = (totalPcmBytesWritten - LIVE_OVERLAP_SAFETY_BYTES).coerceAtLeast(transcribedByteOffset)
        transcribedByteOffset = conservativeOffset
        _sessionState.update { st ->
            val merged = mergeTranscriptText(st.confirmedTranscript, clean)
            st.copy(
                confirmedTranscript = merged,
                interimText = "",
                transcribedByteOffset = transcribedByteOffset
            )
        }
        managerScope.launch { persistActiveDraftLocked() }
    }

    private fun switchToBatchFallback(reasonNotice: String) {
        isLiveStreamingActive.set(false)
        liveFailedInCurrentSession = true
        liveClient?.close()
        liveClient = null

        // If Live dropped before emitting any finalized text, ensure offset stays at liveSegmentStartByte
        if (!liveReceivedFinalSinceConnect) {
            transcribedByteOffset = minOf(transcribedByteOffset, liveSegmentStartByte)
        }

        _sessionState.update { st ->
            if (st.recordingState == RecordingState.RECORDING) {
                st.copy(
                    engineMode = LiveEngineMode.BATCH_FALLBACK,
                    interimText = "",
                    fallbackNotice = reasonNotice,
                    statusMessage = "Batched mode ($currentTranscribeModel) — transcribing in steps, audio safely stored",
                    transcribedByteOffset = transcribedByteOffset
                )
            } else st
        }
        launchBatchTranscribeLoop()
    }

    private fun launchBatchTranscribeLoop() {
        if (batchLoopJob?.isActive == true) return
        batchLoopJob = managerScope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive && isCaptureActive.get()) {
                val pending = totalPcmBytesWritten - transcribedByteOffset
                val quietReady = pending >= BATCH_MIN_SILENCE_BYTES && lastQuietDurationMs.get() >= QUIET_SPLIT_MS
                val targetReady = pending >= BATCH_TARGET_BYTES

                if ((targetReady || quietReady) && currentApiKey.isNotBlank()) {
                    val res = flushPendingPcmSlices(
                        minBytes = BATCH_MIN_SILENCE_BYTES,
                        applyRamblerCleanup = currentMode == VoiceTranscriptionMode.BATCH_RAMBLER
                    )
                    if (res.isSuccess) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        val backoffMs = when (consecutiveFailures) {
                            1 -> 3_000L
                            2 -> 6_000L
                            3 -> 12_000L
                            else -> 20_000L
                        }
                        _sessionState.update { st ->
                            st.copy(
                                batchPipelineStage = BatchPipelineStage.IDLE,
                                pendingRetryBatchCount = consecutiveFailures,
                                statusMessage = "Batch retry in ${backoffMs / 1000L}s (all audio safely stored on device)"
                            )
                        }
                        delay(backoffMs)
                        continue
                    }
                }
                delay(1_000L)
            }
        }
    }

    private suspend fun closeLiveClientGracefully() {
        liveConnectJob?.cancel()
        liveConnectJob = null
        val client = liveClient
        liveClient = null
        if (client != null && isLiveStreamingActive.getAndSet(false)) {
            client.sendAudioStreamEnd()
            delay(900L)
            client.close()
        } else {
            client?.close()
        }
    }

    /**
     * Transcribes untranscribed PCM bytes [transcribedByteOffset, fileLength) in <=45s WAV chunks:
     * - Step 1: `gemini-3.5-transcribe` converts audio into raw literal speech text.
     *   Never advances `transcribedByteOffset` on failure so zero speech is lost.
     * - Step 2 (when `applyRamblerCleanup == true`, i.e., Batch mode): passes the raw literal
     *   segment into the configured Flash model (`gemini-3.5-flash`) with the Rambler system prompt
     *   before displaying and saving the polished transcript. If Step 2 fails transiently, preserves
     *   the raw literal text so zero speech is ever lost.
     */
    private suspend fun flushPendingPcmSlices(
        minBytes: Long,
        applyRamblerCleanup: Boolean = false
    ): Result<Int> = batchTranscribeLock.withLock {
        val pcmFile = activePcmFile ?: return@withLock Result.success(0)
        if (!pcmFile.exists()) return@withLock Result.success(0)

        runCatching { pcmOutputStream?.flush() }
        var chunksDone = 0

        try {
            while (true) {
                val availableEnd = maxOf(totalPcmBytesWritten, pcmFile.length())
                val remaining = availableEnd - transcribedByteOffset
                if (remaining < minBytes) break

                val chunkEnd = minOf(availableEnd, transcribedByteOffset + MAX_INLINE_CHUNK_BYTES)
                val wavBytes = PcmAudioEncoder.readPcmSliceAsWavBytes(pcmFile, transcribedByteOffset, chunkEnd)
                if (wavBytes.size <= 44) break

                val chunkSec = ((chunkEnd - transcribedByteOffset) / PcmAudioEncoder.BYTES_PER_SECOND).coerceAtLeast(1L)

                _sessionState.update { st ->
                    st.copy(
                        inFlightBatchCount = st.inFlightBatchCount + 1,
                        batchPipelineStage = BatchPipelineStage.STEP_1_TRANSCRIBING,
                        statusMessage = if (applyRamblerCleanup) {
                            "Step 1/2: Transcribing ${chunkSec}s audio via $currentTranscribeModel..."
                        } else {
                            "Transcribing ${chunkSec}s batch via $currentTranscribeModel..."
                        }
                    )
                }

                // Step 1: Raw literal transcription via gemini-3.5-transcribe
                val step1Result = geminiService.transcribeInlineWavChunk(
                    apiKey = currentApiKey,
                    transcribeModelId = currentTranscribeModel,
                    wavBytes = wavBytes
                )

                if (step1Result.isFailure) {
                    val err = step1Result.exceptionOrNull() ?: IllegalStateException("Batch transcription failed")
                    _sessionState.update { st ->
                        st.copy(
                            inFlightBatchCount = (st.inFlightBatchCount - 1).coerceAtLeast(0),
                            batchPipelineStage = BatchPipelineStage.IDLE,
                            pendingRetryBatchCount = st.pendingRetryBatchCount + 1
                        )
                    }
                    return@withLock Result.failure(err)
                }

                val rawSegmentText = step1Result.getOrNull().orEmpty().trim()
                var ramblerFallbackNote: String? = null

                // Step 2 (Batch Mode only): Rambler-like prose cleanup via Flash model
                val finalSegmentText = if (applyRamblerCleanup && rawSegmentText.isNotEmpty()) {
                    _sessionState.update { st ->
                        st.copy(
                            batchPipelineStage = BatchPipelineStage.STEP_2_RAMBLER_CLEANUP,
                            statusMessage = "Step 2/2: Rambler cleanup via $currentFlashModel..."
                        )
                    }
                    val priorContextTail = _sessionState.value.confirmedTranscript.takeLast(240)
                    val step2Result = geminiService.cleanTranscriptRamblerStyle(
                        apiKey = currentApiKey,
                        flashModelId = currentFlashModel,
                        rawTranscript = rawSegmentText,
                        priorContextTail = priorContextTail.takeIf { it.isNotBlank() }
                    )
                    if (step2Result.isSuccess) {
                        step2Result.getOrNull().orEmpty().trim().ifBlank { rawSegmentText }
                    } else {
                        ramblerFallbackNote = "Flash Rambler cleanup busy — showing raw transcript (no speech lost)"
                        rawSegmentText
                    }
                } else {
                    rawSegmentText
                }

                transcribedByteOffset = chunkEnd
                chunksDone++

                _sessionState.update { st ->
                    val merged = if (finalSegmentText.isNotEmpty()) {
                        mergeTranscriptText(st.confirmedTranscript, finalSegmentText)
                    } else {
                        st.confirmedTranscript
                    }
                    val nextDone = st.completedBatchCount + 1
                    st.copy(
                        confirmedTranscript = merged,
                        completedBatchCount = nextDone,
                        inFlightBatchCount = (st.inFlightBatchCount - 1).coerceAtLeast(0),
                        batchPipelineStage = BatchPipelineStage.IDLE,
                        pendingRetryBatchCount = 0,
                        transcribedByteOffset = transcribedByteOffset,
                        statusMessage = when {
                            ramblerFallbackNote != null -> ramblerFallbackNote
                            st.recordingState == RecordingState.RECORDING && applyRamblerCleanup ->
                                "Batch Rambler ($currentTranscribeModel → $currentFlashModel) — $nextDone batch(es) cleaned"
                            st.recordingState == RecordingState.RECORDING ->
                                "Batched mode ($currentTranscribeModel) — $nextDone batch(es) transcribed"
                            else -> st.statusMessage
                        }
                    )
                }
                persistActiveDraftLocked()
            }
            Result.success(chunksDone)
        } finally {
            _sessionState.update { it.copy(batchPipelineStage = BatchPipelineStage.IDLE) }
        }
    }

    private fun commitInterimIfAny() {
        val interim = _sessionState.value.interimText.trim()
        if (interim.isNotEmpty()) {
            _sessionState.update { st ->
                st.copy(
                    confirmedTranscript = mergeTranscriptText(st.confirmedTranscript, interim),
                    interimText = ""
                )
            }
        }
    }

    /**
     * Enforces the user's audio safety & cleanup rule:
     * - If ANY untranscribed bytes remain (>0) or transcript is empty, KEEP `session.pcm` safely on device!
     * - Once 100% of audio is transcribed (`remaining <= MIN_TAIL_FLUSH_BYTES` or 0) AND the transcript is
     *   non-blank and durably saved in Room, delete the temporary `session.pcm` file so audio is not unnecessarily archived.
     */
    private suspend fun maybePurgeAudioIfCompletelySafe(allowPurgeWhilePaused: Boolean) {
        val st = _sessionState.value
        if (st.recordingState == RecordingState.RECORDING) return
        if (!allowPurgeWhilePaused && st.recordingState == RecordingState.PAUSED) return
        val pcmFile = activePcmFile ?: return
        if (!pcmFile.exists()) return

        val actualLen = pcmFile.length()
        val remaining = (actualLen - transcribedByteOffset).coerceAtLeast(0L)
        val isCompletelyTranscribed = remaining < MIN_TAIL_FLUSH_BYTES
        val isTranscriptSafe = st.confirmedTranscript.isNotBlank() && !st.activeDraftId.isNullOrBlank()

        if (isCompletelyTranscribed && isTranscriptSafe) {
            // Ensure Room row with complete transcript is saved BEFORE deleting audio
            persistActiveDraftInternal()
            val deleted = runCatching { pcmFile.delete() }.getOrDefault(false)
            if (deleted || !pcmFile.exists()) {
                basePriorDurationMs = st.elapsedMs
                totalPcmBytesWritten = 0L
                transcribedByteOffset = 0L
                liveSegmentStartByte = 0L
                _sessionState.update {
                    it.copy(
                        totalPcmBytes = 0L,
                        transcribedByteOffset = 0L,
                        hasSafeAudioBackup = false
                    )
                }
                persistActiveDraftInternal()
            }
        }
    }

    private suspend fun persistActiveDraftLocked() {
        stateLock.withLock {
            persistActiveDraftInternal()
        }
    }

    private suspend fun persistActiveDraftInternal() {
        val st = _sessionState.value
        val draftId = st.activeDraftId ?: return
        val pcmPath = activePcmFile?.absolutePath ?: draftPcmFile(draftId).absolutePath
        val actualFileLen = File(pcmPath).takeIf { it.exists() }?.length() ?: 0L
        val safeOffset = transcribedByteOffset.coerceIn(0L, actualFileLen)
        val modeLabel = when {
            actualFileLen - safeOffset >= MIN_TAIL_FLUSH_BYTES -> "Audio Backup Safe"
            st.confirmedTranscript.isNotBlank() -> "Transcribed & Safe"
            else -> st.engineMode.label
        }
        val entity = VoiceDraftEntity(
            id = draftId,
            title = st.draftTitle.trim().ifBlank { formatDefaultDraftTitle(activeDraftCreatedAtMs) },
            transcriptText = st.confirmedTranscript,
            pcmFilePath = pcmPath,
            durationMs = st.elapsedMs,
            totalPcmBytes = actualFileLen,
            transcribedByteOffset = safeOffset,
            lastModeLabel = modeLabel,
            createdAtMs = activeDraftCreatedAtMs,
            updatedAtMs = System.currentTimeMillis()
        )
        draftDao.upsertDraft(entity)
    }

    private fun computeNormalizedRms(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0.06f
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val norm = sample / 32768.0
            sumSquares += norm * norm
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0.06f
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms * 4.2f).coerceIn(0.06f, 1.0f)
    }
}
