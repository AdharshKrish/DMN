package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

data class RecordedAudioResult(
    val tempFile: File,
    val suggestedFileName: String,
    val durationMs: Long
)

class DmnAudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var suggestedName: String = ""
    private var meterJob: Job? = null
    private var accumulatedMs: Long = 0L
    private var activeStartMs: Long = 0L

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _amplitudeHistory = MutableStateFlow<List<Float>>(List(36) { 0.08f })
    val amplitudeHistory: StateFlow<List<Float>> = _amplitudeHistory.asStateFlow()

    fun startRecording(scope: CoroutineScope, labelPrefix: String = "DMN"): Result<Unit> {
        return runCatching {
            stopAndDiscard()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safePrefix = labelPrefix.trim()
                .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
                .take(24)
                .ifBlank { "DMN" }
            suggestedName = "${safePrefix}_$stamp.m4a"
            val file = File(context.cacheDir, "active_dmn_recording.m4a")
            if (file.exists()) file.delete()
            outputFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mr
            accumulatedMs = 0L
            activeStartMs = System.currentTimeMillis()
            _elapsedMs.value = 0L
            _amplitudeHistory.value = List(36) { 0.08f }
            _state.value = RecordingState.RECORDING
            startMeterLoop(scope)
        }
    }

    fun pauseRecording() {
        val mr = recorder ?: return
        if (_state.value == RecordingState.RECORDING) {
            runCatching { mr.pause() }
            accumulatedMs += (System.currentTimeMillis() - activeStartMs)
            _elapsedMs.value = accumulatedMs
            _state.value = RecordingState.PAUSED
        }
    }

    fun resumeRecording() {
        val mr = recorder ?: return
        if (_state.value == RecordingState.PAUSED) {
            runCatching { mr.resume() }
            activeStartMs = System.currentTimeMillis()
            _state.value = RecordingState.RECORDING
        }
    }

    fun stopRecording(): Result<RecordedAudioResult> {
        return runCatching {
            meterJob?.cancel()
            meterJob = null
            val mr = recorder ?: throw IllegalStateException("No active recording.")
            val totalMs = if (_state.value == RecordingState.RECORDING) {
                accumulatedMs + (System.currentTimeMillis() - activeStartMs)
            } else {
                accumulatedMs
            }

            runCatching { mr.stop() }
            runCatching { mr.release() }
            recorder = null
            _state.value = RecordingState.IDLE

            val file = outputFile ?: throw IllegalStateException("Recording file missing.")
            require(file.exists() && file.length() > 0L) { "Recorded audio was empty." }
            RecordedAudioResult(
                tempFile = file,
                suggestedFileName = suggestedName,
                durationMs = totalMs
            )
        }
    }

    fun stopAndDiscard() {
        meterJob?.cancel()
        meterJob = null
        recorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
        accumulatedMs = 0L
        _elapsedMs.value = 0L
        _amplitudeHistory.value = List(36) { 0.08f }
        _state.value = RecordingState.IDLE
    }

    private fun startMeterLoop(scope: CoroutineScope) {
        meterJob?.cancel()
        meterJob = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value != RecordingState.IDLE) {
                if (_state.value == RecordingState.RECORDING) {
                    _elapsedMs.value = accumulatedMs + (System.currentTimeMillis() - activeStartMs)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = (amp / 22000f).coerceIn(0.06f, 1.0f)
                    _amplitudeHistory.value = (_amplitudeHistory.value.drop(1) + normalized)
                }
                delay(80L)
            }
        }
    }
}
