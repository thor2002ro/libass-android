package io.github.peerless2012.ass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssAtlasFrameTest {
    @Test
    fun unchangedFrameContainsNoPayload() {
        val frame = AssAtlasFrame.unchanged()

        assertEquals(AssAtlasFrame.CHANGE_NONE, frame.changed)
        assertEquals(0, frame.imageCount)
        assertFalse(frame.hasImages)
        assertNull(frame.pages)
        assertNull(frame.patches)
        assertArrayEquals(IntArray(0), frame.patchRects)
        assertArrayEquals(IntArray(0), frame.activeBounds)
        assertEquals(0L, frame.contentSerial)
        assertEquals(0L, frame.baseContentSerial)
        assertEquals(0L, frame.copiedMaskBytes)
    }

    @Test
    fun metadataFrameContainsNoMaskPayload() {
        val frame = AssAtlasFrame(
            pages = null,
            pageWidths = intArrayOf(64),
            pageHeights = intArrayOf(32),
            quads = intArrayOf(10, 20, 8, 6, 0x11223344, 0, 1, 1),
            changed = AssAtlasFrame.CHANGE_METADATA,
            dirtyRects = IntArray(0),
            patches = null,
            patchRects = IntArray(0),
            activeBounds = intArrayOf(10, 20, 8, 6),
            contentSerial = 4L,
            baseContentSerial = 4L,
            copiedMaskBytes = 0L,
        )

        assertNull(frame.pages)
        assertNull(frame.patches)
        assertEquals(1, frame.imageCount)
        assertEquals(AssAtlasFrame.CHANGE_METADATA, frame.changed)
    }

    @Test
    fun patchRectUsesPageLeftTopWidthHeightStride() {
        assertEquals(5, AssAtlasFrame.PATCH_RECT_STRIDE)
    }

    @Test
    fun packedFrameReadsMetadataAndReusesOneDirectBufferForPayloads() {
        val packet = ByteBuffer.allocateDirect(180).order(ByteOrder.nativeOrder()).apply {
            putInt(0, 0x41544631)
            putInt(4, 1)
            putInt(8, 180)
            putInt(12, AssAtlasFrame.CHANGE_REPLACE)
            putInt(16, 1)
            putInt(20, 1)
            putInt(24, 0)
            putInt(32, 10)
            putInt(36, 20)
            putInt(40, 2)
            putInt(44, 2)
            putLong(48, 7L)
            putLong(56, 6L)
            putLong(64, 4L)
            putInt(72, 96)
            putInt(76, 128)
            putInt(80, 160)
            putInt(84, 176)

            putInt(96, 2)
            putInt(100, 2)
            putInt(104, 176)
            putInt(108, 4)
            putInt(112, 0)
            putInt(116, 0)
            putInt(120, 2)
            putInt(124, 2)

            putInt(128, 10)
            putInt(132, 20)
            putInt(136, 2)
            putInt(140, 2)
            putInt(144, 0x11223344)
            putInt(148, 0)
            putInt(152, 0)
            putInt(156, 0)

            put(176, 1)
            put(177, 2)
            put(178, 3)
            put(179, 4)
        }

        val frame = AssAtlasFrame(packet)

        assertEquals(AssAtlasFrame.CHANGE_REPLACE, frame.changed)
        assertEquals(1, frame.pageCount)
        assertEquals(1, frame.imageCount)
        assertEquals(2, frame.pageWidth(0))
        assertEquals(20, frame.quadValue(0, AssAtlasFrame.QUAD_DST_Y))
        assertEquals(7L, frame.contentSerial)
        assertEquals(10, frame.activeBound(0))
        val first = frame.positionPageData(0)
        assertSame(first, frame.positionPageData(0))
        assertEquals(4, first.remaining())
        assertEquals(1, first.get().toInt())
        assertEquals(2, first.get().toInt())
        assertEquals(3, first.get().toInt())
        assertEquals(4, first.get().toInt())
    }

    @Test
    fun mapsNativeYCbCrValues() {
        assertEquals(AssYCbCrMatrix.BT709_TV, AssYCbCrMatrix.fromNative(5))
        assertEquals(AssYCbCrMatrix.UNKNOWN, AssYCbCrMatrix.fromNative(999))
    }
}
