package com.clipmaker.core.beat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BeatAnalysis(
    val bpm: Float,
    /** Beat positions relative to the start of the analysed audio. */
    val beatsUs: List<Long>,
    /** Strong transients (drum hits, attacks) usable as additional cut points. */
    val onsetsUs: List<Long>,
    /** Indices in [beatsUs] estimated to be the first beat of each bar (4/4). */
    val downbeatIndices: List<Int>,
    /** 0..1: how periodic the music is (low for speech or ambient). */
    val confidence: Float,
)

/**
 * Tempo and beat tracker:
 * 1. log-compressed spectral flux onset envelope,
 * 2. tempo from the autocorrelation of the envelope (weighted around 120 BPM),
 * 3. beat positions by dynamic programming (Ellis, 2007).
 */
object BeatDetector {
    private const val FRAME = 1024
    private const val HOP = 512

    fun analyze(mono: FloatArray, sampleRate: Int, minBpm: Float = 60f, maxBpm: Float = 200f): BeatAnalysis {
        val envelope = onsetEnvelope(mono, sampleRate)
        val hopUs = HOP * 1_000_000.0 / sampleRate
        if (envelope.size < 16) return BeatAnalysis(0f, emptyList(), emptyList(), emptyList(), 0f)

        val (period, confidence) = estimatePeriod(envelope, hopUs, minBpm, maxBpm)
        val bpm = (60_000_000.0 / (period * hopUs)).toFloat()
        val beatFrames = trackBeats(envelope, period)
        val beatsUs = beatFrames.map { (it * hopUs).toLong() }
        val onsets = pickOnsets(envelope).map { (it * hopUs).toLong() }
        val downbeats = estimateDownbeats(envelope, beatFrames)
        return BeatAnalysis(roundBpm(bpm), beatsUs, onsets, downbeats, confidence)
    }

    /** Snaps near-integer tempi (e.g. 119.8 -> 120) for friendlier display. */
    private fun roundBpm(bpm: Float): Float {
        val rounded = bpm.roundToInt().toFloat()
        return if (kotlin.math.abs(rounded - bpm) < 0.6f) rounded else (bpm * 10).roundToInt() / 10f
    }

