package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
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

    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var activeSegmentFile: File? = null
    private val segmentFiles = mutableListOf<File>()
    private var suggestedName: String = ""
    private var activeScope: CoroutineScope? = null
    private var meterJob: Job? = null
    private var accumulatedMs: Long = 0L
    private var activeStartMs: Long = 0L

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _amplitudeHistory = MutableStateFlow<List<Float>>(List(36) { 0.08f })
    val amplitudeHistory: StateFlow<List<Float>> = _amplitudeHistory.asStateFlow()

    companion object {
        private const val SAMPLE_RATE_HZ = 44_100
        private const val AAC_FRAME_SAMPLES = 1024L
        private const val AAC_FRAME_DURATION_US = (AAC_FRAME_SAMPLES * 1_000_000L) / SAMPLE_RATE_HZ // ~23,219 us
        private const val MUX_BUFFER_BYTES = 512 * 1024

        internal fun computeNextPtsOffsetUs(
            currentOffsetUs: Long,
            segmentMaxRawPtsUs: Long,
            segmentHeaderDurationUs: Long
        ): Long {
            val advanceUs = maxOf(
                segmentMaxRawPtsUs + AAC_FRAME_DURATION_US,
                segmentHeaderDurationUs
            ).coerceAtLeast(AAC_FRAME_DURATION_US)
            return currentOffsetUs + advanceUs
        }

        internal fun ensureMonotonicPtsUs(candidatePtsUs: Long, lastWrittenPtsUs: Long): Long {
            return if (candidatePtsUs <= lastWrittenPtsUs) {
                lastWrittenPtsUs + 1L
            } else {
                candidatePtsUs
            }
        }
    }

    fun startRecording(scope: CoroutineScope, labelPrefix: String = "DMN"): Result<Unit> {
        return runCatching {
            synchronized(lock) {
                stopAndDiscardLocked()
                activeScope = scope
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safePrefix = labelPrefix.trim()
                    .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
                    .take(24)
                    .ifBlank { "DMN" }
                suggestedName = "${safePrefix}_$stamp.m4a"
                accumulatedMs = 0L
                _elapsedMs.value = 0L
                _amplitudeHistory.value = List(36) { 0.08f }

                startNewSegmentLocked()
                activeStartMs = System.currentTimeMillis()
                _state.value = RecordingState.RECORDING
                startMeterLoopLocked(scope)
            }
        }
    }

    /**
     * Releases the microphone hardware completely while paused by finalizing the current segment.
     * Existing segments remain safely stored in cache until resumed or saved.
     */
    fun pauseRecording() {
        synchronized(lock) {
            if (_state.value != RecordingState.RECORDING) return
            finalizeCurrentSegmentLocked()
            accumulatedMs += (System.currentTimeMillis() - activeStartMs).coerceAtLeast(0L)
            _elapsedMs.value = accumulatedMs
            _amplitudeHistory.value = List(36) { 0.08f }
            _state.value = RecordingState.PAUSED
        }
    }

    /**
     * Re-acquires microphone access by starting a new matching AAC segment that will later be
     * merged into a single seamless .m4a file on stopRecording().
     */
    fun resumeRecording(): Result<Unit> {
        return runCatching {
            synchronized(lock) {
                if (_state.value != RecordingState.PAUSED) return@runCatching
                startNewSegmentLocked()
                activeStartMs = System.currentTimeMillis()
                _state.value = RecordingState.RECORDING
                val scope = activeScope
                if (scope != null && (meterJob == null || meterJob?.isActive != true)) {
                    startMeterLoopLocked(scope)
                }
            }
        }
    }

    suspend fun stopRecording(): Result<RecordedAudioResult> = withContext(Dispatchers.IO) {
        runCatching {
            val segmentsToMerge: List<File>
            val totalMs: Long
            val outName: String

            synchronized(lock) {
                meterJob?.cancel()
                meterJob = null
                totalMs = if (_state.value == RecordingState.RECORDING) {
                    accumulatedMs + (System.currentTimeMillis() - activeStartMs).coerceAtLeast(0L)
                } else {
                    accumulatedMs
                }

                if (_state.value == RecordingState.RECORDING || recorder != null) {
                    finalizeCurrentSegmentLocked()
                }
                _state.value = RecordingState.IDLE
                segmentsToMerge = segmentFiles.filter { it.exists() && it.length() > 0L }.toList()
                segmentFiles.clear()
                outName = suggestedName.ifBlank {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    "DMN_$stamp.m4a"
                }
            }

            require(segmentsToMerge.isNotEmpty()) { "Recorded audio was empty." }

            val finalFile = File(context.cacheDir, "active_dmn_recording.m4a")
            if (finalFile.exists()) finalFile.delete()

            try {
                if (segmentsToMerge.size == 1) {
                    val single = segmentsToMerge.first()
                    if (!single.renameTo(finalFile)) {
                        single.copyTo(finalFile, overwrite = true)
                    }
                } else {
                    mergeM4aSegments(segmentsToMerge, finalFile)
                }
            } finally {
                segmentsToMerge.forEach { seg ->
                    if (seg.absolutePath != finalFile.absolutePath && seg.exists()) {
                        seg.delete()
                    }
                }
            }

            require(finalFile.exists() && finalFile.length() > 0L) { "Recorded audio was empty." }
            RecordedAudioResult(
                tempFile = finalFile,
                suggestedFileName = outName,
                durationMs = totalMs
            )
        }
    }

    fun stopAndDiscard() {
        synchronized(lock) {
            stopAndDiscardLocked()
        }
    }

    private fun stopAndDiscardLocked() {
        meterJob?.cancel()
        meterJob = null
        recorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        recorder = null
        activeSegmentFile?.delete()
        activeSegmentFile = null
        segmentFiles.forEach { it.delete() }
        segmentFiles.clear()
        File(context.cacheDir, "active_dmn_recording.m4a").delete()
        accumulatedMs = 0L
        activeStartMs = 0L
        _elapsedMs.value = 0L
        _amplitudeHistory.value = List(36) { 0.08f }
        _state.value = RecordingState.IDLE
    }

    private fun startNewSegmentLocked() {
        val segFile = File(
            context.cacheDir,
            "dmn_rec_seg_${segmentFiles.size}_${System.currentTimeMillis()}.m4a"
        )
        if (segFile.exists()) segFile.delete()

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setOutputFile(segFile.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            activeSegmentFile = segFile
        } catch (e: Exception) {
            runCatching { mr.release() }
            segFile.delete()
            throw e
        }
    }

    private fun finalizeCurrentSegmentLocked() {
        val mr = recorder
        val seg = activeSegmentFile
        recorder = null
        activeSegmentFile = null

        var stopSucceeded = false
        if (mr != null) {
            stopSucceeded = runCatching { mr.stop() }.isSuccess
            runCatching { mr.release() }
        }
        if (seg != null) {
            if (stopSucceeded && seg.exists() && seg.length() > 0L) {
                segmentFiles.add(seg)
            } else {
                seg.delete()
            }
        }
    }

    private fun startMeterLoopLocked(scope: CoroutineScope) {
        meterJob?.cancel()
        meterJob = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value != RecordingState.IDLE) {
                if (_state.value == RecordingState.RECORDING) {
                    _elapsedMs.value = accumulatedMs + (System.currentTimeMillis() - activeStartMs).coerceAtLeast(0L)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = (amp / 22000f).coerceIn(0.06f, 1.0f)
                    _amplitudeHistory.value = (_amplitudeHistory.value.drop(1) + normalized)
                }
                delay(80L)
            }
        }
    }

    /**
     * Losslessly concatenates paused AAC-MP4 segments into one single .m4a container.
     */
    private fun mergeM4aSegments(segments: List<File>, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerTrackIndex = -1
        var ptsOffsetUs = 0L
        var lastWrittenPtsUs = -1L
        val buffer = ByteBuffer.allocateDirect(MUX_BUFFER_BYTES)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            for (seg in segments) {
                if (!seg.exists() || seg.length() == 0L) continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(seg.absolutePath)
                    var audioTrackIdx = -1
                    var trackFormat: MediaFormat? = null
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("audio/")) {
                            audioTrackIdx = i
                            trackFormat = fmt
                            break
                        }
                    }
                    if (audioTrackIdx < 0 || trackFormat == null) continue

                    if (!muxerStarted) {
                        muxerTrackIndex = muxer.addTrack(trackFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    extractor.selectTrack(audioTrackIdx)
                    var segmentMaxRawPtsUs = 0L
                    var wroteAnySample = false

                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val rawPtsUs = extractor.sampleTime.coerceAtLeast(0L)
                        segmentMaxRawPtsUs = maxOf(segmentMaxRawPtsUs, rawPtsUs)
                        val adjustedPts = ensureMonotonicPtsUs(ptsOffsetUs + rawPtsUs, lastWrittenPtsUs)
                        lastWrittenPtsUs = adjustedPts

                        val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        @Suppress("DEPRECATION")
                        val codecFlags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                        bufferInfo.set(0, sampleSize, adjustedPts, codecFlags)
                        buffer.limit(sampleSize)
                        buffer.position(0)
                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        wroteAnySample = true

                        extractor.advance()
                    }

                    if (wroteAnySample) {
                        val headerDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                            trackFormat.getLong(MediaFormat.KEY_DURATION)
                        } else {
                            0L
                        }
                        ptsOffsetUs = computeNextPtsOffsetUs(ptsOffsetUs, segmentMaxRawPtsUs, headerDurationUs)
                    }
                } finally {
                    runCatching { extractor.release() }
                }
            }

            if (!muxerStarted) {
                throw IllegalStateException("No valid audio tracks found to combine recording segments.")
            }
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }
}
