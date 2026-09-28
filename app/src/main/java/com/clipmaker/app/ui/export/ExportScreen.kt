package com.clipmaker.app.ui.export

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.clipmaker.app.AppContainer
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.model.ExportPreset
import com.clipmaker.core.model.VideoCodec
import com.clipmaker.core.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
@Composable
fun ExportScreen(container: AppContainer, session: ProjectSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val project by session.project.collectAsState()
    var preset by remember { mutableStateOf(ExportPreset.ALL.first()) }
    var codec by remember { mutableStateOf(VideoCodec.H264) }
    var quality by remember { mutableFloatStateOf(1f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(job) {
        view.keepScreenOn = job != null
        onDispose { view.keepScreenOn = false }
    }

    val config = preset.copy(codec = codec, qualityBpp = preset.qualityBpp * quality).resolve(project.settings)
    val estimatedMb = config.bitrate.toLong() * project.durationUs / 1_000_000 / 8 / 1_000_000

    fun start() {
        error = null
        result = null
        progress = 0f
        job = scope.launch {
            try {
                val out = File(context.cacheDir, "export/${project.id}.mp4")
                container.exporter.export(project, config, out) { progress = it }
                val name = project.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + "_" + System.currentTimeMillis() / 1000
                result = container.exporter.publishToGallery(out, name)
                out.delete()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                job = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { job?.cancel(); onDone() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Text("Exporter « ${project.name} »", style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item { SectionTitle("Destination") }
            items(ExportPreset.ALL) { p ->
                val selected = p.id == preset.id
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Palette.AccentSoft else Palette.Surface)
                        .border(1.5.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable(enabled = job == null) { preset = p; codec = p.codec }
                        .padding(12.dp),
                ) {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    Text(p.description, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
            item {
                SectionTitle("Codec")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoCodec.entries.forEach { c -> Pill(c.label, c == codec, { codec = c }) }
                }
                SectionTitle("Qualité")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.6f to "Légère", 1f to "Haute", 1.6f to "Maximale").forEach { (q, l) -> Pill(l, q == quality, { quality = q }) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${config.width}×${config.height} · ${config.frameRate} i/s · ${config.bitrate / 1_000_000} Mb/s · " +
                        "durée ${TimeFormat.short(project.durationUs)} · ~$estimatedMb Mo",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                )
                if (preset.aspectRatio != null && preset.aspectRatio != project.settings.aspectRatio) {
                    Text(
                        "Le format ${preset.aspectRatio!!.label} diffère du projet (${project.settings.aspectRatio.label}) : l'image sera recadrée.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Amber,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Palette.Surface).padding(16.dp)) {
            when {
                job != null -> {
                    Text("Rendu en cours… ${(progress * 100).toInt()} %", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Palette.Accent)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
                }
                result != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Palette.Green)
                        Text("  Enregistré dans la galerie (Films/ClipMaker)", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, result)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Partager le clip"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Default.Share, null); Text("  Partager") }
                        OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Retour au montage") }
                    }
                }
                else -> {
                    error?.let { Text("Échec de l'export : $it", color = Palette.Danger, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = ::start,
                        enabled = project.durationUs > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Exporter la vidéo", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
