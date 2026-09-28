package com.clipmaker.app.ui.editor.panels

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipmaker.app.AppContainer
import com.clipmaker.app.ui.components.ParamSlider
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.audio.SoundFxType
import com.clipmaker.core.model.AudioEffect
import com.clipmaker.core.model.AudioEffectType
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.TextAnimation
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.TextFont
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind
import kotlinx.coroutines.launch

private data class AudioPreset(val label: String, val effects: List<AudioEffect>)

private val audioPresets = listOf(
    AudioPreset("Voix claire", listOf(
        AudioEffect(AudioEffectType.HIGH_PASS, mapOf("cutoff" to 90f, "q" to 0.7f)),
        AudioEffect(AudioEffectType.EQUALIZER, mapOf("low" to -2f, "lowMid" to -2f, "mid" to 1f, "highMid" to 3f, "high" to 2f)),
        AudioEffect(AudioEffectType.COMPRESSOR),
    )),
    AudioPreset("Voix radio", listOf(AudioEffect(AudioEffectType.TELEPHONE), AudioEffect(AudioEffectType.DISTORTION, mapOf("drive" to 0.2f, "mix" to 0.5f)))),
    AudioPreset("Grande salle", listOf(AudioEffect(AudioEffectType.REVERB, mapOf("room" to 0.9f, "damping" to 0.3f, "mix" to 0.4f)))),
    AudioPreset("Écho stade", listOf(AudioEffect(AudioEffectType.DELAY, mapOf("time" to 420f, "feedback" to 0.45f, "mix" to 0.35f)), AudioEffect(AudioEffectType.REVERB))),
    AudioPreset("Lo-fi", listOf(AudioEffect(AudioEffectType.BITCRUSHER, mapOf("bits" to 10f, "downsample" to 3f)), AudioEffect(AudioEffectType.LOW_PASS, mapOf("cutoff" to 4_500f, "q" to 0.7f)))),
    AudioPreset("Grosse voix", listOf(AudioEffect(AudioEffectType.PITCH, mapOf("semitones" to -5f)), AudioEffect(AudioEffectType.EQUALIZER, mapOf("low" to 4f)))),
    AudioPreset("Voix aiguë", listOf(AudioEffect(AudioEffectType.PITCH, mapOf("semitones" to 6f)))),
    AudioPreset("Basses boostées", listOf(AudioEffect(AudioEffectType.EQUALIZER, mapOf("low" to 8f, "lowMid" to 2f)), AudioEffect(AudioEffectType.COMPRESSOR))),
    AudioPreset("Sous l'eau", listOf(AudioEffect(AudioEffectType.LOW_PASS, mapOf("cutoff" to 500f, "q" to 1.2f)), AudioEffect(AudioEffectType.CHORUS))),
)

@Composable
fun AudioFxPanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    Column {
        SectionTitle("Préréglages")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Aucun", clip.audioEffects.isEmpty(), { update("Effets audio", null) { it.copy(audioEffects = emptyList()) } })
            audioPresets.forEach { preset ->
                Pill(preset.label, clip.audioEffects == preset.effects, { update(preset.label, null) { it.copy(audioEffects = preset.effects) } })
            }
        }
        SectionTitle("Effets")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioEffectType.entries.forEach { type ->
                val active = clip.audioEffects.any { it.type == type }
                Pill(type.label, active, {
                    update(type.label, null) { c ->
                        if (active) c.copy(audioEffects = c.audioEffects.filterNot { it.type == type })
                        else c.copy(audioEffects = c.audioEffects + AudioEffect(type))
                    }
                })
            }
        }
        clip.audioEffects.forEach { effect ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(effect.type.label, Modifier.weight(1f))
                Switch(effect.enabled, { on ->
                    update(effect.type.label, null) { c -> c.copy(audioEffects = c.audioEffects.map { if (it.type == effect.type) it.copy(enabled = on) else it }) }
                }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent))
            }
            effect.type.params.forEach { spec ->
                ParamSlider(spec, effect.param(spec.key), onChange = { v ->
                    update(effect.type.label, "afx-${effect.type}-${spec.key}") { c ->
                        c.copy(audioEffects = c.audioEffects.map { if (it.type == effect.type) it.copy(params = it.params + (spec.key to v)) else it })
                    }
                }, onChangeFinished = onCommit)
            }
        }
    }
}

