package io.github.peerless2012.ass.media.executor

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val syncHandoff = SyncHandoff<T>()

    // Guarded by stateLock.
    private var pendingRequest: RenderRequest? = null
    private var activeRequest: RenderRequest? = null
    private var workerScheduled = false
    private var shutdownRequested = false

    @Synchronized
    fun renderFrame(presentationTimeUs: Long, parameter: P): T? {
        // A render that exceeded the previous call's deadline is still useful
        // on the next video frame.
        val deferredFrame = consumeDeferredFrame(presentationTimeUs)
        val generation = syncHandoff.begin(presentationTimeUs)
        enqueue(
            RenderRequest(
                presentationTimeUs = presentationTimeUs,
                parameter = parameter,
                syncGeneration = generation,
            )
        )

        // Continue rendering the newest timestamp, but make the late result
        // from the previous request available immediately.
        if (deferredFrame != null) return deferredFrame.frame

        var timedOut = false
        try {
            if (syncHandoff.await(generation, renderWaitTimeoutMs, TimeUnit.MILLISECONDS)) {
                // A request that missed the previous deadline may finish just
                // before this one. Publish that older usable frame first so a
                // fast current render cannot skip it.
                consumeDeferredFrame(presentationTimeUs, generation)?.let { return it.frame }
                syncHandoff.take(generation)?.let { return it.frame }
                return unchangedFrame
            } else {
                timedOut = true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (timedOut) onTimeout()

        // Cover completion on the timeout boundary.
        syncHandoff.take(generation)?.let { return it.frame }

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

        syncHandoff.clear()
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
        except: Long? = null,
    ): SyncResult<T>? = syncHandoff.takeDeferred(requestedTimeUs, except)

    private inner class RenderRequest(
        val presentationTimeUs: Long,
        val parameter: P,
        val syncGeneration: Long? = null,
        val callback: ((T?) -> Unit)? = null,
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(frame: T?, publishSyncResult: Boolean) {
            if (!completed.compareAndSet(false, true)) return

            syncGeneration?.let { generation ->
                syncHandoff.complete(generation, presentationTimeUs, frame, publishSyncResult)
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

    private data class SyncResult<T>(val frame: T?)

    private class SyncHandoff<T> {
        private val lock = ReentrantLock()
        private val completed = lock.newCondition()
        private var nextGeneration = 0L
        private var currentGeneration = 0L
        private var currentTimeUs = 0L
        private var currentFrame: T? = null
        private var currentComplete = false
        private var currentConsumed = true
        private var deferredGeneration = 0L
        private var deferredTimeUs = 0L
        private var deferredFrame: T? = null
        private var deferredAvailable = false

        fun begin(presentationTimeUs: Long): Long = lock.withLock {
            currentGeneration = ++nextGeneration
            currentTimeUs = presentationTimeUs
            currentFrame = null
            currentComplete = false
            currentConsumed = false
            currentGeneration
        }

        fun complete(generation: Long, presentationTimeUs: Long, frame: T?, publish: Boolean) =
            lock.withLock {
                if (generation == currentGeneration) {
                    currentFrame = frame
                    currentComplete = true
                } else if (publish && generation < currentGeneration &&
                    (!deferredAvailable || generation > deferredGeneration)
                ) {
                    deferredGeneration = generation
                    deferredTimeUs = presentationTimeUs
                    deferredFrame = frame
                    deferredAvailable = true
                }
                completed.signalAll()
            }

        fun await(generation: Long, timeout: Long, unit: TimeUnit): Boolean = lock.withLock {
            var remainingNs = unit.toNanos(timeout)
            while (generation == currentGeneration && !currentComplete && remainingNs > 0L) {
                remainingNs = completed.awaitNanos(remainingNs)
            }
            generation == currentGeneration && currentComplete
        }

        fun take(generation: Long): SyncResult<T>? = lock.withLock {
            if (generation != currentGeneration || !currentComplete || currentConsumed) return null
            currentConsumed = true
            SyncResult(currentFrame)
        }

        fun takeDeferred(requestedTimeUs: Long, except: Long?): SyncResult<T>? = lock.withLock {
            if (currentComplete && !currentConsumed && currentGeneration != except) {
                currentConsumed = true
                if (currentTimeUs <= requestedTimeUs) return SyncResult(currentFrame)
            }
            if (deferredAvailable && deferredGeneration != except) {
                deferredAvailable = false
                if (deferredTimeUs <= requestedTimeUs) return SyncResult(deferredFrame)
            }
            null
        }

        fun clear() = lock.withLock {
            currentConsumed = true
            deferredAvailable = false
            completed.signalAll()
        }
    }
}
