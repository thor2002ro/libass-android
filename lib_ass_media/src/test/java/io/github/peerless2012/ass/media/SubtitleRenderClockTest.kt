package io.github.peerless2012.ass.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRenderClockTest {

    @Test
    fun limitsByPresentationTimeRatherThanCallbackCount() {
        val clock = SubtitleRenderClock(30f)

        assertTrue(clock.shouldRender(0L))
        assertFalse(clock.shouldRender(10_000L))
        assertFalse(clock.shouldRender(20_000L))
        assertFalse(clock.shouldRender(30_000L))
        assertTrue(clock.shouldRender(34_000L))
    }

    @Test
    fun rendersImmediatelyAfterBackwardSeek() {
        val clock = SubtitleRenderClock(24f)

        assertTrue(clock.shouldRender(1_000_000L))
        assertFalse(clock.shouldRender(1_010_000L))
        assertTrue(clock.shouldRender(100_000L))
    }

    @Test
    fun ignoresNegativeAndDuplicateTimestamps() {
        val clock = SubtitleRenderClock(60f)

        assertFalse(clock.shouldRender(-1L))
        assertTrue(clock.shouldRender(100_000L))
        assertFalse(clock.shouldRender(100_000L))
    }

    @Test
    fun zeroDisablesThrottling() {
        val clock = SubtitleRenderClock(0f)

        assertTrue(clock.shouldRender(1L))
        assertTrue(clock.shouldRender(2L))
        assertTrue(clock.shouldRender(3L))
    }

    @Test
    fun resetMakesNextTimestampRenderImmediately() {
        val clock = SubtitleRenderClock(1f)

        assertTrue(clock.shouldRender(0L))
        assertFalse(clock.shouldRender(10L))
        clock.reset()
        assertTrue(clock.shouldRender(10L))
    }
}
