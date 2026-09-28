package com.clipmaker.app.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.clipmaker.app.AppContainer
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.media.render.CompositionFactory
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.edit.TimelineEditor
import com.clipmaker.core.model.AnimatedFloat
import com.clipmaker.core.model.AssetOrigin
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Built-in camera. "Play-back" mode plays the project soundtrack from the playhead while filming
 * (lip-sync / dance), and drops the shot at that exact position so it is already in sync.
 */
@UnstableApi
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(container: AppContainer, session: ProjectSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val project by session.project.collectAsState()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.all { it } }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    var front by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(true) }
    var uhd by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf(project.durationUs > 0) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var toOverlay by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedUs by remember { mutableLongStateOf(0L) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var player by remember { mutableStateOf<CompositionPlayer?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            player?.release()
        }
    }

    LaunchedEffect(granted, front, uhd) {
        if (!granted) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(if (uhd) Quality.UHD else Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture,
                )
            }.getOrNull()
            videoCapture = capture
        }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun finishRecording(file: File, startAtUs: Long) = scope.launch {
        val asset = container.probe.probe(Uri.fromFile(file), AssetOrigin.CAMERA, "Prise caméra") ?: return@launch
        session.edit("Plan filmé") { p ->
            val withAsset = TimelineEditor.addAsset(p, asset)
            val clip = TimelineEditor.newClipForAsset(asset, startAtUs).let {
                // In play-back mode the soundtrack comes from the project: mute the camera mic.
                if (playback) it.copy(volume = AnimatedFloat(0f)) else it
            }
            if (toOverlay) {
                val (withTrack, track) = TimelineEditor.findOrCreateFreeTrack(withAsset, TrackKind.OVERLAY, startAtUs, startAtUs + asset.durationUs)
                TimelineEditor.placeClip(withTrack, track.id, clip)
            } else {
                TimelineEditor.placeClip(withAsset, withAsset.mainVideoTrack!!.id, clip)
            }
        }
    }

    fun startRecording() {
        val capture = videoCapture ?: return
        val file = File(container.projects.mediaDir(project.id), "camera_${System.currentTimeMillis()}.mp4")
        val startAt = session.playheadUs.value
        scope.launch {
            for (s in timerSeconds downTo 1) {
                countdown = s
                delay(1000)
            }
            countdown = 0
            if (playback && project.durationUs > 0) {
                player?.release()
                player = CompositionFactory.build(project)?.let { comp ->
                    CompositionPlayer.Builder(context).build().apply { setComposition(comp); prepare(); seekTo(startAt / 1000) }
                }
            }
            recording = capture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> player?.play()
                        is VideoRecordEvent.Status -> elapsedUs = event.recordingStats.recordedDurationNanos / 1000
                        is VideoRecordEvent.Finalize -> {
                            player?.pause()
                            recording = null
                            elapsedUs = 0
                            if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) finishRecording(file, startAt)
                        }
                    }
                }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!granted) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("L'accès à la caméra et au micro est nécessaire pour filmer.", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Autoriser") }
            }
        } else {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.3f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
                }
            }
        }

        // Top controls
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { recording?.stop(); onDone() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (recording != null) {
                Text("● ${TimeFormat.short(elapsedUs)}", color = Palette.Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = { torch = !torch }) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Lampe", tint = Color.White) }
            IconButton(onClick = { grid = !grid }) { Icon(Icons.Default.Grid3x3, "Grille", tint = if (grid) Palette.Accent else Color.White) }
            IconButton(onClick = { timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 } }) {
                Icon(Icons.Default.Timer, "Minuteur", tint = if (timerSeconds > 0) Palette.Accent else Color.White)
            }
            if (timerSeconds > 0) Text("${timerSeconds}s", color = Palette.Accent)
            IconButton(onClick = { playback = !playback }, enabled = project.durationUs > 0) {
                Icon(if (playback) Icons.Default.MusicNote else Icons.Default.MusicOff, "Play-back", tint = if (playback) Palette.Accent else Color.White)
            }
        }

        if (countdown > 0) {
            Text("$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        // Bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (uhd) "4K" else "1080p", uhd, { if (recording == null) uhd = !uhd })
                Pill("Piste principale", !toOverlay, { toOverlay = false })
                Pill("Incrustation", toOverlay, { toOverlay = true })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (playback) "Play-back : la musique du projet démarre à ${TimeFormat.short(session.playheadUs.value)}" else "Son de la caméra conservé",
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(48.dp))
                Box(
                    Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(enabled = granted && countdown == 0) {
                        if (recording != null) recording?.stop() else startRecording()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(if (recording != null) 30.dp else 62.dp)
                            .clip(if (recording != null) RoundedCornerShape(6.dp) else CircleShape)
                            .background(Palette.Danger),
                    )
                }
                IconButton(onClick = { if (recording == null) front = !front }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Cameraswitch, "Changer de caméra", tint = Color.White)
                }
            }
        }
    }
}
