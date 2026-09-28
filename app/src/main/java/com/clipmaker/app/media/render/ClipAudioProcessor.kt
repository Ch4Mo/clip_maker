package com.clipmaker.app.media.render

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.clipmaker.core.audio.AudioEffectChain
import com.clipmaker.core.model.AudioEffect
import com.clipmaker.core.model.Clip
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a clip's volume automation, fades, and effect chain (plus the track's gain and bus
 * effects) to 16-bit PCM, using the ClipMaker DSP engine.
 */
@UnstableApi
class ClipAudioProcessor(
    private val clip: Clip,
    private val trackGain: Float,
    private val trackEffects: List<AudioEffect>,
) : BaseAudioProcessor() {
    private var chain: AudioEffectChain? = null
    private var framesProcessed = 0L
    private var startLocalUs = 0L
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        chain = AudioEffectChain(clip.audioEffects + trackEffects, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val samples = bytes / 2
        val frames = samples / channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until samples) scratch[i] = input.getShort(input.position() + i * 2) / 32768f
        inputBuffer.position(inputBuffer.limit())

        // Gain envelope (volume curve, fades, track fader), evaluated every 64 frames.
        var gain = 1f
        for (f in 0 until frames) {
            if (f % 64 == 0) {
                val localUs = startLocalUs + (framesProcessed + f) * 1_000_000L / format.sampleRate
                gain = clip.gainAt(localUs) * trackGain
            }
            for (ch in 0 until channels) scratch[f * channels + ch] *= gain
        }
        chain?.takeIf { !it.isEmpty }?.process(scratch, frames, channels)
        framesProcessed += frames

        val out = replaceOutputBuffer(samples * 2)
        for (i in 0 until samples) {
            val v = (scratch[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out.putShort(v.toShort())
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesProcessed = 0
        val pos = streamMetadata.positionOffsetUs
        startLocalUs = when {
            pos in 0..clip.durationUs -> pos
            pos in clip.startUs..clip.endUs -> pos - clip.startUs
            else -> 0L
        }
        chain?.reset()
    }

    override fun onReset() {
        chain = null
        framesProcessed = 0
        startLocalUs = 0
    }
}
