package io.github.peerless2012.ass.media

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class AssPerformanceStatsRecorderTest {

    @Test
    fun recordsFrameRateAndRenderDurations() {
        var nowNs = 0L
        val recorder = AssPerformanceStatsRecorder(nowNs = { nowNs }, slowRenderThresholdMs = 2.0)

        recorder.record(1_000_000, AssFrame(null, 0))
        nowNs = 1_000_000_000
        recorder.record(3_000_000, AssFrame(arrayOf(testTex(2, 3), testTex(4, 5)), 1))
        recorder.recordExecutorTimeout()
        recorder.recordSupersededRequest()

        val stats = recorder.snapshot()

        assertEquals(2L, stats.renderCount)
        assertEquals(1L, stats.changedRenderCount)
        assertEquals(1L, stats.emptyRenderCount)
        assertEquals(1L, stats.slowRenderCount)
        assertEquals(2, stats.maxImageCount)
        assertEquals(20L, stats.maxBitmapPixels)
        assertEquals(26L, stats.totalBitmapPixels)
        assertEquals(1L, stats.executorTimeoutCount)
        assertEquals(1L, stats.supersededRequestCount)
        assertEquals(2.0, stats.fps, 0.01)
        assertEquals(0.5, stats.changedRatio, 0.01)
        assertEquals(2.0, stats.averageRenderMs, 0.01)
        assertEquals(1.0, stats.minRenderMs, 0.01)
        assertEquals(3.0, stats.maxRenderMs, 0.01)
        assertEquals(3.0, stats.lastRenderMs, 0.01)
        assertEquals(
            "fps=2.0 renderMs(avg/min/max/last)=2.00/1.00/3.00/3.00 frames=2 changed=1 changedRatio=0.50 empty=1 images=2 maxImages=2 slow=1 executorTimeouts=1 superseded=1 maxBitmapPixels=20 totalBitmapPixels=26",
            stats.toSummaryString()
        )
    }

    @Test
    fun collectorCanBeReadAndResetByApp() {
        val collector = AssPerformanceStatsCollector()

        collector.record(1_000_000, AssFrame(null, 0))
        assertEquals(1L, collector.snapshot().renderCount)
        collector.reset()
        assertEquals(0L, collector.snapshot().renderCount)
    }

    @Test
    fun recordsAtlasFrames() {
        val collector = AssPerformanceStatsCollector()
        val frame = AssAtlasFrame(
            pages = null,
            pageWidths = intArrayOf(16),
            pageHeights = intArrayOf(16),
            quads = intArrayOf(
                0, 0, 3, 4, 0, 0, 0, 0,
                8, 8, 5, 6, 0, 0, 4, 0,
            ),
            changed = AssAtlasFrame.CHANGE_CONTENT,
        )

        collector.record(2_000_000, frame)
        val stats = collector.snapshot()

        assertEquals(1L, stats.renderCount)
        assertEquals(1L, stats.changedRenderCount)
        assertEquals(0L, stats.emptyRenderCount)
        assertEquals(2L, stats.imageCount)
        assertEquals(2, stats.maxImageCount)
        assertEquals(30L, stats.maxBitmapPixels)
        assertEquals(42L, stats.totalBitmapPixels)
    }

    @Test
    fun ignoresNonPositiveImageAreas() {
        val collector = AssPerformanceStatsCollector()

        collector.record(
            1_000_000,
            AssFrame(arrayOf(testTex(0, 5), testTex(-2, 5), testTex(3, 4)), 1),
        )

        val stats = collector.snapshot()
        assertEquals(12L, stats.maxBitmapPixels)
        assertEquals(12L, stats.totalBitmapPixels)
    }

    private fun testTex(width: Int, height: Int) =
        io.github.peerless2012.ass.AssTex(0, 0, width, height, 0)
}
