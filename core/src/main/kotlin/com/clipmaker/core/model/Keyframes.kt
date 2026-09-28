package com.clipmaker.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

@Serializable
enum class Easing(val label: String) {
    LINEAR("Linéaire"),
    EASE_IN("Accélération"),
    EASE_OUT("Décélération"),
    EASE_IN_OUT("Douce"),
    HOLD("Maintien"),
    BOUNCE("Rebond"),
    ELASTIC("Élastique");

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x * x
            EASE_OUT -> 1f - (1f - x).pow(3)
            EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
            HOLD -> if (x < 1f) 0f else 1f
            BOUNCE -> bounceOut(x)
            ELASTIC -> if (x == 0f || x == 1f) x else
                (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * (2 * PI / 3)) + 1).toFloat()
        }
    }

    private fun bounceOut(x: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { val t = x - 1.5f / d1; n1 * t * t + 0.75f }
            x < 2.5f / d1 -> { val t = x - 2.25f / d1; n1 * t * t + 0.9375f }
            else -> { val t = x - 2.625f / d1; n1 * t * t + 0.984375f }
        }
    }
}

/** A keyframe; [timeUs] is relative to the start of the owning clip. */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val value: Float,
    /** Easing used to travel from this keyframe to the next one. */
    val easing: Easing = Easing.EASE_IN_OUT,
)

/** A float parameter that is either constant or animated by keyframes. */
@Serializable
data class AnimatedFloat(
    val value: Float,
    val keyframes: List<Keyframe> = emptyList(),
) {
    fun isConstant(expected: Float): Boolean = keyframes.isEmpty() && value == expected

    fun valueAt(timeUs: Long): Float {
        if (keyframes.isEmpty()) return value
        val sorted = keyframes
        if (timeUs <= sorted.first().timeUs) return sorted.first().value
        if (timeUs >= sorted.last().timeUs) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (timeUs >= a.timeUs && timeUs < b.timeUs) {
                val span = (b.timeUs - a.timeUs).coerceAtLeast(1)
                val t = (timeUs - a.timeUs).toFloat() / span
                return a.value + (b.value - a.value) * a.easing.apply(t)
            }
        }
        return sorted.last().value
    }

    /** Adds or replaces the keyframe at [timeUs]. */
    fun withKeyframe(timeUs: Long, newValue: Float, easing: Easing = Easing.EASE_IN_OUT): AnimatedFloat {
        val others = keyframes.filterNot { kotlin.math.abs(it.timeUs - timeUs) < KEYFRAME_TOLERANCE_US }
        return copy(keyframes = (others + Keyframe(timeUs, newValue, easing)).sortedBy { it.timeUs })
    }

    fun withoutKeyframeNear(timeUs: Long): AnimatedFloat =
        copy(keyframes = keyframes.filterNot { kotlin.math.abs(it.timeUs - timeUs) < KEYFRAME_TOLERANCE_US })

    fun hasKeyframeNear(timeUs: Long): Boolean =
        keyframes.any { kotlin.math.abs(it.timeUs - timeUs) < KEYFRAME_TOLERANCE_US }

    /** Sets the value at [timeUs]: edits the curve when animated, the constant otherwise. */
    fun set(timeUs: Long, newValue: Float): AnimatedFloat =
        if (keyframes.isEmpty()) copy(value = newValue) else withKeyframe(timeUs, newValue)

    /** Keeps keyframes inside [fromUs, toUs) and re-bases them so that [fromUs] becomes 0. */
    fun slice(fromUs: Long, toUs: Long): AnimatedFloat {
        if (keyframes.isEmpty()) return this
        val inside = keyframes.filter { it.timeUs in fromUs until toUs }.map { it.copy(timeUs = it.timeUs - fromUs) }
        val startValue = valueAt(fromUs)
        val endValue = valueAt(toUs)
        val result = buildList {
            if (inside.none { it.timeUs == 0L }) add(Keyframe(0, startValue))
            addAll(inside)
            add(Keyframe(toUs - fromUs, endValue))
        }
        return copy(value = startValue, keyframes = result.sortedBy { it.timeUs })
    }

    /** Moves every keyframe by [deltaUs] (used when the clip start is trimmed). */
    fun shifted(deltaUs: Long): AnimatedFloat =
        if (keyframes.isEmpty() || deltaUs == 0L) this
        else copy(keyframes = keyframes.map { it.copy(timeUs = it.timeUs + deltaUs) })

    /** Scales keyframe times (used when the clip speed changes). */
    fun scaledTime(factor: Double): AnimatedFloat =
        if (keyframes.isEmpty()) this
        else copy(keyframes = keyframes.map { it.copy(timeUs = (it.timeUs * factor).toLong()) })

    companion object {
        const val KEYFRAME_TOLERANCE_US = 20_000L
    }
}
