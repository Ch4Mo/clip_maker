package com.clipmaker.app.media.render

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.clipmaker.core.audio.AudioEffectChain
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.MediaKind
import com.clipmaker.core.model.Project
import com.clipmaker.core.model.Track
import com.clipmaker.core.model.TrackKind
import com.clipmaker.core.render.RenderPlan
import com.clipmaker.core.render.Segment
import com.clipmaker.core.render.SequencePlan

/**
 * Builds the Media3 [Composition] of a project. The very same composition is used for the live
 * preview ([androidx.media3.transformer.CompositionPlayer]) and for the export (Transformer), so
 * what you see is exactly what you get.
 */
@UnstableApi
object CompositionFactory {

    /** Returns null when the timeline is empty. */
    fun build(
        project: Project,
        width: Int = project.settings.outputWidth,
        height: Int = project.settings.outputHeight,
        /** When set (export), frames are dropped to reach this output frame rate. */
        targetFrameRate: Int? = null,
    ): Composition? {
        val plan = RenderPlan.build(project)
        if (plan.durationUs <= 0) return null
        val fps = project.settings.frameRate

        // The compositor draws the first sequence on top: order visual tracks top-most first.
        val visualPlans = plan.video.reversed()
        val sequences = ArrayList<EditedMediaItemSequence>()
        val visibility = ArrayList<SequencePlan>()
        visualPlans.forEach { seqPlan ->
            val isMain = seqPlan.track.kind == TrackKind.VIDEO && seqPlan === plan.video.first()
            sequences += buildSequence(project, seqPlan, setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), width, height, fps, opaque = isMain)
            visibility += seqPlan
        }
        if (sequences.isEmpty()) {
            // Audio-only or titles-only project: render over a blank background.
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addGap(plan.durationUs).build()
        }
        plan.audio.forEach { seqPlan ->
            sequences += buildSequence(project, seqPlan, setOf(C.TRACK_TYPE_AUDIO), width, height, fps, opaque = false)
        }

        val compositionVideoEffects = buildList<Effect> {
            if (targetFrameRate != null) add(FrameDropEffect.createDefaultFrameDropEffect(targetFrameRate.toFloat()))
            if (plan.texts.isNotEmpty()) add(OverlayEffect(listOf(TextOverlayRenderer(plan.texts, width, height))))
        }
        val compositionEffects = if (compositionVideoEffects.isEmpty()) Effects.EMPTY else Effects(emptyList(), compositionVideoEffects)

        return Composition.Builder(sequences)
            .setEffects(compositionEffects)
            .apply {
                // A single video layer is rendered by SingleInputVideoGraph, which rejects any
                // non-default compositor settings.
                if (visibility.size > 1) setVideoCompositorSettings(LayerCompositorSettings(width, height, visibility))
                if (Build.VERSION.SDK_INT >= 29) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            }
            .build()
    }

    /** Whether [composition] stacks several video layers, which needs a multiple-input video graph. */
    fun isLayered(composition: Composition): Boolean =
        composition.sequences.count { C.TRACK_TYPE_VIDEO in it.trackTypes } > 1

    private fun buildSequence(
        project: Project,
        plan: SequencePlan,
        trackTypes: Set<Int>,
        width: Int,
        height: Int,
        fps: Int,
        opaque: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        val audioOnly = trackTypes == setOf(C.TRACK_TYPE_AUDIO)
        for (segment in plan.segments) {
            when (segment) {
                is Segment.Gap -> builder.addGap(segment.durationUs)
                is Segment.Media -> {
                    val item = buildItem(project, plan.track, segment.clip, audioOnly, width, height, fps, opaque)
                    if (item != null) builder.addItem(item) else builder.addGap(segment.durationUs)
                }
            }
        }
        return builder.build()
    }

    private fun buildItem(
        project: Project,
        track: Track,
        clip: Clip,
        audioOnly: Boolean,
        width: Int,
        height: Int,
        fps: Int,
        opaque: Boolean,
    ): EditedMediaItem? {
        val asset = project.asset(clip.assetId) ?: return null
        val mediaItem = MediaItem.Builder().setUri(asset.uri).setMediaId(clip.id)
        if (asset.kind == MediaKind.IMAGE) {
            if (audioOnly) return null
            mediaItem.setImageDurationMs((clip.durationUs / 1000).coerceAtLeast(1))
        } else {
            mediaItem.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceStartUs.coerceAtLeast(0))
                    .setEndPositionUs(clip.sourceEndUs.coerceAtMost(asset.durationUs))
                    .build(),
            )
        }

        val audioProcessors = ArrayList<AudioProcessor>()
        val trackGain = if (track.muted) 0f else track.volume
        if (asset.hasAudio && asset.kind != MediaKind.IMAGE) {
            val semitones = AudioEffectChain.pitchSemitones(clip.audioEffects)
            if (semitones != 0f) {
                audioProcessors += SonicAudioProcessor().apply { setPitch(AudioEffectChain.pitchFactor(semitones)) }
            }
            audioProcessors += ClipAudioProcessor(clip, trackGain, track.audioEffects)
        }
        val videoEffects = if (audioOnly || asset.kind == MediaKind.AUDIO) emptyList()
        else VisualEffects.forClip(clip, width, height, opaque, project.beatGrid)

        val builder = EditedMediaItem.Builder(mediaItem.build())
            .setEffects(Effects(audioProcessors, videoEffects))
        if (asset.kind == MediaKind.IMAGE) {
            builder.setFrameRate(fps)
        } else {
            builder.setDurationUs(asset.durationUs)
            if (audioOnly && asset.hasVideo) builder.setRemoveVideo(true)
            if (clip.speed != 1f) builder.setSpeed(ConstantSpeed(clip.speed))
        }
        return builder.build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /**
     * Full-frame layering: every visual track is already rendered at output size with its own
     * transform, so layers are stacked as-is. Tracks with no clip at a given time are hidden, which
     * makes the blank frames of gaps transparent.
     */
    private class LayerCompositorSettings(
        private val width: Int,
        private val height: Int,
        private val layers: List<SequencePlan>,
    ) : VideoCompositorSettings {
        private val visible = StaticOverlaySettings.Builder().build()
        private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val layer = layers.getOrNull(inputId) ?: return visible
            // The bottom (main) layer is always opaque.
            if (inputId == layers.lastIndex && layer.track.kind == TrackKind.VIDEO) return visible
            val active = layer.segments.any { it is Segment.Media && presentationTimeUs >= it.clip.startUs && presentationTimeUs < it.clip.endUs }
            return if (active) visible else hidden
        }
    }
}
