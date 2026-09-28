package com.clipmaker.core.model

import kotlinx.serialization.Serializable

/** All times in the model are expressed in microseconds. */
const val US_PER_SECOND = 1_000_000L
const val US_PER_MS = 1_000L

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val settings: ProjectSettings = ProjectSettings(),
    val tracks: List<Track> = Track.defaultTracks(),
    /** Media pool: every imported, filmed, recorded, extracted or generated asset. */
    val assets: List<MediaAsset> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val beatGrid: BeatGrid? = null,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {
    val durationUs: Long
        get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    fun asset(id: String?): MediaAsset? = id?.let { wanted -> assets.firstOrNull { it.id == wanted } }

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun findClip(clipId: String): Pair<Track, Clip>? {
        for (track in tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId }
            if (clip != null) return track to clip
        }
        return null
    }

    /** The bottom-most video track: the "main" track that drives the output. */
    val mainVideoTrack: Track?
        get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun create(id: String, name: String, nowMs: Long, settings: ProjectSettings = ProjectSettings()) =
            Project(id = id, name = name, createdAtMs = nowMs, updatedAtMs = nowMs, settings = settings)
    }
}

@Serializable
data class ProjectSettings(
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE_16_9,
    val frameRate: Int = 30,
    /** Length in pixels of the short side of the output (1080 = Full HD). */
    val shortSide: Int = 1080,
    /** ARGB background color shown behind letterboxed media. */
    val backgroundColor: Long = 0xFF000000,
) {
    val outputWidth: Int get() = outputSize().first
    val outputHeight: Int get() = outputSize().second

    fun outputSize(short: Int = shortSide): Pair<Int, Int> {
        val ratio = aspectRatio.width.toDouble() / aspectRatio.height
        return if (ratio >= 1.0) {
            even(short * ratio) to even(short.toDouble())
        } else {
            even(short.toDouble()) to even(short / ratio)
        }
    }

    private fun even(v: Double): Int {
        val i = Math.round(v).toInt()
        return if (i % 2 == 0) i else i + 1
    }
}

@Serializable
data class AspectRatio(val width: Int, val height: Int, val label: String) {
    val value: Float get() = width.toFloat() / height

    companion object {
        val LANDSCAPE_16_9 = AspectRatio(16, 9, "16:9")
        val PORTRAIT_9_16 = AspectRatio(9, 16, "9:16")
        val SQUARE_1_1 = AspectRatio(1, 1, "1:1")
        val PORTRAIT_4_5 = AspectRatio(4, 5, "4:5")
        val CLASSIC_4_3 = AspectRatio(4, 3, "4:3")
        val CINEMA_239 = AspectRatio(239, 100, "2.39:1")
        val ULTRAWIDE_21_9 = AspectRatio(21, 9, "21:9")

        val PRESETS = listOf(
            LANDSCAPE_16_9, PORTRAIT_9_16, SQUARE_1_1, PORTRAIT_4_5, CLASSIC_4_3, CINEMA_239, ULTRAWIDE_21_9,
        )
    }
}

@Serializable
enum class MediaKind { VIDEO, AUDIO, IMAGE }

@Serializable
enum class AssetOrigin { IMPORTED, CAMERA, RECORDED, EXTRACTED, GENERATED }

@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val name: String,
    val kind: MediaKind,
    /** Intrinsic duration. Images use [IMAGE_DEFAULT_DURATION_US] as a nominal value. */
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = kind != MediaKind.IMAGE,
    val hasVideo: Boolean = kind != MediaKind.AUDIO,
    val origin: AssetOrigin = AssetOrigin.IMPORTED,
    /** Tempo detected on this asset (music), if analysed. */
    val bpm: Float? = null,
) {
    /** Images and generated titles can be stretched indefinitely. */
    val isStretchable: Boolean get() = kind == MediaKind.IMAGE

    companion object {
        const val IMAGE_DEFAULT_DURATION_US = 3 * US_PER_SECOND
    }
}

@Serializable
data class Marker(val id: String, val timeUs: Long, val label: String = "", val color: Long = 0xFFFFC107)

@Serializable
data class BeatGrid(
    val bpm: Float,
    /** Absolute timeline positions of detected beats. */
    val beatsUs: List<Long>,
    /** Indices in [beatsUs] that start a bar (downbeats). */
    val downbeatIndices: List<Int> = emptyList(),
    val sourceClipId: String? = null,
)
