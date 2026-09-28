package com.clipmaker.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackKind {
    /** Main visual track (the first one is magnetic by default, like CapCut). */
    VIDEO,

    /** Picture-in-picture / overlay layers drawn above the main track. */
    OVERLAY,

    /** Music, voice, recorded samples, sound effects. */
    AUDIO,

    /** Titles and captions. */
    TEXT;

    val isVisual: Boolean get() = this == VIDEO || this == OVERLAY

    fun accepts(kind: MediaKind?): Boolean = when (this) {
        VIDEO, OVERLAY -> kind == MediaKind.VIDEO || kind == MediaKind.IMAGE
        AUDIO -> kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
        TEXT -> kind == null
    }
}

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val solo: Boolean = false,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    /** Linear gain of the whole track (mixer fader). */
    val volume: Float = 1f,
    /** Magnetic tracks keep their clips packed one after the other without gaps. */
    val magnetic: Boolean = false,
    /** Effects applied to the whole track (bus effects). */
    val audioEffects: List<AudioEffect> = emptyList(),
) {
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L

    val sortedClips: List<Clip> get() = clips.sortedBy { it.startUs }

    fun clipAt(timeUs: Long): Clip? = clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }

    companion object {
        fun defaultTracks(): List<Track> = listOf(
            Track(id = "track-video-main", kind = TrackKind.VIDEO, name = "Vidéo", magnetic = true),
            Track(id = "track-overlay-1", kind = TrackKind.OVERLAY, name = "Incrustation 1"),
            Track(id = "track-text-1", kind = TrackKind.TEXT, name = "Texte"),
            Track(id = "track-audio-music", kind = TrackKind.AUDIO, name = "Musique"),
            Track(id = "track-audio-2", kind = TrackKind.AUDIO, name = "Voix / Sons"),
        )
    }
}
