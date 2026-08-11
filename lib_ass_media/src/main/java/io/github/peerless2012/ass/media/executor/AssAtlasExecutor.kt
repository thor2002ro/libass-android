package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector

/** Latest-frame-only executor for batched atlas rendering. */
internal class AssAtlasExecutor internal constructor(
    frameRenderer: (timeMs: Long) -> AssAtlasFrame?,
    renderWaitTimeoutMs: Long = DEFAULT_RENDER_WAIT_TIMEOUT_MS,
    statsCollector: AssPerformanceStatsCollector? = null,
) {
    private val delegate = CoalescingFrameExecutor(
        renderer = { presentationTimeUs: Long, _: Unit ->
            frameRenderer(presentationTimeUs / 1_000L)
        },
        unchangedFrame = AssAtlasFrame.unchanged(),
        renderWaitTimeoutMs = renderWaitTimeoutMs,
        threadName = "AssAtlasRender",
        onTimeout = { statsCollector?.recordExecutorTimeout() },
        onSuperseded = { statsCollector?.recordSupersededRequest() },
    )

    fun renderFrame(presentationTimeUs: Long): AssAtlasFrame? =
        delegate.renderFrame(presentationTimeUs, Unit)

    fun shutdown() = delegate.shutdown()

    private companion object {
        const val DEFAULT_RENDER_WAIT_TIMEOUT_MS = 8L
    }
}
