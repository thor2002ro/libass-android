package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssAtlasExecutorTest {

    @Test
    fun timeoutIsReportedToStatsCollector() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stats = AssPerformanceStatsCollector()
        val executor = AssAtlasExecutor(
            frameRenderer = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                AssAtlasFrame.unchanged()
            },
            renderWaitTimeoutMs = 1,
            statsCollector = stats,
        )

        try {
            executor.renderFrame(1_000)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(1L, stats.snapshot().executorTimeoutCount)
        } finally {
            release.countDown()
            executor.shutdown()
        }
    }

    @Test
    fun supersededPendingRequestIsReportedToStatsCollector() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        val renderTimes = CopyOnWriteArrayList<Long>()
        val stats = AssPerformanceStatsCollector()
        val executor = AssAtlasExecutor(
            frameRenderer = { timeMs ->
                renderTimes += timeMs
                if (timeMs == 1L) {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                if (timeMs == 3L) {
                    thirdStarted.countDown()
                }
                AssAtlasFrame.unchanged()
            },
            statsCollector = stats,
        )

        try {
            executor.renderFrame(1_000)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            executor.renderFrame(2_000)
            executor.renderFrame(3_000)

            releaseFirst.countDown()
            assertTrue(thirdStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1L, stats.snapshot().supersededRequestCount)
            assertEquals(listOf(1L, 3L), renderTimes.toList())
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
        }
    }
}
