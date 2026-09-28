package com.clipmaker.app.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.clipmaker.core.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/** Records the microphone to a 48 kHz mono 16-bit WAV file, exposing a live input level. */
class AudioRecorder {
    private val _level = MutableStateFlow(0f)

    /** Peak level of the last buffer, 0..1 (drives the VU meter). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _elapsedUs = MutableStateFlow(0L)
    val elapsedUs: StateFlow<Long> = _elapsedUs.asStateFlow()

    /** Records until the calling coroutine is cancelled; returns the duration in microseconds. */
    @SuppressLint("MissingPermission")
    suspend fun record(output: File, sampleRate: Int = 48_000): Long = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = max(minBuffer, sampleRate / 10 * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { android.os.Build.VERSION.SDK_INT >= 24 } ?: MediaRecorder.AudioSource.MIC,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
        ).let { r ->
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            }
        }
        output.parentFile?.mkdirs()
        var frames = 0L
        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0)
            raf.write(Wav.header(sampleRate, 1, 0))
            val shorts = ShortArray(bufferSize / 2)
            val bytes = ByteArray(shorts.size * 2)
            recorder.startRecording()
            try {
                while (coroutineContext.isActive) {
                    val n = recorder.read(shorts, 0, shorts.size)
                    if (n <= 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val s = shorts[i].toInt()
                        peak = max(peak, abs(s))
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    raf.write(bytes, 0, n * 2)
                    frames += n
                    _level.value = peak / 32768f
                    _elapsedUs.value = frames * 1_000_000L / sampleRate
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                _level.value = 0f
            }
        }
        Wav.finalizeHeader(output, sampleRate, 1)
        frames * 1_000_000L / sampleRate
    }
}
