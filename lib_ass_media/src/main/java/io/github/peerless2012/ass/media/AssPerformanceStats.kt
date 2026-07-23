package io.github.peerless2012.ass.media

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssFrame
import java.util.Locale

data class AssPerformanceStats(
    /** Frames that reached the measured libass render path. */
    val renderCount: Long = 0,

    /** Frames where libass reported changed subtitle content. */
    val changedRenderCount: Long = 0,

    /** Frames with no subtitle images. */
    val emptyRenderCount: Long = 0,

    /** Total subtitle images returned across all rendered frames. */
    val imageCount: Long = 0,

    /** Rendered frames slower than the collector's threshold. */
    val slowRenderCount: Long = 0,

    /** Largest number of subtitle images returned by one frame. */
    val maxImageCount: Int = 0,

    /** Largest subtitle image area returned by one frame, in pixels. */
    val maxBitmapPixels: Long = 0,

    /** Sum of subtitle image areas returned across all frames, in pixels. */
    val totalBitmapPixels: Long = 0,

    /** Synchronous executor renders that missed the wait budget. */
    val executorTimeoutCount: Long = 0,

    /** Queued executor requests replaced by a newer timestamp. */
    val supersededRequestCount: Long = 0,

    /** Wall-clock span between the first and latest measured render. */
    val elapsedMs: Double = 0.0,

    /** Measured subtitle render calls per second over elapsedMs. */
    val fps: Double = 0.0,

    /** Fraction of measured renders where libass reported changed content. */
    val changedRatio: Double = 0.0,

    /** Mean measured render duration. */
    val averageRenderMs: Double = 0.0,

    /** Fastest measured render duration. */
    val minRenderMs: Double = 0.0,

    /** Slowest measured render duration. */
    val maxRenderMs: Double = 0.0,

    /** Most recent measured render duration. */
    val lastRenderMs: Double = 0.0,
) {
    /** Single-line summary for app-owned debug UI or manual logging. */
    fun toSummaryString(): String = String.format(
        Locale.US,
        "fps=%.1f renderMs(avg/min/max/last)=%.2f/%.2f/%.2f/%.2f frames=%d changed=%d changedRatio=%.2f empty=%d images=%d maxImages=%d slow=%d executorTimeouts=%d superseded=%d maxBitmapPixels=%d totalBitmapPixels=%d",
        fps,
        averageRenderMs,
        minRenderMs,
        maxRenderMs,
        lastRenderMs,
        renderCount,
        changedRenderCount,
        changedRatio,
        emptyRenderCount,
        imageCount,
        maxImageCount,
        slowRenderCount,
        executorTimeoutCount,
        supersededRequestCount,
        maxBitmapPixels,
        totalBitmapPixels
    )
}

class AssPerformanceStatsCollector(
    /** Render duration above this is counted in slowRenderCount. */
    val slowRenderThresholdMs: Double = 16.67
) {
    private val recorder = AssPerformanceStatsRecorder(slowRenderThresholdMs = slowRenderThresholdMs)

    fun snapshot(): AssPerformanceStats = recorder.snapshot()

    fun reset() {
        recorder.reset()
    }

    internal fun record(renderDurationNs: Long, frame: AssFrame?) {
        recorder.record(renderDurationNs, frame)
    }

    internal fun record(renderDurationNs: Long, frame: AssAtlasFrame?) {
        recorder.record(renderDurationNs, frame)
    }

    internal fun recordExecutorTimeout() {
        recorder.recordExecutorTimeout()
    }

    internal fun recordSupersededRequest() {
        recorder.recordSupersededRequest()
    }
}

