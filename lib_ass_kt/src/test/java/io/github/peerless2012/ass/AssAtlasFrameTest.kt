package io.github.peerless2012.ass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssAtlasFrameTest {
    @Test
    fun unchangedFrameContainsNoPayload() {
        val frame = AssAtlasFrame.unchanged()

        assertEquals(AssAtlasFrame.CHANGE_NONE, frame.changed)
        assertEquals(0, frame.imageCount)
        assertFalse(frame.hasImages)
        assertEquals(null, frame.pages)
    }

    @Test
    fun mapsNativeYCbCrValues() {
        assertEquals(AssYCbCrMatrix.BT709_TV, AssYCbCrMatrix.fromNative(5))
        assertEquals(AssYCbCrMatrix.UNKNOWN, AssYCbCrMatrix.fromNative(999))
    }
}
