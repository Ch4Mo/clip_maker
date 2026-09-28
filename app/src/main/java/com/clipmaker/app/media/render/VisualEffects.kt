package com.clipmaker.app.media.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import com.clipmaker.core.color.ColorLooks
import com.clipmaker.core.model.BeatGrid
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.ColorLookId
import com.clipmaker.core.model.Transition
import com.clipmaker.core.model.TransitionType
import com.clipmaker.core.model.VideoEffect
import com.clipmaker.core.model.VideoEffectType
import kotlin.math.exp

/**
 * Translates a clip's visual settings into a Media3 effect chain:
 * fit/fill to the output frame -> colour and stylistic effects -> transform -> transitions.
 *
 * Shader effects receive the composition time; the clip-local time is `pts - clip.startUs`.
 */
@UnstableApi
object VisualEffects {

    fun forClip(
        clip: Clip,
        width: Int,
        height: Int,
        opaque: Boolean,
        beatGrid: BeatGrid?,
    ): List<Effect> {
        val effects = ArrayList<Effect>()
        effects += Presentation.createForWidthAndHeight(
            width, height,
            if (clip.transform.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        clip.videoEffects.filter { it.enabled }.forEach { e -> build(e, clip, beatGrid)?.let { effects += it } }
        if (!clip.transform.isIdentity || clip.transform.isAnimated || clip.transform.opacity.value != 1f || !opaque) {
            effects += transform(clip, opaque)
        }
        clip.transitionIn?.let { effects += transition(clip, it, incoming = true, opaque = opaque) }
        clip.transitionOut?.let { effects += transition(clip, it, incoming = false, opaque = opaque) }
        return effects
    }

    private fun localSeconds(clip: Clip, ptsUs: Long): Float = (ptsUs - clip.startUs).coerceAtLeast(0) / 1_000_000f

    private fun shader(src: String, clip: Clip, bind: Uniforms.(localUs: Long) -> Unit) =
        ShaderEffect(src) { pts ->
            float("uTime", localSeconds(clip, pts))
            bind(pts - clip.startUs)
        }

    private val lutCache = HashMap<Pair<ColorLookId, Int>, Array<Array<IntArray>>>()

    private fun lut(look: ColorLookId, intensity: Float): Array<Array<IntArray>> {
        val key = look to (intensity * 20).toInt()
        return synchronized(lutCache) { lutCache.getOrPut(key) { ColorLooks.buildLut(look, 33, key.second / 20f) } }
    }

    private fun build(e: VideoEffect, clip: Clip, beatGrid: BeatGrid?): Effect? = when (e.type) {
        VideoEffectType.COLOR_GRADE -> shader(Shaders.COLOR_GRADE, clip) {
            float("uExposure", e.param("exposure")); float("uContrast", e.param("contrast"))
            float("uSaturation", e.param("saturation")); float("uVibrance", e.param("vibrance"))
            float("uTemperature", e.param("temperature")); float("uTint", e.param("tint"))
            float("uHue", e.param("hue")); float("uHighlights", e.param("highlights"))
            float("uShadows", e.param("shadows")); float("uFade", e.param("fade"))
        }
        VideoEffectType.LOOK -> e.look?.let { SingleColorLut.createFromCube(lut(it, e.param("intensity"))) }
        VideoEffectType.VIGNETTE -> shader(Shaders.VIGNETTE, clip) { float("uAmount", e.param("amount")); float("uSize", e.param("size")) }
        VideoEffectType.GRAIN -> shader(Shaders.GRAIN, clip) { float("uAmount", e.param("amount")) }
        VideoEffectType.BLUR -> e.param("radius").takeIf { it > 0.01f }?.let { GaussianBlur(it * 20f) }
        VideoEffectType.SHARPEN -> shader(Shaders.SHARPEN, clip) { float("uAmount", e.param("amount")) }
        VideoEffectType.BLACK_AND_WHITE -> shader(Shaders.BLACK_AND_WHITE, clip) { float("uAmount", e.param("intensity")) }
        VideoEffectType.INVERT -> shader(Shaders.INVERT, clip) {}
        VideoEffectType.GLITCH -> shader(Shaders.GLITCH, clip) { float("uAmount", e.param("amount")); float("uSpeed", e.param("speed")) }
        VideoEffectType.VHS -> shader(Shaders.VHS, clip) { float("uAmount", e.param("amount")) }
        VideoEffectType.RGB_SPLIT -> shader(Shaders.RGB_SPLIT, clip) { float("uAmount", e.param("amount")) }
        VideoEffectType.PIXELATE -> shader(Shaders.PIXELATE, clip) { float("uSize", e.param("size")) }
        VideoEffectType.MIRROR -> shader(Shaders.MIRROR, clip) { float("uMode", e.param("mode")) }
        VideoEffectType.KALEIDOSCOPE -> shader(Shaders.KALEIDOSCOPE, clip) { float("uSegments", e.param("segments")) }
        VideoEffectType.WAVE -> shader(Shaders.WAVE, clip) { float("uAmount", e.param("amount")); float("uSpeed", e.param("speed")) }
        VideoEffectType.ZOOM_PULSE -> {
            val beats = beatGrid?.beatsUs
            val periodUs = (60_000_000f / e.param("bpm")).toLong().coerceAtLeast(1)
            ShaderEffect(Shaders.ZOOM_PULSE) { pts ->
                float("uTime", localSeconds(clip, pts))
                float("uAmount", e.param("amount"))
                float("uPulse", pulse(pts, beats, periodUs))
            }
        }
        VideoEffectType.SHAKE -> shader(Shaders.SHAKE, clip) { float("uAmount", e.param("amount")); float("uSpeed", e.param("speed")) }
        VideoEffectType.STROBE -> shader(Shaders.STROBE, clip) { float("uRate", e.param("rate")) }
        VideoEffectType.LETTERBOX -> shader(Shaders.LETTERBOX, clip) { float("uRatio", e.param("ratio")) }
        VideoEffectType.CHROMA_KEY -> shader(Shaders.CHROMA_KEY, clip) {
            float("uKeyHue", e.param("hue")); float("uTolerance", e.param("tolerance")); float("uSoftness", e.param("softness"))
        }
    }

    /** Exponential decay envelope restarting on each beat (from the grid, or at a fixed tempo). */
    fun pulse(ptsUs: Long, beats: List<Long>?, periodUs: Long): Float {
        val sinceBeat = if (!beats.isNullOrEmpty()) {
            var lo = 0
            var hi = beats.size - 1
            if (ptsUs < beats[0]) return 0f
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (beats[mid] <= ptsUs) lo = mid else hi = mid - 1
            }
            ptsUs - beats[lo]
        } else {
            ptsUs % periodUs
        }
        return exp(-sinceBeat / 90_000.0).toFloat()
    }

    private fun transform(clip: Clip, opaque: Boolean) = ShaderEffect(Shaders.TRANSFORM) { pts ->
        val local = pts - clip.startUs
        val t = clip.transform.sample(local)
        // Offsets are fractions of the frame; the shader works in aspect-corrected NDC units.
        vec2("uOffset", t.x * 2f * aspect, t.y * 2f)
        float("uScale", t.scale)
        float("uRotation", Math.toRadians(t.rotationDeg.toDouble()).toFloat())
        vec2("uFlip", if (clip.transform.flipHorizontal) -1f else 1f, if (clip.transform.flipVertical) -1f else 1f)
        float("uOpacity", t.opacity)
        float("uOpaque", if (opaque) 1f else 0f)
    }

    private fun transition(clip: Clip, t: Transition, incoming: Boolean, opaque: Boolean): Effect {
        val duration = t.durationUs.coerceIn(1, clip.durationUs)
        return ShaderEffect(Shaders.TRANSITION) { pts ->
            val local = pts - clip.startUs
            val progress = if (incoming) local.toFloat() / duration else (clip.durationUs - local).toFloat() / duration
            float("uTime", local / 1_000_000f)
            int("uType", t.type.shaderIndex)
            float("uProgress", progress.coerceIn(0f, 1f))
            float("uDir", if (incoming) 1f else -1f)
            float("uOpaque", if (opaque) 1f else 0f)
        }
    }

    private val TransitionType.shaderIndex: Int
        get() = when (this) {
            TransitionType.FADE_BLACK -> 0
            TransitionType.FADE_WHITE -> 1
            TransitionType.FLASH -> 2
            TransitionType.ZOOM_IN -> 3
            TransitionType.ZOOM_OUT -> 4
            TransitionType.SPIN -> 5
            TransitionType.BLUR -> 6
            TransitionType.GLITCH -> 7
            TransitionType.SLIDE_LEFT -> 8
            TransitionType.SLIDE_RIGHT -> 9
            TransitionType.SLIDE_UP -> 10
            TransitionType.SLIDE_DOWN -> 11
            TransitionType.WHIP -> 12
            TransitionType.SHAKE -> 13
            TransitionType.RGB_SPLIT -> 14
        }
}
