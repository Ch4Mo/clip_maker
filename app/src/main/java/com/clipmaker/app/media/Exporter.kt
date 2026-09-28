package com.clipmaker.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.clipmaker.app.media.render.CompositionFactory
import com.clipmaker.core.model.ExportConfig
import com.clipmaker.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class Exporter(private val context: Context) {

    /** Renders [project] to an MP4 file. Must be cancelled through the calling coroutine. */
    suspend fun export(project: Project, config: ExportConfig, output: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.Main) {
            val composition = CompositionFactory.build(project, config.width, config.height, config.frameRate)
                ?: throw IllegalStateException("Le projet est vide")
            output.parentFile?.mkdirs()
            output.delete()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(config.bitrate).build())
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(config.codec.mimeType)
                .setEncoderFactory(encoderFactory)
                .build()
            run(transformer, onProgress) { transformer.start(composition, output.absolutePath) }
            output
        }

    /** Extracts the soundtrack of a media file to an AAC (.m4a) file. */
    suspend fun extractAudio(source: Uri, output: File, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.Main) {
            output.parentFile?.mkdirs()
            output.delete()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveVideo(true).build()
            val transformer = Transformer.Builder(context).build()
            run(transformer, onProgress) { transformer.start(item, output.absolutePath) }
            output
        }

    private suspend fun run(transformer: Transformer, onProgress: (Float) -> Unit, start: () -> Unit) = coroutineScope {
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(250)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                cont.invokeOnCancellation { transformer.cancel() }
                start()
            }
            onProgress(1f)
        } finally {
            poller.cancel()
        }
    }

    /** Copies the exported video to the gallery (Movies/ClipMaker). Returns a shareable URI. */
    suspend fun publishToGallery(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClipMaker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Impossible d'enregistrer dans la galerie")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ClipMaker").apply { mkdirs() }
            val target = File(dir, "$displayName.mp4")
            file.copyTo(target, overwrite = true)
            FileProvider.getUriForFile(context, context.packageName + ".files", target)
        }
    }
}
