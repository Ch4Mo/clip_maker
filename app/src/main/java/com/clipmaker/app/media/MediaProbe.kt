package com.clipmaker.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.clipmaker.core.model.AssetOrigin
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads duration, size and track information of a media file. */
class MediaProbe(private val context: Context) {

    suspend fun probe(uri: Uri, origin: AssetOrigin = AssetOrigin.IMPORTED, nameOverride: String? = null): MediaAsset? =
        withContext(Dispatchers.IO) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: guessMime(uri)
                val name = nameOverride ?: displayName(uri)
                if (mime.startsWith("image/")) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    return@runCatching MediaAsset(
                        id = Ids.asset(), uri = uri.toString(), name = name, kind = MediaKind.IMAGE,
                        durationUs = MediaAsset.IMAGE_DEFAULT_DURATION_US, width = opts.outWidth, height = opts.outHeight,
                        hasAudio = false, hasVideo = true, origin = origin,
                    )
                }
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                    if (durationMs <= 0) return@runCatching null
                    MediaAsset(
                        id = Ids.asset(), uri = uri.toString(), name = name,
                        kind = if (hasVideo) MediaKind.VIDEO else MediaKind.AUDIO,
                        durationUs = durationMs * 1000, width = w, height = h,
                        hasAudio = hasAudio, hasVideo = hasVideo, origin = origin,
                    )
                } finally {
                    r.release()
                }
            }.getOrNull()
        }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Média"
    }

    private fun guessMime(uri: Uri): String {
        val ext = uri.toString().substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "heic" -> "image/$ext"
            "wav", "mp3", "m4a", "aac", "ogg", "flac" -> "audio/$ext"
            else -> "video/mp4"
        }
    }
}
