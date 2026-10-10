package io.github.peerless2012.ass.media

import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssFrame
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

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

    /** Atlas pages uploaded across content-changing atlas frames. */
    val atlasUploadPageCount: Long = 0,

    /** Largest number of atlas pages uploaded by one content-changing frame. */
    val maxAtlasUploadPageCount: Int = 0,

    /** Largest uploaded atlas page area, in pixels. */
    val maxAtlasUploadPagePixels: Long = 0,

    /** Sum of uploaded atlas page areas, in pixels. */
    val totalAtlasUploadPagePixels: Long = 0,

    /** Atlas frames that reused masks and changed only geometry or color. */
    val metadataReuseCount: Long = 0,

    /** Atlas frames that replaced only changed masks in a stable layout. */
    val incrementalAtlasUpdateCount: Long = 0,

    /** Atlas frames that replaced the complete atlas layout. */
    val completeAtlasReplacementCount: Long = 0,

    /** Mask bytes copied into owned native frame buffers. */
    val nativeCopiedMaskBytes: Long = 0,

    /** Mask bytes submitted to OpenGL texture uploads. */
    val glUploadedMaskBytes: Long = 0,

    /** Cumulative CPU time spent submitting OpenGL texture uploads. */
    val glUploadSubmissionMs: Double = 0.0,

    /** Visible subtitle surface area for the latest submitted frame. */
    val activeSurfacePixels: Long = 0,

    /** Allocated subtitle surface area for the latest submitted frame. */
    val allocatedSurfacePixels: Long = 0,

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

    /** OpenGL path actually used by the subtitle renderer. */
    val openGlMode: String? = null,
) {
    /** Single-line summary for app-owned debug UI or manual logging. */
    fun toSummaryString(): String = String.format(
        Locale.US,
        "fps=%.1f renderMs(avg/min/max/last)=%.2f/%.2f/%.2f/%.2f frames=%d changed=%d changedRatio=%.2f empty=%d images=%d maxImages=%d slow=%d executorTimeouts=%d superseded=%d maxBitmapPixels=%d totalBitmapPixels=%d atlasUploadPages=%d maxAtlasUploadPages=%d maxAtlasUploadPixels=%d totalAtlasUploadPixels=%d",
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
        totalBitmapPixels,
        atlasUploadPageCount,
        maxAtlasUploadPageCount,
        maxAtlasUploadPagePixels,
        totalAtlasUploadPagePixels
    )
}

