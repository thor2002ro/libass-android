package io.github.peerless2012.ass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

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
    fun mapsNativeYCbCrValues() {
        assertEquals(AssYCbCrMatrix.BT709_TV, AssYCbCrMatrix.fromNative(5))
        assertEquals(AssYCbCrMatrix.UNKNOWN, AssYCbCrMatrix.fromNative(999))
    }
}
