package com.clipmaker.core

import com.clipmaker.core.audio.AudioEffectChain
import com.clipmaker.core.audio.Biquad
import com.clipmaker.core.audio.CompressorNode
import com.clipmaker.core.audio.PcmAudio
import com.clipmaker.core.audio.SoundFx
import com.clipmaker.core.audio.SoundFxType
import com.clipmaker.core.audio.Wav
import com.clipmaker.core.audio.WaveformBuilder
import com.clipmaker.core.beat.BeatDetector
import com.clipmaker.core.model.AudioEffect
import com.clipmaker.core.model.AudioEffectType
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    private val sr = 44_100

    private fun sine(freq: Double, seconds: Double) = FloatArray((sr * seconds).toInt()) { (sin(2 * PI * freq * it / sr) * 0.5).toFloat() }
    private fun rms(x: FloatArray, from: Int = x.size / 4) = sqrt(x.drop(from).map { it * it }.average()).toFloat()

    @Test
    fun `low pass attenuates high frequencies`() {
        val high = sine(8_000.0, 0.5)
        val low = sine(100.0, 0.5)
        val f1 = Biquad(1).configure(Biquad.Type.LOW_PASS, sr, 1_000.0)
        val f2 = Biquad(1).configure(Biquad.Type.LOW_PASS, sr, 1_000.0)
        val h = FloatArray(high.size) { f1.processSample(high[it].toDouble(), 0).toFloat() }
        val l = FloatArray(low.size) { f2.processSample(low[it].toDouble(), 0).toFloat() }
        assertTrue(rms(h) < rms(high) * 0.1f)
        assertTrue(rms(l) > rms(low) * 0.9f)
    }

    @Test
    fun `compressor reduces loud signals`() {
        val c = CompressorNode(sr, thresholdDb = -20f, ratio = 4f, attackMs = 1f, releaseMs = 50f, makeupDb = 0f)
        assertEquals(0f, c.gainReductionDb(-40f))
        assertEquals(15f, c.gainReductionDb(0f), 0.01f)
        val x = sine(440.0, 0.5).map { it * 1.8f }.toFloatArray()
        val before = rms(x)
        c.process(x, x.size, 1)
        assertTrue(rms(x) < before * 0.6f)
    }

    @Test
    fun `every effect produces finite bounded output`() {
        val rnd = Random(1)
        AudioEffectType.entries.forEach { type ->
            val buf = FloatArray(sr) { rnd.nextFloat() * 2 - 1 }
            val chain = AudioEffectChain(listOf(AudioEffect(type)), sr, 2)
            chain.process(buf, buf.size / 2, 2)
            assertTrue(buf.all { it.isFinite() && abs(it) <= 1.5f }, "effect $type")
        }
    }

    @Test
    fun `wav round trip`() {
        val audio = PcmAudio(sine(440.0, 0.1), sr, 1)
        val out = ByteArrayOutputStream()
        Wav.write(out, audio)
        val back = Wav.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sr, back.sampleRate)
        assertEquals(audio.samples.size, back.samples.size)
        assertEquals(audio.samples[100], back.samples[100], 0.001f)
    }

    @Test
    fun `waveform peaks`() {
        val w = WaveformBuilder(100)
        w.add(FloatArray(250) { if (it == 120) 0.9f else 0f })
        val peaks = w.peaks()
        assertEquals(6, peaks.size)
        assertEquals(0.9f, peaks[3])
    }

    @Test
    fun `all sound fx are generated normalised`() {
        SoundFxType.entries.forEach {
            val a = SoundFx.generate(it)
            assertTrue(a.samples.isNotEmpty())
            val peak = a.samples.maxOf { s -> abs(s) }
            assertTrue(peak in 0.5f..0.9f, "$it peak=$peak")
        }
    }

    @Test
    fun `beat detector finds tempo of a click track`() {
        for (bpm in listOf(90.0, 120.0, 128.0)) {
            val seconds = 20.0
            val buf = FloatArray((sr * seconds).toInt())
            val kick = SoundFx.generate(SoundFxType.KICK, sr).samples
            val hat = SoundFx.generate(SoundFxType.HIHAT, sr).samples
            val period = 60.0 / bpm
            var t = 0.25
            var n = 0
            while (t < seconds - 1) {
                val start = (t * sr).toInt()
                val sample = if (n % 2 == 0) kick else hat
                for (i in sample.indices) if (start + i < buf.size) buf[start + i] += sample[i] * 0.7f
                t += period; n++
            }
            val analysis = BeatDetector.analyze(buf, sr)
            assertEquals(bpm.toFloat(), analysis.bpm, 2f, "bpm for $bpm")
            // Beats should line up with the clicks (within 30 ms).
            val expected = (0 until 20).map { ((0.25 + it * period) * 1_000_000).toLong() }
            val matched = expected.count { e -> analysis.beatsUs.any { abs(it - e) < 30_000 } }
            assertTrue(matched >= 16, "matched $matched beats at $bpm")
        }
    }

    @Test
    fun `tap tempo and grid`() {
        assertEquals(120f, BeatDetector.tapTempo(listOf(0L, 500_000, 1_000_000, 1_500_000))!!, 0.01f)
        assertEquals(5, BeatDetector.gridFromBpm(120f, 0, 2_000_000).size)
    }

    @Test
    fun `silence trimming finds the sound`() {
        val buf = FloatArray(sr) // 1 s
        for (i in 22_050 until 33_075) buf[i] = 0.5f * sin(i * 0.05).toFloat()
        val (start, end) = com.clipmaker.core.audio.Silence.trimBounds(buf, sr)
        assertTrue(start in 470_000L..500_000L, "start=$start")
        assertTrue(end in 750_000L..790_000L, "end=$end")
    }
}
