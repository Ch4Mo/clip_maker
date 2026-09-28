package com.clipmaker.app.media.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.TextAlign
import com.clipmaker.core.model.TextAnimator
import com.clipmaker.core.model.TextContent
import com.clipmaker.core.model.TextFont

/**
 * Draws every title of the timeline into a full-frame bitmap overlay. Two bitmaps are alternated so
 * that Media3 re-uploads the texture only when the rendered content actually changes.
 */
@UnstableApi
class TextOverlayRenderer(
    private val texts: List<Clip>,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {
    private val buffers = arrayOf(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
    )
    private var current = 0
    private var lastKey: String? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val active = texts.filter { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        val states = active.map { clip ->
            val local = presentationTimeUs - clip.startUs
            clip to TextAnimator.stateAt(clip.text!!, clip.durationUs, local)
        }
        val key = states.joinToString("|") { (c, s) ->
            val t = c.transform.sample(presentationTimeUs - c.startUs)
            "${c.id}:${(s.alpha * 100).toInt()}:${(s.scale * 100).toInt()}:${(s.offsetY * 500).toInt()}:${s.visibleChars}:" +
                "${(s.jitterX * 500).toInt()}:${(t.x * 500).toInt()}:${(t.y * 500).toInt()}:${(t.scale * 100).toInt()}:" +
                "${t.rotationDeg.toInt()}:${(t.opacity * 100).toInt()}"
        }
        if (key == lastKey) return buffers[current]
        lastKey = key
        current = 1 - current
        val bitmap = buffers[current]
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        for ((clip, state) in states) {
            val t = clip.transform.sample(presentationTimeUs - clip.startUs)
            draw(canvas, clip.text!!, state, t.x, t.y, t.scale, t.rotationDeg, t.opacity)
        }
        return bitmap
    }

    private fun draw(
        canvas: Canvas,
        text: TextContent,
        state: com.clipmaker.core.model.TextFrameState,
        x: Float,
        y: Float,
        scale: Float,
        rotation: Float,
        opacity: Float,
    ) {
        val alpha = (state.alpha * opacity).coerceIn(0f, 1f)
        if (alpha <= 0.001f) return
        val content = if (state.visibleChars >= 0) text.text.take(state.visibleChars) else text.text
        if (content.isEmpty()) return
        val paint = paintFor(text, height * text.size)
        paint.alpha = (alpha * 255).toInt()
        val lines = content.split('\n')
        val lineHeight = paint.fontSpacing
        val blockWidth = lines.maxOf { paint.measureText(it) }
        val blockHeight = lineHeight * lines.size

        canvas.save()
        val cx = width / 2f + x * width + state.jitterX * width
        val cy = height / 2f - (y + state.offsetY) * height
        canvas.translate(cx, cy)
        canvas.rotate(-rotation)
        canvas.scale(scale * state.scale, scale * state.scale)
        if (state.blur > 0.01f) paint.maskFilter = BlurMaskFilter(state.blur * height * 0.02f + 0.1f, BlurMaskFilter.Blur.NORMAL)

        val bg = text.backgroundColor.toInt()
        if (Color.alpha(bg) > 0) {
            val pad = lineHeight * 0.3f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bg
                this.alpha = (Color.alpha(bg) * alpha).toInt()
            }
            canvas.drawRoundRect(RectF(-blockWidth / 2 - pad, -blockHeight / 2 - pad / 2, blockWidth / 2 + pad, blockHeight / 2 + pad / 2), pad, pad, bgPaint)
        }
        lines.forEachIndexed { i, line ->
            val w = paint.measureText(line)
            val lx = when (text.align) {
                TextAlign.LEFT -> -blockWidth / 2
                TextAlign.CENTER -> -w / 2
                TextAlign.RIGHT -> blockWidth / 2 - w
            }
            val baseline = -blockHeight / 2 + i * lineHeight - paint.ascent()
            if (text.strokeWidth > 0f) {
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = text.strokeWidth * paint.textSize * 0.15f
                    strokeJoin = Paint.Join.ROUND
                    color = text.strokeColor.toInt()
                    this.alpha = (alpha * 255).toInt()
                    clearShadowLayer()
                }
                canvas.drawText(line, lx, baseline, stroke)
            }
            canvas.drawText(line, lx, baseline, paint)
        }
        canvas.restore()
    }

    private fun paintFor(text: TextContent, sizePx: Float): Paint {
        val family = when (text.font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
            TextFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            TextFont.DISPLAY -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            TextFont.HANDWRITING -> Typeface.create("cursive", Typeface.NORMAL)
        }
        val style = when {
            text.bold && text.italic -> Typeface.BOLD_ITALIC
            text.bold -> Typeface.BOLD
            text.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(family, style)
            textSize = sizePx
            color = text.color.toInt()
            letterSpacing = text.letterSpacing
            if (text.shadow) setShadowLayer(sizePx * 0.08f, 0f, sizePx * 0.04f, 0x99000000.toInt())
            xfermode = null
        }
    }

    override fun release() {
        super.release()
        buffers.forEach { it.recycle() }
    }
}
