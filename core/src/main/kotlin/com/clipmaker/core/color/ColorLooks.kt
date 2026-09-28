package com.clipmaker.core.color

import com.clipmaker.core.model.ColorLookId
import kotlin.math.pow

/** Simple RGB triple in 0..1. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    val luma: Float get() = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun clamp() = Rgb(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    fun mix(other: Rgb, t: Float) = Rgb(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)

    fun toArgb(): Int {
        val c = clamp()
        return (0xFF shl 24) or (Math.round(c.r * 255) shl 16) or (Math.round(c.g * 255) shl 8) or Math.round(c.b * 255)
    }
}

/**
 * Procedural colour "looks" (film emulation / Instagram-style filters). Each look is a pure colour
 * transform that is baked into a 3D LUT and applied on the GPU.
 */
object ColorLooks {

    fun apply(look: ColorLookId, c: Rgb): Rgb = when (look) {
        ColorLookId.CINEMATIC -> c.contrast(1.15f).splitTone(shadow = Rgb(0.05f, 0.15f, 0.2f), highlight = Rgb(1f, 0.85f, 0.65f), amount = 0.18f)
            .saturate(0.9f).lift(0.03f)
        ColorLookId.TEAL_ORANGE -> c.splitTone(shadow = Rgb(0f, 0.45f, 0.55f), highlight = Rgb(1f, 0.6f, 0.25f), amount = 0.35f).contrast(1.1f)
        ColorLookId.VINTAGE -> c.saturate(0.7f).curve(0.08f, 0.92f).tint(Rgb(1.05f, 1f, 0.82f))
        ColorLookId.NOIR -> Rgb(c.luma, c.luma, c.luma).contrast(1.45f)
        ColorLookId.WARM -> c.tint(Rgb(1.08f, 1.0f, 0.88f)).saturate(1.05f)
        ColorLookId.COOL -> c.tint(Rgb(0.9f, 1.0f, 1.1f))
        ColorLookId.FADED -> c.saturate(0.75f).curve(0.12f, 0.9f)
        ColorLookId.VIVID -> c.saturate(1.45f).contrast(1.12f)
        ColorLookId.MATRIX -> c.tint(Rgb(0.75f, 1.1f, 0.7f)).contrast(1.2f).saturate(0.8f)
        ColorLookId.SUNSET -> c.tint(Rgb(1.12f, 0.92f, 0.8f)).splitTone(Rgb(0.4f, 0.1f, 0.4f), Rgb(1f, 0.7f, 0.3f), 0.2f)
        ColorLookId.BLEACH_BYPASS -> c.mix(Rgb(c.luma, c.luma, c.luma), 0.55f).contrast(1.35f)
        ColorLookId.CYBERPUNK -> c.splitTone(Rgb(0.2f, 0.0f, 0.6f), Rgb(1f, 0.2f, 0.8f), 0.35f).contrast(1.15f).saturate(1.2f)
        ColorLookId.KODAK -> c.curve(0.03f, 0.97f).tint(Rgb(1.06f, 1.02f, 0.9f)).contrast(1.08f).saturate(1.1f)
        ColorLookId.MOODY -> c.saturate(0.65f).contrast(1.2f).gamma(1.15f).tint(Rgb(0.95f, 1f, 1.05f))
    }.clamp()

    /**
     * Builds an N×N×N LUT, indexed as `cube[r][g][b]`, each entry an ARGB_8888 colour.
     * [intensity] blends between identity (0) and the full look (1).
     */
    fun buildLut(look: ColorLookId, size: Int = 33, intensity: Float = 1f): Array<Array<IntArray>> {
        val scale = 1f / (size - 1)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    val src = Rgb(r * scale, g * scale, b * scale)
                    src.mix(apply(look, src), intensity.coerceIn(0f, 1f)).toArgb()
                }
            }
        }
    }

    private fun Rgb.contrast(k: Float) = Rgb((r - 0.5f) * k + 0.5f, (g - 0.5f) * k + 0.5f, (b - 0.5f) * k + 0.5f)

    private fun Rgb.saturate(k: Float): Rgb {
        val l = luma
        return Rgb(l + (r - l) * k, l + (g - l) * k, l + (b - l) * k)
    }

    private fun Rgb.tint(m: Rgb) = Rgb(r * m.r, g * m.g, b * m.b)

    /** Lifts blacks to [black] and lowers whites to [white]. */
    private fun Rgb.curve(black: Float, white: Float) =
        Rgb(black + r * (white - black), black + g * (white - black), black + b * (white - black))

    private fun Rgb.lift(amount: Float) = curve(amount, 1f)

    private fun Rgb.gamma(g0: Float) = Rgb(r.coerceAtLeast(0f).pow(g0), g.coerceAtLeast(0f).pow(g0), b.coerceAtLeast(0f).pow(g0))

    /** Pushes shadows toward [shadow] and highlights toward [highlight]. */
    private fun Rgb.splitTone(shadow: Rgb, highlight: Rgb, amount: Float): Rgb {
        val l = luma
        val sw = (1f - l).pow(2) * amount
        val hw = l.pow(2) * amount
        fun ch(v: Float, s: Float, h: Float) = v + (s - 0.5f) * sw + (h - 0.5f) * hw
        return Rgb(ch(r, shadow.r, highlight.r), ch(g, shadow.g, highlight.g), ch(b, shadow.b, highlight.b))
    }
}
