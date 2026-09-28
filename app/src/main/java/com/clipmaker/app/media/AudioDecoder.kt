package com.clipmaker.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.clipmaker.core.audio.PcmAudio
import com.clipmaker.core.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes the audio track of any media file to mono float PCM at a reduced sample rate.
 * Used for waveform display and beat detection (not for playback).
 */
class AudioDecoder(private val context: Context) {

    suspend fun decodeMono(
        uri: Uri,
        targetRate: Int = 22_050,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        maxDurationUs: Long = 15 * 60 * 1_000_000L,
    ): PcmAudio? = withContext(Dispatchers.IO) {
        if (uri.toString().endsWith(".wav", ignoreCase = true) && uri.scheme == "file") {
            return@withContext runCatching { resampleWav(Wav.read(File(uri.path!!)), targetRate, startUs, endUs) }.getOrNull()
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(trackIndex)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val limitUs = minOf(endUs, startUs + maxDurationUs)
            val out = FloatArrayBuilder()
            var resamplePos = 0.0
            var step = sampleRate.toDouble() / targetRate
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime > limitUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        step = sampleRate.toDouble() / targetRate
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = buf.asShortBuffer()
                        val frames = shorts.remaining() / channels
                        val bufferStartUs = info.presentationTimeUs
                        for (i in 0 until frames) {
                            val t = bufferStartUs + i * 1_000_000L / sampleRate
                            if (t < startUs) continue
                            // Simple decimating resampler: pick frames at the target rate.
                            resamplePos += 1.0
                            if (resamplePos >= step) {
                                resamplePos -= step
                                var sum = 0f
                                for (ch in 0 until channels) sum += shorts.get(i * channels + ch) / 32768f
                                out.add(sum / channels)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || bufferStartUs > limitUs) outputDone = true
                    }
                }
            }
            PcmAudio(out.toArray(), targetRate, 1)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun resampleWav(pcm: PcmAudio, targetRate: Int, startUs: Long, endUs: Long): PcmAudio {
        val mono = pcm.toMono()
        val from = (startUs * pcm.sampleRate / 1_000_000L).toInt().coerceIn(0, mono.size)
        val to = if (endUs == Long.MAX_VALUE) mono.size else (endUs * pcm.sampleRate / 1_000_000L).toInt().coerceIn(from, mono.size)
        val step = pcm.sampleRate.toDouble() / targetRate
        val count = ((to - from) / step).toInt()
        return PcmAudio(FloatArray(count) { mono[from + (it * step).toInt()] }, targetRate, 1)
    }
}

private class FloatArrayBuilder {
    private var data = FloatArray(1 shl 16)
    private var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
