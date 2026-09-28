package com.clipmaker.app.record

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.clipmaker.core.audio.SoundFx
import com.clipmaker.core.audio.SoundFxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Sample-accurate metronome rendered into an AudioTrack stream. */
class Metronome {
    private val high = SoundFx.generate(SoundFxType.METRONOME_HIGH).samples
    private val low = SoundFx.generate(SoundFxType.METRONOME_LOW).samples
    private val _beat = MutableStateFlow(-1)

    /** Index of the current beat inside the bar (0..beatsPerBar-1), -1 when stopped. */
    val beat: StateFlow<Int> = _beat.asStateFlow()

    /** Plays until cancelled. When [countInBeats] > 0, returns after that many beats instead. */
    suspend fun play(bpm: Float, beatsPerBar: Int = 4, volume: Float = 0.8f, countInBeats: Int = 0) = withContext(Dispatchers.Default) {
        val rate = SoundFx.SAMPLE_RATE
        val chunk = rate / 50
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(chunk * 4 * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val samplesPerBeat = (rate * 60.0 / bpm).toLong()
        var position = 0L
        val buffer = FloatArray(chunk)
        track.play()
        try {
            while (coroutineContext.isActive) {
                val beatIndex = position / samplesPerBeat
                if (countInBeats > 0 && beatIndex >= countInBeats) break
                for (i in 0 until chunk) {
                    val p = position + i
                    val b = p / samplesPerBeat
                    val offset = (p - b * samplesPerBeat).toInt()
                    val click = if (b % beatsPerBar == 0L) high else low
                    buffer[i] = if (offset < click.size) click[offset] * volume else 0f
                }
                _beat.value = (beatIndex % beatsPerBar).toInt()
                track.write(buffer, 0, chunk, AudioTrack.WRITE_BLOCKING)
                position += chunk
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            _beat.value = -1
        }
    }
}
