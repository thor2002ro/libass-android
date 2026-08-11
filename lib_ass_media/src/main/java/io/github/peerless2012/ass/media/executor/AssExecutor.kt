package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector

/** Latest-frame-only executor for bitmap/texture ASS rendering. */
class AssExecutor internal constructor(
    frameRenderer: (timeMs: Long, type: AssTexType) -> AssFrame?,
    renderWaitTimeoutMs: Long,
    statsCollector: AssPerformanceStatsCollector? = null,
) {

    constructor(render: AssRender) : this(render::renderFrame, DEFAULT_RENDER_WAIT_TIMEOUT_MS)

    internal constructor(frameRenderer: (Long, AssTexType) -> AssFrame?) :
        this(frameRenderer, DEFAULT_RENDER_WAIT_TIMEOUT_MS)

    internal constructor(
        frameRenderer: (Long, AssTexType) -> AssFrame?,
        statsCollector: AssPerformanceStatsCollector?
    ) : this(frameRenderer, DEFAULT_RENDER_WAIT_TIMEOUT_MS, statsCollector)

    private val delegate = CoalescingFrameExecutor(
        renderer = { presentationTimeUs: Long, type: AssTexType ->
            frameRenderer(presentationTimeUs / 1_000L, type)
        },
        unchangedFrame = AssFrame(null, 0),
        renderWaitTimeoutMs = renderWaitTimeoutMs,
        threadName = "AssRender",
        onTimeout = { statsCollector?.recordExecutorTimeout() },
        onSuperseded = { statsCollector?.recordSupersededRequest() },
    )

    fun renderFrame(presentationTimeUs: Long, type: AssTexType): AssFrame? =
        delegate.renderFrame(presentationTimeUs, type)

    fun asyncRenderFrame(
        presentationTimeUs: Long,
        type: AssTexType,
        callback: (AssFrame?) -> Unit,
    ) = delegate.asyncRenderFrame(presentationTimeUs, type, callback)

    fun shutdown() = delegate.shutdown()

    private companion object {
        const val DEFAULT_RENDER_WAIT_TIMEOUT_MS = 8L
    }
}
