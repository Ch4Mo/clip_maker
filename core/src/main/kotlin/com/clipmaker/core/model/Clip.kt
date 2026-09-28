package com.clipmaker.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
data class Clip(
    val id: String,
    /** Media asset played by this clip, or null for generated content (titles). */
    val assetId: String? = null,
    /** Position of the clip on the timeline. */
    val startUs: Long,
    /** In-point inside the source media. */
    val sourceStartUs: Long = 0L,
    /** Out-point inside the source media (exclusive). */
    val sourceEndUs: Long,
    /** Playback speed (0.1x .. 10x). */
    val speed: Float = 1f,
    /** Clip gain; can be keyframed to draw a volume automation curve. */
    val volume: AnimatedFloat = AnimatedFloat(1f),
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transform: Transform = Transform(),
    val videoEffects: List<VideoEffect> = emptyList(),
    val audioEffects: List<AudioEffect> = emptyList(),
    val transitionIn: Transition? = null,
    val transitionOut: Transition? = null,
    val text: TextContent? = null,
    val label: String = "",
    val color: Long? = null,
) {
    val sourceDurationUs: Long get() = sourceEndUs - sourceStartUs

    /** Duration of the clip on the timeline, after speed change. */
    val durationUs: Long get() = (sourceDurationUs / speed.toDouble()).roundToLong()

    val endUs: Long get() = startUs + durationUs

    fun contains(timelineUs: Long): Boolean = timelineUs >= startUs && timelineUs < endUs

    /** Converts a timeline position into a time relative to the clip start. */
    fun localTime(timelineUs: Long): Long = timelineUs - startUs

    /** Converts a timeline position into a position inside the source media. */
    fun sourceTime(timelineUs: Long): Long =
        sourceStartUs + (localTime(timelineUs) * speed.toDouble()).roundToLong()

    /** Gain envelope combining the volume curve and fades, at a clip-local time. */
    fun gainAt(localUs: Long): Float {
        var gain = volume.valueAt(localUs)
        if (fadeInUs > 0 && localUs < fadeInUs) {
            gain *= (localUs.toFloat() / fadeInUs).coerceIn(0f, 1f)
        }
        val remaining = durationUs - localUs
        if (fadeOutUs > 0 && remaining < fadeOutUs) {
            gain *= (remaining.toFloat() / fadeOutUs).coerceIn(0f, 1f)
        }
        return gain
    }
}

@Serializable
data class Transform(
    /** Horizontal offset in fractions of the frame width (0 = centered). */
    val x: AnimatedFloat = AnimatedFloat(0f),
    /** Vertical offset in fractions of the frame height (0 = centered, positive = up). */
    val y: AnimatedFloat = AnimatedFloat(0f),
    val scale: AnimatedFloat = AnimatedFloat(1f),
    val rotationDeg: AnimatedFloat = AnimatedFloat(0f),
    val opacity: AnimatedFloat = AnimatedFloat(1f),
    /** Fill the frame (crop) instead of fitting inside it (letterbox). */
    val fill: Boolean = true,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    val isIdentity: Boolean
        get() = x.isConstant(0f) && y.isConstant(0f) && scale.isConstant(1f) &&
            rotationDeg.isConstant(0f) && !flipHorizontal && !flipVertical

    val isAnimated: Boolean
        get() = listOf(x, y, scale, rotationDeg, opacity).any { it.keyframes.isNotEmpty() }

    fun map(f: (AnimatedFloat) -> AnimatedFloat) = copy(
        x = f(x), y = f(y), scale = f(scale), rotationDeg = f(rotationDeg), opacity = f(opacity),
    )

    fun sample(localUs: Long) = TransformSample(
        x = x.valueAt(localUs),
        y = y.valueAt(localUs),
        scale = scale.valueAt(localUs),
        rotationDeg = rotationDeg.valueAt(localUs),
        opacity = opacity.valueAt(localUs).coerceIn(0f, 1f),
    )
}

data class TransformSample(
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotationDeg: Float,
    val opacity: Float,
)

@Serializable
enum class TransitionType(val label: String) {
    FADE_BLACK("Fondu au noir"),
    FADE_WHITE("Fondu au blanc"),
    FLASH("Flash"),
    ZOOM_IN("Zoom avant"),
    ZOOM_OUT("Zoom arrière"),
    SPIN("Rotation"),
    BLUR("Flou"),
    GLITCH("Glitch"),
    SLIDE_LEFT("Glisser ←"),
    SLIDE_RIGHT("Glisser →"),
    SLIDE_UP("Glisser ↑"),
    SLIDE_DOWN("Glisser ↓"),
    WHIP("Whip pan"),
    SHAKE("Secousse"),
    RGB_SPLIT("Décalage RVB"),
}

@Serializable
data class Transition(val type: TransitionType, val durationUs: Long = 500_000L)
