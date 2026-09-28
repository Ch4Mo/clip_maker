package com.clipmaker.core.model

import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
enum class TextFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono"), CONDENSED("Condensée"), DISPLAY("Impact"), HANDWRITING("Manuscrite") }

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class TextAnimation(val label: String) {
    NONE("Aucune"),
    FADE("Fondu"),
    POP("Pop"),
    TYPEWRITER("Machine à écrire"),
    SLIDE_UP("Glisser vers le haut"),
    SLIDE_DOWN("Glisser vers le bas"),
    ZOOM("Zoom"),
    BLUR("Flou"),
    GLITCH("Glitch"),
    BOUNCE("Rebond"),
}

@Serializable
data class TextContent(
    val text: String,
    val font: TextFont = TextFont.DISPLAY,
    /** Font size as a fraction of the output frame height. */
    val size: Float = 0.08f,
    val color: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    val strokeColor: Long = 0xFF000000,
    val strokeWidth: Float = 0f,
    val shadow: Boolean = true,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.CENTER,
    val letterSpacing: Float = 0f,
    val animationIn: TextAnimation = TextAnimation.FADE,
    val animationOut: TextAnimation = TextAnimation.FADE,
    val animationDurationUs: Long = 400_000L,
)

/** Resolved rendering state of an animated title at a given time. */
data class TextFrameState(
    val alpha: Float,
    val scale: Float,
    /** Vertical offset in fractions of frame height (positive = up). */
    val offsetY: Float,
    /** Number of characters to reveal (typewriter), or -1 for all. */
    val visibleChars: Int,
    val blur: Float,
    val jitterX: Float,
)

object TextAnimator {
    fun stateAt(text: TextContent, clipDurationUs: Long, localUs: Long): TextFrameState {
        val animDuration = min(text.animationDurationUs, clipDurationUs / 2).coerceAtLeast(1)
        var alpha = 1f
        var scale = 1f
        var offsetY = 0f
        var visible = -1
        var blur = 0f
        var jitter = 0f

        fun applyPhase(anim: TextAnimation, progress: Float, entering: Boolean) {
            // progress: 0 = invisible end of the animation, 1 = fully shown.
            val p = progress.coerceIn(0f, 1f)
            when (anim) {
                TextAnimation.NONE -> Unit
                TextAnimation.FADE -> alpha *= p
                TextAnimation.POP -> {
                    alpha *= p
                    scale *= Easing.ELASTIC.apply(p).coerceAtLeast(0.01f)
                }
                TextAnimation.TYPEWRITER -> if (entering) {
                    visible = (text.text.length * p).toInt()
                } else {
                    alpha *= p
                }
                TextAnimation.SLIDE_UP -> {
                    alpha *= p
                    offsetY += (1f - Easing.EASE_OUT.apply(p)) * (if (entering) -0.15f else 0.15f)
                }
                TextAnimation.SLIDE_DOWN -> {
                    alpha *= p
                    offsetY += (1f - Easing.EASE_OUT.apply(p)) * (if (entering) 0.15f else -0.15f)
                }
                TextAnimation.ZOOM -> {
                    alpha *= p
                    scale *= 0.3f + 0.7f * Easing.EASE_OUT.apply(p)
                }
                TextAnimation.BLUR -> {
                    alpha *= p
                    blur = maxOf(blur, 1f - p)
                }
                TextAnimation.GLITCH -> {
                    alpha *= if (p < 1f && ((p * 20).toInt() % 2 == 0)) 0.3f else 1f
                    jitter = (1f - p) * 0.03f * (if ((p * 37).toInt() % 2 == 0) 1 else -1)
                }
                TextAnimation.BOUNCE -> {
                    alpha *= minOf(1f, p * 3f)
                    offsetY += (1f - Easing.BOUNCE.apply(p)) * 0.2f
                }
            }
        }

        if (localUs < animDuration) {
            applyPhase(text.animationIn, localUs.toFloat() / animDuration, entering = true)
        }
        val remaining = clipDurationUs - localUs
        if (remaining < animDuration) {
            applyPhase(text.animationOut, remaining.toFloat() / animDuration, entering = false)
        }
        return TextFrameState(alpha.coerceIn(0f, 1f), scale, offsetY, visible, blur, jitter)
    }
}
