package io.github.peerless2012.ass.media.executor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-worker, latest-request-only scheduler.
 *
 * At most one render is active and one render is pending. A newer request
 * supersedes a stale pending request, preventing animated subtitles from
 * building a latency-producing queue.
 */
internal class CoalescingFrameExecutor<P, T>(
    private val renderer: (presentationTimeUs: Long, parameter: P) -> T?,
    private val unchangedFrame: T,
    private val renderWaitTimeoutMs: Long,
    threadName: String,
    private val onTimeout: () -> Unit = {},
    private val onSuperseded: () -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }
    private val stateLock = Any()
    private val completedSyncFrame = AtomicReference<SyncCompletion<T>?>(null)

    // Guarded by stateLock.
    private var pendingRequest: RenderRequest? = null
    private var activeRequest: RenderRequest? = null
    private var workerScheduled = false
    private var shutdownRequested = false

    fun renderFrame(presentationTimeUs: Long, parameter: P): T? {
        // A render that exceeded the previous call's deadline is still useful
        // on the next video frame.
        val deferredFrame = consumeDeferredFrame(presentationTimeUs)
        val completion = SyncCompletion<T>(presentationTimeUs)
        enqueue(
            RenderRequest(
                presentationTimeUs = presentationTimeUs,
                parameter = parameter,
                syncCompletion = completion,
            )
        )

        // Continue rendering the newest timestamp, but make the late result
        // from the previous request available immediately.
        if (deferredFrame != null) return deferredFrame.frame

        var timedOut = false
        try {
            if (completion.await(renderWaitTimeoutMs, TimeUnit.MILLISECONDS)) {
                consumeDeferredFrame(presentationTimeUs, except = completion)?.let { deferredCompletion ->
                    completedSyncFrame.compareAndSet(null, completion)
                    return deferredCompletion.frame
                }
                if (!completion.tryConsume()) return unchangedFrame
                completedSyncFrame.compareAndSet(completion, null)
                return completion.frame
            } else {
                timedOut = true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (timedOut) onTimeout()

        // Cover completion on the timeout boundary.
        if (completion.isComplete && completion.tryConsume()) {
            completedSyncFrame.compareAndSet(completion, null)
            return completion.frame
        }

        consumeDeferredFrame(presentationTimeUs)?.let { return it.frame }
        return unchangedFrame
    }

    fun asyncRenderFrame(
        presentationTimeUs: Long,
        parameter: P,
        callback: (T?) -> Unit,
    ) {
        enqueue(
            RenderRequest(
                presentationTimeUs = presentationTimeUs,
                parameter = parameter,
                callback = callback,
            )
        )
    }

    fun shutdown() {
        val requestsToCancel = synchronized(stateLock) {
            if (shutdownRequested) return
            shutdownRequested = true
            listOfNotNull(activeRequest, pendingRequest).distinct().also {
                pendingRequest = null
            }
        }

        completedSyncFrame.set(null)
        requestsToCancel.forEach { request ->
            request.complete(unchangedFrame, publishSyncResult = false)
        }
        executor.shutdown()
    }

    private fun enqueue(request: RenderRequest) {
        var supersededRequest: RenderRequest? = null
        var shouldStartWorker = false
        var rejectRequest = false

        synchronized(stateLock) {
            if (shutdownRequested) {
                rejectRequest = true
            } else {
                supersededRequest = pendingRequest
                pendingRequest = request
                if (!workerScheduled) {
                    workerScheduled = true
                    shouldStartWorker = true
                }
            }
        }

        // Complete outside the lock because client callbacks may re-enter.
        supersededRequest?.let {
            onSuperseded()
            it.complete(unchangedFrame, publishSyncResult = false)
        }

        if (rejectRequest) {
            request.complete(unchangedFrame, publishSyncResult = false)
            return
        }
        if (!shouldStartWorker) return

        try {
            executor.execute(::drainRequests)
        } catch (_: RejectedExecutionException) {
            val rejected = synchronized(stateLock) {
                shutdownRequested = true
                workerScheduled = false
                pendingRequest.also { pendingRequest = null }
            }
            rejected?.complete(unchangedFrame, publishSyncResult = false)
            executor.shutdown()
        }
    }

    private fun drainRequests() {
        while (true) {
            val request = synchronized(stateLock) {
                val next = pendingRequest
                if (next == null) {
                    activeRequest = null
                    workerScheduled = false
                    return
                }
                pendingRequest = null
                activeRequest = next
                next
            }

            val frame = try {
                renderer(request.presentationTimeUs, request.parameter)
            } catch (_: Exception) {
                null
            }

            val publishSyncResult = synchronized(stateLock) { !shutdownRequested }
            request.complete(frame, publishSyncResult)

            synchronized(stateLock) {
                if (activeRequest === request) activeRequest = null
            }
        }
    }

    private fun consumeDeferredFrame(
        requestedTimeUs: Long,
        except: SyncCompletion<T>? = null,
    ): SyncCompletion<T>? {
        while (true) {
            val completion = completedSyncFrame.get() ?: return null
            if (completion === except) return null
            if (!completedSyncFrame.compareAndSet(completion, null)) continue
            // Never display a frame from the future after a backward seek.
            if (completion.presentationTimeUs > requestedTimeUs) {
                completion.tryConsume()
                continue
            }
            if (completion.tryConsume()) return completion
        }
    }

    private inner class RenderRequest(
        val presentationTimeUs: Long,
        val parameter: P,
        val syncCompletion: SyncCompletion<T>? = null,
        val callback: ((T?) -> Unit)? = null,
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(frame: T?, publishSyncResult: Boolean) {
            if (!completed.compareAndSet(false, true)) return

            syncCompletion?.let { completion ->
                completion.setFrame(frame)
                if (publishSyncResult) {
                    completedSyncFrame.compareAndSet(null, completion)
                }
                completion.signal()
            }

            callback?.let { renderCallback ->
                try {
                    renderCallback(frame)
                } catch (_: Exception) {
                    // A client callback must not terminate the render worker.
                }
            }
        }
    }

    private class SyncCompletion<T>(
        val presentationTimeUs: Long,
    ) {
        private val latch = CountDownLatch(1)
        private val consumed = AtomicBoolean(false)

        @Volatile
        var frame: T? = null
            private set

        val isComplete: Boolean
            get() = latch.count == 0L

        fun setFrame(frame: T?) {
            this.frame = frame
        }

        fun signal() = latch.countDown()

        fun await(timeout: Long, unit: TimeUnit): Boolean = latch.await(timeout, unit)

        fun tryConsume(): Boolean = consumed.compareAndSet(false, true)
    }
}
