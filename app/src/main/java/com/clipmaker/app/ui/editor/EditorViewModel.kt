package com.clipmaker.app.ui.editor

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.clipmaker.app.AppContainer
import com.clipmaker.app.editor.ProjectSession
import com.clipmaker.app.media.render.CompositionFactory
import com.clipmaker.app.media.reportError
import com.clipmaker.core.audio.SoundFxType
import com.clipmaker.core.beat.BeatDetector
import com.clipmaker.core.edit.SnapEngine
import com.clipmaker.core.edit.TimelineEditor
import com.clipmaker.core.model.AssetOrigin
import com.clipmaker.core.model.BeatGrid
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class EditorPanel {
    NONE, MEDIA, AUDIO, TEXT, SOUND_FX, BEAT, TRACKS,
    SPLIT_SPEED, VOLUME, FILTERS, ADJUST, EFFECTS, TRANSFORM, TRANSITION, AUDIO_FX, TEXT_STYLE, PROJECT,
}

/**
 * Editor logic: owns the preview player (rebuilt from the project on every edit) and exposes
 * high-level editing commands on top of [ProjectSession].
 */
@OptIn(FlowPreview::class)
@UnstableApi
class EditorController(val container: AppContainer, val session: ProjectSession) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _player = MutableStateFlow<CompositionPlayer?>(null)
    val player: StateFlow<CompositionPlayer?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _panel = MutableStateFlow(EditorPanel.NONE)
    val panel: StateFlow<EditorPanel> = _panel.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Target track for "add" actions; defaults per media kind when null. */
    val targetTrackId = MutableStateFlow<String?>(null)

    private var ticker: Job? = null
    private var scrubbing = false

    val project get() = session.project
    val playhead get() = session.playheadUs
    val selection get() = session.selection

    init {
        // Rebuild the preview when anything that affects rendering changes.
        scope.launch {
            session.project
                .map { it.copy(updatedAtMs = 0, name = "", markers = emptyList()) }
                .distinctUntilChanged()
                .debounce(250)
                .collect { rebuildPlayer(it) }
        }
        scope.launch {
            session.project.collect { p -> p.assets.forEach { container.waveforms.request(it) } }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    private fun rebuildPlayer(project: Project) {
        val wasPlaying = _player.value?.isPlaying == true
        _player.value?.release()
        _player.value = null
        val composition = runCatching { CompositionFactory.build(project) }.getOrNull() ?: return
        val player = CompositionPlayer.Builder(container.appContext)
            .apply { if (CompositionFactory.isLayered(composition)) setVideoGraphFactory(MultipleInputVideoGraph.Factory()) }
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicker() else ticker?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _message.value = "Aperçu : " + reportError("Preview failed", error)
            }
        })
        player.setComposition(composition)
        player.prepare()
        player.seekTo(session.playheadUs.value / 1000)
        if (wasPlaying) player.play()
        _player.value = player
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = _player.value ?: break
                if (!scrubbing) session.playheadUs.value = p.currentPosition * 1000
                delay(16)
            }
        }
    }

    fun togglePlay() {
        val p = _player.value ?: return
        if (p.isPlaying) p.pause() else {
            if (session.playheadUs.value >= session.current.durationUs - 50_000) seek(0)
            p.play()
        }
    }

    fun pause() = _player.value?.pause()

    fun seek(us: Long) {
        val clamped = us.coerceIn(0, session.current.durationUs.coerceAtLeast(0))
        session.playheadUs.value = clamped
        _player.value?.seekTo(clamped / 1000)
    }

    fun setScrubbing(active: Boolean) {
        scrubbing = active
        _player.value?.setScrubbingModeEnabled(active)
    }

    fun release() {
        _player.value?.release()
        _player.value = null
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------------

    fun openPanel(panel: EditorPanel) {
        _panel.value = if (_panel.value == panel) EditorPanel.NONE else panel
    }

    fun closePanel() {
        _panel.value = EditorPanel.NONE
    }

    fun toast(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun select(clipId: String?, additive: Boolean = false) {
        session.selection.value = when {
            clipId == null -> emptySet()
            additive -> session.selection.value.let { if (clipId in it) it - clipId else it + clipId }
            else -> setOf(clipId)
        }
        if (clipId == null && _panel.value >= EditorPanel.SPLIT_SPEED) _panel.value = EditorPanel.NONE
    }

    val selectedClip: Pair<Track, Clip>?
        get() = session.selection.value.singleOrNull()?.let { session.current.findClip(it) }

    // ---------------------------------------------------------------------------------------------
    // Media import
    // ---------------------------------------------------------------------------------------------

    fun importMedia(uris: List<Uri>) = scope.launch {
        _busy.value = "Import des médias…"
        var cursor = session.playheadUs.value
        val imported = ArrayList<MediaAsset>()
        for (uri in uris) {
            runCatching {
                container.appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            container.probe.probe(uri)?.let { imported += it }
        }
        _busy.value = null
        if (imported.isEmpty()) {
            toast("Aucun média compatible")
            return@launch
        }
        session.edit("Importer") { p ->
            var result = p
            for (asset in imported) {
                val trackId = trackFor(result, asset.kind)
                if (trackId == null) {
                    result = TimelineEditor.addAsset(result, asset)
                    continue
                }
                val (next, clip) = TimelineEditor.insertAsset(result, trackId, asset, cursor)
                result = next
                cursor = next.findClip(clip.id)?.second?.endUs ?: cursor
            }
            result
        }
    }

    /** Chooses the destination track for a new asset: explicit target, else first suitable track. */
    private fun trackFor(p: Project, kind: MediaKind): String? {
        targetTrackId.value?.let { id ->
            val t = p.track(id)
            if (t != null && t.kind.accepts(kind) && !t.locked) return id
        }
        return when (kind) {
            MediaKind.AUDIO -> p.tracks.firstOrNull { it.kind == TrackKind.AUDIO && !it.locked }?.id
            else -> p.mainVideoTrack?.id
        }
    }

    fun addAssetAtPlayhead(asset: MediaAsset, trackKind: TrackKind? = null) {
        session.edit("Ajouter ${asset.name}") { p ->
            val at = session.playheadUs.value
            val kind = trackKind ?: if (asset.kind == MediaKind.AUDIO) TrackKind.AUDIO else TrackKind.VIDEO
            val explicit = targetTrackId.value?.let { p.track(it) }?.takeIf { it.kind == kind && !it.locked }
            val (withTrack, track) = when {
                explicit != null -> p to explicit
                kind == TrackKind.VIDEO -> p to p.mainVideoTrack!!
                else -> TimelineEditor.findOrCreateFreeTrack(p, kind, at, at + asset.durationUs)
            }
            TimelineEditor.insertAsset(withTrack, track.id, asset, at).first
        }
    }

    fun addSoundFx(type: SoundFxType) = scope.launch {
        val asset = container.sounds.asset(type)
        addAssetAtPlayhead(asset, TrackKind.AUDIO)
        toast("${type.label} ajouté")
    }

    fun addText(text: String = "Votre titre") {
        var created: Clip? = null
        session.edit("Ajouter un texte") { p ->
            val at = session.playheadUs.value
            val (withTrack, track) = TimelineEditor.findOrCreateFreeTrack(p, TrackKind.TEXT, at, at + TimelineEditor.DEFAULT_TEXT_DURATION_US)
            val (next, clip) = TimelineEditor.insertText(withTrack, track.id, TextContent(text), at)
            created = clip
            next
        }
        created?.let { select(it.id); _panel.value = EditorPanel.TEXT_STYLE }
    }

    /** Extracts the soundtrack of the selected video clip to a new audio file on its own track. */
    fun extractAudioToFile() {
        val (_, clip) = selectedClip ?: return
        val asset = session.current.asset(clip.assetId) ?: return
        if (!asset.hasAudio || asset.kind != MediaKind.VIDEO) {
            toast("Ce clip n'a pas de son")
            return
        }
        scope.launch {
            _busy.value = "Extraction de l'audio…"
            try {
                val out = File(container.projects.mediaDir(session.current.id), "audio_${System.currentTimeMillis()}.m4a")
                container.exporter.extractAudio(Uri.parse(asset.uri), out)
                val extracted = MediaAsset(
                    id = com.clipmaker.core.util.Ids.asset(), uri = Uri.fromFile(out).toString(), name = "Audio · ${asset.name}",
                    kind = MediaKind.AUDIO, durationUs = asset.durationUs, hasVideo = false, origin = AssetOrigin.EXTRACTED,
                )
                session.edit("Extraire l'audio") { p ->
                    val (withTrack, track) = TimelineEditor.findOrCreateFreeTrack(p, TrackKind.AUDIO, clip.startUs, clip.endUs)
                    val audioClip = clip.copy(
                        id = com.clipmaker.core.util.Ids.clip(), assetId = extracted.id, videoEffects = emptyList(),
                        transform = com.clipmaker.core.model.Transform(), transitionIn = null, transitionOut = null, label = extracted.name,
                    )
                    val placed = TimelineEditor.placeClip(TimelineEditor.addAsset(withTrack, extracted), track.id, audioClip)
                    TimelineEditor.updateClip(placed, clip.id) { it.copy(volume = com.clipmaker.core.model.AnimatedFloat(0f)) }
                }
                toast("Audio extrait sur une nouvelle piste")
            } catch (t: Throwable) {
                toast("Échec de l'extraction : ${t.message}")
            } finally {
                _busy.value = null
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Beat detection
    // ---------------------------------------------------------------------------------------------

    /** Analyses the music clip (selected audio clip or first clip of the first audio track). */
    fun detectBeats() {
        val p = session.current
        val clip = selectedClip?.second?.takeIf { p.asset(it.assetId)?.hasAudio == true }
            ?: p.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.clips.isNotEmpty() }?.sortedClips?.first()
            ?: p.mainVideoTrack?.sortedClips?.firstOrNull { p.asset(it.assetId)?.hasAudio == true }
        val asset = p.asset(clip?.assetId)
        if (clip == null || asset == null) {
            toast("Ajoutez d'abord une musique")
            return
        }
        scope.launch {
            _busy.value = "Analyse du rythme…"
            try {
                val pcm = container.decoder.decodeMono(Uri.parse(asset.uri), 22_050, clip.sourceStartUs, clip.sourceEndUs)
                    ?: throw IllegalStateException("audio illisible")
                val analysis = withContext(Dispatchers.Default) { BeatDetector.analyze(pcm.samples, pcm.sampleRate) }
                // Convert source-relative beats into timeline positions.
                val beats = analysis.beatsUs.map { clip.startUs + (it / clip.speed).toLong() }.filter { it < clip.endUs }
                session.edit("Détection du rythme") {
                    it.copy(
                        beatGrid = BeatGrid(analysis.bpm * clip.speed, beats, analysis.downbeatIndices, clip.id),
                        assets = it.assets.map { a -> if (a.id == asset.id) a.copy(bpm = analysis.bpm) else a },
                    )
                }
                toast("${analysis.bpm.toInt()} BPM · ${beats.size} temps détectés")
            } catch (t: Throwable) {
                toast("Analyse impossible : ${t.message}")
            } finally {
                _busy.value = null
            }
        }
    }

    fun setManualTempo(bpm: Float, offsetUs: Long) {
        session.edit("Tempo manuel") { p ->
            val beats = BeatDetector.gridFromBpm(bpm, offsetUs, p.durationUs.coerceAtLeast(60_000_000))
            p.copy(beatGrid = BeatGrid(bpm, beats, beats.indices.filter { it % 4 == 0 }))
        }
    }

    fun clearBeats() = session.edit("Effacer le rythme") { it.copy(beatGrid = null) }

    fun autoCutOnBeats(everyN: Int) {
        val grid = session.current.beatGrid ?: return toast("Détectez d'abord le rythme")
        val track = selectedClip?.first?.takeIf { it.kind.isVisual } ?: session.current.mainVideoTrack ?: return
        session.edit("Coupes sur le rythme") { TimelineEditor.autoCutOnBeats(it, track.id, grid.beatsUs, everyN) }
        toast("Coupes placées tous les $everyN temps")
    }

    fun beatMontage(everyN: Int) {
        val p = session.current
        val grid = p.beatGrid ?: return toast("Détectez d'abord le rythme")
        val main = p.mainVideoTrack ?: return
        val visuals = main.sortedClips.mapNotNull { p.asset(it.assetId) }.distinctBy { it.id }
            .ifEmpty { p.assets.filter { it.kind != MediaKind.AUDIO } }
        if (visuals.isEmpty()) return toast("Importez des vidéos ou photos")
        session.edit("Montage automatique") { TimelineEditor.beatSyncMontage(it, main.id, visuals, grid.beatsUs, everyN) }
        toast("Montage généré sur ${grid.bpm.toInt()} BPM")
    }

    // ---------------------------------------------------------------------------------------------
    // Clip editing commands
    // ---------------------------------------------------------------------------------------------

    fun splitAtPlayhead() {
        val at = session.playheadUs.value
        val sel = session.selection.value
        session.edit("Couper") { TimelineEditor.splitAt(it, at, sel.ifEmpty { null }) }
    }

    fun deleteSelection(ripple: Boolean = false) {
        val sel = session.selection.value
        if (sel.isEmpty()) return
        session.edit("Supprimer") { TimelineEditor.deleteClips(it, sel, ripple) }
        session.selection.value = emptySet()
        closePanel()
    }

    fun duplicateSelection() {
        val (_, clip) = selectedClip ?: return
        var dup: Clip? = null
        session.edit("Dupliquer") { p -> TimelineEditor.duplicateClip(p, clip.id).also { dup = it.second }.first }
        dup?.let { select(it.id) }
    }

    fun detachAudio() {
        val (_, clip) = selectedClip ?: return
        session.edit("Séparer l'audio") { TimelineEditor.detachAudio(it, clip.id).first }
    }

    fun updateSelected(label: String, coalesce: String? = null, update: (Clip) -> Clip) {
        val (_, clip) = selectedClip ?: return
        session.edit(label, coalesce) { TimelineEditor.updateClip(it, clip.id, update) }
    }

    fun setSpeed(speed: Float, coalesce: String? = "speed") {
        val (_, clip) = selectedClip ?: return
        session.edit("Vitesse", coalesce) { TimelineEditor.setSpeed(it, clip.id, speed) }
    }

    fun moveClip(clipId: String, trackId: String, startUs: Long) {
        session.edit("Déplacer", coalesce = "move-$clipId") { TimelineEditor.moveClip(it, clipId, trackId, startUs) }
    }

    fun trimClip(clipId: String, leftEdge: Boolean, timeUs: Long) {
        session.edit("Rogner", coalesce = "trim-$clipId") {
            if (leftEdge) TimelineEditor.trimStart(it, clipId, timeUs) else TimelineEditor.trimEnd(it, clipId, timeUs)
        }
    }

    fun endGesture() = session.commit()

    /** Snaps a time to nearby edges/beats given a pixel threshold converted to time. */
    fun snap(timeUs: Long, thresholdUs: Long, exclude: Set<String>): Long {
        val points = SnapEngine.collectPoints(session.current, session.playheadUs.value, exclude)
        return SnapEngine.snap(timeUs, points, thresholdUs).timeUs
    }

    /** Returns the snapped start and, when snapped, the position of the snap guide. */
    fun snapRange(startUs: Long, durationUs: Long, thresholdUs: Long, exclude: Set<String>): Pair<Long, Long?> {
        val points = SnapEngine.collectPoints(session.current, session.playheadUs.value, exclude)
        val r = SnapEngine.snapRange(startUs, durationUs, points, thresholdUs)
        return r.timeUs to r.snappedTo?.timeUs
    }

    fun addTrack(kind: TrackKind) = session.edit("Ajouter une piste") { TimelineEditor.addTrack(it, kind).first }

    fun updateTrack(trackId: String, label: String, update: (Track) -> Track) =
        session.edit(label) { TimelineEditor.updateTrack(it, trackId, update) }

    fun removeTrack(trackId: String) = session.edit("Supprimer la piste") { TimelineEditor.removeTrack(it, trackId) }

    fun addMarker() = session.edit("Marqueur") {
        it.copy(markers = it.markers + com.clipmaker.core.model.Marker(com.clipmaker.core.util.Ids.marker(), session.playheadUs.value))
    }
}
