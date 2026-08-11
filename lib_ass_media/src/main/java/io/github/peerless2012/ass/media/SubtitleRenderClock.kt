package io.github.peerless2012.ass.media

/**
 * Limits subtitle rendering by presentation time instead of by an arbitrary callback count.
 *
 * ExoPlayer may call a renderer more frequently than the source frame rate. This clock keeps the
 * subtitle animation cadence deterministic while still rendering immediately after seeks and
 * timestamp discontinuities.
 */
internal class SubtitleRenderClock(maxFramesPerSecond: Float) {

    private val minimumIntervalUs = when {
        !maxFramesPerSecond.isFinite() || maxFramesPerSecond <= 0f -> 0L
        else -> (MICROS_PER_SECOND / maxFramesPerSecond).toLong().coerceAtLeast(1L)
    }

    private var lastObservedTimeUs = TIME_UNSET
    private var lastRenderedTimeUs = TIME_UNSET

    fun shouldRender(presentationTimeUs: Long): Boolean {
        if (presentationTimeUs < 0L) {
            return false
        }

        if (presentationTimeUs == lastObservedTimeUs) {
            return false
        }

        val movedBackwards =
            lastObservedTimeUs != TIME_UNSET && presentationTimeUs < lastObservedTimeUs
        lastObservedTimeUs = presentationTimeUs

        if (lastRenderedTimeUs == TIME_UNSET || movedBackwards) {
            lastRenderedTimeUs = presentationTimeUs
            return true
        }

        if (minimumIntervalUs == 0L || presentationTimeUs - lastRenderedTimeUs >= minimumIntervalUs) {
            lastRenderedTimeUs = presentationTimeUs
            return true
        }

        return false
    }

    fun reset() {
        lastObservedTimeUs = TIME_UNSET
        lastRenderedTimeUs = TIME_UNSET
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000f
        const val TIME_UNSET = Long.MIN_VALUE
    }
}
