package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmAudioEncoder {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = CHANNELS * (BITS_PER_SAMPLE / 8)
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE // 32,000 bytes/sec

    fun computeDurationMs(pcmByteCount: Long): Long {
        if (pcmByteCount <= 0L) return 0L
        return (pcmByteCount * 1000L) / BYTES_PER_SECOND
    }

    fun buildWavHeader(
        pcmDataLength: Long,
        sampleRate: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE
    ): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val clampedDataLen = pcmDataLength.coerceIn(0L, Int.MAX_VALUE.toLong() - 36L).toInt()
        val chunkSize = 36 + clampedDataLen

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(chunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM subchunk1 size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(clampedDataLen)
        return buffer.array()
    }

    /**
     * Reads an exact byte slice [startByte, endByte) from a raw 16kHz PCM file and wraps it
     * in a valid RIFF/WAVE header for inline Gemini transcription requests.
     */
    fun readPcmSliceAsWavBytes(
        pcmFile: File,
        startByte: Long,
        endByte: Long
    ): ByteArray {
        if (!pcmFile.exists()) return ByteArray(0)
        val fileLen = pcmFile.length()
        val alignedStart = (startByte.coerceIn(0L, fileLen) / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE
        val alignedEnd = (endByte.coerceIn(alignedStart, fileLen) / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE
        val sliceLen = (alignedEnd - alignedStart).toInt()
        if (sliceLen <= 0) return ByteArray(0)

        val header = buildWavHeader(sliceLen.toLong())
        val out = ByteArray(header.size + sliceLen)
        System.arraycopy(header, 0, out, 0, header.size)

        RandomAccessFile(pcmFile, "r").use { raf ->
            raf.seek(alignedStart)
            var offset = header.size
            var remaining = sliceLen
            while (remaining > 0) {
                val read = raf.read(out, offset, remaining)
                if (read <= 0) break
                offset += read
                remaining -= read
            }
            if (remaining > 0) {
                val actualDataLen = sliceLen - remaining
                val correctedHeader = buildWavHeader(actualDataLen.toLong())
                System.arraycopy(correctedHeader, 0, out, 0, correctedHeader.size)
                return out.copyOf(correctedHeader.size + actualDataLen)
            }
        }
        return out
    }

    /**
     * Encodes raw 16kHz mono 16-bit PCM into a single AAC-LC .m4a file using hardware/platform
     * MediaCodec + MediaMuxer. Falls back gracefully if an encoder isn't available on the host.
     */
    fun encodePcmFileToM4a(
        pcmFile: File,
        outputM4aFile: File,
        sampleRate: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNELS,
        bitRate: Int = 64_000
    ): Boolean {
        if (!pcmFile.exists() || pcmFile.length() <= 0L) return false
        if (outputM4aFile.exists()) outputM4aFile.delete()
        outputM4aFile.parentFile?.mkdirs()

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1

        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            val readBuf = ByteArray(4096)
            var totalBytesSubmitted = 0L
            var inputDone = false
            var outputDone = false
            var lastWrittenPtsUs = -1L

            FileInputStream(pcmFile).use { input ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val toRead = minOf(readBuf.size, inBuf.capacity())
                                val bytesRead = input.read(readBuf, 0, toRead)
                                if (bytesRead <= 0) {
                                    val eosPtsUs = (totalBytesSubmitted * 1_000_000L) /
                                        (sampleRate.toLong() * channels * (BITS_PER_SAMPLE / 8))
                                    codec.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        eosPtsUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    inBuf.put(readBuf, 0, bytesRead)
                                    val ptsUs = (totalBytesSubmitted * 1_000_000L) /
                                        (sampleRate.toLong() * channels * (BITS_PER_SAMPLE / 8))
                                    totalBytesSubmitted += bytesRead
                                    codec.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0)
                                }
                            }
                        }
                    }

                    var draining = true
                    while (draining) {
                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                        when {
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                draining = false
                            }
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    trackIndex = muxer.addTrack(codec.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                            }
                            outIdx >= 0 -> {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    if (bufferInfo.presentationTimeUs <= lastWrittenPtsUs) {
                                        bufferInfo.presentationTimeUs = lastWrittenPtsUs + 1L
                                    }
                                    lastWrittenPtsUs = bufferInfo.presentationTimeUs
                                    muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                    draining = false
                                }
                            }
                            else -> {
                                draining = false
                            }
                        }
                    }
                }
            }
            muxerStarted && outputM4aFile.exists() && outputM4aFile.length() > 0L
        } catch (_: Throwable) {
            outputM4aFile.delete()
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    fun encodePcmFileToWav(pcmFile: File, outputWavFile: File): Boolean {
        if (!pcmFile.exists() || pcmFile.length() <= 0L) return false
        if (outputWavFile.exists()) outputWavFile.delete()
        outputWavFile.parentFile?.mkdirs()

        return runCatching {
            val pcmLen = pcmFile.length()
            val header = buildWavHeader(pcmLen)
            FileOutputStream(outputWavFile).use { out ->
                out.write(header)
                FileInputStream(pcmFile).use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
                out.flush()
            }
            outputWavFile.exists() && outputWavFile.length() > 44L
        }.getOrDefault(false)
    }
}