@Composable
fun TextStylePanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    val text = clip.text ?: return
    fun set(label: String, coalesce: String? = null, f: (TextContent) -> TextContent) =
        update(label, coalesce) { c -> c.copy(text = f(c.text ?: text), label = f(c.text ?: text).text) }
    Column {
        OutlinedTextField(
            value = text.text,
            onValueChange = { v -> set("Texte", "text-edit") { it.copy(text = v) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Texte") },
            maxLines = 3,
        )
        SectionTitle("Police")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextFont.entries.forEach { f -> Pill(f.label, text.font == f, { set("Police") { it.copy(font = f) } }) }
            Pill("Gras", text.bold, { set("Gras") { it.copy(bold = !it.bold) } })
            Pill("Italique", text.italic, { set("Italique") { it.copy(italic = !it.italic) } })
        }
        ParamSlider("Taille", text.size * 100, 2f..30f, { v -> set("Taille", "text-size") { it.copy(size = v / 100f) } }, onChangeFinished = onCommit)
        SectionTitle("Couleur")
        ColorSwatches(text.color, { c -> set("Couleur") { it.copy(color = c) } })
        SectionTitle("Contour")
        ColorSwatches(text.strokeColor, { c -> set("Contour") { it.copy(strokeColor = c, strokeWidth = if (it.strokeWidth == 0f) 0.5f else it.strokeWidth) } })
        ParamSlider("Épaisseur du contour", text.strokeWidth, 0f..1f, { v -> set("Contour", "stroke") { it.copy(strokeWidth = v) } }, default = 0f, onChangeFinished = onCommit)
        SectionTitle("Fond")
        ColorSwatches(text.backgroundColor, { c -> set("Fond") { it.copy(backgroundColor = c) } }, includeTransparent = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ombre portée", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(text.shadow, { v -> set("Ombre") { it.copy(shadow = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent))
        }
        SectionTitle("Animation d'entrée")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAnimation.entries.forEach { a -> Pill(a.label, text.animationIn == a, { set("Animation") { it.copy(animationIn = a) } }) }
        }
        SectionTitle("Animation de sortie")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAnimation.entries.forEach { a -> Pill(a.label, text.animationOut == a, { set("Animation") { it.copy(animationOut = a) } }) }
        }
        ParamSlider("Durée des animations", text.animationDurationUs / 1e6f, 0.1f..2f, { v -> set("Animation", "anim-duration") { it.copy(animationDurationUs = (v * 1e6).toLong()) } }, unit = "s", onChangeFinished = onCommit)
        Text("Position, taille et rotation : onglet « Animation » (images clés).", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

@Composable
fun SoundFxPanel(container: AppContainer, onAdd: (SoundFxType) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Column {
        Text("Touchez pour écouter, « + » pour placer le son à la tête de lecture.", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        container.sounds.categories.forEach { (category, types) ->
            SectionTitle(category)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { type ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.SurfaceHighest)
                            .clickable {
                                scope.launch {
                                    val asset = container.sounds.asset(type)
                                    player?.release()
                                    player = MediaPlayer.create(context, Uri.parse(asset.uri))?.apply { start() }
                                }
                            }
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.GraphicEq, null, tint = Palette.Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(type.label, style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { onAdd(type) }) { Icon(Icons.Default.Add, "Ajouter", tint = Palette.Accent) }
                    }
                }
            }
        }
    }
}

