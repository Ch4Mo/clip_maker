package com.clipmaker.core.edit

import com.clipmaker.core.model.Project
import kotlin.math.abs

enum class SnapSource { CLIP_EDGE, PLAYHEAD, MARKER, BEAT, ORIGIN }

data class SnapPoint(val timeUs: Long, val source: SnapSource)

data class SnapResult(val timeUs: Long, val snappedTo: SnapPoint?)

/** Magnetic snapping of edits to clip edges, playhead, markers and musical beats. */
object SnapEngine {

    fun collectPoints(
        project: Project,
        playheadUs: Long,
        excludeClipIds: Set<String> = emptySet(),
        includeBeats: Boolean = true,
    ): List<SnapPoint> = buildList {
        add(SnapPoint(0, SnapSource.ORIGIN))
        add(SnapPoint(playheadUs, SnapSource.PLAYHEAD))
        project.tracks.forEach { t ->
            t.clips.filter { it.id !in excludeClipIds }.forEach {
                add(SnapPoint(it.startUs, SnapSource.CLIP_EDGE))
                add(SnapPoint(it.endUs, SnapSource.CLIP_EDGE))
            }
        }
        project.markers.forEach { add(SnapPoint(it.timeUs, SnapSource.MARKER)) }
        if (includeBeats) project.beatGrid?.beatsUs?.forEach { add(SnapPoint(it, SnapSource.BEAT)) }
    }.sortedBy { it.timeUs }

    fun snap(timeUs: Long, points: List<SnapPoint>, thresholdUs: Long): SnapResult {
        var best: SnapPoint? = null
        var bestDistance = Long.MAX_VALUE
        for (p in points) {
            val d = abs(p.timeUs - timeUs)
            // Prefer non-beat targets at equal distance: clip edges are more intentional.
            val better = d < bestDistance || (d == bestDistance && best?.source == SnapSource.BEAT)
            if (d <= thresholdUs && better) {
                best = p
                bestDistance = d
            }
        }
        return SnapResult(best?.timeUs ?: timeUs, best)
    }

    /** Snaps a moving range by whichever of its edges is closest to a snap point. */
    fun snapRange(startUs: Long, durationUs: Long, points: List<SnapPoint>, thresholdUs: Long): SnapResult {
        val byStart = snap(startUs, points, thresholdUs)
        val byEnd = snap(startUs + durationUs, points, thresholdUs)
        val dStart = if (byStart.snappedTo != null) abs(byStart.timeUs - startUs) else Long.MAX_VALUE
        val dEnd = if (byEnd.snappedTo != null) abs(byEnd.timeUs - (startUs + durationUs)) else Long.MAX_VALUE
        return when {
            dStart == Long.MAX_VALUE && dEnd == Long.MAX_VALUE -> SnapResult(startUs, null)
            dStart <= dEnd -> byStart
            else -> SnapResult(byEnd.timeUs - durationUs, byEnd.snappedTo)
        }.let { if (it.timeUs < 0) SnapResult(0, it.snappedTo) else it }
    }
}
