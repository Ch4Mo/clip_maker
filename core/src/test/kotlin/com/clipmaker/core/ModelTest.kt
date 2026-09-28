package com.clipmaker.core

import com.clipmaker.core.edit.History
import com.clipmaker.core.edit.SnapEngine
import com.clipmaker.core.edit.SnapSource
import com.clipmaker.core.io.ProjectJson
import com.clipmaker.core.model.AnimatedFloat
import com.clipmaker.core.model.AspectRatio
import com.clipmaker.core.model.AudioEffect
import com.clipmaker.core.model.AudioEffectType
import com.clipmaker.core.model.BeatGrid
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.ColorLookId
import com.clipmaker.core.model.Easing
import com.clipmaker.core.model.ExportPreset
import com.clipmaker.core.model.Keyframe
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.ProjectSettings
import com.clipmaker.core.model.TextAnimation
import com.clipmaker.core.model.TextAnimator
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.Transition
import com.clipmaker.core.model.TransitionType
import com.clipmaker.core.model.VideoEffect
import com.clipmaker.core.model.VideoEffectType
import com.clipmaker.core.render.RenderPlan
import com.clipmaker.core.render.Segment
import com.clipmaker.core.color.ColorLooks
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun `keyframes interpolate with easing and clamp outside`() {
        val a = AnimatedFloat(0f, listOf(Keyframe(0, 0f, Easing.LINEAR), Keyframe(1_000_000, 10f)))
        assertEquals(0f, a.valueAt(-5))
        assertEquals(5f, a.valueAt(500_000), 0.001f)
        assertEquals(10f, a.valueAt(2_000_000))
        val eased = AnimatedFloat(0f, listOf(Keyframe(0, 0f, Easing.EASE_IN), Keyframe(1_000_000, 10f)))
        assertTrue(eased.valueAt(500_000) < 5f)
    }

    @Test
    fun `set on constant changes value, on animated adds keyframe`() {
        assertEquals(3f, AnimatedFloat(1f).set(500, 3f).value)
        val animated = AnimatedFloat(1f, listOf(Keyframe(0, 1f))).set(1_000_000, 5f)
        assertEquals(2, animated.keyframes.size)
    }

    @Test
    fun `clip gain applies fades`() {
        val c = Clip(id = "c", startUs = 0, sourceEndUs = 10_000_000, fadeInUs = 1_000_000, fadeOutUs = 2_000_000)
        assertEquals(0f, c.gainAt(0))
        assertEquals(0.5f, c.gainAt(500_000), 0.001f)
        assertEquals(1f, c.gainAt(5_000_000))
        assertEquals(0.5f, c.gainAt(9_000_000), 0.001f)
    }

    @Test
    fun `output size respects ratio and even dimensions`() {
        assertEquals(1920 to 1080, ProjectSettings(AspectRatio.LANDSCAPE_16_9).outputSize())
        assertEquals(1080 to 1920, ProjectSettings(AspectRatio.PORTRAIT_9_16).outputSize())
        assertEquals(1080 to 1350, ProjectSettings(AspectRatio.PORTRAIT_4_5).outputSize())
        val (w, h) = ProjectSettings(AspectRatio.CINEMA_239).outputSize()
        assertEquals(0, w % 2); assertEquals(1080, h)
        val cfg = ExportPreset.ALL.first { it.id == "reels" }.resolve(ProjectSettings())
        assertEquals(1080, cfg.width); assertEquals(1920, cfg.height)
    }

    @Test
    fun `project json round trip`() {
        val p = Project.create("p", "Clip", 1).copy(
            beatGrid = BeatGrid(120f, listOf(0, 500_000)),
            tracks = Project.create("p", "Clip", 1).tracks.map { t ->
                if (t.id != "track-video-main") t else t.copy(
                    clips = listOf(
                        Clip(
                            id = "c", assetId = "a", startUs = 0, sourceEndUs = 1_000_000,
                            videoEffects = listOf(VideoEffect(VideoEffectType.LOOK, look = ColorLookId.TEAL_ORANGE)),
                            audioEffects = listOf(AudioEffect(AudioEffectType.REVERB)),
                            transitionIn = Transition(TransitionType.GLITCH),
                            text = TextContent("Yo"),
                        ),
                    ),
                )
            },
        )
        val decoded = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `json decoding tolerates unknown keys`() {
        val json = ProjectJson.encode(Project.create("p", "X", 0)).replaceFirst("{", "{\"futureField\":42,")
        assertEquals("X", ProjectJson.decode(json).name)
    }

    @Test
    fun `history undo redo and coalescing`() {
        val h = History(0)
        h.push(1, "a"); h.push(2, "b", coalesce = "drag"); h.push(3, "b", coalesce = "drag")
        h.commit()
        h.push(4, "c")
        assertEquals(3, h.undo()); assertEquals(1, h.undo()); assertEquals(0, h.undo())
        assertFalse(h.canUndo)
        assertEquals(1, h.redo())
        h.push(9)
        assertFalse(h.canRedo)
    }

    @Test
    fun `snapping prefers nearest point within threshold`() {
        val p = Project.create("p", "X", 0).copy(beatGrid = BeatGrid(120f, listOf(1_000_000, 1_500_000)))
        val pts = SnapEngine.collectPoints(p, playheadUs = 3_000_000)
        val r = SnapEngine.snap(1_040_000, pts, 100_000)
        assertEquals(1_000_000, r.timeUs); assertEquals(SnapSource.BEAT, r.snappedTo!!.source)
        assertEquals(2_000_000, SnapEngine.snap(2_000_000, pts, 100_000).timeUs)
        val range = SnapEngine.snapRange(1_000_000, 1_950_000, pts, 100_000)
        assertEquals(1_000_000, range.timeUs)
    }

    @Test
    fun `render plan fills gaps and pads to project duration`() {
        val base = Project.create("p", "X", 0)
        val p = base.copy(tracks = base.tracks.map { t ->
            when (t.id) {
                "track-overlay-1" -> t.copy(clips = listOf(Clip(id = "o", assetId = "a", startUs = 2_000_000, sourceEndUs = 1_000_000)))
                "track-audio-music" -> t.copy(clips = listOf(Clip(id = "m", assetId = "s", startUs = 0, sourceEndUs = 5_000_000)))
                else -> t
            }
        })
        val plan = RenderPlan.build(p)
        assertEquals(5_000_000, plan.durationUs)
        val overlay = plan.video.single()
        assertEquals(listOf(2_000_000L, 1_000_000L, 2_000_000L), overlay.segments.map { it.durationUs })
        assertTrue(overlay.segments[0] is Segment.Gap && overlay.segments[1] is Segment.Media)
        assertEquals(5_000_000, plan.audio.single().durationUs)
    }

    @Test
    fun `text animation fades in and out`() {
        val text = TextContent("Hi", animationIn = TextAnimation.FADE, animationOut = TextAnimation.FADE, animationDurationUs = 1_000_000)
        assertEquals(0f, TextAnimator.stateAt(text, 4_000_000, 0).alpha)
        assertEquals(1f, TextAnimator.stateAt(text, 4_000_000, 2_000_000).alpha)
        assertEquals(0.5f, TextAnimator.stateAt(text, 4_000_000, 3_500_000).alpha, 0.01f)
        val typed = text.copy(animationIn = TextAnimation.TYPEWRITER, text = "abcd")
        assertEquals(2, TextAnimator.stateAt(typed, 4_000_000, 500_000).visibleChars)
    }

    @Test
    fun `lut identity at zero intensity and cube layout`() {
        val lut = ColorLooks.buildLut(ColorLookId.NOIR, size = 5, intensity = 0f)
        assertEquals(5, lut.size)
        assertEquals(0xFFFF0000.toInt(), lut[4][0][0])
        assertEquals(0xFF0000FF.toInt(), lut[0][0][4])
        val noir = ColorLooks.buildLut(ColorLookId.NOIR, size = 5)
        val c = noir[4][0][0]
        assertEquals((c shr 16) and 0xFF, (c shr 8) and 0xFF) // grey
    }
}
