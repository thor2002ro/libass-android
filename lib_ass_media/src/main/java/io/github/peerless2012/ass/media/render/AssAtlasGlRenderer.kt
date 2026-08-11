package io.github.peerless2012.ass.media.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssAtlasFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws libass alpha-atlas pages into the currently bound framebuffer. */
@OptIn(UnstableApi::class)
internal class AssAtlasGlRenderer {
    enum class DrawResult {
        UNCHANGED,
        REDRAWN_EMPTY,
        REDRAWN_CONTENT,
    }

    private val geometry = AssAtlasVertexBuffer()
    private val textureIds = mutableListOf<Int>()
    private var textureWidths = IntArray(0)
    private var textureHeights = IntArray(0)

    private var program: GlProgram? = null
    private var vertexBufferId = 0
    private var gpuBufferCapacityBytes = 0
    private var floatBuffer: FloatBuffer? = null
    private var floatBufferCapacity = 0
    private var uploadBuffer: ByteBuffer? = null
    private var uploadBufferCapacity = 0

    private var positionLocation = -1
    private var texCoordLocation = -1
    private var colorLocation = -1
    private var textureLocation = -1
    private var initialized = false
    private var hasContent = false
    private var isGles3 = false

    fun initialize() {
        if (initialized) return
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        isGles3 = version.contains("OpenGL ES 3")

        val fragmentShader = if (isGles3) {
            FRAGMENT_SHADER_RED
        } else {
            FRAGMENT_SHADER_ALPHA
        }
        try {
            program = GlProgram(VERTEX_SHADER, fragmentShader).also { glProgram ->
                glProgram.use()
                positionLocation = glProgram.getAttributeArrayLocationAndEnable("a_Position")
                texCoordLocation = glProgram.getAttributeArrayLocationAndEnable("a_TexCoord")
                colorLocation = glProgram.getAttributeArrayLocationAndEnable("a_Color")
                textureLocation = glProgram.getUniformLocation("u_Atlas")
                GLES20.glDisableVertexAttribArray(positionLocation)
                GLES20.glDisableVertexAttribArray(texCoordLocation)
                GLES20.glDisableVertexAttribArray(colorLocation)
            }
            val buffers = IntArray(1)
            GLES20.glGenBuffers(1, buffers, 0)
            vertexBufferId = buffers[0]
            check(vertexBufferId != 0) { "Unable to create subtitle vertex buffer" }
            GlUtil.checkGlError()
            initialized = true
        } catch (error: Exception) {
            if (vertexBufferId != 0) {
                GlUtil.deleteBuffer(vertexBufferId)
                vertexBufferId = 0
            }
            program?.delete()
            program = null
            throw error
        }
    }

    fun render(
        frame: AssAtlasFrame?,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        forceRedraw: Boolean = false,
    ): DrawResult {
        check(initialized) { "AssAtlasGlRenderer.initialize() must be called first" }
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return DrawResult.UNCHANGED
        }

        if (frame == null && !forceRedraw) return DrawResult.UNCHANGED
        if (frame?.changed == AssAtlasFrame.CHANGE_NONE && !forceRedraw) {
            return DrawResult.UNCHANGED
        }