class AssPerformanceStatsCollector(
    /** Render duration above this is counted in slowRenderCount. */
    val slowRenderThresholdMs: Double = 16.67
) {
    private val recorder = AssPerformanceStatsRecorder(slowRenderThresholdMs = slowRenderThresholdMs)
    private val openGlMode = AtomicReference<String?>(null)

    fun snapshot(): AssPerformanceStats = recorder.snapshot().copy(openGlMode = openGlMode.get())

    fun reset() {
        recorder.reset()
        openGlMode.set(null)
    }

    fun recordOpenGlMode(mode: String) {
        openGlMode.set(mode)
    }

    internal fun record(renderDurationNs: Long, frame: AssFrame?) {
        recorder.record(renderDurationNs, frame)
    }

    internal fun record(renderDurationNs: Long, frame: AssAtlasFrame?) {
        recorder.record(renderDurationNs, frame)
    }

    internal fun recordGlUpload(
        uploadedBytes: Long,
        submissionDurationNs: Long,
        activeSurfacePixels: Long,
        allocatedSurfacePixels: Long,
    ) {
        recorder.recordGlUpload(
            uploadedBytes,
            submissionDurationNs,
            activeSurfacePixels,
            allocatedSurfacePixels,
        )
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
    private var atlasUploadPageCount = 0L
    private var maxAtlasUploadPageCount = 0
    private var maxAtlasUploadPagePixels = 0L
    private var totalAtlasUploadPagePixels = 0L
    private var metadataReuseCount = 0L
    private var incrementalAtlasUpdateCount = 0L
    private var completeAtlasReplacementCount = 0L
    private var nativeCopiedMaskBytes = 0L
    private var glUploadedMaskBytes = 0L
    private var glUploadSubmissionNs = 0L
    private var activeSurfacePixels = 0L
    private var allocatedSurfacePixels = 0L
    private var executorTimeoutCount = 0L
    private var supersededRequestCount = 0L
    private var totalRenderNs = 0L
    private var minRenderNs = Long.MAX_VALUE
    private var maxRenderNs = 0L
    private var lastRenderDurationNs = 0L
    private val rollingRenderTimesNs = LongArray(ROLLING_RATE_CAPACITY)
    private var rollingRenderWriteIndex = 0
    private var rollingRenderCount = 0

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
        recordAtlasUploads(frame)
        if (frame != null) {
            when (frame.changed) {
                AssAtlasFrame.CHANGE_METADATA -> metadataReuseCount++
                AssAtlasFrame.CHANGE_INCREMENTAL -> incrementalAtlasUpdateCount++
                AssAtlasFrame.CHANGE_REPLACE -> completeAtlasReplacementCount++
            }
            nativeCopiedMaskBytes += frame.copiedMaskBytes.coerceAtLeast(0L)
        }
        frame ?: return
        repeat(imageCount) { image ->
            recordImagePixels(
                frame.quadValue(image, AssAtlasFrame.QUAD_WIDTH).toLong() *
                    frame.quadValue(image, AssAtlasFrame.QUAD_HEIGHT)
            )
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
        rollingRenderTimesNs[rollingRenderWriteIndex] = now
        rollingRenderWriteIndex = (rollingRenderWriteIndex + 1) % rollingRenderTimesNs.size
        if (rollingRenderCount < rollingRenderTimesNs.size) rollingRenderCount++

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

    private fun recordAtlasUploads(frame: AssAtlasFrame?) {
        frame ?: return
        if (frame.changed != AssAtlasFrame.CHANGE_REPLACE) return
        val pageCount = frame.pageCount
        if (pageCount <= 0) return
        atlasUploadPageCount += pageCount
        maxAtlasUploadPageCount = maxOf(maxAtlasUploadPageCount, pageCount)
        repeat(pageCount) { page ->
            recordAtlasUploadPagePixels(frame.pageWidth(page).toLong() * frame.pageHeight(page))
        }
    }

    private fun recordAtlasUploadPagePixels(pixels: Long) {
        if (pixels <= 0L) return
        maxAtlasUploadPagePixels = maxOf(maxAtlasUploadPagePixels, pixels)
        totalAtlasUploadPagePixels += pixels
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
    fun recordGlUpload(
        uploadedBytes: Long,
        submissionDurationNs: Long,
        activeSurfacePixels: Long,
        allocatedSurfacePixels: Long,
    ) {
        glUploadedMaskBytes += uploadedBytes.coerceAtLeast(0L)
        glUploadSubmissionNs += submissionDurationNs.coerceAtLeast(0L)
        this.activeSurfacePixels = activeSurfacePixels.coerceAtLeast(0L)
        this.allocatedSurfacePixels = allocatedSurfacePixels.coerceAtLeast(0L)
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
        atlasUploadPageCount = 0L
        maxAtlasUploadPageCount = 0
        maxAtlasUploadPagePixels = 0L
        totalAtlasUploadPagePixels = 0L
        metadataReuseCount = 0L
        incrementalAtlasUpdateCount = 0L
        completeAtlasReplacementCount = 0L
        nativeCopiedMaskBytes = 0L
        glUploadedMaskBytes = 0L
        glUploadSubmissionNs = 0L
        activeSurfacePixels = 0L
        allocatedSurfacePixels = 0L
        executorTimeoutCount = 0L
        supersededRequestCount = 0L
        totalRenderNs = 0L
        minRenderNs = Long.MAX_VALUE
        maxRenderNs = 0L
        lastRenderDurationNs = 0L
        rollingRenderWriteIndex = 0
        rollingRenderCount = 0
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
            atlasUploadPageCount = atlasUploadPageCount,
            maxAtlasUploadPageCount = maxAtlasUploadPageCount,
            maxAtlasUploadPagePixels = maxAtlasUploadPagePixels,
            totalAtlasUploadPagePixels = totalAtlasUploadPagePixels,
            metadataReuseCount = metadataReuseCount,
            incrementalAtlasUpdateCount = incrementalAtlasUpdateCount,
            completeAtlasReplacementCount = completeAtlasReplacementCount,
            nativeCopiedMaskBytes = nativeCopiedMaskBytes,
            glUploadedMaskBytes = glUploadedMaskBytes,
            glUploadSubmissionMs = glUploadSubmissionNs.toMs(),
            activeSurfacePixels = activeSurfacePixels,
            allocatedSurfacePixels = allocatedSurfacePixels,
            executorTimeoutCount = executorTimeoutCount,
            supersededRequestCount = supersededRequestCount,
            elapsedMs = elapsedNs.toMs(),
            fps = rollingRenderRate(nowNs()),
            changedRatio = if (renderCount > 0L) changedRenderCount.toDouble() / renderCount else 0.0,
            averageRenderMs = if (renderCount > 0L) (totalRenderNs / renderCount).toMs() else 0.0,
            minRenderMs = if (minRenderNs != Long.MAX_VALUE) minRenderNs.toMs() else 0.0,
            maxRenderMs = maxRenderNs.toMs(),
            lastRenderMs = lastRenderDurationNs.toMs(),
        )
    }

    private fun Long.toMs(): Double = this / 1_000_000.0

    private fun rollingRenderRate(now: Long): Double {
        val cutoff = now - ROLLING_RATE_WINDOW_NS
        var recent = 0
        repeat(rollingRenderCount) { offset ->
            val index = (rollingRenderWriteIndex - 1 - offset + rollingRenderTimesNs.size) %
                rollingRenderTimesNs.size
            if (rollingRenderTimesNs[index] >= cutoff) recent++ else return@repeat
        }
        return recent.toDouble()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
        const val ROLLING_RATE_WINDOW_NS = 1_000_000_000L
        const val ROLLING_RATE_CAPACITY = 512
    }
}
