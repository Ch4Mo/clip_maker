package com.clipmaker.app.data

import android.content.Context
import android.net.Uri
import com.clipmaker.core.audio.SoundFx
import com.clipmaker.core.audio.SoundFxType
import com.clipmaker.core.audio.Wav
import com.clipmaker.core.model.AssetOrigin
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in sound bank: procedural sound effects rendered to WAV on first use. */
class SoundLibrary(context: Context) {
    private val dir = File(context.filesDir, "soundfx").apply { mkdirs() }

    val categories: Map<String, List<SoundFxType>> = SoundFxType.entries
        .filterNot { it.category == "Métronome" }
        .groupBy { it.category }

    suspend fun asset(type: SoundFxType): MediaAsset = withContext(Dispatchers.IO) {
        val file = File(dir, "${type.name.lowercase()}.wav")
        val audio = if (file.exists()) Wav.read(file) else SoundFx.generate(type).also { Wav.write(file, it) }
        MediaAsset(
            id = "sfx-${type.name.lowercase()}",
            uri = Uri.fromFile(file).toString(),
            name = type.label,
            kind = MediaKind.AUDIO,
            durationUs = audio.durationUs,
            hasAudio = true,
            hasVideo = false,
            origin = AssetOrigin.GENERATED,
        )
    }
}
