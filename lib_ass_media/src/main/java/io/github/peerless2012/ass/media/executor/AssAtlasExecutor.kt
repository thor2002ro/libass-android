package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssAtlasFrame

/** Latest-frame-only executor for batched atlas rendering. */
internal class AssAtlasExecutor internal constructor(
    frameRenderer: (timeMs: Long) -> AssAtlasFrame?,
    renderWaitTimeoutMs: Long = DEFAULT_RENDER_WAIT_TIMEOUT_MS,
) {
    private val delegate = CoalescingFrameExecutor(
        renderer = { presentationTimeUs: Long, _: Unit ->
            frameRenderer(presentationTimeUs / 1_000L)
        },
        unchangedFrame = AssAtlasFrame.unchanged(),
        renderWaitTimeoutMs = renderWaitTimeoutMs,
        threadName = "AssAtlasRender",
    )

    fun renderFrame(presentationTimeUs: Long): AssAtlasFrame? =
        delegate.renderFrame(presentationTimeUs, Unit)

    fun shutdown() = delegate.shutdown()

    private companion object {
        const val DEFAULT_RENDER_WAIT_TIMEOUT_MS = 8L
    }
}
