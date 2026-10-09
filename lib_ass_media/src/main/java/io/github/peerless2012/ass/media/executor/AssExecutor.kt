package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes libass rendering and keeps at most one pending request.
 *
 * Rendering can take longer than a video-frame budget for complex ASS effects. When that happens,
 * intermediate requests are superseded and only the newest timestamp is retained. This prevents an
 * ever-growing queue of stale subtitle frames while keeping state publication thread-safe.
 */
class AssExecutor internal constructor(
    private val frameRenderer: (Long, AssTexType) -> AssFrame?,
    private val renderWaitTimeoutMs: Long,
    private val statsCollector: AssPerformanceStatsCollector? = null,
) {

    constructor(render: AssRender) : this(render::renderFrame, DEFAULT_RENDER_WAIT_TIMEOUT_MS)

    internal constructor(frameRenderer: (Long, AssTexType) -> AssFrame?) :
        this(frameRenderer, DEFAULT_RENDER_WAIT_TIMEOUT_MS)

    internal constructor(
        frameRenderer: (Long, AssTexType) -> AssFrame?,
        statsCollector: AssPerformanceStatsCollector?
    ) : this(frameRenderer, DEFAULT_RENDER_WAIT_TIMEOUT_MS, statsCollector)

    private val assFrameNotChanged = AssFrame(null, 0)
    private val executor = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private val completedSyncFrame = AtomicReference<SyncCompletion?>(null)

    // Guarded by stateLock.
    private var pendingRequest: RenderRequest? = null
    private var activeRequest: RenderRequest? = null
    private var workerScheduled = false
    private var shutdownRequested = false

    public fun renderFrame(presentationTimeUs: Long, type: AssTexType): AssFrame? {
        // A render that exceeded the previous call's deadline is still useful on the next frame.
        val deferredFrame = consumeDeferredFrame()
        val completion = SyncCompletion()
        enqueue(
            RenderRequest(
                presentationTimeUs = presentationTimeUs,
                type = type,
                syncCompletion = completion,
            )
        )

        // Keep rendering the current timestamp, but return the most recently completed frame now.
        if (deferredFrame != null) {
            return deferredFrame.frame
        }

        var timedOut = false
        try {
            if (completion.await(renderWaitTimeoutMs, TimeUnit.MILLISECONDS)) {
                consumeDeferredFrame(except = completion)?.let { deferredCompletion ->
                    completedSyncFrame.compareAndSet(null, completion)
                    return deferredCompletion.frame
                }
                if (!completion.tryConsume()) {
                    return assFrameNotChanged
                }
                completedSyncFrame.compareAndSet(completion, null)
                return completion.frame
            } else {
                timedOut = true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (timedOut) {
            statsCollector?.recordExecutorTimeout()
        }

        // Cover the boundary where rendering completed just after await timed out.
        if (completion.isComplete && completion.tryConsume()) {
            completedSyncFrame.compareAndSet(completion, null)
            return completion.frame
        }

        // Another request may have completed while this call was waiting.
        consumeDeferredFrame()?.let { deferredCompletion ->
            return deferredCompletion.frame
        }

        return assFrameNotChanged
    }

    public fun asyncRenderFrame(
        presentationTimeUs: Long,
        type: AssTexType,
        callback: (AssFrame?) -> Unit,
    ) {
        enqueue(
            RenderRequest(
                presentationTimeUs = presentationTimeUs,
                type = type,
                callback = callback,
            )
        )
    }

    public fun shutdown() {
        val requestsToCancel = synchronized(stateLock) {
            if (shutdownRequested) {
                return
            }

            shutdownRequested = true
            val requests = listOfNotNull(activeRequest, pendingRequest).distinct()
            pendingRequest = null
            requests
        }

        completedSyncFrame.set(null)

        // Complete outside the lock because callbacks may re-enter this executor.
        requestsToCancel.forEach { request ->
            request.complete(assFrameNotChanged, publishSyncResult = false)
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

        // Complete outside the lock because callbacks may re-enter this executor.
        supersededRequest?.let {
            statsCollector?.recordSupersededRequest()
            it.complete(assFrameNotChanged, publishSyncResult = false)
        }

        if (rejectRequest) {
            request.complete(assFrameNotChanged, publishSyncResult = false)
            return
        }

        if (!shouldStartWorker) {
            return
        }

        try {
            executor.execute(::drainRequests)
        } catch (_: RejectedExecutionException) {
            val rejectedRequest = synchronized(stateLock) {
                shutdownRequested = true
                workerScheduled = false
                pendingRequest.also { pendingRequest = null }
            }
            rejectedRequest?.complete(assFrameNotChanged, publishSyncResult = false)
            executor.shutdown()
        }
    }

    private fun drainRequests() {
        while (true) {
            val request = synchronized(stateLock) {
                val nextRequest = pendingRequest
                if (nextRequest == null) {
                    activeRequest = null
                    workerScheduled = false
                    return
                }

                pendingRequest = null
                activeRequest = nextRequest
                nextRequest
            }

            val frame = try {
                frameRenderer(request.presentationTimeUs / 1000, request.type)
            } catch (_: Exception) {
                null
            }

            val publishSyncResult = synchronized(stateLock) {
                !shutdownRequested
            }
            request.complete(frame, publishSyncResult)

            synchronized(stateLock) {
                if (activeRequest === request) {
                    activeRequest = null
                }
            }
        }
    }

    private fun consumeDeferredFrame(except: SyncCompletion? = null): SyncCompletion? {
        while (true) {
            val completion = completedSyncFrame.get() ?: return null
            if (completion === except) {
                return null
            }
            if (!completedSyncFrame.compareAndSet(completion, null)) {
                continue
            }
            if (completion.tryConsume()) {
                return completion
            }
        }
    }

    private inner class RenderRequest(
        val presentationTimeUs: Long,
        val type: AssTexType,
        val syncCompletion: SyncCompletion? = null,
        val callback: ((AssFrame?) -> Unit)? = null,
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(frame: AssFrame?, publishSyncResult: Boolean) {
            if (!completed.compareAndSet(false, true)) {
                return
            }

            syncCompletion?.let { completion ->
                completion.setFrame(frame)
                if (publishSyncResult) {
                    // Publish before waking a waiting caller so it can remove this exact result.
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

    private class SyncCompletion {
        private val latch = CountDownLatch(1)
        private val consumed = AtomicBoolean(false)

        @Volatile
        var frame: AssFrame? = null
            private set

        val isComplete: Boolean
            get() = latch.count == 0L

        fun setFrame(frame: AssFrame?) {
            this.frame = frame
        }

        fun signal() {
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean {
            return latch.await(timeout, unit)
        }

        fun tryConsume(): Boolean {
            return consumed.compareAndSet(false, true)
        }
    }

    private companion object {
        const val DEFAULT_RENDER_WAIT_TIMEOUT_MS = 8L
    }
}
