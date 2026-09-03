package io.github.peerless2012.ass.media.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssAtlasUploadPolicyTest {
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
