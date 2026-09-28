package com.clipmaker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.clipmaker.app.AppContainer
import com.clipmaker.app.data.ProjectSummary
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.model.AspectRatio
import com.clipmaker.core.model.ProjectSettings
import com.clipmaker.core.util.TimeFormat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val projects by container.projects.projects.collectAsState()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
    }

    LaunchedEffect(Unit) { container.projects.refresh() }

    Scaffold(
        containerColor = Palette.Background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                containerColor = Palette.Accent,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nouveau projet") },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Hero(onCreate = { showCreate = true }) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(if (projects.isEmpty()) "Aucun projet pour l'instant" else "Mes projets (${projects.size})")
            }
            items(projects, key = { it.id }) { p ->
                ProjectCard(
                    p, imageLoader,
                    onOpen = { onOpen(p.id) },
                    onRename = { renaming = p },
                    onDuplicate = { scope.launch { container.projects.duplicate(p.id) } },
                    onDelete = { deleting = p },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, settings ->
                showCreate = false
                scope.launch { onOpen(container.projects.create(name, settings).id) }
            },
        )
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.projects.rename(p.id, name) }
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Le projet et les médias enregistrés dans l'application seront supprimés. Les fichiers importés de votre galerie ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = {
                    container.closeSession(p.id)
                    scope.launch { container.projects.delete(p.id) }
                    deleting = null
                }) { Text("Supprimer", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Hero(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Palette.Accent, Color(0xFF2B1B5E), Palette.Pink.copy(alpha = 0.7f))))
            .clickable(onClick = onCreate)
            .padding(20.dp),
    ) {
        Text("ClipMaker", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Filmez, montez sur le rythme, enregistrez vos sons, ajoutez effets et titres, exportez en 4K.",
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ProjectCard(
    p: ProjectSummary,
    imageLoader: ImageLoader,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
            if (p.coverUri != null) {
                AsyncImage(model = p.coverUri, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Movie, null, tint = Palette.TextSecondary, modifier = Modifier.size(36.dp))
            }
            Text(
                p.aspectLabel,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = Color.White,
            )
            Text(
                TimeFormat.short(p.durationUs).substringBeforeLast('.'),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = Color.White,
            )
        }
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.updatedAtMs)),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Palette.TextSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renommer") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Dupliquer") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, ProjectSettings) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf(AspectRatio.LANDSCAPE_16_9) }
    var fps by remember { mutableIntStateOf(30) }
    var shortSide by remember { mutableIntStateOf(1080) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Nouveau projet", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nom du clip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionTitle("Format")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(AspectRatio.PRESETS) { r -> RatioTile(r, r == ratio) { ratio = r } }
                }
                SectionTitle("Images par seconde")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 25, 30, 60).forEach { f -> Pill("$f", f == fps, { fps = f }) }
                }
                SectionTitle("Résolution")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K").forEach { (s, l) -> Pill(l, s == shortSide, { shortSide = s }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, ProjectSettings(aspectRatio = ratio, frameRate = fps, shortSide = shortSide)) }) {
                Text("Créer", color = Palette.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun RatioTile(ratio: AspectRatio, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            val w = if (ratio.value >= 1f) 48f else 48f * ratio.value
            val h = if (ratio.value >= 1f) 48f / ratio.value else 48f
            Box(
                Modifier.width(w.dp).height(h.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (selected) Palette.AccentSoft else Palette.SurfaceHighest)
                    .border(2.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(4.dp)),
            )
        }
        Text(ratio.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.Accent else Palette.TextSecondary)
    }
}
