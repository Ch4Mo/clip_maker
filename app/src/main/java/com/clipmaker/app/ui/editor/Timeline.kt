package com.clipmaker.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.clipmaker.app.media.ThumbnailRepository
import com.clipmaker.app.media.WaveformRepository
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.util.TimeFormat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private val HEADER_WIDTH = 52.dp
private val RULER_HEIGHT = 22.dp
private val ROW_GAP = 4.dp

private fun rowHeight(kind: TrackKind) = when (kind) {
    TrackKind.VIDEO -> 58.dp
    TrackKind.OVERLAY -> 44.dp
    TrackKind.AUDIO -> 44.dp
    TrackKind.TEXT -> 30.dp
}

private fun clipColor(kind: TrackKind) = when (kind) {
    TrackKind.VIDEO -> Palette.VideoClip
    TrackKind.OVERLAY -> Palette.OverlayClip
    TrackKind.AUDIO -> Palette.AudioClip
    TrackKind.TEXT -> Palette.TextClip
}

private enum class DragMode { NONE, SCRUB, MOVE, TRIM_LEFT, TRIM_RIGHT, ZOOM }

private data class Ghost(val clipId: String, val trackId: String, val startUs: Long, val endUs: Long, val left: Boolean? = null)

/**
 * Multi-track timeline with a fixed centre playhead (CapCut-style): drag to scrub, pinch to zoom,
 * tap to select, long-press and drag to move a clip (also across tracks), drag the handles of the
 * selected clip to trim. Moves and trims snap to clip edges, playhead, markers and beats.
 */