internal class AssPerformanceStatsRecorder(
    private val nowNs: () -> Long = System::nanoTime,
    private val slowRenderThresholdMs: Double = 16.67
) {
    private val slowRenderThresholdNs = when {
        slowRenderThresholdMs.isNaN() -> Long.MAX_VALUE
        slowRenderThresholdMs <= 0.0 -> -1L
        slowRenderThresholdMs >= Long.MAX_VALUE / NANOS_PER_MILLI -> Long.MAX_VALUE
        else -> (slowRenderThresholdMs * NANOS_PER_MILLI).toLong()
    }

    private var firstRenderNs = 0L
    private var lastRenderNs = 0L
    private var renderCount = 0L
    private var changedRenderCount = 0L
    private var emptyRenderCount = 0L
    private var imageCount = 0L
    private var slowRenderCount = 0L
    private var maxImageCount = 0
    private var maxBitmapPixels = 0L
    private var totalBitmapPixels = 0L
    private var executorTimeoutCount = 0L
    private var supersededRequestCount = 0L
    private var totalRenderNs = 0L
    private var minRenderNs = Long.MAX_VALUE
    private var maxRenderNs = 0L
    private var lastRenderDurationNs = 0L

    @Synchronized
    fun record(renderDurationNs: Long, frame: AssFrame?) {
        val images = frame?.images
        recordHeader(renderDurationNs, frame?.changed ?: 0, images?.size ?: 0)
        images?.forEach { image ->
            recordImagePixels(image.w.toLong() * image.h)
        }
    }

    @Synchronized
    fun record(renderDurationNs: Long, frame: AssAtlasFrame?) {
        val imageCount = frame?.imageCount ?: 0
        recordHeader(renderDurationNs, frame?.changed ?: AssAtlasFrame.CHANGE_NONE, imageCount)
        val quads = frame?.quads ?: return
        var offset = 0
        repeat(imageCount) {
            recordImagePixels(
                quads[offset + AssAtlasFrame.QUAD_WIDTH].toLong() *
                    quads[offset + AssAtlasFrame.QUAD_HEIGHT]
            )
            offset += AssAtlasFrame.QUAD_STRIDE
        }
    }

    private fun recordHeader(
        renderDurationNs: Long,
        changed: Int,
        imageCount: Int,
    ) {
        val now = nowNs()
        if (renderCount == 0L) {
            firstRenderNs = now
        }
        lastRenderNs = now
        renderCount++

        if (changed != 0) {
            changedRenderCount++
        }
        if (imageCount == 0) {
            emptyRenderCount++
        } else {
            this.imageCount += imageCount
            maxImageCount = maxOf(maxImageCount, imageCount)
        }

        val durationNs = renderDurationNs.coerceAtLeast(0)
        if (durationNs > slowRenderThresholdNs) {
            slowRenderCount++
        }
        totalRenderNs += durationNs
        minRenderNs = minOf(minRenderNs, durationNs)
        maxRenderNs = maxOf(maxRenderNs, durationNs)
        lastRenderDurationNs = durationNs
    }

    private fun recordImagePixels(pixels: Long) {
        if (pixels <= 0L) return
        maxBitmapPixels = maxOf(maxBitmapPixels, pixels)
        totalBitmapPixels += pixels
    }

    @Synchronized
    fun recordExecutorTimeout() {
        executorTimeoutCount++
    }

    @Synchronized
    fun recordSupersededRequest() {
        supersededRequestCount++
    }

    @Synchronized
    fun reset() {
        firstRenderNs = 0L
        lastRenderNs = 0L
        renderCount = 0L
        changedRenderCount = 0L
        emptyRenderCount = 0L
        imageCount = 0L
        slowRenderCount = 0L
        maxImageCount = 0
        maxBitmapPixels = 0L
        totalBitmapPixels = 0L
        executorTimeoutCount = 0L
        supersededRequestCount = 0L
        totalRenderNs = 0L
        minRenderNs = Long.MAX_VALUE
        maxRenderNs = 0L
        lastRenderDurationNs = 0L
    }

    @Synchronized
    fun snapshot(): AssPerformanceStats {
        val elapsedNs = (lastRenderNs - firstRenderNs).coerceAtLeast(0)
        return AssPerformanceStats(
            renderCount = renderCount,
            changedRenderCount = changedRenderCount,
            emptyRenderCount = emptyRenderCount,
            imageCount = imageCount,
            slowRenderCount = slowRenderCount,
            maxImageCount = maxImageCount,
            maxBitmapPixels = maxBitmapPixels,
            totalBitmapPixels = totalBitmapPixels,
            executorTimeoutCount = executorTimeoutCount,
            supersededRequestCount = supersededRequestCount,
            elapsedMs = elapsedNs.toMs(),
            fps = if (renderCount > 0L && elapsedNs > 0L) renderCount * 1_000_000_000.0 / elapsedNs else 0.0,
            changedRatio = if (renderCount > 0L) changedRenderCount.toDouble() / renderCount else 0.0,
            averageRenderMs = if (renderCount > 0L) (totalRenderNs / renderCount).toMs() else 0.0,
            minRenderMs = if (minRenderNs != Long.MAX_VALUE) minRenderNs.toMs() else 0.0,
            maxRenderMs = maxRenderNs.toMs(),
            lastRenderMs = lastRenderDurationNs.toMs(),
        )
    }

    private fun Long.toMs(): Double = this / 1_000_000.0

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
