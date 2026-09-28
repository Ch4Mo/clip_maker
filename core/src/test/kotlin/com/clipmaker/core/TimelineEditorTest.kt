package com.clipmaker.core

import com.clipmaker.core.edit.TimelineEditor
import com.clipmaker.core.model.AnimatedFloat
import com.clipmaker.core.model.Keyframe
import com.clipmaker.core.model.MediaAsset
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.model.Transform
import com.clipmaker.core.util.Ids
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineEditorTest {
    private var counter = 0
    private val video10s = MediaAsset("a1", "file://a1.mp4", "A", MediaKind.VIDEO, 10_000_000)
    private val video4s = MediaAsset("a2", "file://a2.mp4", "B", MediaKind.VIDEO, 4_000_000)
    private val song = MediaAsset("s1", "file://song.mp3", "Song", MediaKind.AUDIO, 60_000_000)
    private val image = MediaAsset("i1", "file://img.jpg", "Img", MediaKind.IMAGE, 0)

    @Before
    fun setUp() {
        counter = 0
        Ids.generator = { prefix -> "$prefix-${++counter}" }
    }

    private fun empty() = Project.create("p", "Test", 0)
    private val main = "track-video-main"
    private val music = "track-audio-music"
    private val overlay = "track-overlay-1"

    @Test
    fun `magnetic main track packs clips without gaps`() {
        var p = empty()
        p = TimelineEditor.insertAsset(p, main, video10s, 0).first
        p = TimelineEditor.insertAsset(p, main, video4s, 50_000_000).first
        val clips = p.track(main)!!.sortedClips
        assertEquals(listOf(0L, 10_000_000L), clips.map { it.startUs })
        assertEquals(14_000_000L, p.durationUs)
    }

    @Test
    fun `inserting before the middle of a clip puts the new clip first`() {
        var p = empty()
        p = TimelineEditor.insertAsset(p, main, video10s, 0).first
        val (p2, c) = TimelineEditor.insertAsset(p, main, video4s, 1_000_000)
        assertEquals(c.id, p2.track(main)!!.sortedClips.first().id)
    }

    @Test
    fun `split keeps total duration and source continuity`() {
        var p = empty()
        val (p1, clip) = TimelineEditor.insertAsset(p, main, video10s, 0)
        p = TimelineEditor.split(p1, clip.id, 4_000_000)
        val clips = p.track(main)!!.sortedClips
        assertEquals(2, clips.size)
        assertEquals(4_000_000, clips[0].durationUs)
        assertEquals(6_000_000, clips[1].durationUs)
        assertEquals(clips[0].sourceEndUs, clips[1].sourceStartUs)
        assertEquals(10_000_000, p.durationUs)
    }

    @Test
    fun `split with speed maps to source time`() {
        var (p, clip) = TimelineEditor.insertAsset(empty(), main, video10s, 0)
        p = TimelineEditor.setSpeed(p, clip.id, 2f)
        assertEquals(5_000_000, p.durationUs)
        p = TimelineEditor.split(p, clip.id, 1_000_000)
        val clips = p.track(main)!!.sortedClips
        assertEquals(2_000_000, clips[0].sourceEndUs)
        assertEquals(2_000_000, clips[1].sourceStartUs)
        assertEquals(4_000_000, clips[1].durationUs)
    }

    @Test
    fun `split slices keyframes`() {
        var (p, clip) = TimelineEditor.insertAsset(empty(), main, video10s, 0)
        p = TimelineEditor.updateClip(p, clip.id) {
            it.copy(transform = Transform(scale = AnimatedFloat(1f, listOf(Keyframe(0, 1f), Keyframe(10_000_000, 2f)))))
        }
        p = TimelineEditor.split(p, clip.id, 5_000_000)
        val (left, right) = p.track(main)!!.sortedClips
        assertEquals(1.5f, left.transform.scale.valueAt(5_000_000), 0.05f)
        assertEquals(1.5f, right.transform.scale.valueAt(0), 0.05f)
        assertEquals(2f, right.transform.scale.valueAt(5_000_000), 0.001f)
    }

    @Test
    fun `free track overwrite trims underlying clips`() {
        var p = empty()
        p = TimelineEditor.insertAsset(p, music, song, 0).first
        p = TimelineEditor.insertAsset(p, music, video4s, 10_000_000).first
        val clips = p.track(music)!!.sortedClips
        assertEquals(3, clips.size)
        assertEquals(10_000_000, clips[0].endUs)
        assertEquals(14_000_000, clips[2].startUs)
        assertEquals(14_000_000, clips[2].sourceStartUs)
    }

    @Test
    fun `trim end cannot exceed media length but images can stretch`() {
        var (p, clip) = TimelineEditor.insertAsset(empty(), overlay, video4s, 0)
        p = TimelineEditor.trimEnd(p, clip.id, 20_000_000)
        assertEquals(4_000_000, p.findClip(clip.id)!!.second.endUs)
        val (p2, img) = TimelineEditor.insertAsset(p, overlay, image, 5_000_000)
        val p3 = TimelineEditor.trimEnd(p2, img.id, 20_000_000)
        assertEquals(20_000_000, p3.findClip(img.id)!!.second.endUs)
    }

    @Test
    fun `trim start reveals earlier media up to source start`() {
        var (p, clip) = TimelineEditor.insertAsset(empty(), overlay, video10s, 5_000_000)
        p = TimelineEditor.trimStart(p, clip.id, 7_000_000)
        var c = p.findClip(clip.id)!!.second
        assertEquals(7_000_000, c.startUs)
        assertEquals(2_000_000, c.sourceStartUs)
        p = TimelineEditor.trimStart(p, clip.id, 0)
        c = p.findClip(clip.id)!!.second
        assertEquals(5_000_000, c.startUs)
        assertEquals(0, c.sourceStartUs)
    }

    @Test
    fun `ripple delete shifts following clips`() {
        var p = empty()
        val (p1, a) = TimelineEditor.insertAsset(p, overlay, video4s, 0)
        val (p2, b) = TimelineEditor.insertAsset(p1, overlay, video4s, 6_000_000)
        p = TimelineEditor.deleteClips(p2, setOf(a.id), ripple = true)
        assertEquals(2_000_000, p.findClip(b.id)!!.second.startUs)
    }

    @Test
    fun `move clip between compatible tracks only`() {
        var (p, clip) = TimelineEditor.insertAsset(empty(), main, video10s, 0)
        p = TimelineEditor.moveClip(p, clip.id, overlay, 3_000_000)
        assertEquals(overlay, p.findClip(clip.id)!!.first.id)
        assertEquals(3_000_000, p.findClip(clip.id)!!.second.startUs)
        val (p2, s) = TimelineEditor.insertAsset(p, music, song, 0)
        val p3 = TimelineEditor.moveClip(p2, s.id, main, 0)
        assertEquals(music, p3.findClip(s.id)!!.first.id)
    }

    @Test
    fun `detach audio creates muted video and audio clip`() {
        val (p, clip) = TimelineEditor.insertAsset(empty(), main, video10s, 0)
        val (p2, audio) = TimelineEditor.detachAudio(p, clip.id)
        assertNotNull(audio)
        assertEquals(0f, p2.findClip(clip.id)!!.second.volume.value)
        assertEquals(TrackKind.AUDIO, p2.findClip(audio.id)!!.first.kind)
        assertEquals(10_000_000, p2.findClip(audio.id)!!.second.durationUs)
    }

    @Test
    fun `duplicate on magnetic track inserts right after`() {
        val (p, clip) = TimelineEditor.insertAsset(empty(), main, video4s, 0)
        val (p1, _) = TimelineEditor.insertAsset(p, main, video10s, 10_000_000)
        val (p2, dup) = TimelineEditor.duplicateClip(p1, clip.id)
        val clips = p2.track(main)!!.sortedClips
        assertEquals(listOf(clip.id, dup!!.id), clips.take(2).map { it.id })
        assertEquals(18_000_000, p2.durationUs)
    }

    @Test
    fun `auto cut on beats splits at every beat`() {
        val (p, _) = TimelineEditor.insertAsset(empty(), main, video10s, 0)
        val beats = (1..9).map { it * 1_000_000L }
        val cut = TimelineEditor.autoCutOnBeats(p, main, beats, everyN = 2)
        assertEquals(6, cut.track(main)!!.clips.size)
    }

    @Test
    fun `beat sync montage alternates assets on beat boundaries`() {
        val beats = (0..8).map { it * 500_000L }
        val p = TimelineEditor.beatSyncMontage(empty(), main, listOf(video10s, video4s), beats, everyN = 2)
        val clips = p.track(main)!!.sortedClips
        assertEquals(4, clips.size)
        assertEquals(listOf("a1", "a2", "a1", "a2"), clips.map { it.assetId })
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L), clips.map { it.startUs })
        // Second use of a1 continues where the first shot ended.
        assertEquals(1_000_000, clips[2].sourceStartUs)
    }

    @Test
    fun `text clips are placed on text tracks`() {
        val (p, clip) = TimelineEditor.insertText(empty(), "track-text-1", TextContent("Hello"), 2_000_000)
        assertEquals(5_000_000, p.findClip(clip.id)!!.second.endUs)
    }

    @Test
    fun `main track cannot be removed`() {
        val p = TimelineEditor.removeTrack(empty(), main)
        assertNotNull(p.track(main))
        val (p2, t) = TimelineEditor.addTrack(p, TrackKind.AUDIO)
        assertTrue(p2.tracks.indexOf(t) > p2.tracks.indexOfFirst { it.id == music })
        assertNull(TimelineEditor.removeTrack(p2, t.id).track(t.id))
    }
}