@UnstableApi
@Composable
fun Timeline(
    controller: EditorController,
    thumbnails: ThumbnailRepository,
    waveforms: WaveformRepository,
    modifier: Modifier = Modifier,
) {
    val project by controller.project.collectAsState()
    val playhead by controller.playhead.collectAsState()
    val selection by controller.selection.collectAsState()
    val thumbVersion by thumbnails.version.collectAsState()
    val peaks by waveforms.peaks.collectAsState()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    var pxPerSecond by remember { mutableFloatStateOf(80f) }
    var verticalScroll by remember { mutableFloatStateOf(0f) }
    var ghost by remember { mutableStateOf<Ghost?>(null) }
    var snapLineUs by remember { mutableStateOf<Long?>(null) }

    val currentProject by rememberUpdatedState(project)
    val currentSelection by rememberUpdatedState(selection)

    BoxWithConstraints(modifier.background(Palette.Background).clipToBounds()) {
        val headerPx = with(density) { HEADER_WIDTH.toPx() }
        val rulerPx = with(density) { RULER_HEIGHT.toPx() }
        val gapPx = with(density) { ROW_GAP.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val centerX = headerPx + (widthPx - headerPx) / 2f
        val handlePx = with(density) { 16.dp.toPx() }

        // Row layout (top positions in px, before vertical scroll).
        val rows = remember(project.tracks, density) {
            var y = rulerPx + gapPx
            project.tracks.map { t ->
                val h = with(density) { rowHeight(t.kind).toPx() }
                val top = y
                y += h + gapPx
                Triple(t.id, top, h)
            }
        }
        val contentHeight = (rows.lastOrNull()?.let { it.second + it.third } ?: 0f) + gapPx
        val maxScroll = max(0f, contentHeight - with(density) { maxHeight.toPx() })

        fun xOf(timeUs: Long, playheadUs: Long = controller.playhead.value, pps: Float = pxPerSecond) =
            centerX + (timeUs - playheadUs) / 1_000_000f * pps

        fun timeAt(x: Float, playheadUs: Long = controller.playhead.value) =
            playheadUs + ((x - centerX) / pxPerSecond * 1_000_000f).toLong()

        fun rowAt(y: Float): Int = rows.indexOfFirst { (_, top, h) -> y + verticalScroll >= top && y + verticalScroll < top + h + gapPx }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(project.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = currentProject
                        val rowIndex = rowAt(down.position.y)
                        val track = rows.getOrNull(rowIndex)?.let { r -> p.track(r.first) }
                        val downTime = timeAt(down.position.x)
                        val clip = track?.clips?.firstOrNull { downTime >= it.startUs && downTime < it.endUs }
                        val selected = currentSelection
                        // Trim handles of the selected clip take priority.
                        val handleHit = rows.asSequence().mapNotNull { (id, top, h) ->
                            val t = p.track(id) ?: return@mapNotNull null
                            val c = t.clips.firstOrNull { it.id in selected } ?: return@mapNotNull null
                            val y = down.position.y + verticalScroll
                            if (y < top || y > top + h) return@mapNotNull null
                            val lx = xOf(c.startUs)
                            val rx = xOf(c.endUs)
                            when {
                                abs(down.position.x - lx) < handlePx -> Triple(t, c, true)
                                abs(down.position.x - rx) < handlePx -> Triple(t, c, false)
                                else -> null
                            }
                        }.firstOrNull()

                        var mode = if (handleHit != null && !handleHit.first.locked) {
                            if (handleHit.third) DragMode.TRIM_LEFT else DragMode.TRIM_RIGHT
                        } else DragMode.NONE
                        val activeClip = handleHit?.second ?: clip
                        val activeTrack = handleHit?.first ?: track
                        var moved = false
                        var pinchStart = 0f
                        var ppsStart = pxPerSecond
                        val slop = viewConfiguration.touchSlop

                        if (mode == DragMode.NONE && clip != null && track?.locked == false) {
                            // Long press on a clip starts a move.
                            val outcome = withTimeoutOrNull(380L) {
                                var released: Boolean? = null
                                while (released == null) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    if (ev.changes.size > 1 || !ch.pressed || (ch.position - down.position).getDistance() > slop) released = false
                                }
                                released
                            }
                            val longPressed = outcome == null
                            if (longPressed) {
                                mode = DragMode.MOVE
                                controller.select(clip.id)
                                ghost = Ghost(clip.id, track.id, clip.startUs, clip.endUs)
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2 && mode != DragMode.MOVE && mode != DragMode.TRIM_LEFT && mode != DragMode.TRIM_RIGHT) {
                                val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                if (mode != DragMode.ZOOM) {
                                    mode = DragMode.ZOOM
                                    pinchStart = d
                                    ppsStart = pxPerSecond
                                    controller.setScrubbing(false)
                                } else if (pinchStart > 0) {
                                    pxPerSecond = (ppsStart * d / pinchStart).coerceIn(4f, 1200f)
                                }
                                event.changes.forEach { it.consume() }
                                moved = true
                                continue
                            }
                            val change = event.changes.first()
                            val total = change.position - down.position
                            if (!moved && total.getDistance() > slop) {
                                moved = true
                                if (mode == DragMode.NONE) {
                                    mode = DragMode.SCRUB
                                    controller.pause()
                                    controller.setScrubbing(true)
                                }
                            }
                            if (!moved) continue
                            val thresholdUs = (handlePx / pxPerSecond * 1_000_000f).toLong()
                            when (mode) {
                                DragMode.SCRUB -> {
                                    val delta = change.positionChange()
                                    if (abs(delta.x) >= abs(delta.y) * 0.6f) {
                                        val newTime = controller.playhead.value - (delta.x / pxPerSecond * 1_000_000f).toLong()
                                        controller.seek(newTime)
                                    }
                                    verticalScroll = (verticalScroll - delta.y).coerceIn(0f, maxScroll)
                                }
                                DragMode.MOVE -> {
                                    val c = activeClip ?: break
                                    val rawStart = c.startUs + (total.x / pxPerSecond * 1_000_000f).toLong()
                                    val targetRow = rowAt(change.position.y).takeIf { it >= 0 }
                                    val targetTrack = targetRow?.let { p.track(rows[it].first) }
                                        ?.takeIf { it.kind.accepts(p.asset(c.assetId)?.kind) && !it.locked }
                                        ?: activeTrack!!
                                    val (snapped, line) = controller.snapRange(rawStart.coerceAtLeast(0), c.durationUs, thresholdUs, setOf(c.id))
                                    snapLineUs = line
                                    ghost = Ghost(c.id, targetTrack.id, snapped, snapped + c.durationUs)
                                    change.consume()
                                }
                                DragMode.TRIM_LEFT, DragMode.TRIM_RIGHT -> {
                                    val c = activeClip ?: break
                                    val edge = if (mode == DragMode.TRIM_LEFT) c.startUs else c.endUs
                                    val raw = edge + (total.x / pxPerSecond * 1_000_000f).toLong()
                                    val snapped = controller.snap(raw, thresholdUs, setOf(c.id))
                                    snapLineUs = if (snapped != raw) snapped else null
                                    ghost = if (mode == DragMode.TRIM_LEFT) {
                                        Ghost(c.id, activeTrack!!.id, snapped.coerceAtMost(c.endUs - 66_000), c.endUs, left = true)
                                    } else {
                                        Ghost(c.id, activeTrack!!.id, c.startUs, snapped.coerceAtLeast(c.startUs + 66_000), left = false)
                                    }
                                    change.consume()
                                }
                                else -> Unit
                            }
                        }

                        // Gesture finished.
                        val g = ghost
                        when (mode) {
                            DragMode.NONE -> if (!moved) {
                                if (clip != null) controller.select(clip.id) else {
                                    controller.select(null)
                                }
                            }
                            DragMode.SCRUB -> controller.setScrubbing(false)
                            DragMode.MOVE -> if (g != null) {
                                controller.moveClip(g.clipId, g.trackId, g.startUs)
                                controller.endGesture()
                            }
                            DragMode.TRIM_LEFT -> if (g != null) {
                                controller.trimClip(g.clipId, true, g.startUs)
                                controller.endGesture()
                            }
                            DragMode.TRIM_RIGHT -> if (g != null) {
                                controller.trimClip(g.clipId, false, g.endUs)
                                controller.endGesture()
                            }
                            DragMode.ZOOM -> Unit
                        }
                        ghost = null
                        snapLineUs = null
                    }
                },
        ) {
            if (thumbVersion < 0) return@Canvas
            val toX: (Long) -> Float = { xOf(it, playhead) }
            val visibleFrom = timeAt(headerPx, playhead)
            val visibleTo = timeAt(size.width, playhead)

            // Ruler
            drawRect(Palette.Surface, Offset(headerPx, 0f), Size(size.width - headerPx, rulerPx))
            val step = rulerStep(pxPerSecond)
            var t = (visibleFrom / step) * step
            while (t <= visibleTo) {
                if (t >= 0) {
                    val x = xOf(t, playhead)
                    val major = (t / step) % 5 == 0L
                    drawLine(Palette.TextSecondary.copy(alpha = if (major) 0.8f else 0.35f), Offset(x, rulerPx * (if (major) 0.35f else 0.65f)), Offset(x, rulerPx), 1f)
                    if (major) {
                        drawText(textMeasurer, TimeFormat.short(t).substringBeforeLast('.').let { if (step < 1_000_000) TimeFormat.short(t).dropLast(1) else it },
                            Offset(x + 3f, 1f), style = TextStyle(color = Palette.TextSecondary, fontSize = 9.sp))
                    }
                }
                t += step
            }

            clipRect(left = headerPx, top = rulerPx) {
                // Tracks
                project.tracks.forEachIndexed { i, track ->
                    val (_, top0, h) = rows.getOrNull(i) ?: return@forEachIndexed
                    val top = top0 - verticalScroll
                    if (top + h < rulerPx || top > size.height) return@forEachIndexed
                    drawRect(Palette.Surface.copy(alpha = 0.5f), Offset(headerPx, top), Size(size.width - headerPx, h))
                    for (clip in track.clips) {
                        val g = ghost
                        if (g != null && g.clipId == clip.id) continue
                        drawClip(project, track, clip, clip.startUs, clip.endUs, top, h, playhead, clip.id in selection, 1f,
                            toX, textMeasurer, thumbnails, peaks, visibleFrom, visibleTo)
                    }
                }
                // Ghost of the clip being moved/trimmed.
                ghost?.let { g ->
                    val found = project.findClip(g.clipId) ?: return@let
                    val row = rows.firstOrNull { it.first == g.trackId } ?: return@let
                    val shifted = if (g.left == null) found.second.copy(startUs = g.startUs) else found.second
                    drawClip(project, project.track(g.trackId) ?: found.first, shifted, g.startUs, g.endUs, row.second - verticalScroll, row.third,
                        playhead, true, 0.85f, toX, textMeasurer, thumbnails, peaks, visibleFrom, visibleTo)
                }
                // Beat grid
                project.beatGrid?.let { grid ->
                    val down = grid.downbeatIndices.toSet()
                    grid.beatsUs.forEachIndexed { i, b ->
                        if (b < visibleFrom || b > visibleTo) return@forEachIndexed
                        val x = xOf(b, playhead)
                        val strong = i in down
                        drawLine(Palette.Beat.copy(alpha = if (strong) 0.55f else 0.25f), Offset(x, rulerPx), Offset(x, size.height), if (strong) 2f else 1f)
                    }
                }
            }
            // Markers on the ruler
            project.markers.forEach { m ->
                val x = xOf(m.timeUs, playhead)
                val path = Path().apply { moveTo(x - 6f, 0f); lineTo(x + 6f, 0f); lineTo(x, 10f); close() }
                drawPath(path, Color(m.color))
            }
            snapLineUs?.let { s ->
                val x = xOf(s, playhead)
                drawLine(Palette.Cyan, Offset(x, rulerPx), Offset(x, size.height), 2f)
            }
            // Playhead (fixed at the centre)
            drawLine(Palette.Playhead, Offset(centerX, 0f), Offset(centerX, size.height), 3f)
            drawCircle(Palette.Playhead, 6f, Offset(centerX, 4f))
            // Header background
            drawRect(Palette.Background, Offset.Zero, Size(headerPx, size.height))
        }

        // Track headers (mute / lock), aligned with rows.
        project.tracks.forEachIndexed { i, track ->
            val (_, top, h) = rows.getOrNull(i) ?: return@forEachIndexed
            val y = top - verticalScroll
            if (y + h < rulerPx) return@forEachIndexed
            TrackHeader(
                track,
                modifier = Modifier
                    .offset { IntOffset(0, y.roundToInt()) }
                    .width(HEADER_WIDTH)
                    .height(with(density) { h.toDp() }),
                onToggleMute = { controller.updateTrack(track.id, if (track.muted) "Réactiver" else "Couper le son") { it.copy(muted = !it.muted) } },
                onToggleLock = { controller.updateTrack(track.id, "Verrouiller") { it.copy(locked = !it.locked) } },
                onSelect = { controller.targetTrackId.value = track.id },
                targeted = controller.targetTrackId.collectAsState().value == track.id,
            )
        }
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    modifier: Modifier,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onSelect: () -> Unit,
    targeted: Boolean,
) {
    Box(modifier.padding(end = 4.dp).background(if (targeted) Palette.AccentSoft else Palette.Surface).clickable(onClick = onSelect)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = when (track.kind) {
                TrackKind.VIDEO -> Icons.Default.Videocam
                TrackKind.OVERLAY -> Icons.Default.PictureInPicture
                TrackKind.AUDIO -> Icons.Default.MusicNote
                TrackKind.TEXT -> Icons.Default.TextFields
            }
            Icon(icon, track.name, tint = clipColor(track.kind), modifier = Modifier.size(14.dp).padding(top = 2.dp))
            if (track.kind != TrackKind.TEXT) {
                Row {
                    Icon(
                        if (track.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Muet",
                        tint = if (track.muted) Palette.Danger else Palette.TextSecondary,
                        modifier = Modifier.size(18.dp).clickable(onClick = onToggleMute).padding(1.dp),
                    )
                    Icon(
                        if (track.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Verrou",
                        tint = if (track.locked) Palette.Amber else Palette.TextSecondary,
                        modifier = Modifier.size(18.dp).clickable(onClick = onToggleLock).padding(1.dp),
                    )
                }
            }
        }
    }
}

private fun rulerStep(pxPerSecond: Float): Long {
    val candidates = longArrayOf(100_000, 250_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 30_000_000, 60_000_000)
    // Minor ticks every `step`; major labels every 5 steps.
    return candidates.firstOrNull { it / 1_000_000f * pxPerSecond >= 14f } ?: candidates.last()
}

private fun DrawScope.drawClip(
    project: Project,
    track: Track,
    clip: Clip,
    startUs: Long,
    endUs: Long,
    top: Float,
    height: Float,
    playhead: Long,
    selected: Boolean,
    alpha: Float,
    toX: (Long) -> Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    thumbnails: ThumbnailRepository,
    peaks: Map<String, FloatArray>,
    visibleFrom: Long,
    visibleTo: Long,
) {
    if (endUs < visibleFrom || startUs > visibleTo) return
    val left = toX(startUs)
    val right = toX(endUs)
    val w = max(2f, right - left)
    val base = clipColor(track.kind)
    val radius = CornerRadius(8f, 8f)
    val asset = project.asset(clip.assetId)
    drawRoundRect(base.copy(alpha = 0.35f * alpha), Offset(left, top), Size(w, height), radius)

    clipRect(left, top, left + w, top + height) {
        when {
            asset != null && asset.kind != MediaKind.AUDIO && track.kind.isVisual -> {
                // Filmstrip
                val tileW = height * (if (asset.height > 0) (asset.width.toFloat() / asset.height).coerceIn(0.5f, 2.2f) else 16f / 9f)
                val firstTile = max(left, toX(visibleFrom) - tileW)
                var x = left + ((firstTile - left) / tileW).toInt() * tileW
                while (x < right && x < size.width) {
                    val tileTime = startUs + ((x - left) / (right - left) * (endUs - startUs)).toLong()
                    val sourceSec = (clip.sourceStartUs + (tileTime - startUs) * clip.speed).toLong() / 1_000_000
                    thumbnails.get(asset, sourceSec.toInt())?.let { img ->
                        drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), top.toInt()),
                            dstSize = IntSize(tileW.toInt() + 1, height.toInt()), alpha = alpha)
                    }
                    x += tileW
                }
            }
            asset != null && asset.hasAudio -> {
                val data = peaks[asset.id]
                val mid = top + height / 2
                if (data != null) {
                    val buckets = data.size / 2
                    val from = max(left, 0f).toInt()
                    val to = minOf(right, size.width).toInt()
                    var x = from
                    while (x < to) {
                        val tl = startUs + ((x - left) / (right - left) * (endUs - startUs)).toLong()
                        val src = clip.sourceStartUs + ((tl - startUs) * clip.speed).toLong()
                        val idx = (src / 10_000).toInt()
                        if (idx in 0 until buckets) {
                            val gain = clip.gainAt(tl - startUs).coerceIn(0f, 2f)
                            val lo = data[idx * 2] * gain
                            val hi = data[idx * 2 + 1] * gain
                            drawLine(base.copy(alpha = alpha), Offset(x.toFloat(), mid - hi * height / 2), Offset(x.toFloat(), mid - lo * height / 2), 1.5f)
                        }
                        x += 2
                    }
                } else {
                    drawLine(base.copy(alpha = 0.6f), Offset(left, mid), Offset(right, mid), 1f)
                }
            }
        }
        // Fades
        if (clip.fadeInUs > 0) {
            val fx = toX(startUs + clip.fadeInUs)
            drawPath(Path().apply { moveTo(left, top + height); lineTo(fx, top); lineTo(left, top); close() }, Color.Black.copy(alpha = 0.35f))
        }
        if (clip.fadeOutUs > 0) {
            val fx = toX(endUs - clip.fadeOutUs)
            drawPath(Path().apply { moveTo(right, top + height); lineTo(fx, top); lineTo(right, top); close() }, Color.Black.copy(alpha = 0.35f))
        }
        // Label & badges
        val badges = buildString {
            if (clip.speed != 1f) append("${"%.2f".format(clip.speed).trimEnd('0').trimEnd('.')}x ")
            if (clip.videoEffects.isNotEmpty()) append("fx${clip.videoEffects.size} ")
            if (clip.audioEffects.isNotEmpty()) append("♫fx ")
            if (clip.volume.value == 0f && clip.volume.keyframes.isEmpty() && asset?.hasAudio == true) append("🔇 ")
        }
        val label = (clip.text?.text ?: clip.label).take(40)
        drawText(
            textMeasurer, "$badges$label", Offset(left + 6f, top + 2f),
            style = TextStyle(color = Color.White.copy(alpha = alpha), fontSize = 10.sp,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(1f, 1f), 2f)),
            maxLines = 1,
            size = IntSize(max(1, (w - 8f).toInt()), (height - 2).toInt().coerceAtLeast(1)),
        )
        // Transition markers
        if (clip.transitionIn != null) drawPath(Path().apply { moveTo(left, top + height); lineTo(left + 14f, top + height); lineTo(left, top + height - 14f); close() }, Palette.Cyan)
        if (clip.transitionOut != null) drawPath(Path().apply { moveTo(right, top + height); lineTo(right - 14f, top + height); lineTo(right, top + height - 14f); close() }, Palette.Cyan)
        // Keyframes of the selected clip
        if (selected) {
            val t = clip.transform
            val times = (listOf(t.x, t.y, t.scale, t.rotationDeg, t.opacity, clip.volume).flatMap { a -> a.keyframes.map { it.timeUs } }).distinct()
            times.forEach { kt ->
                val kx = toX(startUs + kt)
                val cy = top + height - 8f
                drawPath(Path().apply { moveTo(kx, cy - 5f); lineTo(kx + 5f, cy); lineTo(kx, cy + 5f); lineTo(kx - 5f, cy); close() }, Palette.Amber)
            }
        }
    }

    if (selected) {
        drawRoundRect(Color.White, Offset(left, top), Size(w, height), radius, style = Stroke(2.5f))
        // Trim handles
        drawRoundRect(Color.White, Offset(left - 6f, top), Size(12f, height), CornerRadius(4f, 4f))
        drawRoundRect(Color.White, Offset(right - 6f, top), Size(12f, height), CornerRadius(4f, 4f))
        drawLine(Palette.Background, Offset(left, top + height * 0.35f), Offset(left, top + height * 0.65f), 2f)
        drawLine(Palette.Background, Offset(right, top + height * 0.35f), Offset(right, top + height * 0.65f), 2f)
    } else {
        drawRoundRect(base.copy(alpha = 0.9f * alpha), Offset(left, top), Size(w, height), radius, style = Stroke(1.5f))
    }
}
