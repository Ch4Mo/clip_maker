package com.clipmaker.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
fun gainToDb(gain: Float): Float = if (gain <= 1e-9f) -180f else 20f * log10(gain)

/**
 * An audio processor working on interleaved float samples in [-1, 1].
 * Implementations keep one state per channel and process in place.
 */
interface AudioNode {
    fun process(buffer: FloatArray, frames: Int, channels: Int)
    fun reset() {}
}

/** RBJ "Audio EQ cookbook" biquad filter. */
class Biquad(private val channels: Int) {
    enum class Type { LOW_PASS, HIGH_PASS, BAND_PASS, PEAK, LOW_SHELF, HIGH_SHELF }

    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private val x1 = DoubleArray(channels); private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels); private val y2 = DoubleArray(channels)

    fun configure(type: Type, sampleRate: Int, frequency: Double, q: Double = 0.7071, gainDb: Double = 0.0): Biquad {
        val f = frequency.coerceIn(10.0, sampleRate * 0.49)
        val w0 = 2 * PI * f / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * q.coerceAtLeast(0.05))
        val a = 10.0.pow(gainDb / 40)
        val (nb0, nb1, nb2, na0, na1, na2) = when (type) {
            Type.LOW_PASS -> Six((1 - cosW) / 2, 1 - cosW, (1 - cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
            Type.HIGH_PASS -> Six((1 + cosW) / 2, -(1 + cosW), (1 + cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
            Type.BAND_PASS -> Six(alpha, 0.0, -alpha, 1 + alpha, -2 * cosW, 1 - alpha)
            Type.PEAK -> Six(1 + alpha * a, -2 * cosW, 1 - alpha * a, 1 + alpha / a, -2 * cosW, 1 - alpha / a)
            Type.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                Six(
                    a * ((a + 1) - (a - 1) * cosW + s), 2 * a * ((a - 1) - (a + 1) * cosW), a * ((a + 1) - (a - 1) * cosW - s),
                    (a + 1) + (a - 1) * cosW + s, -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - s,
                )
            }
            Type.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                Six(
                    a * ((a + 1) + (a - 1) * cosW + s), -2 * a * ((a - 1) + (a + 1) * cosW), a * ((a + 1) + (a - 1) * cosW - s),
                    (a + 1) - (a - 1) * cosW + s, 2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - s,
                )
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
        return this
    }

    fun processSample(x: Double, ch: Int): Double {
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]; x1[ch] = x
        y2[ch] = y1[ch]; y1[ch] = y
        return y
    }

    fun reset() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }

    private data class Six(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double)
}

class FilterNode(private val filters: List<Biquad>) : AudioNode {
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames) for (ch in 0 until channels) {
            val idx = i * channels + ch
            var v = buffer[idx].toDouble()
            for (f in filters) v = f.processSample(v, ch)
            buffer[idx] = v.toFloat()
        }
    }

    override fun reset() = filters.forEach { it.reset() }
}

class GainNode(var gain: Float) : AudioNode {
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        if (gain == 1f) return
        for (i in 0 until frames * channels) buffer[i] *= gain
    }
}

/** Feed-forward compressor with soft knee and make-up gain. */
class CompressorNode(
    sampleRate: Int,
    private val thresholdDb: Float,
    private val ratio: Float,
    attackMs: Float,
    releaseMs: Float,
    private val makeupDb: Float,
    private val kneeDb: Float = 6f,
) : AudioNode {
    private val attack = exp(-1.0 / (sampleRate * attackMs.coerceAtLeast(0.05f) / 1000.0)).toFloat()
    private val release = exp(-1.0 / (sampleRate * releaseMs.coerceAtLeast(1f) / 1000.0)).toFloat()
    private var envelopeDb = -120f

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val makeup = dbToGain(makeupDb)
        for (i in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(buffer[i * channels + ch]))
            val inDb = gainToDb(peak)
            val coeff = if (inDb > envelopeDb) attack else release
            envelopeDb = coeff * envelopeDb + (1 - coeff) * inDb
            val reduction = gainReductionDb(envelopeDb)
            val g = dbToGain(-reduction) * makeup
            for (ch in 0 until channels) buffer[i * channels + ch] *= g
        }
    }

    fun gainReductionDb(levelDb: Float): Float {
        val over = levelDb - thresholdDb
        return when {
            2 * over < -kneeDb -> 0f
            2 * abs(over) <= kneeDb -> {
                val x = over + kneeDb / 2
                (1 - 1 / ratio) * x * x / (2 * kneeDb)
            }
            else -> over * (1 - 1 / ratio)
        }
    }

    override fun reset() {
        envelopeDb = -120f
    }
}

class NoiseGateNode(sampleRate: Int, thresholdDb: Float) : AudioNode {
    private val threshold = dbToGain(thresholdDb)
    private val attack = exp(-1.0 / (sampleRate * 0.001)).toFloat()
    private val release = exp(-1.0 / (sampleRate * 0.08)).toFloat()
    private val hold = (sampleRate * 0.05).toInt()
    private var env = 0f
    private var gain = 0f
    private var holdCounter = 0

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(buffer[i * channels + ch]))
            env = max(peak, env * 0.999f)
            val open = env > threshold
            if (open) holdCounter = hold else if (holdCounter > 0) holdCounter--
            val target = if (open || holdCounter > 0) 1f else 0f
            val c = if (target > gain) attack else release
            gain = c * gain + (1 - c) * target
            for (ch in 0 until channels) buffer[i * channels + ch] *= gain
        }
    }

    override fun reset() {
        env = 0f; gain = 0f; holdCounter = 0
    }
}

