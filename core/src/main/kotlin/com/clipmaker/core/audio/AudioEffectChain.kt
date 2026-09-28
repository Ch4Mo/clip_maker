package com.clipmaker.core.audio

import com.clipmaker.core.model.AudioEffect
import com.clipmaker.core.model.AudioEffectType

/**
 * Builds a DSP chain from the model's [AudioEffect] list.
 *
 * [AudioEffectType.PITCH] is not handled here: pitch shifting is delegated to the platform time
 * stretcher (Sonic on Android), which has better quality than a naive implementation.
 */
class AudioEffectChain(
    effects: List<AudioEffect>,
    val sampleRate: Int,
    val channels: Int,
) : AudioNode {
    private val nodes: List<AudioNode> = effects.filter { it.enabled }.mapNotNull { build(it) } +
        if (effects.any { it.enabled }) listOf(SoftClipNode()) else emptyList()

    val isEmpty: Boolean get() = nodes.isEmpty()

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (node in nodes) node.process(buffer, frames, channels)
    }

    override fun reset() = nodes.forEach { it.reset() }

    private fun build(e: AudioEffect): AudioNode? = when (e.type) {
        AudioEffectType.GAIN -> GainNode(dbToGain(e.param("db")))
        AudioEffectType.EQUALIZER -> FilterNode(
            listOf(
                Biquad(channels).configure(Biquad.Type.LOW_SHELF, sampleRate, 100.0, 0.7, e.param("low").toDouble()),
                Biquad(channels).configure(Biquad.Type.PEAK, sampleRate, 400.0, 1.0, e.param("lowMid").toDouble()),
                Biquad(channels).configure(Biquad.Type.PEAK, sampleRate, 1_200.0, 1.0, e.param("mid").toDouble()),
                Biquad(channels).configure(Biquad.Type.PEAK, sampleRate, 3_500.0, 1.0, e.param("highMid").toDouble()),
                Biquad(channels).configure(Biquad.Type.HIGH_SHELF, sampleRate, 9_000.0, 0.7, e.param("high").toDouble()),
            ).filterIndexed { i, _ -> e.param(listOf("low", "lowMid", "mid", "highMid", "high")[i]) != 0f },
        )
        AudioEffectType.LOW_PASS -> FilterNode(
            listOf(Biquad(channels).configure(Biquad.Type.LOW_PASS, sampleRate, e.param("cutoff").toDouble(), e.param("q").toDouble())),
        )
        AudioEffectType.HIGH_PASS -> FilterNode(
            listOf(Biquad(channels).configure(Biquad.Type.HIGH_PASS, sampleRate, e.param("cutoff").toDouble(), e.param("q").toDouble())),
        )
        AudioEffectType.TELEPHONE -> FilterNode(
            listOf(
                Biquad(channels).configure(Biquad.Type.HIGH_PASS, sampleRate, 400.0, 0.9),
                Biquad(channels).configure(Biquad.Type.HIGH_PASS, sampleRate, 400.0, 0.9),
                Biquad(channels).configure(Biquad.Type.LOW_PASS, sampleRate, 3_200.0, 0.9),
                Biquad(channels).configure(Biquad.Type.LOW_PASS, sampleRate, 3_200.0, 0.9),
                Biquad(channels).configure(Biquad.Type.PEAK, sampleRate, 1_500.0, 1.2, 6.0),
            ),
        )
        AudioEffectType.COMPRESSOR -> CompressorNode(
            sampleRate, e.param("threshold"), e.param("ratio"), e.param("attack"), e.param("release"), e.param("makeup"),
        )
        AudioEffectType.REVERB -> ReverbNode(sampleRate, channels, e.param("room"), e.param("damping"), e.param("mix"))
        AudioEffectType.DELAY -> DelayNode(sampleRate, channels, e.param("time"), e.param("feedback"), e.param("mix"))
        AudioEffectType.CHORUS -> ChorusNode(sampleRate, channels, e.param("rate"), e.param("depth"), e.param("mix"))
        AudioEffectType.DISTORTION -> DistortionNode(e.param("drive"), e.param("mix"))
        AudioEffectType.BITCRUSHER -> BitcrusherNode(e.param("bits"), e.param("downsample"))
        AudioEffectType.NOISE_GATE -> NoiseGateNode(sampleRate, e.param("threshold"))
        AudioEffectType.PITCH -> null
    }

    companion object {
        /** Semitone shift requested by the effect list (for the platform pitch shifter). */
        fun pitchSemitones(effects: List<AudioEffect>): Float =
            effects.filter { it.enabled && it.type == AudioEffectType.PITCH }.sumOf { it.param("semitones").toDouble() }.toFloat()

        fun pitchFactor(semitones: Float): Float = Math.pow(2.0, semitones / 12.0).toFloat()
    }
}
