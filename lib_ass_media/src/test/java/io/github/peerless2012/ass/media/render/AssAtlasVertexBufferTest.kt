package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssAtlasVertexBufferTest {

    @Test
    fun buildsNormalizedGeometryAndAssColor() {
        val geometry = AssAtlasVertexBuffer()
        val frame = frame(
            pages = arrayOf(ByteArray(8 * 8)),
            widths = intArrayOf(8),
            heights = intArrayOf(8),
            quads = intArrayOf(
                10, 20, 2, 4,
                0x11223344,
                0, 1, 1,
            ),
        )

        assertTrue(geometry.update(frame, sourceWidth = 100, sourceHeight = 100))
        assertEquals(4, geometry.vertexCount)
        assertEquals(6, geometry.indexCount)
        assertEquals(1, geometry.runCount)
        assertEquals(0, geometry.runPages[0])
        assertEquals(0, geometry.runFirstIndices[0])
        assertEquals(6, geometry.runIndexCounts[0])

        assertFloatEquals(-0.8f, geometry.vertices.getFloat(0))
        assertFloatEquals(0.6f, geometry.vertices.getFloat(4))
        assertFloatEquals(1f / 8f, geometry.vertices.getFloat(8))
        assertFloatEquals(1f / 8f, geometry.vertices.getFloat(12))
        assertEquals(0x11, geometry.vertices.get(16).toInt() and 0xFF)
        assertEquals(0x22, geometry.vertices.get(17).toInt() and 0xFF)
        assertEquals(0x33, geometry.vertices.get(18).toInt() and 0xFF)
        assertEquals(0xFF - 0x44, geometry.vertices.get(19).toInt() and 0xFF)
    }

    @Test
    fun preservesCompositionOrderAcrossPageRuns() {
        val geometry = AssAtlasVertexBuffer()
        val quads = intArrayOf(
            0, 0, 2, 2, 0xFFFFFFFF.toInt(), 0, 0, 0,
            2, 0, 2, 2, 0xFFFFFFFF.toInt(), 1, 0, 0,
            4, 0, 2, 2, 0xFFFFFFFF.toInt(), 0, 2, 0,
        )
        val frame = frame(
            pages = arrayOf(ByteArray(16), ByteArray(16)),
            widths = intArrayOf(4, 4),
            heights = intArrayOf(4, 4),
            quads = quads,
        )

        assertTrue(geometry.update(frame, 10, 10))
        assertEquals(3, geometry.runCount)
        assertEquals(listOf(0, 1, 0), geometry.runPages.take(3))
        assertEquals(listOf(0, 6, 12), geometry.runFirstIndices.take(3))
        assertEquals(listOf(6, 6, 6), geometry.runIndexCounts.take(3))
    }

    @Test
    fun positionOnlyFrameNeedsNoPageBytes() {
        val geometry = AssAtlasVertexBuffer()
        val frame = AssAtlasFrame(
            pages = null,
            pageWidths = intArrayOf(16),
            pageHeights = intArrayOf(16),
            quads = intArrayOf(1, 2, 4, 5, 0x00000000, 0, 3, 4),
            changed = AssAtlasFrame.CHANGE_POSITION,
            dirtyRects = IntArray(0),
            contentSerial = 1,
        )

        assertTrue(geometry.update(frame, 100, 50))
        assertEquals(4, geometry.vertexCount)
    }

    @Test
    fun rejectsOutOfBoundsAtlasCoordinates() {
        val geometry = AssAtlasVertexBuffer()
        val frame = frame(
            pages = arrayOf(ByteArray(16)),
            widths = intArrayOf(4),
            heights = intArrayOf(4),
            quads = intArrayOf(0, 0, 3, 3, 0, 0, 2, 2),
        )

        assertFalse(geometry.update(frame, 10, 10))
    }

    @Test
    fun buildsGeometryRelativeToSurfaceOrigin() {
        val geometry = AssAtlasVertexBuffer()
        val frame = frame(
            pages = arrayOf(ByteArray(16)),
            widths = intArrayOf(4),
            heights = intArrayOf(4),
            quads = intArrayOf(110, 220, 4, 4, 0, 0, 0, 0),
        )

        assertTrue(geometry.update(frame, 100, 100, originX = 100, originY = 200))
        assertFloatEquals(-0.8f, geometry.vertices.getFloat(0))
        assertFloatEquals(0.6f, geometry.vertices.getFloat(4))
    }

    private fun frame(
        pages: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        quads: IntArray,
    ) = AssAtlasFrame(
        pages = Array(pages.size) { index ->
            ByteBuffer.allocateDirect(pages[index].size).order(ByteOrder.nativeOrder()).apply {
                put(pages[index])
                flip()
            }
        },
        pageWidths = widths,
        pageHeights = heights,
        quads = quads,
        changed = AssAtlasFrame.CHANGE_CONTENT,
        dirtyRects = IntArray(widths.size * 4),
        contentSerial = 1,
    )

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.000_01)
    }
}
