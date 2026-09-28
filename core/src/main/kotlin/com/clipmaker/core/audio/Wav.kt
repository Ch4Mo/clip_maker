package com.clipmaker.core.audio

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Decoded PCM audio as interleaved floats. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int, val channels: Int) {
    val frames: Int get() = samples.size / channels
    val durationUs: Long get() = frames * 1_000_000L / sampleRate

    fun toMono(): FloatArray {
        if (channels == 1) return samples
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) sum += samples[i * channels + ch]
            out[i] = sum / channels
        }
        return out
    }
}

object Wav {
    /** Encodes 16-bit PCM WAV. */
    fun write(out: OutputStream, audio: PcmAudio) {
        val dataSize = audio.samples.size * 2
        out.write(header(audio.sampleRate, audio.channels, dataSize))
        val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in audio.samples) buffer.putShort(floatToPcm16(s))
        out.write(buffer.array())
    }

    fun write(file: File, audio: PcmAudio) = file.outputStream().buffered().use { write(it, audio) }

    fun header(sampleRate: Int, channels: Int, dataSize: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataSize); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(sampleRate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataSize)
        return b.array()
    }

    /** Rewrites the sizes of a WAV header after streaming PCM data into [file]. */
    fun finalizeHeader(file: File, sampleRate: Int, channels: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = (raf.length() - 44).toInt().coerceAtLeast(0)
            raf.seek(0)
            raf.write(header(sampleRate, channels, dataSize))
        }
    }

    fun read(file: File): PcmAudio = file.inputStream().buffered().use { read(it) }

    /** Reads PCM 16-bit / 24-bit / 32-bit float WAV files. */
    fun read(input: InputStream): PcmAudio {
        val data = DataInputStream(input)
        val riff = ByteArray(12)
        data.readFully(riff)
        require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "Not a WAV file" }
        var sampleRate = 44_100
        var channels = 1
        var bits = 16
        var format = 1
        while (true) {
            val chunkHeader = ByteArray(8)
            data.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = ByteArray(size)
            data.readFully(body)
            if (size % 2 == 1) data.skipBytes(1)
            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            when (id) {
                "fmt " -> {
                    format = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int; bb.short
                    bits = bb.short.toInt()
                    if (format == 0xFFFE) format = if (bits == 32) 3 else 1
                }
                "data" -> {
                    val bytesPerSample = bits / 8
                    val count = size / bytesPerSample
                    val samples = FloatArray(count)
                    for (i in 0 until count) {
                        samples[i] = when {
                            format == 3 && bits == 32 -> bb.float
                            bits == 16 -> bb.short / 32768f
                            bits == 24 -> {
                                val b0 = bb.get().toInt() and 0xFF
                                val b1 = bb.get().toInt() and 0xFF
                                val b2 = bb.get().toInt()
                                ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                            }
                            bits == 32 -> bb.int / 2_147_483_648f
                            bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                            else -> 0f
                        }
                    }
                    return PcmAudio(samples, sampleRate, channels)
                }
            }
        }
    }

    fun floatToPcm16(s: Float): Short = (max(-1f, min(1f, s)) * 32767f).toInt().toShort()
}

/**
 * Streaming min/max peak extraction for waveform display. Feed any amount of mono samples and
 * read [peaks]: one (min, max) pair per bucket of [samplesPerBucket] samples.
 */
class WaveformBuilder(private val samplesPerBucket: Int) {
    private val mins = ArrayList<Float>()
    private val maxs = ArrayList<Float>()
    private var count = 0
    private var curMin = 0f
    private var curMax = 0f

    fun add(samples: FloatArray, offset: Int = 0, length: Int = samples.size) {
        for (i in offset until offset + length) {
            val s = samples[i]
            if (count == 0) { curMin = s; curMax = s } else {
                if (s < curMin) curMin = s
                if (s > curMax) curMax = s
            }
            count++
            if (count == samplesPerBucket) flushBucket()
        }
    }

    private fun flushBucket() {
        mins += curMin; maxs += curMax; count = 0
    }

    /** Returns interleaved [min0, max0, min1, max1, ...]. */
    fun peaks(): FloatArray {
        if (count > 0) flushBucket()
        val out = FloatArray(mins.size * 2)
        for (i in mins.indices) { out[i * 2] = mins[i]; out[i * 2 + 1] = maxs[i] }
        return out
    }
}
