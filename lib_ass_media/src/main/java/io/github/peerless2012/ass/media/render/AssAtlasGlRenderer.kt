package io.github.peerless2012.ass.media.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws libass alpha-atlas pages into the currently bound framebuffer. */
@OptIn(UnstableApi::class)
internal class AssAtlasGlRenderer(
    private val statsCollector: AssPerformanceStatsCollector? = null,
) {
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
    private var indexBufferId = 0
    private var gpuVertexCapacityBytes = 0
    private var gpuIndexCapacityBytes = 0
    private var uploadBuffer: ByteBuffer? = null
    private var uploadBufferCapacity = 0
    private var pboIds = IntArray(0)
    private var pboCapacities = IntArray(0)
    private var nextPbo = 0
    private var pboEnabled = false

    private var positionLocation = -1
    private var texCoordLocation = -1
    private var colorLocation = -1
    private var textureLocation = -1
    private var initialized = false
    private var hasContent = false
    private var isGles3 = false
    private var uploadedContentSerial = 0L
    private var currentMode: String? = null

    fun initialize() {
        if (initialized) return
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        isGles3 = version.contains("OpenGL ES 3")
        reportMode(if (isGles3) MODE_GLES3_DIRECT else MODE_GLES2)

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
            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vertexBufferId = buffers[0]
            indexBufferId = buffers[1]
            check(vertexBufferId != 0 && indexBufferId != 0) {
                "Unable to create subtitle geometry buffers"
            }
            GlUtil.checkGlError()
            if (isGles3) initializePbos()
            initialized = true
        } catch (error: Exception) {
            if (vertexBufferId != 0) {
                GlUtil.deleteBuffer(vertexBufferId)
                vertexBufferId = 0
            }
            if (indexBufferId != 0) {
                GlUtil.deleteBuffer(indexBufferId)
                indexBufferId = 0
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
        currentMode?.let { statsCollector?.recordOpenGlMode(it) }
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
                    if (frame.quads.isNotEmpty() &&
                        (!pageLayoutMatches(frame) || frame.contentSerial != uploadedContentSerial)
                    ) {
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
            GLES20.GL_UNSIGNED_BYTE,
            true,
            AssAtlasVertexBuffer.VERTEX_STRIDE_BYTES,
            AssAtlasVertexBuffer.COLOR_OFFSET_BYTES,
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(textureLocation, 0)
        repeat(geometry.runCount) { run ->
            val page = geometry.runPages[run]
            if (page !in textureIds.indices) return@repeat
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[page])
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                geometry.runIndexCounts[run],
                GLES20.GL_UNSIGNED_SHORT,
                geometry.runFirstIndices[run] * AssAtlasVertexBuffer.BYTES_PER_INDEX,
            )
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
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
        deletePbos()
        if (vertexBufferId != 0) {
            GlUtil.deleteBuffer(vertexBufferId)
            vertexBufferId = 0
        }
        if (indexBufferId != 0) {
            GlUtil.deleteBuffer(indexBufferId)
            indexBufferId = 0
        }
        program?.delete()
        program = null
        geometry.clear()
        hasContent = false
        gpuVertexCapacityBytes = 0
        gpuIndexCapacityBytes = 0
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
            uploadedContentSerial = frame.contentSerial
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
                pages[index].capacity() < expectedBytes.toInt()
            ) {
                return false
            }
        }

        while (textureIds.size < pages.size) textureIds += 0

        val newWidths = frame.pageWidths.copyOf()
        val newHeights = frame.pageHeights.copyOf()
        val dirtySequenceValid = uploadedContentSerial != 0L &&
            frame.contentSerial == uploadedContentSerial + 1L
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
                if (isGles3) {
                    val dirty = if (dirtySequenceValid) {
                        dirtyRect(frame, index, width, height) ?: return false
                    } else {
                        DirtyRect(0, 0, width, height)
                    }
                    if (dirty.width > 0 && dirty.height > 0) {
                        val buffer = prepareRegionBuffer(bytes, width, dirty)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                        uploadGles3Region(dirty, buffer)
                    }
                } else {
                    val buffer = bytes.duplicate().apply { clear(); limit(width * height) }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
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
        uploadedContentSerial = frame.contentSerial
        GlUtil.checkGlError()
        return true
    }

    private fun createAlphaTexture(width: Int, height: Int, bytes: ByteBuffer): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        if (texture == 0) return 0

        try {
            val buffer = bytes.duplicate().apply { clear(); limit(width * height) }
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
        if (geometry.vertexBytes == 0 || geometry.indexBytes == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        if (geometry.vertexBytes > gpuVertexCapacityBytes) {
            gpuVertexCapacityBytes = nextPowerOfTwo(geometry.vertexBytes)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                gpuVertexCapacityBytes,
                null,
                GLES20.GL_DYNAMIC_DRAW,
            )
        }
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER,
            0,
            geometry.vertexBytes,
            geometry.vertices.duplicate(),
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        if (geometry.indexBytes > gpuIndexCapacityBytes) {
            gpuIndexCapacityBytes = nextPowerOfTwo(geometry.indexBytes)
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                gpuIndexCapacityBytes,
                null,
                GLES20.GL_DYNAMIC_DRAW,
            )
        }
        GLES20.glBufferSubData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            0,
            geometry.indexBytes,
            geometry.indices.duplicate(),
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GlUtil.checkGlError()
    }

    private fun prepareRegionBuffer(bytes: ByteBuffer, pageWidth: Int, rect: DirtyRect): ByteBuffer {
        val required = rect.width * rect.height
        if (uploadBuffer == null || uploadBufferCapacity < required) {
            uploadBufferCapacity = nextPowerOfTwo(required.coerceAtLeast(1))
            uploadBuffer = ByteBuffer.allocateDirect(uploadBufferCapacity)
                .order(ByteOrder.nativeOrder())
        }
        return requireNotNull(uploadBuffer).apply {
            clear()
            val source = bytes.duplicate()
            repeat(rect.height) { row ->
                val offset = (rect.top + row) * pageWidth + rect.left
                source.position(offset)
                source.limit(offset + rect.width)
                put(source)
            }
            flip()
        }
    }

    private fun dirtyRect(frame: AssAtlasFrame, page: Int, width: Int, height: Int): DirtyRect? {
        if (frame.dirtyRects.size != frame.pageWidths.size * 4) return null
        val offset = page * 4
        val left = frame.dirtyRects[offset]
        val top = frame.dirtyRects[offset + 1]
        val dirtyWidth = frame.dirtyRects[offset + 2]
        val dirtyHeight = frame.dirtyRects[offset + 3]
        if (left < 0 || top < 0 || dirtyWidth < 0 || dirtyHeight < 0 ||
            left.toLong() + dirtyWidth > width || top.toLong() + dirtyHeight > height
        ) return null
        return DirtyRect(left, top, dirtyWidth, dirtyHeight)
    }

    private fun initializePbos() {
        val ids = IntArray(2)
        GLES30.glGenBuffers(ids.size, ids, 0)
        if (ids.all { it != 0 }) {
            pboIds = ids
            pboCapacities = IntArray(ids.size)
            pboEnabled = true
            reportMode(MODE_GLES3_PBO)
        } else {
            val validIds = ids.filter { it != 0 }.toIntArray()
            if (validIds.isNotEmpty()) GLES30.glDeleteBuffers(validIds.size, validIds, 0)
            reportMode(MODE_GLES3_DIRECT)
        }
    }

    private fun uploadGles3Region(rect: DirtyRect, source: ByteBuffer) {
        if (pboEnabled && uploadGles3RegionWithPbo(rect, source)) return
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        GLES30.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            rect.left,
            rect.top,
            rect.width,
            rect.height,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            source,
        )
        reportMode(MODE_GLES3_DIRECT)
    }

    private fun uploadGles3RegionWithPbo(rect: DirtyRect, source: ByteBuffer): Boolean {
        val required = source.remaining()
        val slot = nextPbo
        nextPbo = (nextPbo + 1) % pboIds.size
        return try {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // Clear stale errors so only this PBO operation controls fallback.
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pboIds[slot])
            if (pboCapacities[slot] < required) {
                pboCapacities[slot] = nextPowerOfTwo(required.coerceAtLeast(1))
                GLES30.glBufferData(
                    GLES30.GL_PIXEL_UNPACK_BUFFER,
                    pboCapacities[slot],
                    null,
                    GLES30.GL_STREAM_DRAW,
                )
            }
            val mapped = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_UNPACK_BUFFER,
                0,
                required,
                GLES30.GL_MAP_WRITE_BIT or GLES30.GL_MAP_INVALIDATE_BUFFER_BIT,
            ) as? ByteBuffer
            if (mapped == null) return disablePbos()
            mapped.clear()
            mapped.limit(required)
            mapped.put(source.duplicate())
            if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER)) return disablePbos()
            GLES30.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                rect.left,
                rect.top,
                rect.width,
                rect.height,
                GLES30.GL_RED,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) disablePbos() else {
                reportMode(MODE_GLES3_PBO)
                true
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Disabling subtitle PBO uploads", error)
            disablePbos()
        }
    }

    private fun disablePbos(): Boolean {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        deletePbos()
        reportMode(MODE_GLES3_DIRECT)
        return false
    }

    private fun deletePbos() {
        if (pboIds.isNotEmpty()) GLES30.glDeleteBuffers(pboIds.size, pboIds, 0)
        pboIds = IntArray(0)
        pboCapacities = IntArray(0)
        pboEnabled = false
        nextPbo = 0
    }

    private fun deleteTextures() {
        if (textureIds.isNotEmpty()) {
            val ids = textureIds.filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
            textureIds.clear()
        }
        textureWidths = IntArray(0)
        textureHeights = IntArray(0)
        uploadedContentSerial = 0L
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result <= Int.MAX_VALUE / 2) result = result shl 1
        return result.coerceAtLeast(value)
    }

    private fun reportMode(mode: String) {
        currentMode = mode
        statsCollector?.recordOpenGlMode(mode)
    }

    private companion object {
        const val TAG = "AssAtlasGlRenderer"
        const val MODE_GLES2 = "GLES2 CPU masks, direct full upload"
        const val MODE_GLES3_DIRECT = "GLES3 CPU masks, direct dirty upload"
        const val MODE_GLES3_PBO = "GLES3 CPU masks, PBO dirty upload"

        data class DirtyRect(val left: Int, val top: Int, val width: Int, val height: Int)

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
