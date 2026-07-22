package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTexType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssExecutorTest {

    @Test
    fun asyncRequestsKeepOnlyLatestPendingTimestamp() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val renderTimes = CopyOnWriteArrayList<Long>()
        val executor = AssExecutor { timeMs, _ ->
            renderTimes += timeMs
            if (timeMs == 1L) {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
            AssFrame(null, timeMs.toInt())
        }

        val firstFrame = AtomicReference<AssFrame?>()
        val secondFrame = AtomicReference<AssFrame?>()
        val thirdFrame = AtomicReference<AssFrame?>()
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val thirdDone = CountDownLatch(1)

        try {
            executor.asyncRenderFrame(1_000, AssTexType.BITMAP_ALPHA) {
                firstFrame.set(it)
                firstDone.countDown()
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            executor.asyncRenderFrame(2_000, AssTexType.BITMAP_ALPHA) {
                secondFrame.set(it)
                secondDone.countDown()
            }
            executor.asyncRenderFrame(3_000, AssTexType.BITMAP_ALPHA) {
                thirdFrame.set(it)
                thirdDone.countDown()
            }

            // The second request was pending and is superseded by the third request.
            assertTrue(secondDone.await(2, TimeUnit.SECONDS))
            assertEquals(0, secondFrame.get()?.changed)

            releaseFirst.countDown()
            assertTrue(firstDone.await(2, TimeUnit.SECONDS))
            assertTrue(thirdDone.await(2, TimeUnit.SECONDS))

            assertEquals(1, firstFrame.get()?.changed)
            assertEquals(3, thirdFrame.get()?.changed)
            assertEquals(listOf(1L, 3L), renderTimes.toList())
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
        }
    }

    @Test
    fun callbackFailureDoesNotTerminateRenderWorker() {
        val renderTimes = CopyOnWriteArrayList<Long>()
        val firstCallback = CountDownLatch(1)
        val secondCallback = CountDownLatch(1)
        val executor = AssExecutor { timeMs, _ ->
            renderTimes += timeMs
            AssFrame(null, timeMs.toInt())
        }

        try {
            executor.asyncRenderFrame(1_000, AssTexType.BITMAP_ALPHA) {
                firstCallback.countDown()
                error("callback failure")
            }
            assertTrue(firstCallback.await(2, TimeUnit.SECONDS))

            executor.asyncRenderFrame(2_000, AssTexType.BITMAP_ALPHA) {
                assertEquals(2, it?.changed)
                secondCallback.countDown()
            }

            assertTrue(secondCallback.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), renderTimes.toList())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun shutdownCompletesActiveAndPendingCallbacksWithoutRenderingPendingFrame() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val renderTimes = CopyOnWriteArrayList<Long>()
        val executor = AssExecutor { timeMs, _ ->
            renderTimes += timeMs
            if (timeMs == 1L) {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                firstReturned.countDown()
            }
            AssFrame(null, timeMs.toInt())
        }

        val firstFrame = AtomicReference<AssFrame?>()
        val secondFrame = AtomicReference<AssFrame?>()
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        executor.asyncRenderFrame(1_000, AssTexType.BITMAP_ALPHA) {
            firstFrame.set(it)
            firstDone.countDown()
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        executor.asyncRenderFrame(2_000, AssTexType.BITMAP_ALPHA) {
            secondFrame.set(it)
            secondDone.countDown()
        }
        executor.shutdown()

        assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        assertTrue(secondDone.await(2, TimeUnit.SECONDS))
        assertEquals(0, firstFrame.get()?.changed)
        assertEquals(0, secondFrame.get()?.changed)

        releaseFirst.countDown()
        assertTrue(firstReturned.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1L), renderTimes.toList())
    }

    @Test
    fun timedOutSynchronousFrameIsReturnedOnNextCall() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val executor = AssExecutor(
            frameRenderer = { timeMs, _ ->
                if (timeMs == 1L) {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    firstFinished.countDown()
                }
                AssFrame(null, timeMs.toInt())
            },
            renderWaitTimeoutMs = 1,
        )

        try {
            val firstFrame = executor.renderFrame(1_000, AssTexType.BITMAP_ALPHA)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertEquals(0, firstFrame?.changed)

            releaseFirst.countDown()
            assertTrue(firstFinished.await(2, TimeUnit.SECONDS))

            val secondFrame = executor.renderFrame(2_000, AssTexType.BITMAP_ALPHA)
            assertEquals(1, secondFrame?.changed)
        } finally {
            releaseFirst.countDown()
            executor.shutdown()
        }
    }

    @Test
    fun synchronousRenderReturnsCompletedFrame() {
        val executor = AssExecutor(
            frameRenderer = { timeMs, _ -> AssFrame(null, timeMs.toInt()) },
            renderWaitTimeoutMs = 1_000,
        )

        try {
            val frame = executor.renderFrame(42_000, AssTexType.BITMAP_ALPHA)
            assertEquals(42, frame?.changed)
        } finally {
            executor.shutdown()
        }
    }
}
