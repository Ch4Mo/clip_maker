package com.clipmaker.core.render

import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind

sealed interface Segment {
    val durationUs: Long

    data class Gap(override val durationUs: Long) : Segment
    data class Media(val clip: Clip) : Segment {
        override val durationUs: Long get() = clip.durationUs
    }
}

data class SequencePlan(val track: Track, val segments: List<Segment>) {
    val durationUs: Long get() = segments.sumOf { it.durationUs }
}

/**
 * Converts the free-form multi-track timeline into gap-filled linear sequences, which is what
 * rendering engines (Media3 Composition) consume.
 */
data class RenderPlan(
    val durationUs: Long,
    /** Visual sequences, bottom-most (main track) first. */
    val video: List<SequencePlan>,
    val audio: List<SequencePlan>,
    val texts: List<Clip>,
) {
    companion object {
        /** Gaps shorter than this are absorbed to avoid zero-length items (rounding noise). */
        private const val MIN_GAP_US = 1_000L

        fun build(project: Project): RenderPlan {
            val duration = project.durationUs
            val anySolo = project.tracks.any { it.solo && it.kind == TrackKind.AUDIO }
            val visual = project.tracks
                .filter { it.kind.isVisual && !it.hidden && it.clips.any { c -> c.assetId != null } }
                .map { sequenceFor(it, duration) }
            val audible = project.tracks
                .filter { it.kind == TrackKind.AUDIO && !it.muted && (!anySolo || it.solo) && it.clips.isNotEmpty() }
                .map { sequenceFor(it, duration) }
            val texts = project.tracks
                .filter { it.kind == TrackKind.TEXT && !it.hidden }
                .flatMap { t -> t.clips.filter { it.text != null } }
                .sortedBy { it.startUs }
            return RenderPlan(duration, visual, audible, texts)
        }

        /** Lays out clips of [track] one after the other, filling holes with gaps up to [padToUs]. */
        fun sequenceFor(track: Track, padToUs: Long): SequencePlan {
            val segments = ArrayList<Segment>()
            var cursor = 0L
            for (clip in track.sortedClips) {
                if (clip.durationUs <= 0) continue
                val start = maxOf(clip.startUs, cursor)
                val gap = start - cursor
                if (gap >= MIN_GAP_US) segments += Segment.Gap(gap)
                // Overlaps (should not happen after editing) are resolved by trimming the later clip.
                val overlap = cursor - clip.startUs
                val effective = if (overlap > 0) clip.copy(
                    startUs = cursor,
                    sourceStartUs = clip.sourceStartUs + (overlap * clip.speed).toLong(),
                ) else clip
                if (effective.durationUs <= 0) continue
                segments += Segment.Media(effective)
                cursor = effective.endUs
            }
            val tail = padToUs - cursor
            if (tail >= MIN_GAP_US) segments += Segment.Gap(tail)
            return SequencePlan(track, segments)
        }
    }
}
