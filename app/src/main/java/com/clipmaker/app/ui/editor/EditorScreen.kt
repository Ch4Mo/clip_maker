package com.clipmaker.app.ui.editor

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clipmaker.app.AppContainer
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.components.ToolButton
import com.clipmaker.app.ui.editor.panels.AdjustPanel
import com.clipmaker.app.ui.editor.panels.AudioFxPanel
import com.clipmaker.app.ui.editor.panels.BeatPanel
import com.clipmaker.app.ui.editor.panels.EffectsPanel
import com.clipmaker.app.ui.editor.panels.FiltersPanel
import com.clipmaker.app.ui.editor.panels.SoundFxPanel
import com.clipmaker.app.ui.editor.panels.SpeedPanel
import com.clipmaker.app.ui.editor.panels.TextStylePanel
import com.clipmaker.app.ui.editor.panels.TracksPanel
import com.clipmaker.app.ui.editor.panels.TransformPanel
import com.clipmaker.app.ui.editor.panels.TransitionPanel
import com.clipmaker.app.ui.editor.panels.VolumePanel
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.model.AspectRatio
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.util.TimeFormat

@UnstableApi
@Composable
fun EditorScreen(
    container: AppContainer,
    session: ProjectSession,
    onBack: () -> Unit,
    onCamera: () -> Unit,
    onRecorder: () -> Unit,
    onExport: () -> Unit,
) {
    val controller = remember(session) { EditorController(container, session) }
    DisposableEffect(controller) { onDispose { controller.release() } }

    val project by session.project.collectAsState()
    val history by session.historyState.collectAsState()
    val playhead by session.playheadUs.collectAsState()
    val selection by session.selection.collectAsState()
    val player by controller.player.collectAsState()
    val playing by controller.isPlaying.collectAsState()
    val panel by controller.panel.collectAsState()
    val busy by controller.busy.collectAsState()
    val message by controller.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            controller.consumeMessage()
        }
    }

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.importMedia(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            controller.targetTrackId.value = project.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.id
            controller.importMedia(uris)
        }
    }

    BackHandler(enabled = panel != EditorPanel.NONE || selection.isNotEmpty()) {
        if (panel != EditorPanel.NONE) controller.closePanel() else controller.select(null)
    }

    val selected = selection.singleOrNull()?.let { project.findClip(it) }
    val selectedAsset = selected?.let { project.asset(it.second.assetId) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------------- Top bar ----------------
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${project.settings.aspectRatio.label} · ${project.settings.outputWidth}×${project.settings.outputHeight} · ${project.settings.frameRate} i/s",
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                    )
                }
                IconButton(onClick = session::undo, enabled = history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Annuler") }
                IconButton(onClick = session::redo, enabled = history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Rétablir") }
                Button(
                    onClick = { controller.pause(); onExport() },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                    shape = RoundedCornerShape(10.dp),
                    enabled = project.durationUs > 0,
                ) { Text("Exporter", fontWeight = FontWeight.Bold) }
            }

            // ---------------- Preview ----------------
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                val ratio = project.settings.aspectRatio.value
                Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1.3f).background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (project.durationUs == 0L) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Commencez par importer ou filmer", color = Palette.TextSecondary)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Importer", false, { pickVisual.launch(arrayOf("video/*", "image/*")) })
                                Pill("Filmer", false, onCamera)
                            }
                        }
                    }
                }
            }

            // ---------------- Transport ----------------
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.short(playhead)} / ${TimeFormat.short(project.durationUs)}",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Palette.SurfaceHighest).clickable { controller.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    project.beatGrid?.let {
                        Text("♩ ${it.bpm.toInt()}", style = MaterialTheme.typography.labelMedium, color = Palette.Amber, modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
                    }
                    IconButton(onClick = controller::addMarker) { Icon(Icons.Default.Bookmark, "Marqueur", tint = Palette.TextSecondary) }
                }
            }

            // ---------------- Timeline ----------------
            Timeline(
                controller, container.thumbnails, container.waveforms,
                modifier = Modifier.fillMaxWidth().height(if (panel == EditorPanel.NONE) 250.dp else 150.dp),
            )

            // ---------------- Panel or toolbar ----------------
            if (panel != EditorPanel.NONE) {
                PanelHost(controller, panel, container, onDismiss = controller::closePanel)
            } else if (selected != null) {
                val (track, clip) = selected
                val isText = clip.text != null
                val isAudio = track.kind == TrackKind.AUDIO
                val hasSound = selectedAsset?.hasAudio == true && selectedAsset.kind != MediaKind.IMAGE
                ToolRow {
                    ToolButton(Icons.Default.CallSplit, "Couper", controller::splitAtPlayhead)
                    if (!isText) ToolButton(Icons.Default.Speed, "Vitesse", { controller.openPanel(EditorPanel.SPLIT_SPEED) })
                    if (hasSound) ToolButton(Icons.Default.VolumeUp, "Volume", { controller.openPanel(EditorPanel.VOLUME) })
                    if (isText) ToolButton(Icons.Default.TextFields, "Style", { controller.openPanel(EditorPanel.TEXT_STYLE) })
                    if (!isAudio && !isText) {
                        ToolButton(Icons.Default.FilterVintage, "Filtres", { controller.openPanel(EditorPanel.FILTERS) })
                        ToolButton(Icons.Default.Tune, "Réglages", { controller.openPanel(EditorPanel.ADJUST) })
                        ToolButton(Icons.Default.AutoAwesome, "Effets", { controller.openPanel(EditorPanel.EFFECTS) })
                    }
                    if (!isAudio) {
                        ToolButton(Icons.Default.Animation, "Animation", { controller.openPanel(EditorPanel.TRANSFORM) })
                    }
                    if (!isAudio && !isText) ToolButton(Icons.Default.SwapHoriz, "Transition", { controller.openPanel(EditorPanel.TRANSITION) })
                    if (hasSound) ToolButton(Icons.Default.Equalizer, "Effets audio", { controller.openPanel(EditorPanel.AUDIO_FX) })
                    if (hasSound && !isAudio && selectedAsset?.kind == MediaKind.VIDEO) {
                        ToolButton(Icons.Default.LinkOff, "Séparer audio", controller::detachAudio)
                        ToolButton(Icons.Default.AudioFile, "Extraire audio", controller::extractAudioToFile)
                    }
                    ToolButton(Icons.Default.ContentCopy, "Dupliquer", controller::duplicateSelection)
                    ToolButton(Icons.Default.Delete, "Supprimer", { controller.deleteSelection() }, tint = Palette.Danger)
                    ToolButton(Icons.Default.Check, "Terminé", { controller.select(null) })
                }
            } else {
                ToolRow {
                    ToolButton(Icons.Default.PhotoLibrary, "Médias", { pickVisual.launch(arrayOf("video/*", "image/*")) })
                    ToolButton(Icons.Default.Videocam, "Filmer", { controller.pause(); onCamera() })
                    ToolButton(Icons.Default.MusicNote, "Musique", { pickAudio.launch(arrayOf("audio/*", "video/*")) })
                    ToolButton(Icons.Default.Mic, "Enregistrer", { controller.pause(); onRecorder() })
                    ToolButton(Icons.Default.GraphicEq, "Sons", { controller.openPanel(EditorPanel.SOUND_FX) })
                    ToolButton(Icons.Default.TextFields, "Texte", { controller.addText() })
                    ToolButton(Icons.Default.Timer, "Rythme", { controller.openPanel(EditorPanel.BEAT) })
                    ToolButton(Icons.Default.CallSplit, "Couper tout", controller::splitAtPlayhead, enabled = project.durationUs > 0)
                    ToolButton(Icons.Default.Layers, "Pistes", { controller.openPanel(EditorPanel.TRACKS) })
                    ToolButton(Icons.Default.AspectRatio, "Format", { controller.openPanel(EditorPanel.PROJECT) })
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))

        AnimatedVisibility(busy != null, modifier = Modifier.align(Alignment.Center)) {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(busy ?: "")
            }
        }
    }
}