        if (frame != null && frame.changed != AssAtlasFrame.CHANGE_NONE) {
            if (!geometry.isValid(frame, sourceWidth, sourceHeight)) {
                Log.w(TAG, "Ignoring malformed libass atlas geometry")
                return DrawResult.UNCHANGED
            }
            when (frame.changed) {
                AssAtlasFrame.CHANGE_CONTENT -> {
                    if (!uploadPages(frame)) return DrawResult.UNCHANGED
                }

                AssAtlasFrame.CHANGE_POSITION -> {
                    // An empty position-only frame means the subtitle moved fully
                    // out of view. It can clear the target while keeping the old
                    // atlas available for a later position-only frame.
                    if (frame.quads.isNotEmpty() && !pageLayoutMatches(frame)) {
                        Log.w(TAG, "Ignoring position-only frame with a changed atlas layout")
                        return DrawResult.UNCHANGED
                    }
                }

                else -> {
                    Log.w(TAG, "Ignoring unknown libass change state ${frame.changed}")
                    return DrawResult.UNCHANGED
                }
            }

            check(geometry.update(frame, sourceWidth, sourceHeight))
            uploadGeometry()
            hasContent = geometry.vertexCount > 0
        }

        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!hasContent || geometry.vertexCount == 0) {
            return DrawResult.REDRAWN_EMPTY
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        // Shader output is premultiplied; this keeps both RGB and alpha correct.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val currentProgram = program ?: return DrawResult.UNCHANGED
        currentProgram.use()

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glEnableVertexAttribArray(colorLocation)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            AssAtlasVertexBuffer.VERTEX_STRIDE_BYTES,
            AssAtlasVertexBuffer.POSITION_OFFSET_BYTES,
        )
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            AssAtlasVertexBuffer.VERTEX_STRIDE_BYTES,
            AssAtlasVertexBuffer.TEX_COORD_OFFSET_BYTES,
        )
        GLES20.glVertexAttribPointer(
            colorLocation,
            4,
            GLES20.GL_FLOAT,
            false,
            AssAtlasVertexBuffer.VERTEX_STRIDE_BYTES,
            AssAtlasVertexBuffer.COLOR_OFFSET_BYTES,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(textureLocation, 0)
        repeat(geometry.runCount) { run ->
            val page = geometry.runPages[run]
            if (page !in textureIds.indices) return@repeat
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[page])
            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLES,
                geometry.runFirstVertices[run],
                geometry.runVertexCounts[run],
            )
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glDisableVertexAttribArray(colorLocation)
        GlUtil.checkGlError()
        return DrawResult.REDRAWN_CONTENT
    }

    fun clearCachedContent() {
        geometry.clear()
        hasContent = false
        deleteTextures()
    }

    fun release() {
        if (!initialized) return
        deleteTextures()
        if (vertexBufferId != 0) {
            GlUtil.deleteBuffer(vertexBufferId)
            vertexBufferId = 0
        }
        program?.delete()
        program = null
        geometry.clear()
        hasContent = false
        gpuBufferCapacityBytes = 0
        floatBuffer = null
        floatBufferCapacity = 0
        uploadBuffer = null
        uploadBufferCapacity = 0
        positionLocation = -1
        texCoordLocation = -1
        colorLocation = -1
        textureLocation = -1
        initialized = false
    }

    private fun uploadPages(frame: AssAtlasFrame): Boolean {
        val pages = frame.pages
        if (frame.quads.isNotEmpty() && pages == null) return false
        if (frame.pageWidths.size != frame.pageHeights.size) return false
        if (pages != null && pages.size != frame.pageWidths.size) return false

        if (pages == null || pages.isEmpty()) {
            deleteTextures()
            textureWidths = IntArray(0)
            textureHeights = IntArray(0)
            return frame.quads.isEmpty()
        }

        // Validate the complete payload before changing any persistent texture.
        // This prevents a malformed later page from leaving a partially updated
        // atlas that would corrupt subsequent position-only frames.
        for (index in pages.indices) {
            val width = frame.pageWidths[index]
            val height = frame.pageHeights[index]
            val expectedBytes = width.toLong() * height
            if (width <= 0 || height <= 0 || expectedBytes > Int.MAX_VALUE ||
                pages[index].size != expectedBytes.toInt()
            ) {
                return false
            }
        }

        while (textureIds.size < pages.size) textureIds += 0

        val newWidths = frame.pageWidths.copyOf()
        val newHeights = frame.pageHeights.copyOf()
        for (index in pages.indices) {
            val width = newWidths[index]
            val height = newHeights[index]
            val bytes = pages[index]
            var texture = textureIds[index]
            val sizeChanged = index !in textureWidths.indices ||
                textureWidths[index] != width || textureHeights[index] != height
            if (texture == 0 || sizeChanged) {
                val replacement = createAlphaTexture(width, height, bytes)
                if (replacement == 0) return false
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                texture = replacement
                textureIds[index] = texture
            } else {
                val buffer = prepareUploadBuffer(bytes)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                if (isGles3) {
                    GLES30.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        width,
                        height,
                        GLES30.GL_RED,
                        GLES20.GL_UNSIGNED_BYTE,
                        buffer,
                    )
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        width,
                        height,
                        GLES20.GL_ALPHA,
                        GLES20.GL_UNSIGNED_BYTE,
                        buffer,
                    )
                }
            }
        }

        while (textureIds.size > pages.size) {
            val id = textureIds.removeAt(textureIds.lastIndex)
            if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        textureWidths = newWidths
        textureHeights = newHeights
        GlUtil.checkGlError()
        return true
    }

    private fun createAlphaTexture(width: Int, height: Int, bytes: ByteArray): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        if (texture == 0) return 0

        try {
            val buffer = prepareUploadBuffer(bytes)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            if (isGles3) {
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_R8,
                    width,
                    height,
                    0,
                    GLES30.GL_RED,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer,
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_ALPHA,
                    width,
                    height,
                    0,
                    GLES20.GL_ALPHA,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer,
                )
            }
            GlUtil.checkGlError()
            return texture
        } catch (error: Exception) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            throw error
        }
    }

    private fun pageLayoutMatches(frame: AssAtlasFrame): Boolean =
        frame.pageWidths.contentEquals(textureWidths) &&
            frame.pageHeights.contentEquals(textureHeights)

    private fun uploadGeometry() {
        val requiredFloats = geometry.floatCount
        if (requiredFloats == 0) return
        val cpuBuffer = ensureFloatBuffer(requiredFloats)
        cpuBuffer.clear()
        cpuBuffer.put(geometry.data, 0, requiredFloats)
        cpuBuffer.flip()

        val requiredBytes = requiredFloats * AssAtlasVertexBuffer.BYTES_PER_FLOAT
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        if (requiredBytes > gpuBufferCapacityBytes) {
            gpuBufferCapacityBytes = nextPowerOfTwo(requiredBytes)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                gpuBufferCapacityBytes,
                null,
                GLES20.GL_DYNAMIC_DRAW,
            )
        }
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, requiredBytes, cpuBuffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GlUtil.checkGlError()
    }

    private fun ensureFloatBuffer(requiredFloats: Int): FloatBuffer {
        if (floatBuffer == null || floatBufferCapacity < requiredFloats) {
            floatBufferCapacity = nextPowerOfTwo(requiredFloats)
            floatBuffer = ByteBuffer.allocateDirect(floatBufferCapacity * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        return requireNotNull(floatBuffer)
    }

    private fun prepareUploadBuffer(bytes: ByteArray): ByteBuffer {
        if (uploadBuffer == null || uploadBufferCapacity < bytes.size) {
            uploadBufferCapacity = nextPowerOfTwo(bytes.size.coerceAtLeast(1))
            uploadBuffer = ByteBuffer.allocateDirect(uploadBufferCapacity)
                .order(ByteOrder.nativeOrder())
        }
        return requireNotNull(uploadBuffer).apply {
            clear()
            put(bytes)
            flip()
        }
    }

    private fun deleteTextures() {
        if (textureIds.isNotEmpty()) {
            val ids = textureIds.filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
            textureIds.clear()
        }
        textureWidths = IntArray(0)
        textureHeights = IntArray(0)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result <= Int.MAX_VALUE / 2) result = result shl 1
        return result.coerceAtLeast(value)
    }

    private companion object {
        const val TAG = "AssAtlasGlRenderer"

        val VERTEX_SHADER = """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            attribute vec4 a_Color;
            varying vec2 v_TexCoord;
            varying vec4 v_Color;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
                v_Color = a_Color;
            }
        """.trimIndent()

        val FRAGMENT_SHADER_ALPHA = """
            precision mediump float;
            varying vec2 v_TexCoord;
            varying vec4 v_Color;
            uniform sampler2D u_Atlas;
            void main() {
                float coverage = texture2D(u_Atlas, v_TexCoord).a * v_Color.a;
                gl_FragColor = vec4(v_Color.rgb * coverage, coverage);
            }
        """.trimIndent()

        val FRAGMENT_SHADER_RED = """
            precision mediump float;
            varying vec2 v_TexCoord;
            varying vec4 v_Color;
            uniform sampler2D u_Atlas;
            void main() {
                float coverage = texture2D(u_Atlas, v_TexCoord).r * v_Color.a;
                gl_FragColor = vec4(v_Color.rgb * coverage, coverage);
            }
        """.trimIndent()
    }
}
