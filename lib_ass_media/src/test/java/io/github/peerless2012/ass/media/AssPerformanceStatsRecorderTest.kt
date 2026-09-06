package io.github.peerless2012.ass.media

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssFrame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class AssPerformanceStatsRecorderTest {

    @Test
    fun frameRateUsesOnlyTheLatestSecond() {
        var nowNs = 0L
        val recorder = AssPerformanceStatsRecorder(nowNs = { nowNs })

        recorder.record(1L, AssFrame(null, 0))
        nowNs = 200_000_000L
        recorder.record(1L, AssFrame(null, 0))
        nowNs = 400_000_000L
        recorder.record(1L, AssFrame(null, 0))
        assertEquals(3.0, recorder.snapshot().fps, 0.01)

        nowNs = 1_500_000_000L
        assertEquals(0.0, recorder.snapshot().fps, 0.01)
    }

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
        assertEquals(0L, stats.atlasUploadPageCount)
        assertEquals(0, stats.maxAtlasUploadPageCount)
        assertEquals(0L, stats.maxAtlasUploadPagePixels)
        assertEquals(0L, stats.totalAtlasUploadPagePixels)
        assertEquals(1L, stats.executorTimeoutCount)
        assertEquals(1L, stats.supersededRequestCount)
        assertEquals(2.0, stats.fps, 0.01)
        assertEquals(0.5, stats.changedRatio, 0.01)
        assertEquals(2.0, stats.averageRenderMs, 0.01)
        assertEquals(1.0, stats.minRenderMs, 0.01)
        assertEquals(3.0, stats.maxRenderMs, 0.01)
        assertEquals(3.0, stats.lastRenderMs, 0.01)
        assertEquals(
            "fps=2.0 renderMs(avg/min/max/last)=2.00/1.00/3.00/3.00 frames=2 changed=1 changedRatio=0.50 empty=1 images=2 maxImages=2 slow=1 executorTimeouts=1 superseded=1 maxBitmapPixels=20 totalBitmapPixels=26 atlasUploadPages=0 maxAtlasUploadPages=0 maxAtlasUploadPixels=0 totalAtlasUploadPixels=0",
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
            pages = arrayOf(ByteBuffer.allocateDirect(256), ByteBuffer.allocateDirect(32)),
            pageWidths = intArrayOf(16, 8),
            pageHeights = intArrayOf(16, 4),
            quads = intArrayOf(
                0, 0, 3, 4, 0, 0, 0, 0,
                8, 8, 5, 6, 0, 0, 4, 0,
            ),
            changed = AssAtlasFrame.CHANGE_REPLACE,
            dirtyRects = intArrayOf(0, 0, 16, 16, 0, 0, 8, 4),
            contentSerial = 1,
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
        assertEquals(2L, stats.atlasUploadPageCount)
        assertEquals(2, stats.maxAtlasUploadPageCount)
        assertEquals(256L, stats.maxAtlasUploadPagePixels)
        assertEquals(288L, stats.totalAtlasUploadPagePixels)
    }

    @Test
    fun recordsAtlasProtocolCopiesUploadsAndSurfaceSize() {
        val collector = AssPerformanceStatsCollector()
        collector.record(2_000_000, atlasFrame(AssAtlasFrame.CHANGE_METADATA, copiedBytes = 0L))
        collector.record(3_000_000, atlasFrame(AssAtlasFrame.CHANGE_INCREMENTAL, copiedBytes = 48L))
        collector.record(4_000_000, atlasFrame(AssAtlasFrame.CHANGE_REPLACE, copiedBytes = 2_048L))
        collector.recordGlUpload(
            uploadedBytes = 48L,
            submissionDurationNs = 600_000L,
            activeSurfacePixels = 800L,
            allocatedSurfacePixels = 1_024L,
        )
        collector.recordGlUpload(
            uploadedBytes = 2_048L,
            submissionDurationNs = 1_400_000L,
            activeSurfacePixels = 1_200L,
            allocatedSurfacePixels = 2_048L,
        )

        val stats = collector.snapshot()
        assertEquals(1L, stats.metadataReuseCount)
        assertEquals(1L, stats.incrementalAtlasUpdateCount)
        assertEquals(1L, stats.completeAtlasReplacementCount)
        assertEquals(2_096L, stats.nativeCopiedMaskBytes)
        assertEquals(2_096L, stats.glUploadedMaskBytes)
        assertEquals(2.0, stats.glUploadSubmissionMs, 0.001)
        assertEquals(1_200L, stats.activeSurfacePixels)
        assertEquals(2_048L, stats.allocatedSurfacePixels)
    }

    @Test
    fun recordsEmptyChangedBitmapFrame() {
        val collector = AssPerformanceStatsCollector()

        collector.record(1_000_000, AssFrame(null, 1))

        val stats = collector.snapshot()
        assertEquals(1L, stats.renderCount)
        assertEquals(1L, stats.changedRenderCount)
        assertEquals(1L, stats.emptyRenderCount)
        assertEquals(0L, stats.imageCount)
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

    private fun atlasFrame(changed: Int, copiedBytes: Long) = AssAtlasFrame(
        pages = null,
        pageWidths = intArrayOf(64),
        pageHeights = intArrayOf(32),
        quads = intArrayOf(0, 0, 8, 6, 0, 0, 0, 0),
        changed = changed,
        dirtyRects = IntArray(0),
        contentSerial = 1L,
        patches = if (changed == AssAtlasFrame.CHANGE_INCREMENTAL) {
            arrayOf(ByteBuffer.allocateDirect(48))
        } else null,
        patchRects = if (changed == AssAtlasFrame.CHANGE_INCREMENTAL) {
            intArrayOf(0, 0, 0, 8, 6)
        } else IntArray(0),
        activeBounds = intArrayOf(0, 0, 8, 6),
        baseContentSerial = if (changed == AssAtlasFrame.CHANGE_INCREMENTAL) 0L else 1L,
        copiedMaskBytes = copiedBytes,
    )
}