/** Feedback delay (echo) with a gentle low-pass in the feedback loop. */
class DelayNode(sampleRate: Int, channels: Int, timeMs: Float, private val feedback: Float, private val mix: Float) : AudioNode {
    private val delaySamples = (sampleRate * timeMs / 1000f).toInt().coerceAtLeast(1)
    private val lines = Array(channels) { FloatArray(delaySamples) }
    private val lp = FloatArray(channels)
    private var pos = 0

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = i * channels + ch
                val line = lines[ch.coerceAtMost(lines.size - 1)]
                val delayed = line[pos]
                lp[ch] = lp[ch] * 0.3f + delayed * 0.7f
                val dry = buffer[idx]
                line[pos] = dry + lp[ch] * feedback
                buffer[idx] = dry * (1 - mix) + delayed * mix
            }
            pos = (pos + 1) % delaySamples
        }
    }

    override fun reset() {
        lines.forEach { it.fill(0f) }; lp.fill(0f); pos = 0
    }
}

/** Freeverb (Jezar) reverb: 8 parallel comb filters followed by 4 series all-pass filters. */
class ReverbNode(sampleRate: Int, private val channels: Int, room: Float, damping: Float, private val mix: Float) : AudioNode {
    private class Comb(size: Int) {
        val buf = FloatArray(size); var idx = 0; var store = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1 - damp) + store * damp
            buf[idx] = input + store * feedback
            idx = (idx + 1) % buf.size
            return out
        }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(size); var idx = 0
        fun process(input: Float): Float {
            val b = buf[idx]
            val out = -input + b
            buf[idx] = input + b * 0.5f
            idx = (idx + 1) % buf.size
            return out
        }
    }

    private val scale = sampleRate / 44_100f
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassTunings = intArrayOf(556, 441, 341, 225)
    private val combs = Array(channels) { ch -> combTunings.map { Comb(((it + ch * 23) * scale).toInt().coerceAtLeast(1)) } }
    private val allPasses = Array(channels) { ch -> allPassTunings.map { AllPass(((it + ch * 23) * scale).toInt().coerceAtLeast(1)) } }
    private val feedback = 0.7f + 0.28f * room.coerceIn(0f, 1f)
    private val damp = 0.4f * damping.coerceIn(0f, 1f)

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = i * channels + ch
                val c = ch.coerceAtMost(this.channels - 1)
                val input = buffer[idx] * 0.015f
                var wet = 0f
                for (comb in combs[c]) wet += comb.process(input, feedback, damp)
                for (ap in allPasses[c]) wet = ap.process(wet)
                buffer[idx] = buffer[idx] * (1 - mix) + wet * mix * 3f
            }
        }
    }
}

class ChorusNode(private val sampleRate: Int, channels: Int, private val rate: Float, private val depth: Float, private val mix: Float) : AudioNode {
    private val size = (sampleRate * 0.05f).toInt()
    private val lines = Array(channels) { FloatArray(size) }
    private var pos = 0
    private var phase = 0.0

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val baseDelay = sampleRate * 0.015f
        val modDepth = sampleRate * 0.008f * depth
        for (i in 0 until frames) {
            phase += 2 * PI * rate / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI
            for (ch in 0 until channels) {
                val idx = i * channels + ch
                val line = lines[ch.coerceAtMost(lines.size - 1)]
                line[pos] = buffer[idx]
                val chPhase = phase + ch * PI / 2
                val d = baseDelay + modDepth * (1 + sin(chPhase).toFloat()) / 2
                var readPos = pos - d
                while (readPos < 0) readPos += size
                val i0 = floor(readPos).toInt() % size
                val i1 = (i0 + 1) % size
                val frac = readPos - floor(readPos)
                val delayed = line[i0] * (1 - frac) + line[i1] * frac
                buffer[idx] = buffer[idx] * (1 - mix / 2) + delayed * mix / 2 * 1.4f
            }
            pos = (pos + 1) % size
        }
    }
}

class DistortionNode(drive: Float, private val mix: Float) : AudioNode {
    private val pre = 1f + drive.coerceIn(0f, 1f) * 30f
    private val post = 1f / tanh(pre.toDouble()).toFloat()

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val dry = buffer[i]
            val wet = tanh((dry * pre).toDouble()).toFloat() * post * 0.8f
            buffer[i] = dry * (1 - mix) + wet * mix
        }
    }
}

class BitcrusherNode(bits: Float, downsample: Float) : AudioNode {
    private val levels = 2f.pow(bits.coerceIn(2f, 16f) - 1)
    private val step = downsample.toInt().coerceAtLeast(1)
    private var counter = 0
    private val held = FloatArray(8)

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames) {
            if (counter == 0) {
                for (ch in 0 until channels.coerceAtMost(held.size)) {
                    held[ch] = Math.round(buffer[i * channels + ch] * levels) / levels
                }
            }
            for (ch in 0 until channels) buffer[i * channels + ch] = held[ch.coerceAtMost(held.size - 1)]
            counter = (counter + 1) % step
        }
    }
}

/** Hard safety limiter to avoid clipping at the end of a chain. */
class SoftClipNode : AudioNode {
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val x = buffer[i]
            if (x > 0.95f || x < -0.95f) buffer[i] = tanh(x.toDouble()).toFloat()
        }
    }
}
