package com.clipmaker.app.media.render

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

/** Uniform setter handed to effects each frame; silently ignores uniforms optimised out by GLSL. */
@UnstableApi
class Uniforms internal constructor(private val program: GlProgram) {
    private val present = HashMap<String, Boolean>()

    /** Width / height of the frame being processed. */
    var aspect: Float = 1f
        internal set

    private fun has(name: String) = present.getOrPut(name) { program.getUniformLocation(name) != -1 }

    fun float(name: String, value: Float) {
        if (has(name)) program.setFloatUniform(name, value)
    }

    fun int(name: String, value: Int) {
        if (has(name)) program.setIntUniform(name, value)
    }

    fun vec2(name: String, x: Float, y: Float) {
        if (has(name)) program.setFloatsUniform(name, floatArrayOf(x, y))
    }

    fun vec3(name: String, x: Float, y: Float, z: Float) {
        if (has(name)) program.setFloatsUniform(name, floatArrayOf(x, y, z))
    }
}

/**
 * A full-frame GLSL fragment effect. [bind] is invoked for every frame with the presentation time
 * (composition time, in microseconds) and sets the uniforms.
 *
 * Every shader receives `uTexSampler`, `vTexSamplingCoord`, and `uResolution` (output size).
 */
@UnstableApi
class ShaderEffect(
    private val fragmentShader: String,
    private val bind: Uniforms.(presentationTimeUs: Long) -> Unit,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(useHdr, fragmentShader, bind)

    private class Program(
        useHdr: Boolean,
        fragmentShader: String,
        private val bind: Uniforms.(Long) -> Unit,
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
        private val glProgram: GlProgram = try {
            GlProgram(VERTEX_SHADER, fragmentShader)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private val uniforms = Uniforms(glProgram)
        private var width = 1
        private var height = 1

        init {
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                uniforms.vec2("uResolution", width.toFloat(), height.toFloat())
                uniforms.aspect = width.toFloat() / height
                uniforms.bind(presentationTimeUs)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
    }
}