@Composable
fun BeatPanel(
    project: Project,
    onDetect: () -> Unit,
    onManualTempo: (Float, Long) -> Unit,
    onAutoCut: (Int) -> Unit,
    onMontage: (Int) -> Unit,
    onClear: () -> Unit,
    playheadUs: Long,
) {
    val taps = remember { mutableStateListOf<Long>() }
    var every by remember { mutableStateOf(2) }
    Column {
        val grid = project.beatGrid
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (grid != null) "${"%.1f".format(grid.bpm)} BPM" else "Rythme non analysé", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (grid != null) "${grid.beatsUs.size} temps · les coupes et déplacements s'aimantent aux temps" else "Analysez la musique pour caler votre montage sur le beat",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                )
            }
            Button(onClick = onDetect, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)) { Text("Détecter") }
        }
        SectionTitle("Tempo manuel (tapez en rythme)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Palette.AccentSoft).clickable {
                    val now = System.nanoTime() / 1000
                    if (taps.isNotEmpty() && now - taps.last() > 2_000_000) taps.clear()
                    taps.add(now)
                    if (taps.size > 8) taps.removeAt(0)
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.TouchApp, "Tap", tint = Palette.TextPrimary) }
            val tapBpm = com.clipmaker.core.beat.BeatDetector.tapTempo(taps.toList())
            Text(tapBpm?.let { "${it.toInt()} BPM" } ?: "—", style = MaterialTheme.typography.titleMedium)
            if (tapBpm != null) Pill("Appliquer depuis la tête de lecture", false, { onManualTempo(tapBpm, playheadUs) })
        }
        SectionTitle("Montage sur le rythme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4, 8).forEach { n -> Pill("Tous les $n temps", every == n, { every = n }) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAutoCut(every) }, enabled = grid != null, colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHighest)) { Text("Couper sur les temps") }
            Button(onClick = { onMontage(every) }, enabled = grid != null, colors = ButtonDefaults.buttonColors(containerColor = Palette.Pink)) { Text("Montage auto") }
        }
        Text(
            "« Montage auto » répartit vos vidéos et photos sur la piste principale, un plan tous les N temps. " +
                "L'effet « Pulsation (beat) » fait zoomer l'image sur chaque temps.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.padding(top = 6.dp),
        )
        if (grid != null) Pill("Effacer la grille", false, onClear)
    }
}

@Composable
fun TracksPanel(
    project: Project,
    onAdd: (TrackKind) -> Unit,
    onUpdate: (String, String, (Track) -> Track) -> Unit,
    onRemove: (String) -> Unit,
    onCommit: () -> Unit,
) {
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackAddButton("Incrustation", Icons.Default.PictureInPicture) { onAdd(TrackKind.OVERLAY) }
            TrackAddButton("Audio", Icons.Default.MusicNote) { onAdd(TrackKind.AUDIO) }
            TrackAddButton("Texte", Icons.Default.TextFields) { onAdd(TrackKind.TEXT) }
            TrackAddButton("Vidéo", Icons.Default.Videocam) { onAdd(TrackKind.VIDEO) }
        }
        SectionTitle("Table de mixage")
        project.tracks.filter { it.kind != TrackKind.TEXT }.forEach { track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ParamSlider(track.name, track.volume, 0f..2f, { v -> onUpdate(track.id, "Volume piste") { it.copy(volume = v) } },
                        default = 1f, format = { "${(it * 100).toInt()} %" }, onChangeFinished = onCommit)
                }
                if (track.kind == TrackKind.AUDIO) {
                    Pill("Solo", track.solo, { onUpdate(track.id, "Solo") { it.copy(solo = !it.solo) } })
                }
                if (track.id != project.mainVideoTrack?.id) {
                    IconButton(onClick = { onRemove(track.id) }) { Icon(Icons.Default.Delete, "Supprimer", tint = Palette.TextSecondary) }
                }
            }
        }
        project.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.let { music ->
            SectionTitle("Effets de piste · ${music.name}")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AudioEffectType.COMPRESSOR, AudioEffectType.EQUALIZER, AudioEffectType.REVERB).forEach { type ->
                    val active = music.audioEffects.any { it.type == type }
                    Pill(type.label, active, {
                        onUpdate(music.id, type.label) { t ->
                            t.copy(audioEffects = if (active) t.audioEffects.filterNot { it.type == type } else t.audioEffects + AudioEffect(type))
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun TrackAddButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHighest).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
        Icon(icon, null, tint = Palette.TextPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
