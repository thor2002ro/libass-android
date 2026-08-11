package io.github.peerless2012.ass.media.render

import android.opengl.GLES20
import android.opengl.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.executor.AssAtlasExecutor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 texture overlay backed by a persistent, batched libass mask atlas.
 *
 * The first pass composites subtitle fragments into a premultiplied RGBA
 * framebuffer. The second pass converts that framebuffer to straight alpha,
 * which is the representation expected by Media3's [TextureOverlay] pipeline.
 */
@OptIn(UnstableApi::class)
class AssTexOverlay(
    private val handler: AssHandler,
    private val render: AssRender,
) : TextureOverlay() {
    private var outputTextureId = 0
    private var outputFboId = 0
    private var premultipliedTextureId = 0
    private var premultipliedFboId = 0
    private var fullscreenBufferId = 0

    private var textureSize = Size.ZERO
    private var renderSize = Size.ZERO
    private var vertexTransformMatrix = GlUtil.create4x4IdentityMatrix()

    private var atlasRenderer: AssAtlasGlRenderer? = null
    private var executor: AssAtlasExecutor? = null
    private var unpremultiplyProgram: GlProgram? = null
    private var unpremultiplyPositionLocation = -1
    private var unpremultiplyTexCoordLocation = -1
    private var unpremultiplyTextureLocation = -1
    private val renderClock = io.github.peerless2012.ass.media.SubtitleRenderClock(
        handler.config.maxSubtitleFps,
    )
    private var configured = false

    override fun getTextureId(presentationTimeUs: Long): Int {
        if (!configured) return outputTextureId
        val currentExecutor = executor ?: return outputTextureId
        val currentAtlasRenderer = atlasRenderer ?: return outputTextureId
        val timeUs = handler.videoTime.takeIf { it >= 0 } ?: presentationTimeUs
        if (!renderClock.shouldRender(timeUs)) return outputTextureId
        val frame = currentExecutor.renderFrame(timeUs)
        if (frame == null || frame.changed == AssAtlasFrame.CHANGE_NONE) {
            return outputTextureId
        }

        val state = GlStateSnapshot.capture()
        try {
            prepareOffscreenDrawState()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, premultipliedFboId)
            val result = currentAtlasRenderer.render(
                frame = frame,
                sourceWidth = renderSize.width,
                sourceHeight = renderSize.height,
                targetWidth = renderSize.width,
                targetHeight = renderSize.height,
            )
            if (result == AssAtlasGlRenderer.DrawResult.UNCHANGED) {
                return outputTextureId
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFboId)
            GLES20.glViewport(0, 0, renderSize.width, renderSize.height)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (result == AssAtlasGlRenderer.DrawResult.REDRAWN_CONTENT) {
                drawUnpremultiplyPass()
            }
            GlUtil.checkGlError()
        } finally {
            state.restore()
        }
        return outputTextureId
    }

    override fun getTextureSize(presentationTimeUs: Long): Size = textureSize

    override fun getVertexTransformation(presentationTimeUs: Long): FloatArray =
        vertexTransformMatrix

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (configured) releaseGlResources()

        val maxTextureSize = queryMaxTextureSize()
        renderSize = fitWithinTextureLimit(
            handler.computeRenderSize(videoSize.width, videoSize.height),
            maxTextureSize,
        )
        textureSize = renderSize
        render.setFrameSize(renderSize.width, renderSize.height)

        val configuredAtlasLimit = handler.config.maxAtlasTextureSize
        val maxAtlasSize = if (configuredAtlasLimit > 0) {
            minOf(maxTextureSize, configuredAtlasLimit)
        } else {
            maxTextureSize
        }

        try {
            outputTextureId = GlUtil.createTexture(renderSize.width, renderSize.height, false)
            outputFboId = GlUtil.createFboForTexture(outputTextureId)
            premultipliedTextureId = GlUtil.createTexture(renderSize.width, renderSize.height, false)
            premultipliedFboId = GlUtil.createFboForTexture(premultipliedTextureId)

            atlasRenderer = AssAtlasGlRenderer().also { it.initialize() }
            executor = AssAtlasExecutor(
                frameRenderer = { timeMs -> handler.renderAtlasFrame(timeMs, maxAtlasSize) },
            )
            unpremultiplyProgram = GlProgram(
                FULLSCREEN_VERTEX_SHADER,
                UNPREMULTIPLY_FRAGMENT_SHADER,
            ).also { program ->
                program.use()
                unpremultiplyPositionLocation =
                    program.getAttributeArrayLocationAndEnable("a_Position")
                unpremultiplyTexCoordLocation =
                    program.getAttributeArrayLocationAndEnable("a_TexCoord")
                unpremultiplyTextureLocation = program.getUniformLocation("u_Texture")
                GLES20.glDisableVertexAttribArray(unpremultiplyPositionLocation)
                GLES20.glDisableVertexAttribArray(unpremultiplyTexCoordLocation)
            }
            fullscreenBufferId = createFullscreenBuffer()
            renderClock.reset()

            vertexTransformMatrix = GlUtil.create4x4IdentityMatrix()
            if (renderSize.width != videoSize.width || renderSize.height != videoSize.height) {
                Matrix.scaleM(
                    vertexTransformMatrix,
                    0,
                    videoSize.width.toFloat() / renderSize.width,
                    videoSize.height.toFloat() / renderSize.height,
                    1f,
                )
            }

            clearTexture(outputFboId)
            clearTexture(premultipliedFboId)
            configured = true
        } catch (error: Exception) {
            releaseGlResources()
            throw error
        }
    }

    override fun release() {
        releaseGlResources()
        super.release()
    }

    private fun drawUnpremultiplyPass() {
        val program = unpremultiplyProgram ?: return
        program.use()
        val position = unpremultiplyPositionLocation
        val texCoord = unpremultiplyTexCoordLocation
        val texture = unpremultiplyTextureLocation
        if (position < 0 || texCoord < 0 || texture < 0) return

        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullscreenBufferId)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            FULLSCREEN_VERTEX_STRIDE_BYTES,
            0,
        )
        GLES20.glVertexAttribPointer(
            texCoord,
            2,
            GLES20.GL_FLOAT,
            false,
            FULLSCREEN_VERTEX_STRIDE_BYTES,
            2 * BYTES_PER_FLOAT,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, premultipliedTextureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun createFullscreenBuffer(): Int {
        // FBO textures use OpenGL's bottom-left origin. Sampling v=1 at the
        // top of the destination preserves the first pass orientation.
        val data = floatArrayOf(
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
        )
        val buffer = ByteBuffer.allocateDirect(data.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
        buffer.position(0)

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            data.size * BYTES_PER_FLOAT,
            buffer,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GlUtil.checkGlError()
        return ids[0]
    }

    private fun clearTexture(fboId: Int) {
        val state = GlStateSnapshot.capture()
        try {
            prepareOffscreenDrawState()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, renderSize.width, renderSize.height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        } finally {
            state.restore()
        }
    }

    private fun prepareOffscreenDrawState() {
        // Media3 owns the surrounding context. Isolate the subtitle passes from
        // state left by an upstream effect, especially scissor and color masks
        // that could otherwise leave stale pixels in the persistent FBOs.
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glBlendEquationSeparate(GLES20.GL_FUNC_ADD, GLES20.GL_FUNC_ADD)
    }

    private fun releaseGlResources() {
        executor?.shutdown()
        executor = null
        atlasRenderer?.release()
        atlasRenderer = null
        unpremultiplyProgram?.delete()
        unpremultiplyProgram = null
        unpremultiplyPositionLocation = -1
        unpremultiplyTexCoordLocation = -1
        unpremultiplyTextureLocation = -1

        if (outputFboId != 0) GlUtil.deleteFbo(outputFboId)
        if (premultipliedFboId != 0) GlUtil.deleteFbo(premultipliedFboId)
        if (outputTextureId != 0) GlUtil.deleteTexture(outputTextureId)
        if (premultipliedTextureId != 0) GlUtil.deleteTexture(premultipliedTextureId)
        if (fullscreenBufferId != 0) GlUtil.deleteBuffer(fullscreenBufferId)

        outputFboId = 0
        premultipliedFboId = 0
        outputTextureId = 0
        premultipliedTextureId = 0
        fullscreenBufferId = 0
        textureSize = Size.ZERO
        renderSize = Size.ZERO
        vertexTransformMatrix = GlUtil.create4x4IdentityMatrix()
        renderClock.reset()
        configured = false
    }

    private fun queryMaxTextureSize(): Int {
        val value = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
        return value[0].takeIf { it > 0 } ?: 2_048
    }

    private fun fitWithinTextureLimit(size: Size, limit: Int): Size {
        if (size.width <= 0 || size.height <= 0) return Size(2, 2)
        if (limit <= 0) return size
        if (size.width <= limit && size.height <= limit) return size
        val scale = minOf(limit.toFloat() / size.width, limit.toFloat() / size.height)
        val width = ((size.width * scale).toInt() and 0x7FFF_FFFE).coerceAtLeast(2)
        val height = ((size.height * scale).toInt() and 0x7FFF_FFFE).coerceAtLeast(2)
        return Size(width, height)
    }

    private class GlStateSnapshot private constructor(
        private val framebuffer: Int,
        private val viewport: IntArray,
        private val program: Int,
        private val arrayBuffer: Int,
        private val activeTexture: Int,
        private val texture0Binding: Int,
        private val unpackAlignment: Int,
        private val blendEnabled: Boolean,
        private val blendSrcRgb: Int,
        private val blendDstRgb: Int,
        private val blendSrcAlpha: Int,
        private val blendDstAlpha: Int,
        private val blendEquationRgb: Int,
        private val blendEquationAlpha: Int,
        private val scissorEnabled: Boolean,
        private val scissorBox: IntArray,
        private val depthTestEnabled: Boolean,
        private val stencilTestEnabled: Boolean,
        private val cullFaceEnabled: Boolean,
        private val colorMask: BooleanArray,
        private val clearColor: FloatArray,
    ) {
        fun restore() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GLES20.glUseProgram(program)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arrayBuffer)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, unpackAlignment)
            GLES20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
            GLES20.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            setEnabled(GLES20.GL_BLEND, blendEnabled)
            GLES20.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            setEnabled(GLES20.GL_SCISSOR_TEST, scissorEnabled)
            setEnabled(GLES20.GL_DEPTH_TEST, depthTestEnabled)
            setEnabled(GLES20.GL_STENCIL_TEST, stencilTestEnabled)
            setEnabled(GLES20.GL_CULL_FACE, cullFaceEnabled)
            GLES20.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
            GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture0Binding)
            GLES20.glActiveTexture(activeTexture)
        }

        private fun setEnabled(capability: Int, enabled: Boolean) {
            if (enabled) GLES20.glEnable(capability) else GLES20.glDisable(capability)
        }

        companion object {
            fun capture(): GlStateSnapshot {
                val framebuffer = intState(GLES20.GL_FRAMEBUFFER_BINDING)
                val viewport = IntArray(4).also {
                    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0)
                }
                val program = intState(GLES20.GL_CURRENT_PROGRAM)
                val arrayBuffer = intState(GLES20.GL_ARRAY_BUFFER_BINDING)
                val activeTexture = intState(GLES20.GL_ACTIVE_TEXTURE)
                val unpackAlignment = intState(GLES20.GL_UNPACK_ALIGNMENT)
                val blendSrcRgb = intState(GLES20.GL_BLEND_SRC_RGB)
                val blendDstRgb = intState(GLES20.GL_BLEND_DST_RGB)
                val blendSrcAlpha = intState(GLES20.GL_BLEND_SRC_ALPHA)
                val blendDstAlpha = intState(GLES20.GL_BLEND_DST_ALPHA)
                val blendEquationRgb = intState(GLES20.GL_BLEND_EQUATION_RGB)
                val blendEquationAlpha = intState(GLES20.GL_BLEND_EQUATION_ALPHA)
                val scissorBox = IntArray(4).also {
                    GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, it, 0)
                }
                val colorMask = BooleanArray(4).also {
                    GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, it, 0)
                }
                val clearColor = FloatArray(4).also {
                    GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, it, 0)
                }

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                val texture0Binding = intState(GLES20.GL_TEXTURE_BINDING_2D)
                GLES20.glActiveTexture(activeTexture)

                return GlStateSnapshot(
                    framebuffer = framebuffer,
                    viewport = viewport,
                    program = program,
                    arrayBuffer = arrayBuffer,
                    activeTexture = activeTexture,
                    texture0Binding = texture0Binding,
                    unpackAlignment = unpackAlignment,
                    blendEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND),
                    blendSrcRgb = blendSrcRgb,
                    blendDstRgb = blendDstRgb,
                    blendSrcAlpha = blendSrcAlpha,
                    blendDstAlpha = blendDstAlpha,
                    blendEquationRgb = blendEquationRgb,
                    blendEquationAlpha = blendEquationAlpha,
                    scissorEnabled = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST),
                    scissorBox = scissorBox,
                    depthTestEnabled = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST),
                    stencilTestEnabled = GLES20.glIsEnabled(GLES20.GL_STENCIL_TEST),
                    cullFaceEnabled = GLES20.glIsEnabled(GLES20.GL_CULL_FACE),
                    colorMask = colorMask,
                    clearColor = clearColor,
                )
            }

            private fun intState(name: Int): Int = IntArray(1).also {
                GLES20.glGetIntegerv(name, it, 0)
            }[0]
        }
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val FULLSCREEN_VERTEX_STRIDE_BYTES = 4 * BYTES_PER_FLOAT

        val FULLSCREEN_VERTEX_SHADER = """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val UNPREMULTIPLY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_Texture;
            void main() {
                vec4 premultiplied = texture2D(u_Texture, v_TexCoord);
                if (premultiplied.a <= 0.00001) {
                    gl_FragColor = vec4(0.0);
                } else {
                    gl_FragColor = vec4(
                        premultiplied.rgb / premultiplied.a,
                        premultiplied.a
                    );
                }
            }
        """.trimIndent()
    }
}
