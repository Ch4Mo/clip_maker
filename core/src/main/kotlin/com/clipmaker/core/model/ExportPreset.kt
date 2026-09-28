package com.clipmaker.core.model

enum class VideoCodec(val label: String, val mimeType: String) {
    H264("H.264 (compatible)", "video/avc"),
    HEVC("H.265 / HEVC (plus léger)", "video/hevc"),
}

data class ExportPreset(
    val id: String,
    val label: String,
    val description: String,
    /** Forces an aspect ratio, or keeps the project's when null. */
    val aspectRatio: AspectRatio?,
    val shortSide: Int,
    val frameRate: Int,
    val codec: VideoCodec = VideoCodec.H264,
    /** Bits per pixel per frame, used to derive a bitrate that scales with resolution. */
    val qualityBpp: Float = 0.12f,
) {
    fun resolve(settings: ProjectSettings): ExportConfig {
        val s = settings.copy(aspectRatio = aspectRatio ?: settings.aspectRatio, frameRate = frameRate)
        val (w, h) = s.outputSize(shortSide)
        val bitrate = (w.toLong() * h * frameRate * qualityBpp).toInt().coerceIn(1_000_000, 120_000_000)
        return ExportConfig(w, h, frameRate, codec, bitrate)
    }

    companion object {
        val ALL = listOf(
            ExportPreset("project-1080", "Projet · 1080p", "Format du projet, Full HD", null, 1080, 30),
            ExportPreset("project-4k", "Projet · 4K", "Format du projet, qualité maximale", null, 2160, 30, VideoCodec.HEVC, 0.1f),
            ExportPreset("youtube-4k60", "YouTube 4K 60 i/s", "16:9, 3840×2160", AspectRatio.LANDSCAPE_16_9, 2160, 60, VideoCodec.HEVC, 0.08f),
            ExportPreset("youtube-1080", "YouTube 1080p", "16:9, 1920×1080", AspectRatio.LANDSCAPE_16_9, 1080, 30),
            ExportPreset("reels", "Reels / TikTok / Shorts", "9:16, 1080×1920", AspectRatio.PORTRAIT_9_16, 1080, 30),
            ExportPreset("instagram-square", "Instagram carré", "1:1, 1080×1080", AspectRatio.SQUARE_1_1, 1080, 30),
            ExportPreset("instagram-portrait", "Instagram portrait", "4:5, 1080×1350", AspectRatio.PORTRAIT_4_5, 1080, 30),
            ExportPreset("cinema", "Cinéma 2.39:1", "Scope, 1080p 24 i/s", AspectRatio.CINEMA_239, 1080, 24),
            ExportPreset("draft", "Brouillon 720p", "Rapide et léger, pour partager un aperçu", null, 720, 30, VideoCodec.H264, 0.08f),
        )
    }
}

data class ExportConfig(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codec: VideoCodec,
    val bitrate: Int,
)