    fun onsetEnvelope(mono: FloatArray, sampleRate: Int): FloatArray {
        val frames = if (mono.size < FRAME) 0 else (mono.size - FRAME) / HOP + 1
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val bins = FRAME / 2
        // Only consider up to ~8 kHz: most rhythmic energy lives there.
        val maxBin = minOf(bins, (8_000f * FRAME / sampleRate).toInt())
        var previous = FloatArray(maxBin)
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val offset = f * HOP
            for (i in 0 until FRAME) { re[i] = mono[offset + i] * window[i]; im[i] = 0f }
            Fft.transform(re, im)
            val mag = FloatArray(maxBin)
            var sum = 0f
            for (k in 1 until maxBin) {
                val m = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]))
                mag[k] = m
                val d = m - previous[k]
                if (d > 0) sum += d
            }
            flux[f] = sum
            previous = mag
        }
        // Remove the local mean (high-pass) and normalise.
        val smoothed = movingAverage(flux, 16)
        val out = FloatArray(frames) { max(0f, flux[it] - smoothed[it]) }
        val peak = out.maxOrNull() ?: 0f
        if (peak > 0) for (i in out.indices) out[i] /= peak
        return out
    }

    private fun movingAverage(x: FloatArray, radius: Int): FloatArray {
        val out = FloatArray(x.size)
        var sum = 0.0
        var count = 0
        var lo = 0
        var hi = -1
        for (i in x.indices) {
            val wantLo = max(0, i - radius)
            val wantHi = minOf(x.size - 1, i + radius)
            while (hi < wantHi) { hi++; sum += x[hi]; count++ }
            while (lo < wantLo) { sum -= x[lo]; lo++; count-- }
            out[i] = (sum / count).toFloat()
        }
        return out
    }

    /** Returns the beat period in envelope frames, and a confidence value. */
    private fun estimatePeriod(env: FloatArray, hopUs: Double, minBpm: Float, maxBpm: Float): Pair<Double, Float> {
        val minLag = (60_000_000.0 / (maxBpm * hopUs)).toInt().coerceAtLeast(1)
        val maxLag = (60_000_000.0 / (minBpm * hopUs)).toInt().coerceAtMost(env.size - 1)
        if (maxLag <= minLag) return 60_000_000.0 / (120 * hopUs) to 0f
        val ac = DoubleArray(maxLag + 2)
        for (lag in 0..maxLag + 1) {
            if (lag >= env.size) break
            var s = 0.0
            for (i in lag until env.size) s += env[i] * env[i - lag]
            ac[lag] = s / (env.size - lag)
        }
        // Log-gaussian tempo prior centred on 120 BPM (octave width 1.0).
        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val bpm = 60_000_000.0 / (lag * hopUs)
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 1.0).let { it * it })
            // Harmonic reinforcement: a true beat period also correlates at 2x lag.
            val harmonic = if (2 * lag <= maxLag + 1) 0.5 * ac[2 * lag] else 0.0
            val score = (ac[lag] + harmonic) * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        // Parabolic interpolation for sub-frame precision.
        var period = bestLag.toDouble()
        if (bestLag in 1 until ac.size - 1) {
            val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
            val denom = a - 2 * b + c
            if (denom != 0.0) period += (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        }
        val confidence = if (ac[0] > 0) (ac[bestLag] / ac[0]).toFloat().coerceIn(0f, 1f) else 0f
        return period to confidence
    }

    /** Dynamic-programming beat tracker returning envelope frame indices. */
    private fun trackBeats(env: FloatArray, period: Double, tightness: Double = 100.0): List<Int> {
        val n = env.size
        val score = DoubleArray(n)
        val backlink = IntArray(n) { -1 }
        val minPrev = (period / 2).roundToInt().coerceAtLeast(1)
        val maxPrev = (period * 2).roundToInt()
        for (i in 0 until n) {
            var best = 0.0
            var bestIdx = -1
            for (prev in (i - maxPrev).coerceAtLeast(0)..(i - minPrev)) {
                if (prev < 0) continue
                val ratio = (i - prev) / period
                val penalty = -tightness * ln(ratio).let { it * it }
                val candidate = score[prev] + penalty
                if (bestIdx == -1 || candidate > best) { best = candidate; bestIdx = prev }
            }
            score[i] = env[i] + (if (bestIdx >= 0) best else 0.0)
            backlink[i] = bestIdx
        }
        // Start from the best-scoring frame in the last period.
        val tailStart = (n - period.roundToInt()).coerceAtLeast(0)
        var idx = (tailStart until n).maxByOrNull { score[it] } ?: return emptyList()
        val beats = ArrayList<Int>()
        while (idx >= 0) {
            beats += idx
            idx = backlink[idx]
        }
        beats.reverse()
        return beats
    }

    private fun pickOnsets(env: FloatArray, threshold: Float = 0.3f): List<Int> {
        val out = ArrayList<Int>()
        val mean = movingAverage(env, 8)
        var last = -100
        for (i in 1 until env.size - 1) {
            val v = env[i]
            if (v > threshold && v > mean[i] * 1.5f && v >= env[i - 1] && v >= env[i + 1] && i - last > 4) {
                out += i; last = i
            }
        }
        return out
    }

    /** Picks the phase (0..3) whose beats carry the most onset energy as bar starts. */
    private fun estimateDownbeats(env: FloatArray, beats: List<Int>): List<Int> {
        if (beats.size < 8) return beats.indices.filter { it % 4 == 0 }
        val energy = DoubleArray(4)
        beats.forEachIndexed { i, frame ->
            var e = 0.0
            for (k in -2..2) e += env.getOrElse(frame + k) { 0f }
            energy[i % 4] += e
        }
        val phase = energy.indices.maxByOrNull { energy[it] } ?: 0
        return beats.indices.filter { it % 4 == phase }
    }

    /** Generates a regular grid (for tap-tempo or manual BPM entry). */
    fun gridFromBpm(bpm: Float, offsetUs: Long, durationUs: Long): List<Long> {
        if (bpm <= 0f) return emptyList()
        val step = 60_000_000.0 / bpm
        val out = ArrayList<Long>()
        var t = offsetUs.toDouble()
        while (t <= durationUs) { out += t.toLong(); t += step }
        return out
    }

    /** Tempo from a series of taps (timestamps in microseconds). */
    fun tapTempo(tapsUs: List<Long>): Float? {
        if (tapsUs.size < 2) return null
        val intervals = tapsUs.zipWithNext { a, b -> b - a }.filter { it in 250_000..2_000_000 }
        if (intervals.isEmpty()) return null
        val median = intervals.sorted()[intervals.size / 2]
        return (60_000_000.0 / median).toFloat()
    }
}

object Fft {
    /** In-place iterative radix-2 FFT. Array length must be a power of two. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe; im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
