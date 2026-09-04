package io.github.peerless2012.ass.media.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AssAtlasUploadPolicyTest {
    @Test
    fun pboStagingPreservesSourceForDirectFallback() {
        val source = ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(10, 20, 30, 40))
            position(1)
            limit(3)
        }
        val destination = ByteBuffer.allocateDirect(2)

        copyBufferPreservingPosition(destination, source)

        assertEquals(1, source.position())
        assertEquals(3, source.limit())
        destination.flip()
        assertEquals(20, destination.get().toInt())
        assertEquals(30, destination.get().toInt())
    }

    @Test
    fun autoUsesDirectOnMeasuredPowerVrRenderer() {
        assertFalse(
            AssAtlasUploadPolicy.shouldUsePbo(
                AssAtlasGlRenderer.UploadMode.AUTO,
                "PowerVR Rogue GE9215",
            )
        )
    }

    @Test
    fun autoKeepsPboOnOtherGles3Renderers() {
        assertTrue(
            AssAtlasUploadPolicy.shouldUsePbo(
                AssAtlasGlRenderer.UploadMode.AUTO,
                "Adreno (TM) 740",
            )
        )
    }

    @Test
    fun explicitModesOverrideRendererPolicy() {
        assertTrue(
            AssAtlasUploadPolicy.shouldUsePbo(
                AssAtlasGlRenderer.UploadMode.PBO,
                "PowerVR Rogue GE9215",
            )
        )
        assertFalse(
            AssAtlasUploadPolicy.shouldUsePbo(
                AssAtlasGlRenderer.UploadMode.DIRECT,
                "Adreno (TM) 740",
            )
        )
    }
}
