package io.github.peerless2012.ass.media

data class AssHandlerConfig @JvmOverloads constructor(
    val glyphSize: Int = 10_000,
    val cacheSize: Int = 128,

    /**
     * Maximum number of pixels (`width * height`) used for subtitle rasterization.
     *
     * A value of `0` renders at the full target size. `1920 * 1080` is a useful
     * balanced setting for 4K playback; leave this at `0` for maximum sharpness.
     */
    val maxRenderPixels: Int = 0,

    /** Optional stats collector owned by the app. */
    val performanceStatsCollector: AssPerformanceStatsCollector? = null,

    /**
     * Maximum ASS animation update rate, driven by presentation timestamps.
     *
     * - `60` keeps karaoke, transforms, and moving signs smooth.
     * - `30` is a balanced battery-saving option.
     * - `0` disables throttling.
     */
    val maxSubtitleFps: Float = 60f,

    /**
     * Maximum width or height of an alpha-atlas page.
     *
     * `0` uses the device's `GL_MAX_TEXTURE_SIZE`. A smaller value reduces peak
     * upload size at the cost of additional atlas pages and draw calls.
     */
    val maxAtlasTextureSize: Int = 0,
)
