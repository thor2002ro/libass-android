package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame

/** Reusable CPU-side geometry for batched atlas drawing. */
internal class AssAtlasVertexBuffer {
    var data: FloatArray = FloatArray(0)
        private set
    var floatCount: Int = 0
        private set
    var vertexCount: Int = 0
        private set

    var runPages: IntArray = IntArray(0)
        private set
    var runFirstVertices: IntArray = IntArray(0)
        private set
    var runVertexCounts: IntArray = IntArray(0)
        private set
    var runCount: Int = 0
        private set

    fun isValid(frame: AssAtlasFrame, sourceWidth: Int, sourceHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        if (frame.quads.size % AssAtlasFrame.QUAD_STRIDE != 0) return false
        if (frame.pageWidths.size != frame.pageHeights.size) return false

        val imageCount = frame.imageCount
        val requiredFloatCount = imageCount.toLong() * VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        if (requiredFloatCount > Int.MAX_VALUE) return false

        var quadOffset = 0
        repeat(imageCount) {
            val width = frame.quads[quadOffset + AssAtlasFrame.QUAD_WIDTH]
            val height = frame.quads[quadOffset + AssAtlasFrame.QUAD_HEIGHT]
            val page = frame.quads[quadOffset + AssAtlasFrame.QUAD_PAGE]
            val atlasX = frame.quads[quadOffset + AssAtlasFrame.QUAD_ATLAS_X]
            val atlasY = frame.quads[quadOffset + AssAtlasFrame.QUAD_ATLAS_Y]
            quadOffset += AssAtlasFrame.QUAD_STRIDE

            if (width <= 0 || height <= 0) return false
            if (page !in frame.pageWidths.indices) return false
            val pageWidth = frame.pageWidths[page]
            val pageHeight = frame.pageHeights[page]
            if (pageWidth <= 0 || pageHeight <= 0) return false
            if (atlasX < 0 || atlasY < 0 ||
                atlasX.toLong() + width > pageWidth ||
                atlasY.toLong() + height > pageHeight
            ) {
                return false
            }
        }
        return true
    }

    fun update(frame: AssAtlasFrame, sourceWidth: Int, sourceHeight: Int): Boolean {
        if (!isValid(frame, sourceWidth, sourceHeight)) return false

        val imageCount = frame.imageCount
        val requiredFloatCount = imageCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        ensureDataCapacity(requiredFloatCount)
        ensureRunCapacity(imageCount)

        floatCount = 0
        vertexCount = 0
        runCount = 0
        var currentRunPage = -1

        var quadOffset = 0
        repeat(imageCount) {
            val x = frame.quads[quadOffset + AssAtlasFrame.QUAD_DST_X]
            val y = frame.quads[quadOffset + AssAtlasFrame.QUAD_DST_Y]
            val width = frame.quads[quadOffset + AssAtlasFrame.QUAD_WIDTH]
            val height = frame.quads[quadOffset + AssAtlasFrame.QUAD_HEIGHT]
            val color = frame.quads[quadOffset + AssAtlasFrame.QUAD_COLOR]
            val page = frame.quads[quadOffset + AssAtlasFrame.QUAD_PAGE]
            val atlasX = frame.quads[quadOffset + AssAtlasFrame.QUAD_ATLAS_X]
            val atlasY = frame.quads[quadOffset + AssAtlasFrame.QUAD_ATLAS_Y]
            quadOffset += AssAtlasFrame.QUAD_STRIDE

            val pageWidth = frame.pageWidths[page]
            val pageHeight = frame.pageHeights[page]
            if (page != currentRunPage) {
                currentRunPage = page
                runPages[runCount] = page
                runFirstVertices[runCount] = vertexCount
                runVertexCounts[runCount] = 0
                runCount++
            }

            // Use double precision for the addition so malformed native values
            // cannot overflow before they are converted to normalized coordinates.
            val x0 = (x.toDouble() * 2.0 / sourceWidth - 1.0).toFloat()
            val x1 = ((x.toDouble() + width) * 2.0 / sourceWidth - 1.0).toFloat()
            val y0 = (1.0 - y.toDouble() * 2.0 / sourceHeight).toFloat()
            val y1 = (1.0 - (y.toDouble() + height) * 2.0 / sourceHeight).toFloat()

            // Map output pixel centers to atlas texel centers. The native packer
            // supplies transparent gutters where neighboring masks could bleed.
            val u0 = atlasX.toFloat() / pageWidth
            val u1 = (atlasX + width).toFloat() / pageWidth
            val v0 = atlasY.toFloat() / pageHeight
            val v1 = (atlasY + height).toFloat() / pageHeight

            val red = (color ushr 24 and 0xFF) / 255f
            val green = (color ushr 16 and 0xFF) / 255f
            val blue = (color ushr 8 and 0xFF) / 255f
            val alpha = (0xFF - (color and 0xFF)) / 255f

            appendVertex(x0, y0, u0, v0, red, green, blue, alpha)
            appendVertex(x1, y0, u1, v0, red, green, blue, alpha)
            appendVertex(x0, y1, u0, v1, red, green, blue, alpha)
            appendVertex(x0, y1, u0, v1, red, green, blue, alpha)
            appendVertex(x1, y0, u1, v0, red, green, blue, alpha)
            appendVertex(x1, y1, u1, v1, red, green, blue, alpha)

            vertexCount += VERTICES_PER_QUAD
            runVertexCounts[runCount - 1] += VERTICES_PER_QUAD
        }

        return true
    }

    fun clear() {
        floatCount = 0
        vertexCount = 0
        runCount = 0
    }

    private fun appendVertex(
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        data[floatCount++] = x
        data[floatCount++] = y
        data[floatCount++] = u
        data[floatCount++] = v
        data[floatCount++] = red
        data[floatCount++] = green
        data[floatCount++] = blue
        data[floatCount++] = alpha
    }

    private fun ensureDataCapacity(required: Int) {
        if (data.size >= required) return
        data = FloatArray(nextPowerOfTwo(required.coerceAtLeast(64)))
    }

    private fun ensureRunCapacity(required: Int) {
        if (runPages.size >= required) return
        val capacity = nextPowerOfTwo(required.coerceAtLeast(4))
        runPages = IntArray(capacity)
        runFirstVertices = IntArray(capacity)
        runVertexCounts = IntArray(capacity)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result <= Int.MAX_VALUE / 2) result = result shl 1
        return result.coerceAtLeast(value)
    }

    companion object {
        const val FLOATS_PER_VERTEX = 8
        const val BYTES_PER_FLOAT = 4
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * BYTES_PER_FLOAT
        const val VERTICES_PER_QUAD = 6

        const val POSITION_OFFSET_BYTES = 0
        const val TEX_COORD_OFFSET_BYTES = 2 * BYTES_PER_FLOAT
        const val COLOR_OFFSET_BYTES = 4 * BYTES_PER_FLOAT
    }
}
