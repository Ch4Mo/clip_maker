package com.clipmaker.core.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural sound-effect library: royalty-free transitions, drums and FX synthesised on device,
 * so the app ships with a usable sound bank without licensing any samples.
 */
enum class SoundFxType(val label: String, val category: String) {
    WHOOSH("Whoosh", "Transitions"),
    RISER("Montée (riser)", "Transitions"),
    DOWNLIFTER("Descente", "Transitions"),
    IMPACT("Impact", "Transitions"),
    SUB_DROP("Sub drop", "Transitions"),
    REVERSE_CYMBAL("Cymbale inversée", "Transitions"),
    TAPE_STOP("Arrêt bande", "Transitions"),
    KICK("Kick", "Batterie"),
    SNARE("Caisse claire", "Batterie"),
    CLAP("Clap", "Batterie"),
    HIHAT("Charley", "Batterie"),
    OPEN_HAT("Charley ouvert", "Batterie"),
    TOM("Tom", "Batterie"),
    CLICK("Clic", "Interface"),
    POP("Pop", "Interface"),
    BLEEP("Bip censure", "Interface"),
    NOTIFICATION("Notification", "Interface"),
    GLITCH("Glitch", "FX"),
    LASER("Laser", "FX"),
    SCRATCH("Scratch", "FX"),
    VINYL_NOISE("Craquement vinyle", "FX"),
    METRONOME_HIGH("Métronome (temps fort)", "Métronome"),
    METRONOME_LOW("Métronome", "Métronome"),
}

object SoundFx {
    const val SAMPLE_RATE = 48_000

    fun generate(type: SoundFxType, sampleRate: Int = SAMPLE_RATE, seed: Int = 7): PcmAudio {
        val rnd = Random(seed)
        fun noise() = rnd.nextFloat() * 2f - 1f
        fun buffer(seconds: Double) = FloatArray((seconds * sampleRate).toInt())
        val sr = sampleRate.toDouble()

        val samples: FloatArray = when (type) {
            SoundFxType.WHOOSH -> sweepNoise(buffer(1.0), sr, 300.0, 6_000.0, rnd, bell = true)
            SoundFxType.RISER -> {
                val out = sweepNoise(buffer(4.0), sr, 200.0, 12_000.0, rnd, bell = false)
                var phase = 0.0
                for (i in out.indices) {
                    val t = i / out.size.toDouble()
                    phase += 2 * PI * (110 * 2.0.pow(t * 3)) / sr
                    out[i] = (out[i] * 0.6f + sin(phase).toFloat() * 0.35f * t.toFloat()) * t.toFloat()
                }
                out
            }
            SoundFxType.DOWNLIFTER -> sweepNoise(buffer(2.5), sr, 10_000.0, 150.0, rnd, bell = false).also { out ->
                for (i in out.indices) out[i] *= (1 - i / out.size.toFloat())
            }
            SoundFxType.IMPACT -> {
                val out = buffer(2.0)
                var phase = 0.0
                val lp = Biquad(1).configure(Biquad.Type.LOW_PASS, sampleRate, 900.0)
                for (i in out.indices) {
                    val t = i / sr
                    phase += 2 * PI * (40 + 120 * exp(-t * 18)) / sr
                    val body = sin(phase) * exp(-t * 2.5)
                    val crack = lp.processSample(noise().toDouble(), 0) * exp(-t * 10) * 1.5
                    out[i] = (body * 0.9 + crack).toFloat()
                }
                out
            }
            SoundFxType.SUB_DROP -> tone(buffer(2.5), sr) { t, _ -> 30 + 90 * exp(-t * 2.2) }.also { envelope(it, sr, 0.005, 2.3) }
            SoundFxType.REVERSE_CYMBAL -> {
                val out = buffer(2.5)
                val hp = Biquad(1).configure(Biquad.Type.HIGH_PASS, sampleRate, 5_000.0)
                for (i in out.indices) {
                    val t = i / out.size.toDouble()
                    out[i] = (hp.processSample(noise().toDouble(), 0) * t.pow(3) * 0.9).toFloat()
                }
                out
            }
            SoundFxType.TAPE_STOP -> tone(buffer(1.2), sr) { t, _ -> 220 * max(0.02, 1 - t / 1.2).pow(2) }.also { out ->
                for (i in out.indices) out[i] = (out[i] + (sin(out[i] * 6.0) * 0.3).toFloat()) * (1 - i / out.size.toFloat())
            }
            SoundFxType.KICK -> tone(buffer(0.6), sr) { t, _ -> 45 + 110 * exp(-t * 35) }.also { envelope(it, sr, 0.001, 0.45) }
            SoundFxType.SNARE -> {
                val out = buffer(0.35)
                val bp = Biquad(1).configure(Biquad.Type.HIGH_PASS, sampleRate, 1_500.0)
                var phase = 0.0
                for (i in out.indices) {
                    val t = i / sr
                    phase += 2 * PI * 185 / sr
                    out[i] = (sin(phase) * exp(-t * 25) * 0.5 + bp.processSample(noise().toDouble(), 0) * exp(-t * 14) * 0.8).toFloat()
                }
                out
            }
            SoundFxType.CLAP -> {
                val out = buffer(0.4)
                val bp = Biquad(1).configure(Biquad.Type.BAND_PASS, sampleRate, 1_200.0, 1.2)
                for (i in out.indices) {
                    val t = i / sr
                    val bursts = listOf(0.0, 0.011, 0.023, 0.034).sumOf { s -> if (t >= s) exp(-(t - s) * (if (s == 0.034) 16.0 else 180.0)) else 0.0 }
                    out[i] = (bp.processSample(noise().toDouble(), 0) * bursts * 2.2).toFloat()
                }
                out
            }
            SoundFxType.HIHAT, SoundFxType.OPEN_HAT -> {
                val decay = if (type == SoundFxType.HIHAT) 45.0 else 7.0
                val out = buffer(if (type == SoundFxType.HIHAT) 0.12 else 0.6)
                val hp = Biquad(1).configure(Biquad.Type.HIGH_PASS, sampleRate, 7_000.0)
                for (i in out.indices) out[i] = (hp.processSample(noise().toDouble(), 0) * exp(-i / sr * decay) * 0.8).toFloat()
                out
            }
            SoundFxType.TOM -> tone(buffer(0.6), sr) { t, _ -> 90 + 60 * exp(-t * 20) }.also { envelope(it, sr, 0.001, 0.5) }
            SoundFxType.CLICK -> tone(buffer(0.03), sr) { _, _ -> 2_000.0 }.also { envelope(it, sr, 0.0005, 0.02) }
            SoundFxType.POP -> tone(buffer(0.12), sr) { t, _ -> 400 + 900 * (t / 0.12) }.also { envelope(it, sr, 0.002, 0.1) }
            SoundFxType.BLEEP -> tone(buffer(0.6), sr) { _, _ -> 1_000.0 }.also { envelope(it, sr, 0.005, 0.6, sustain = true) }
            SoundFxType.NOTIFICATION -> {
                val out = buffer(0.5)
                val a = tone(FloatArray(out.size / 2), sr) { _, _ -> 880.0 }.also { envelope(it, sr, 0.002, 0.25) }
                val b = tone(FloatArray(out.size - out.size / 2), sr) { _, _ -> 1_318.5 }.also { envelope(it, sr, 0.002, 0.25) }
                a.copyInto(out); b.copyInto(out, a.size)
                out
            }
            SoundFxType.GLITCH -> {
                val out = buffer(0.8)
                var hold = 0f
                var counter = 0
                var period = 200
                for (i in out.indices) {
                    if (counter <= 0) {
                        hold = noise(); counter = period
                        if (rnd.nextFloat() < 0.02f) period = 20 + rnd.nextInt(600)
                    }
                    counter--
                    val gate = if ((i / 2_400) % 3 == 1) 0f else 1f
                    out[i] = hold * 0.6f * gate
                }
                out
            }
            SoundFxType.LASER -> tone(buffer(0.45), sr) { t, _ -> 2_500 * exp(-t * 9) + 150 }.also { envelope(it, sr, 0.001, 0.42) }
            SoundFxType.SCRATCH -> {
                val out = buffer(0.7)
                val bp = Biquad(1).configure(Biquad.Type.BAND_PASS, sampleRate, 1_000.0, 2.0)
                for (i in out.indices) {
                    val t = i / sr
                    val f = 600 + 1_400 * (0.5 + 0.5 * sin(2 * PI * 5 * t))
                    if (i % 256 == 0) bp.configure(Biquad.Type.BAND_PASS, sampleRate, f, 2.0)
                    out[i] = (bp.processSample(noise().toDouble(), 0) * 2.5 * (0.5 + 0.5 * sin(2 * PI * 10 * t))).toFloat()
                }
                out
            }
            SoundFxType.VINYL_NOISE -> {
                val out = buffer(4.0)
                val lp = Biquad(1).configure(Biquad.Type.LOW_PASS, sampleRate, 3_000.0)
                for (i in out.indices) {
                    val crackle = if (rnd.nextFloat() < 0.0006f) noise() * 0.8f else 0f
                    out[i] = (lp.processSample(noise().toDouble() * 0.03, 0)).toFloat() + crackle
                }
                out
            }
            SoundFxType.METRONOME_HIGH -> tone(buffer(0.05), sr) { _, _ -> 1_760.0 }.also { envelope(it, sr, 0.0005, 0.045) }
            SoundFxType.METRONOME_LOW -> tone(buffer(0.05), sr) { _, _ -> 1_100.0 }.also { envelope(it, sr, 0.0005, 0.045) }
        }
        normalize(samples, 0.89f)
        return PcmAudio(samples, sampleRate, 1)
    }

