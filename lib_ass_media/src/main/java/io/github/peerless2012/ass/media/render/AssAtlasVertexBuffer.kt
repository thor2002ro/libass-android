package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reusable packed CPU-side geometry for batched atlas drawing. */
internal class AssAtlasVertexBuffer {
    var vertices: ByteBuffer = newDirectBuffer(MIN_VERTEX_CAPACITY_BYTES)
        private set
    var vertexBytes: Int = 0
        private set
    var vertexCount: Int = 0
        private set
    var indices: ByteBuffer = newDirectBuffer(MIN_INDEX_CAPACITY_BYTES)
        private set
    var indexBytes: Int = 0
        private set
    var indexCount: Int = 0
        private set

    var runPages: IntArray = IntArray(0)
        private set
    var runFirstIndices: IntArray = IntArray(0)
        private set
    var runIndexCounts: IntArray = IntArray(0)
        private set
    var runCount: Int = 0
        private set

    fun isValid(frame: AssAtlasFrame, sourceWidth: Int, sourceHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        val imageCount = frame.imageCount
        if (imageCount > MAX_QUADS) return false
        val requiredVertexBytes = imageCount.toLong() * VERTICES_PER_QUAD * VERTEX_STRIDE_BYTES
        val requiredIndexBytes = imageCount.toLong() * INDICES_PER_QUAD * BYTES_PER_INDEX
        if (requiredVertexBytes > Int.MAX_VALUE || requiredIndexBytes > Int.MAX_VALUE) return false

        repeat(imageCount) { image ->
            val width = frame.quadValue(image, AssAtlasFrame.QUAD_WIDTH)
            val height = frame.quadValue(image, AssAtlasFrame.QUAD_HEIGHT)
            val page = frame.quadValue(image, AssAtlasFrame.QUAD_PAGE)
            val atlasX = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_X)
            val atlasY = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_Y)
            if (width <= 0 || height <= 0 || page !in 0 until frame.pageCount) return false
            val pageWidth = frame.pageWidth(page)
            val pageHeight = frame.pageHeight(page)
            if (pageWidth <= 0 || pageHeight <= 0) return false
            if (atlasX < 0 || atlasY < 0 ||
                atlasX.toLong() + width > pageWidth ||
                atlasY.toLong() + height > pageHeight
            ) return false
        }
        return true
    }

    fun update(
        frame: AssAtlasFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        originX: Int = 0,
        originY: Int = 0,
    ): Boolean {
        if (!isValid(frame, sourceWidth, sourceHeight)) return false
        val imageCount = frame.imageCount
        vertexBytes = imageCount * VERTICES_PER_QUAD * VERTEX_STRIDE_BYTES
        indexBytes = imageCount * INDICES_PER_QUAD * BYTES_PER_INDEX
        ensureVertexCapacity(vertexBytes)
        ensureIndexCapacity(indexBytes)
        ensureRunCapacity(imageCount)
        vertices.clear()
        indices.clear()
        vertexCount = 0
        indexCount = 0
        runCount = 0
        var currentRunPage = -1

        repeat(imageCount) { image ->
            val x = frame.quadValue(image, AssAtlasFrame.QUAD_DST_X)
            val y = frame.quadValue(image, AssAtlasFrame.QUAD_DST_Y)
            val width = frame.quadValue(image, AssAtlasFrame.QUAD_WIDTH)
            val height = frame.quadValue(image, AssAtlasFrame.QUAD_HEIGHT)
            val color = frame.quadValue(image, AssAtlasFrame.QUAD_COLOR)
            val page = frame.quadValue(image, AssAtlasFrame.QUAD_PAGE)
            val atlasX = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_X)
            val atlasY = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_Y)

            val pageWidth = frame.pageWidth(page)
            val pageHeight = frame.pageHeight(page)
            if (page != currentRunPage) {
                currentRunPage = page
                runPages[runCount] = page
                runFirstIndices[runCount] = indexCount
                runIndexCounts[runCount] = 0
                runCount++
            }

            val relativeX = x - originX
            val relativeY = y - originY
            val x0 = (relativeX.toDouble() * 2.0 / sourceWidth - 1.0).toFloat()
            val x1 = ((relativeX.toDouble() + width) * 2.0 / sourceWidth - 1.0).toFloat()
            val y0 = (1.0 - relativeY.toDouble() * 2.0 / sourceHeight).toFloat()
            val y1 = (1.0 - (relativeY.toDouble() + height) * 2.0 / sourceHeight).toFloat()
            val u0 = atlasX.toFloat() / pageWidth
            val u1 = (atlasX + width).toFloat() / pageWidth
            val v0 = atlasY.toFloat() / pageHeight
            val v1 = (atlasY + height).toFloat() / pageHeight
            val red = color ushr 24 and 0xFF
            val green = color ushr 16 and 0xFF
            val blue = color ushr 8 and 0xFF
            val alpha = 0xFF - (color and 0xFF)

            appendVertex(x0, y0, u0, v0, red, green, blue, alpha)
            appendVertex(x1, y0, u1, v0, red, green, blue, alpha)
            appendVertex(x0, y1, u0, v1, red, green, blue, alpha)
            appendVertex(x1, y1, u1, v1, red, green, blue, alpha)
            val first = vertexCount
            appendIndex(first)
            appendIndex(first + 1)
            appendIndex(first + 2)
            appendIndex(first + 2)
            appendIndex(first + 1)
            appendIndex(first + 3)
            vertexCount += VERTICES_PER_QUAD
            indexCount += INDICES_PER_QUAD
            runIndexCounts[runCount - 1] += INDICES_PER_QUAD
        }
        vertices.flip()
        indices.flip()
        return true
    }

    fun clear() {
        vertices.clear()
        vertices.limit(0)
        indices.clear()
        indices.limit(0)
        vertexBytes = 0
        indexBytes = 0
        vertexCount = 0
        indexCount = 0
        runCount = 0
    }

    private fun appendVertex(x: Float, y: Float, u: Float, v: Float, red: Int, green: Int, blue: Int, alpha: Int) {
        vertices.putFloat(x).putFloat(y).putFloat(u).putFloat(v)
        vertices.put(red.toByte()).put(green.toByte()).put(blue.toByte()).put(alpha.toByte())
    }

    private fun appendIndex(index: Int) {
        indices.putShort(index.toShort())
    }

    private fun ensureVertexCapacity(required: Int) {
        if (vertices.capacity() < required) vertices = newDirectBuffer(nextPowerOfTwo(required.coerceAtLeast(MIN_VERTEX_CAPACITY_BYTES)))
    }

    private fun ensureIndexCapacity(required: Int) {
        if (indices.capacity() < required) indices = newDirectBuffer(nextPowerOfTwo(required.coerceAtLeast(MIN_INDEX_CAPACITY_BYTES)))
    }

    private fun ensureRunCapacity(required: Int) {
        if (runPages.size >= required) return
        val capacity = nextPowerOfTwo(required.coerceAtLeast(4))
        runPages = IntArray(capacity)
        runFirstIndices = IntArray(capacity)
        runIndexCounts = IntArray(capacity)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result <= Int.MAX_VALUE / 2) result = result shl 1
        return result.coerceAtLeast(value)
    }

    companion object {
        const val VERTEX_STRIDE_BYTES = 20
        const val VERTICES_PER_QUAD = 4
        const val INDICES_PER_QUAD = 6
        const val BYTES_PER_INDEX = 2
        const val POSITION_OFFSET_BYTES = 0
        const val TEX_COORD_OFFSET_BYTES = 8
        const val COLOR_OFFSET_BYTES = 16
        private const val MAX_QUADS = 16_383
        private const val MIN_VERTEX_CAPACITY_BYTES = 1_280
        private const val MIN_INDEX_CAPACITY_BYTES = 384

        private fun newDirectBuffer(capacity: Int): ByteBuffer =
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }
}
