package io.github.peerless2012.ass.media.render

import androidx.media3.common.util.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class AssAtlasSurfaceLayoutTest {
    @Test
    fun bucketsAndClampsVisibleBounds() {
        val layout = AssAtlasSurfaceLayout.resolve(
            activeBounds = intArrayOf(-10, 900, 210, 200),
            renderSize = Size(1920, 1080),
            videoSize = Size(3840, 2160),
            previousCapacity = Size.ZERO,
        )

        assertEquals(0, layout.originX)
        assertEquals(900, layout.originY)
        assertEquals(200, layout.activeWidth)
        assertEquals(180, layout.activeHeight)
        assertEquals(Size(256, 180), layout.capacity)
    }

    @Test
    fun reusesCapacityAndPlacesCropInVideoCoordinates() {
        val layout = AssAtlasSurfaceLayout.resolve(
            activeBounds = intArrayOf(100, 800, 300, 100),
            renderSize = Size(1920, 1080),
            videoSize = Size(3840, 2160),
            previousCapacity = Size(384, 128),
        )

        assertEquals(Size(384, 128), layout.capacity)
        assertEquals(0.5f, layout.vertexTransform[0], 0.00001f)
        assertEquals(0.5f, layout.vertexTransform[5], 0.00001f)
        assertEquals((1920f - 200f) / 384f - 1f, layout.vertexTransform[12], 0.00001f)
        assertEquals((1600f + 128f - 1080f) / 128f, layout.vertexTransform[13], 0.00001f)
    }

    @Test
    fun emptyBoundsUseSmallTransparentSurface() {
        val layout = AssAtlasSurfaceLayout.resolve(
            activeBounds = IntArray(0),
            renderSize = Size(1920, 1080),
            videoSize = Size(1920, 1080),
            previousCapacity = Size(512, 128),
        )

        assertEquals(Size(2, 2), layout.capacity)
        assertEquals(0, layout.activeWidth)
        assertEquals(0, layout.activeHeight)
    }
}