    private inline fun tone(out: FloatArray, sr: Double, freq: (t: Double, i: Int) -> Double): FloatArray {
        var phase = 0.0
        for (i in out.indices) {
            phase += 2 * PI * freq(i / sr, i) / sr
            out[i] = sin(phase).toFloat()
        }
        return out
    }

    private fun envelope(out: FloatArray, sr: Double, attack: Double, length: Double, sustain: Boolean = false) {
        for (i in out.indices) {
            val t = i / sr
            val a = if (t < attack) t / attack else 1.0
            val d = if (sustain) (if (t > length - 0.01) max(0.0, (length - t) / 0.01) else 1.0) else exp(-(t - attack).coerceAtLeast(0.0) * 5.0 / length)
            out[i] = (out[i] * a * d).toFloat()
        }
    }

    private fun sweepNoise(out: FloatArray, sr: Double, from: Double, to: Double, rnd: Random, bell: Boolean): FloatArray {
        val bp = Biquad(1)
        for (i in out.indices) {
            val t = i / out.size.toDouble()
            if (i % 128 == 0) bp.configure(Biquad.Type.BAND_PASS, sr.toInt(), from * (to / from).pow(t), 1.5)
            val env = if (bell) sin(PI * t).pow(2) else t
            out[i] = (bp.processSample((rnd.nextFloat() * 2 - 1).toDouble(), 0) * env * 3).toFloat()
        }
        return out
    }

    fun normalize(samples: FloatArray, target: Float) {
        var peak = 0f
        for (s in samples) peak = max(peak, kotlin.math.abs(s))
        if (peak < 1e-6f) return
        val g = target / peak
        for (i in samples.indices) samples[i] *= g
    }
}
