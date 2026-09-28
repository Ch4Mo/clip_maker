package com.clipmaker.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.clipmaker.core.audio.WaveformBuilder
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Min/max peaks per asset, 100 buckets per second, cached on disk. */
class WaveformRepository(context: Context, private val decoder: AudioDecoder) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }
    private val _peaks = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val peaks: StateFlow<Map<String, FloatArray>> = _peaks.asStateFlow()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val limiter = Semaphore(2)

    fun request(asset: MediaAsset) {
        if (asset.kind == MediaKind.IMAGE || !asset.hasAudio) return
        if (_peaks.value.containsKey(asset.id) || !pending.add(asset.id)) return
        scope.launch {
            limiter.withPermit {
                val cache = File(dir, asset.id + ".peaks")
                val data = runCatching { ObjectInputStream(cache.inputStream()).use { it.readObject() as FloatArray } }.getOrNull()
                    ?: compute(asset)?.also { p -> runCatching { ObjectOutputStream(cache.outputStream()).use { it.writeObject(p) } } }
                if (data != null) _peaks.value = _peaks.value + (asset.id to data)
            }
            pending.remove(asset.id)
        }
    }

    private suspend fun compute(asset: MediaAsset): FloatArray? {
        val rate = 8_000
        val pcm = decoder.decodeMono(Uri.parse(asset.uri), targetRate = rate) ?: return null
        val builder = WaveformBuilder(rate / BUCKETS_PER_SECOND)
        builder.add(pcm.samples)
        return builder.peaks()
    }

    companion object {
        const val BUCKETS_PER_SECOND = 100
    }
}

/** Timeline filmstrip thumbnails: one frame per second of media, decoded lazily. */
class ThumbnailRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val _version = MutableStateFlow(0)

    /** Incremented whenever new thumbnails are available (drives recomposition). */
    val version: StateFlow<Int> = _version.asStateFlow()
    private val queue = Channel<Pair<MediaAsset, Int>>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()

    init {
        scope.launch {
            for ((asset, second) in queue) {
                val key = key(asset.id, second)
                runCatching { decode(asset, second) }.getOrNull()?.let {
                    cache.put(key, it)
                    _version.value++
                }
                pending.remove(key)
            }
        }
    }

    private fun key(assetId: String, second: Int) = "$assetId@$second"

    fun get(asset: MediaAsset, second: Int): ImageBitmap? {
        val s = if (asset.kind == MediaKind.IMAGE) 0 else second
        val key = key(asset.id, s)
        cache.get(key)?.let { return it }
        if (pending.add(key)) queue.trySend(asset to s)
        return null
    }

    private fun decode(asset: MediaAsset, second: Int): ImageBitmap? {
        val targetH = 96
        if (asset.kind == MediaKind.IMAGE) {
            val uri = Uri.parse(asset.uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = (bounds.outHeight / targetH).coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(sample) }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.let { scaled(it, targetH) }?.asImageBitmap()
        }
        val r = retrievers.getOrPut(asset.id) {
            MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(asset.uri)) }
        }
        val timeUs = second * 1_000_000L
        val bmp = if (Build.VERSION.SDK_INT >= 27) {
            val ratio = if (asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (targetH * ratio).toInt().coerceAtLeast(1), targetH)
        } else {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaled(it, targetH) }
        }
        return bmp?.asImageBitmap()
    }

    private fun scaled(src: Bitmap, targetH: Int): Bitmap {
        if (src.height <= targetH) return src
        val w = (src.width * targetH.toFloat() / src.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, targetH, true)
    }
}
