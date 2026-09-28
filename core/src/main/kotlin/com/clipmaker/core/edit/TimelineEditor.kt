package com.clipmaker.core.edit

import com.clipmaker.core.model.AnimatedFloat
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.util.Ids
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure, immutable timeline operations. Every function returns a new [Project] and never mutates its
 * input, which makes undo/redo trivial (snapshots) and keeps the editing logic unit-testable.
 */
object TimelineEditor {

    /** Shortest clip the editor allows (roughly two frames at 30 fps). */
    const val MIN_CLIP_US = 66_000L
    const val DEFAULT_TEXT_DURATION_US = 3_000_000L

    // ---------------------------------------------------------------------------------------------
    // Assets & tracks
    // ---------------------------------------------------------------------------------------------

    fun addAsset(project: Project, asset: MediaAsset): Project =
        if (project.assets.any { it.id == asset.id }) project
        else project.copy(assets = project.assets + asset)

    fun removeAsset(project: Project, assetId: String): Project = project.copy(
        assets = project.assets.filterNot { it.id == assetId },
        tracks = project.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.assetId == assetId }) }
            .map { if (it.magnetic) compact(it) else it },
    )

    fun addTrack(project: Project, kind: TrackKind, name: String? = null): Pair<Project, Track> {
        val count = project.tracks.count { it.kind == kind } + 1
        val label = name ?: when (kind) {
            TrackKind.VIDEO -> "Vidéo $count"
            TrackKind.OVERLAY -> "Incrustation $count"
            TrackKind.AUDIO -> "Audio $count"
            TrackKind.TEXT -> "Texte $count"
        }
        val track = Track(id = Ids.track(), kind = kind, name = label)
        val lastSameKind = project.tracks.indexOfLast { it.kind == kind }
        val insertAt = if (lastSameKind >= 0) lastSameKind + 1 else project.tracks.size
        val tracks = project.tracks.toMutableList().apply { add(insertAt, track) }
        return project.copy(tracks = tracks) to track
    }

    fun removeTrack(project: Project, trackId: String): Project {
        val track = project.track(trackId) ?: return project
        // Always keep the main video track.
        if (track.id == project.mainVideoTrack?.id) return project
        return project.copy(tracks = project.tracks.filterNot { it.id == trackId })
    }

    fun updateTrack(project: Project, trackId: String, update: (Track) -> Track): Project =
        project.copy(tracks = project.tracks.map { if (it.id == trackId) update(it) else it })

    fun moveTrack(project: Project, trackId: String, toIndex: Int): Project {
        val list = project.tracks.toMutableList()
        val from = list.indexOfFirst { it.id == trackId }
        if (from < 0) return project
        val track = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), track)
        return project.copy(tracks = list)
    }

    /** Returns the first track of [kind] that is free on [fromUs, toUs), creating one if needed. */
    fun findOrCreateFreeTrack(project: Project, kind: TrackKind, fromUs: Long, toUs: Long): Pair<Project, Track> {
        val free = project.tracks.firstOrNull { t ->
            t.kind == kind && !t.locked && !t.magnetic && t.clips.none { it.startUs < toUs && it.endUs > fromUs }
        }
        return if (free != null) project to free else addTrack(project, kind)
    }

    // ---------------------------------------------------------------------------------------------
    // Clip creation
    // ---------------------------------------------------------------------------------------------

    fun newClipForAsset(asset: MediaAsset, startUs: Long): Clip = Clip(
        id = Ids.clip(),
        assetId = asset.id,
        startUs = startUs.coerceAtLeast(0),
        sourceStartUs = 0,
        sourceEndUs = if (asset.kind == MediaKind.IMAGE) MediaAsset.IMAGE_DEFAULT_DURATION_US else asset.durationUs,
        label = asset.name,
    )

    /** Adds [asset] to the media pool (if needed) and places a new clip on [trackId] at [atUs]. */
    fun insertAsset(project: Project, trackId: String, asset: MediaAsset, atUs: Long): Pair<Project, Clip> {
        val withAsset = addAsset(project, asset)
        val clip = newClipForAsset(asset, atUs)
        return placeClip(withAsset, trackId, clip) to clip
    }

    fun insertText(
        project: Project,
        trackId: String,
        text: TextContent,
        atUs: Long,
        durationUs: Long = DEFAULT_TEXT_DURATION_US,
    ): Pair<Project, Clip> {
        val clip = Clip(
            id = Ids.clip(),
            assetId = null,
            startUs = atUs.coerceAtLeast(0),
            sourceStartUs = 0,
            sourceEndUs = durationUs,
            text = text,
            label = text.text,
        )
        return placeClip(project, trackId, clip) to clip
    }

    /**
     * Puts [clip] on a track. Magnetic tracks insert it in the sequence (before or after the clip
     * under [Clip.startUs]) and close gaps; free tracks overwrite whatever lies underneath.
     */
    fun placeClip(project: Project, trackId: String, clip: Clip): Project = updateTrack(project, trackId) { track ->
        if (track.magnetic) {
            val ordered = track.sortedClips.toMutableList()
            var index = ordered.size
            for ((i, c) in ordered.withIndex()) {
                val middle = c.startUs + c.durationUs / 2
                if (clip.startUs < middle) { index = i; break }
            }
            ordered.add(index, clip)
            compact(track.copy(clips = ordered), keepOrder = true)
        } else {
            val cleared = clearRange(track, clip.startUs, clip.endUs, exceptClipId = clip.id)
            cleared.copy(clips = (cleared.clips + clip).sortedBy { it.startUs })
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Moving / trimming / splitting
    // ---------------------------------------------------------------------------------------------

    fun moveClip(project: Project, clipId: String, targetTrackId: String, newStartUs: Long): Project {
        val (source, clip) = project.findClip(clipId) ?: return project
        val target = project.track(targetTrackId) ?: return project
        if (source.locked || target.locked) return project
        val assetKind = project.asset(clip.assetId)?.kind
        if (!target.kind.accepts(assetKind)) return project

        val removed = updateTrack(project, source.id) { t ->
            val remaining = t.copy(clips = t.clips.filterNot { it.id == clipId })
            if (remaining.magnetic) compact(remaining) else remaining
        }
        return placeClip(removed, target.id, clip.copy(startUs = newStartUs.coerceAtLeast(0)))
    }

    /** Moves the left edge of a clip to [newStartUs] (timeline time), revealing or hiding media. */
    fun trimStart(project: Project, clipId: String, newStartUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val asset = project.asset(clip.assetId)
        val trimmed = trimClipStart(clip, newStartUs, asset) ?: return project
        return updateTrack(project, track.id) { t ->
            val others = t.clips.filterNot { it.id == clipId }
            if (t.magnetic) {
                compact(t.copy(clips = (others + trimmed.copy(startUs = clip.startUs)).sortedBy { it.startUs }), keepOrder = true)
            } else {
                val cleared = clearRange(t.copy(clips = others), trimmed.startUs, trimmed.endUs, clipId)
                cleared.copy(clips = (cleared.clips + trimmed).sortedBy { it.startUs })
            }
        }
    }

    /** Moves the right edge of a clip to [newEndUs] (timeline time). */
    fun trimEnd(project: Project, clipId: String, newEndUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val asset = project.asset(clip.assetId)
        val trimmed = trimClipEnd(clip, newEndUs, asset) ?: return project
        return updateTrack(project, track.id) { t ->
            val others = t.clips.filterNot { it.id == clipId }
            if (t.magnetic) {
                compact(t.copy(clips = (others + trimmed).sortedBy { it.startUs }), keepOrder = true)
            } else {
                val cleared = clearRange(t.copy(clips = others), trimmed.startUs, trimmed.endUs, clipId)
                cleared.copy(clips = (cleared.clips + trimmed).sortedBy { it.startUs })
            }
        }
    }

    fun split(project: Project, clipId: String, atUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val parts = splitClip(clip, atUs) ?: return project
        return updateTrack(project, track.id) { t ->
            t.copy(clips = t.clips.flatMap { if (it.id == clipId) parts.toList() else listOf(it) })
        }
    }

    /** Splits every clip under the playhead on unlocked tracks (or only [clipIds] if given). */
    fun splitAt(project: Project, atUs: Long, clipIds: Set<String>? = null): Project {
        var result = project
        for (track in project.tracks) {
            if (track.locked) continue
            val clip = track.clipAt(atUs) ?: continue
            if (clipIds != null && clip.id !in clipIds) continue
            result = split(result, clip.id, atUs)
        }
        return result
    }

    fun deleteClips(project: Project, clipIds: Set<String>, ripple: Boolean = false): Project =
        project.copy(tracks = project.tracks.map { track ->
            if (track.locked || track.clips.none { it.id in clipIds }) return@map track
            if (track.magnetic) return@map compact(track.copy(clips = track.clips.filterNot { it.id in clipIds }), keepOrder = true)
            if (!ripple) return@map track.copy(clips = track.clips.filterNot { it.id in clipIds })
            var shift = 0L
            val out = mutableListOf<Clip>()
            for (c in track.sortedClips) {
                if (c.id in clipIds) {
                    shift += c.durationUs
                } else {
                    out += c.copy(startUs = c.startUs - shift)
                }
            }
            track.copy(clips = out)
        })

    fun duplicateClip(project: Project, clipId: String): Pair<Project, Clip?> {
        val (track, clip) = project.findClip(clipId) ?: return project to null
        val copy = clip.copy(id = Ids.clip(), startUs = clip.endUs)
        if (track.magnetic) {
            val ordered = track.sortedClips.toMutableList()
            ordered.add(ordered.indexOfFirst { it.id == clipId } + 1, copy)
            return updateTrack(project, track.id) { compact(it.copy(clips = ordered), keepOrder = true) } to copy
        }
        // Free track: make room by pushing the following clips to the right.
        val pushed = updateTrack(project, track.id) { t ->
            t.copy(clips = t.clips.map { if (it.startUs >= clip.endUs) it.copy(startUs = it.startUs + clip.durationUs) else it })
        }
        return placeClip(pushed, track.id, copy) to copy
    }

    /** Changes the speed of a clip, keeping its start. Following clips are pushed/pulled. */
    fun setSpeed(project: Project, clipId: String, speed: Float): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        val newSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val factor = clip.speed.toDouble() / newSpeed
        val updated = clip.copy(
            speed = newSpeed,
            fadeInUs = (clip.fadeInUs * factor).roundToLong(),
            fadeOutUs = (clip.fadeOutUs * factor).roundToLong(),
            volume = clip.volume.scaledTime(factor),
            transform = clip.transform.map { it.scaledTime(factor) },
        )
        val delta = updated.durationUs - clip.durationUs
        return updateTrack(project, track.id) { t ->
            val clips = t.clips.map {
                when {
                    it.id == clipId -> updated
                    !t.magnetic && it.startUs >= clip.endUs -> it.copy(startUs = max(0, it.startUs + delta))
                    else -> it
                }
            }
            if (t.magnetic) compact(t.copy(clips = clips), keepOrder = true) else t.copy(clips = clips)
        }
    }

    fun updateClip(project: Project, clipId: String, update: (Clip) -> Clip): Project =
        project.copy(tracks = project.tracks.map { t ->
            if (t.clips.none { it.id == clipId }) t
            else {
                val clips = t.clips.map { if (it.id == clipId) update(it) else it }
                if (t.magnetic) compact(t.copy(clips = clips), keepOrder = true) else t.copy(clips = clips)
            }
        })

    /**
     * Extracts the sound of a video clip to its own audio clip (same media, same range) on a free
     * audio track, and mutes the original clip.
     */
    fun detachAudio(project: Project, clipId: String): Pair<Project, Clip?> {
        val (_, clip) = project.findClip(clipId) ?: return project to null
        val asset = project.asset(clip.assetId) ?: return project to null
        if (!asset.hasAudio || asset.kind != MediaKind.VIDEO) return project to null
        val (withTrack, audioTrack) = findOrCreateFreeTrack(project, TrackKind.AUDIO, clip.startUs, clip.endUs)
        val audioClip = clip.copy(
            id = Ids.clip(),
            transform = com.clipmaker.core.model.Transform(),
            videoEffects = emptyList(),
            transitionIn = null,
            transitionOut = null,
            label = "Audio · ${asset.name}",
        )
        val placed = placeClip(withTrack, audioTrack.id, audioClip)
        val muted = updateClip(placed, clipId) { it.copy(volume = AnimatedFloat(0f), audioEffects = emptyList()) }
        return muted to audioClip
    }

    // ---------------------------------------------------------------------------------------------
    // Beat-based editing
    // ---------------------------------------------------------------------------------------------

    /** Cuts every clip of [trackId] on every [everyN]-th beat. */
    fun autoCutOnBeats(project: Project, trackId: String, beatsUs: List<Long>, everyN: Int = 1): Project {
        val step = everyN.coerceAtLeast(1)
        var result = project
        beatsUs.filterIndexed { index, _ -> index % step == 0 }.forEach { beat ->
            val track = result.track(trackId) ?: return result
            val clip = track.clipAt(beat) ?: return@forEach
            if (beat - clip.startUs >= MIN_CLIP_US && clip.endUs - beat >= MIN_CLIP_US) {
                result = split(result, clip.id, beat)
            }
        }
        return result
    }

    /**
     * Builds a montage where each shot lasts [everyN] beats, cycling through [assets]. Each video
     * is consumed progressively so that consecutive shots of the same file show new footage.
     * Existing clips of the track are replaced.
     */
    fun beatSyncMontage(
        project: Project,
        trackId: String,
        assets: List<MediaAsset>,
        beatsUs: List<Long>,
        everyN: Int = 2,
    ): Project {
        if (assets.isEmpty() || beatsUs.size < 2) return project
        val track = project.track(trackId) ?: return project
        val step = everyN.coerceAtLeast(1)
        val cuts = beatsUs.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (track.magnetic && cuts.first() > 0) cuts[0] = 0L
        val cursors = HashMap<String, Long>()
        val clips = mutableListOf<Clip>()
        var assetIndex = 0
        for (i in 0 until cuts.size - 1) {
            val start = cuts[i]
            val length = cuts[i + 1] - start
            if (length < MIN_CLIP_US) continue
            var chosen: Clip? = null
            var attempts = 0
            while (chosen == null && attempts < assets.size) {
                val asset = assets[(assetIndex + attempts) % assets.size]
                attempts++
                if (asset.kind == MediaKind.IMAGE) {
                    chosen = Clip(id = Ids.clip(), assetId = asset.id, startUs = start, sourceStartUs = 0, sourceEndUs = length, label = asset.name)
                } else if (asset.hasVideo && asset.durationUs >= length) {
                    var from = cursors[asset.id] ?: 0L
                    if (from + length > asset.durationUs) from = 0L
                    cursors[asset.id] = from + length
                    chosen = Clip(id = Ids.clip(), assetId = asset.id, startUs = start, sourceStartUs = from, sourceEndUs = from + length, label = asset.name)
                }
            }
            assetIndex = (assetIndex + attempts) % assets.size
            chosen?.let { clips += it.copy(volume = AnimatedFloat(0f)) }
        }
        var result = project
        assets.forEach { result = addAsset(result, it) }
        return updateTrack(result, trackId) { t ->
            val base = t.copy(clips = clips)
            if (t.magnetic) compact(base, keepOrder = true) else base
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Low-level helpers (public for tests)
    // ---------------------------------------------------------------------------------------------

    fun compact(track: Track, keepOrder: Boolean = false): Track {
        var cursor = 0L
        val ordered = if (keepOrder) track.clips else track.sortedClips
        return track.copy(clips = ordered.map { c -> c.copy(startUs = cursor).also { cursor += c.durationUs } })
    }

    /** Removes the time range [fromUs, toUs) from every clip of [track] (except [exceptClipId]). */
    fun clearRange(track: Track, fromUs: Long, toUs: Long, exceptClipId: String? = null): Track {
        if (toUs <= fromUs) return track
        val out = mutableListOf<Clip>()
        for (c in track.clips) {
            if (c.id == exceptClipId || c.endUs <= fromUs || c.startUs >= toUs) {
                out += c
                continue
            }
            val keepLeft = c.startUs < fromUs
            val keepRight = c.endUs > toUs
            when {
                keepLeft && keepRight -> {
                    val parts = splitClip(c, fromUs)
                    if (parts != null) {
                        out += parts.first
                        splitClip(parts.second, toUs)?.let { out += it.second }
                    }
                }
                keepLeft -> trimClipEnd(c, fromUs, null, allowExtend = false)?.let { out += it }
                keepRight -> trimClipStart(c, toUs, null, allowExtend = false)?.let { out += it }
                else -> Unit // fully covered: dropped
            }
        }
        return track.copy(clips = out.sortedBy { it.startUs })
    }

    fun splitClip(clip: Clip, atUs: Long): Pair<Clip, Clip>? {
        val local = atUs - clip.startUs
        if (local < MIN_CLIP_US || clip.durationUs - local < MIN_CLIP_US) return null
        val sourceCut = clip.sourceTime(atUs)
        val left = clip.copy(
            sourceEndUs = sourceCut,
            transitionOut = null,
            fadeOutUs = 0,
            volume = clip.volume.slice(0, local),
            transform = clip.transform.map { it.slice(0, local) },
        )
        val right = clip.copy(
            id = Ids.clip(),
            startUs = atUs,
            sourceStartUs = sourceCut,
            transitionIn = null,
            fadeInUs = 0,
            volume = clip.volume.slice(local, clip.durationUs),
            transform = clip.transform.map { it.slice(local, clip.durationUs) },
            text = clip.text,
        )
        return left to right
    }

    /**
     * Returns [clip] with its left edge moved to [newStartUs], or null if the result would be
     * too short. When [asset] is null the media bounds are not checked (only shrinking allowed).
     */
    fun trimClipStart(clip: Clip, newStartUs: Long, asset: MediaAsset?, allowExtend: Boolean = true): Clip? {
        val stretchable = clip.assetId == null || asset?.isStretchable == true
        var start = min(newStartUs, clip.endUs - MIN_CLIP_US).coerceAtLeast(0)
        if (!allowExtend) start = max(start, clip.startUs)
        val delta = start - clip.startUs
        if (clip.endUs - start < MIN_CLIP_US) return null
        return if (stretchable) {
            clip.copy(
                startUs = start,
                sourceEndUs = clip.sourceEndUs - (delta * clip.speed).roundToLong(),
                volume = clip.volume.shifted(-delta),
                transform = clip.transform.map { it.shifted(-delta) },
            )
        } else {
            var newSourceStart = clip.sourceStartUs + (delta * clip.speed).roundToLong()
            var appliedDelta = delta
            if (newSourceStart < 0) {
                appliedDelta = (-clip.sourceStartUs / clip.speed).roundToLong()
                newSourceStart = 0
            }
            clip.copy(
                startUs = clip.startUs + appliedDelta,
                sourceStartUs = newSourceStart,
                volume = clip.volume.shifted(-appliedDelta),
                transform = clip.transform.map { it.shifted(-appliedDelta) },
            )
        }
    }

    fun trimClipEnd(clip: Clip, newEndUs: Long, asset: MediaAsset?, allowExtend: Boolean = true): Clip? {
        val stretchable = clip.assetId == null || asset?.isStretchable == true
        var end = max(newEndUs, clip.startUs + MIN_CLIP_US)
        if (!allowExtend) end = min(end, clip.endUs)
        var sourceEnd = clip.sourceStartUs + ((end - clip.startUs) * clip.speed).roundToLong()
        if (!stretchable && asset != null) sourceEnd = min(sourceEnd, asset.durationUs)
        if (!stretchable && asset == null) sourceEnd = min(sourceEnd, clip.sourceEndUs)
        if (sourceEnd - clip.sourceStartUs < (MIN_CLIP_US * clip.speed).roundToLong()) return null
        return clip.copy(sourceEndUs = sourceEnd)
    }

    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 10f
}