@Composable
private fun ToolRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@UnstableApi
@Composable
private fun PanelHost(controller: EditorController, panel: EditorPanel, container: AppContainer, onDismiss: () -> Unit) {
    val project by controller.project.collectAsState()
    val playhead by controller.playhead.collectAsState()
    val selection by controller.selection.collectAsState()
    val selected = selection.singleOrNull()?.let { project.findClip(it) }?.second
    val localUs = selected?.let { (playhead - it.startUs).coerceIn(0, it.durationUs) } ?: 0L
    val update: (String, String?, (com.clipmaker.core.model.Clip) -> com.clipmaker.core.model.Clip) -> Unit =
        { label, coalesce, f -> controller.updateSelected(label, coalesce, f) }
    val commit = { controller.endGesture() }

    val title = when (panel) {
        EditorPanel.SPLIT_SPEED -> "Vitesse"
        EditorPanel.VOLUME -> "Volume & fondus"
        EditorPanel.FILTERS -> "Filtres"
        EditorPanel.ADJUST -> "Réglages couleur"
        EditorPanel.EFFECTS -> "Effets vidéo"
        EditorPanel.TRANSFORM -> "Animation · images clés"
        EditorPanel.TRANSITION -> "Transitions"
        EditorPanel.AUDIO_FX -> "Effets audio"
        EditorPanel.TEXT_STYLE -> "Texte"
        EditorPanel.SOUND_FX -> "Bibliothèque de sons"
        EditorPanel.BEAT -> "Rythme & beat sync"
        EditorPanel.TRACKS -> "Pistes & mixage"
        EditorPanel.PROJECT -> "Format du projet"
        else -> ""
    }
    Column(Modifier.fillMaxWidth().background(Palette.Surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Check, "Valider", tint = Palette.Accent) }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            when (panel) {
                EditorPanel.SPLIT_SPEED -> selected?.let { SpeedPanel(it, { s, c -> controller.setSpeed(s, c) }, commit) }
                EditorPanel.VOLUME -> selected?.let { VolumePanel(it, localUs, update, commit) }
                EditorPanel.FILTERS -> selected?.let { FiltersPanel(it, update, commit) }
                EditorPanel.ADJUST -> selected?.let { AdjustPanel(it, update, commit) }
                EditorPanel.EFFECTS -> selected?.let { EffectsPanel(it, update, commit) }
                EditorPanel.TRANSFORM -> selected?.let { TransformPanel(it, localUs, update, commit) }
                EditorPanel.TRANSITION -> selected?.let { TransitionPanel(it, update, commit) }
                EditorPanel.AUDIO_FX -> selected?.let { AudioFxPanel(it, update, commit) }
                EditorPanel.TEXT_STYLE -> selected?.let { TextStylePanel(it, update, commit) }
                EditorPanel.SOUND_FX -> SoundFxPanel(container) { controller.addSoundFx(it) }
                EditorPanel.BEAT -> BeatPanel(
                    project, controller::detectBeats, controller::setManualTempo, controller::autoCutOnBeats,
                    controller::beatMontage, controller::clearBeats, playhead,
                )
                EditorPanel.TRACKS -> TracksPanel(project, controller::addTrack, controller::updateTrack, controller::removeTrack, commit)
                EditorPanel.PROJECT -> ProjectSettingsPanel(controller)
                else -> Unit
            }
        }
    }
}

@UnstableApi
@Composable
private fun ProjectSettingsPanel(controller: EditorController) {
    val project by controller.project.collectAsState()
    val s = project.settings
    Column {
        SectionTitle("Format d'image")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectRatio.PRESETS.forEach { r ->
                Pill(r.label, r == s.aspectRatio, { controller.session.edit("Format") { it.copy(settings = it.settings.copy(aspectRatio = r)) } })
            }
        }
        SectionTitle("Images par seconde")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 25, 30, 50, 60).forEach { f ->
                Pill("$f", f == s.frameRate, { controller.session.edit("Fréquence") { it.copy(settings = it.settings.copy(frameRate = f)) } })
            }
        }
        SectionTitle("Résolution de travail")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K").forEach { (v, l) ->
                Pill(l, v == s.shortSide, { controller.session.edit("Résolution") { it.copy(settings = it.settings.copy(shortSide = v)) } })
            }
        }
        Text(
            "Changer de format recadre automatiquement tous les plans (remplissage) ; ajustez ensuite chaque plan dans « Animation ».",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
