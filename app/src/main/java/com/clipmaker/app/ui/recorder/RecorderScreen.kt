package com.clipmaker.app.ui.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.clipmaker.app.AppContainer
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.media.render.CompositionFactory
import com.clipmaker.app.record.AudioRecorder
import com.clipmaker.app.record.Metronome
import com.clipmaker.app.ui.components.ParamSlider
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.audio.Silence
import com.clipmaker.core.audio.Wav
import com.clipmaker.core.edit.TimelineEditor
import com.clipmaker.core.model.AssetOrigin
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.util.Ids
import com.clipmaker.core.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice & sample studio: record takes with metronome, count-in and project playback (for
 * singing / rapping in sync), then drop them on the timeline or keep them in the sample bank.
 */
@UnstableApi
@Composable
fun RecorderScreen(container: AppContainer, session: ProjectSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project by session.project.collectAsState()
    val recorder = remember { AudioRecorder() }
    val metronome = remember { Metronome() }
    val level by recorder.level.collectAsState()
    val elapsed by recorder.elapsedUs.collectAsState()
    val beat by metronome.beat.collectAsState()

    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var metronomeJob by remember { mutableStateOf<Job?>(null) }
    var useMetronome by remember { mutableStateOf(false) }
    var countIn by remember { mutableStateOf(true) }
    var monitorProject by remember { mutableStateOf(true) }
    var autoPlace by remember { mutableStateOf(true) }
    var trimSilence by remember { mutableStateOf(true) }
    var bpm by remember { mutableFloatStateOf(project.beatGrid?.bpm ?: 120f) }
    var countingIn by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<CompositionPlayer?>(null) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    val hasPermission = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission.value = it }

    DisposableEffect(Unit) {
        onDispose {
            recordingJob?.cancel(); metronomeJob?.cancel(); player?.release(); preview?.release()
        }
    }

    val takes = project.assets.filter { it.origin == AssetOrigin.RECORDED }.reversed()
    val startPlayhead = remember { session.playheadUs.value }

    fun stopAll() {
        recordingJob?.cancel()
        metronomeJob?.cancel()
        player?.pause()
    }

    fun start() {
        if (!hasPermission.value) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val file = File(container.projects.mediaDir(project.id), "take_${System.currentTimeMillis()}.wav")
        val recordAt = session.playheadUs.value
        recordingJob = scope.launch {
            // Optional project playback so the performer hears the beat.
            if (monitorProject && project.durationUs > 0) {
                player?.release()
                player = CompositionFactory.build(project)?.let { comp ->
                    CompositionPlayer.Builder(context).build().apply { setComposition(comp); prepare(); seekTo(recordAt / 1000) }
                }
            }
            if (useMetronome && countIn) {
                countingIn = true
                metronome.play(bpm, countInBeats = 4)
                countingIn = false
            }
            if (useMetronome) metronomeJob = scope.launch { metronome.play(bpm) }
            player?.play()
            val durationUs = try {
                recorder.record(file)
            } finally {
                metronomeJob?.cancel()
                player?.pause()
            }
            if (durationUs < 50_000) return@launch
            val (from, to) = if (trimSilence) withContext(Dispatchers.Default) {
                val pcm = Wav.read(file)
                Silence.trimBounds(pcm.toMono(), pcm.sampleRate)
            } else 0L to durationUs
            val index = takes.size + 1
            val asset = MediaAsset(
                id = Ids.asset(), uri = Uri.fromFile(file).toString(), name = "Prise $index", kind = MediaKind.AUDIO,
                durationUs = durationUs, hasVideo = false, origin = AssetOrigin.RECORDED,
            )
            session.edit("Enregistrement") { p ->
                val withAsset = TimelineEditor.addAsset(p, asset)
                if (!autoPlace) return@edit withAsset
                // When trimming the start, keep the performance in sync by shifting its position.
                val at = recordAt + from
                val (withTrack, track) = TimelineEditor.findOrCreateFreeTrack(withAsset, TrackKind.AUDIO, at, recordAt + to)
                val clip = TimelineEditor.newClipForAsset(asset, at).copy(sourceStartUs = from, sourceEndUs = to)
                TimelineEditor.placeClip(withTrack, track.id, clip)
            }
        }.also { job -> job.invokeOnCompletion { recordingJob = null } }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stopAll(); onDone() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Text("Studio d'enregistrement", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        // Big record button + meter
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    countingIn -> "Décompte… ${beat + 1}"
                    recordingJob != null -> TimeFormat.short(elapsed)
                    else -> "Position : ${TimeFormat.short(session.playheadUs.value)}"
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (recordingJob != null) Palette.Danger else Palette.TextPrimary,
            )
            Spacer(Modifier.height(16.dp))
            val animatedLevel by animateFloatAsState(level, label = "level")
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size((120 + animatedLevel * 60).dp).clip(CircleShape).background(Palette.Danger.copy(alpha = 0.15f)))
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(if (recordingJob != null) Palette.Danger else Palette.SurfaceHighest)
                        .border(4.dp, Palette.Danger, CircleShape)
                        .clickable { if (recordingJob != null) stopAll() else start() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (recordingJob != null) Icon(Icons.Default.Stop, "Stop", tint = Color.White, modifier = Modifier.size(40.dp))
                    else Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Danger))
                }
            }
            Spacer(Modifier.height(12.dp))
            // Metronome beat lights
            if (useMetronome) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i -> Box(Modifier.size(12.dp).clip(CircleShape).background(if (beat == i) (if (i == 0) Palette.Amber else Palette.Cyan) else Palette.SurfaceHighest)) }
            }
            LevelMeter(level, Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp).height(8.dp))
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Options")
                OptionRow("Écouter le projet pendant la prise", monitorProject) { monitorProject = it }
                OptionRow("Métronome", useMetronome) { useMetronome = it }
                if (useMetronome) {
                    ParamSlider("Tempo", bpm, 40f..220f, { bpm = it.toInt().toFloat() }, unit = "BPM", default = project.beatGrid?.bpm)
                    OptionRow("Décompte d'une mesure", countIn) { countIn = it }
                }
                OptionRow("Placer la prise à la tête de lecture", autoPlace) { autoPlace = it }
                OptionRow("Rogner les silences (idéal pour les samples)", trimSilence) { trimSilence = it }
                Text(
                    "Astuce : enregistrez plusieurs petits sons à la suite ; ils restent dans la banque ci-dessous et se placent où vous voulez avec « + ».",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                )
                SectionTitle("Banque de prises (${takes.size})")
            }
            items(takes, key = { it.id }) { take ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        preview?.release()
                        preview = MediaPlayer.create(context, Uri.parse(take.uri))?.apply { start() }
                    }) { Icon(Icons.Default.PlayArrow, "Écouter", tint = Palette.Cyan) }
                    Column(Modifier.weight(1f)) {
                        Text(take.name, style = MaterialTheme.typography.titleSmall)
                        Text(TimeFormat.short(take.durationUs), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                    }
                    IconButton(onClick = {
                        session.edit("Placer ${take.name}") { p ->
                            val at = session.playheadUs.value
                            val (withTrack, track) = TimelineEditor.findOrCreateFreeTrack(p, TrackKind.AUDIO, at, at + take.durationUs)
                            TimelineEditor.insertAsset(withTrack, track.id, take, at).first
                        }
                    }) { Icon(Icons.Default.Add, "Placer", tint = Palette.Accent) }
                    IconButton(onClick = { session.edit("Supprimer la prise") { TimelineEditor.removeAsset(it, take.id) } }) {
                        Icon(Icons.Default.Delete, "Supprimer", tint = Palette.TextSecondary)
                    }
                }
            }
        }
        Text(
            "Départ de l'enregistrement : ${TimeFormat.short(startPlayhead)}",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, fontSize = 10.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent))
    }
}

@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Palette.SurfaceHighest)) {
        val w = size.width * level.coerceIn(0f, 1f)
        val color = when {
            level > 0.9f -> Palette.Danger
            level > 0.6f -> Palette.Amber
            else -> Palette.Green
        }
        drawRect(color, Offset.Zero, androidx.compose.ui.geometry.Size(w, size.height))
    }
}
