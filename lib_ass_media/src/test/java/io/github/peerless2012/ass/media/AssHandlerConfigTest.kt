package io.github.peerless2012.ass.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssHandlerConfigTest {

    @Test
    fun renderThreadCountMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            AssHandlerConfig(renderThreads = 0)
        }
    }

    @Test
    fun defaultUsesTwoRenderWorkers() {
        assertEquals(2, AssHandlerConfig().renderThreads)
    }
}
