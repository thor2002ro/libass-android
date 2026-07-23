package io.github.peerless2012.ass.media

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.TEXT_SSA
import androidx.media3.common.Player.Listener
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import io.github.peerless2012.ass.media.parser.AssHeaderParser
import io.github.peerless2012.ass.media.render.AssOverlayManager
import io.github.peerless2012.ass.media.type.AssRenderType

/**
 * Handles ASS subtitle rendering and integration with ExoPlayer.
 *
 * This class listens to ExoPlayer events and manages the creation, selection, and rendering of ASS
 * subtitle tracks.
 * @param renderType The subtitle render type.
 */
@OptIn(UnstableApi::class)
class AssHandler(
    val renderType: AssRenderType,
    val config: AssHandlerConfig = AssHandlerConfig()
    ) : Listener {

    /** The ASS instance used for creating tracks and renderers. This is lazy to avoid loading
     * libass if the played media does not have ASS tracks. */
    private val assDelegate = lazy { Ass() }
    val ass by assDelegate

    /** The current ASS renderer. It's created as soon as a ASS track is detected. */
    var render: AssRender? = null
        private set

    /**
     * AssRender changed callback
     */
    var renderCallback: ((AssRender?) -> Unit)? = null

    /** The currently selected ASS track. */
    var track: AssTrack? = null
        private set

    /** The available ASS tracks in the current media. */
    private val availableTracks = mutableMapOf<String, AssTrack>()

    /** Fonts encountered before any ASS track was created. Flushed in [createTrack]. */
    private val pendingFonts = mutableListOf<Pair<String, ByteArray>>()

    /** Embedded fonts accepted for the current media item. */
    private val embeddedFontUids = mutableSetOf<Long>()

    private var embeddedFontBytes = 0L
    private var embeddedFontCount = 0
    private var embeddedFontCandidateBytes = 0L
    private var embeddedFontCandidateCount = 0

    private var mediaGeneration = 0L

    /** Maximum cumulative size of embedded fonts for one media item. */
    @Volatile
    var maxEmbeddedFontBytes = 32L * 1024 * 1024
        set(value) {
            require(value >= 0) { "maxEmbeddedFontBytes must not be negative" }
            field = value
        }

    /** The size of the video track. */
    var videoSize = Size.ZERO
        private set

    /** The size of the surface on which subtitles are rendered. */
    var surfaceSize = Size.ZERO
        private set

    private val subtitleRenderClock = SubtitleRenderClock(config.maxSubtitleFps)
    private var pixelAspectRatio: Double = 1.0

    var videoTime = -1L
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (subtitleRenderClock.shouldRender(value)) {
                videoTimeCallback?.invoke(value)
            }
        }

    var videoTimeCallback: ((Long) -> Unit)? = null

    /** The overlay manager for toggling the effects renderer. */
    private var overlayManager: AssOverlayManager? = null

    /** The current selected ass format. */
    private var format: Format? = null

    /** The playback control thread handler. */
    private lateinit var handler: Handler

    private var player: ExoPlayer? = null

    private var released = false

    val performanceStats: AssPerformanceStats
        get() = config.performanceStatsCollector?.snapshot() ?: AssPerformanceStats()

    /**
     * Initializes the handler with the provided ExoPlayer instance.
     * @param player The ExoPlayer instance to attach to.
     */
    @Synchronized
    fun init(player: ExoPlayer) {
        this.player?.removeListener(this)
        this.player = player
        player.addListener(this)
        handler = Handler(player.applicationLooper)
        if (renderType == AssRenderType.EFFECTS_CANVAS || renderType == AssRenderType.EFFECTS_OPEN_GL) {
            overlayManager = AssOverlayManager(this, player, renderType == AssRenderType.EFFECTS_OPEN_GL)
        }
    }

    /**
     * Handles transitions between media items in the player and resets everything to the initial
     * state.
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        reset()
    }

    /** Clears resources owned by the current media item while keeping this handler reusable. */
    @Synchronized
    fun reset() {
        if (released) return
        resetInternal()
    }

    private fun resetInternal(updatePlayer: Boolean = true) {
        if (updatePlayer) overlayManager?.disable()
        render?.release()
        render = null
        track = null
        format = null
        availableTracks.values.forEach { it.release() }
        availableTracks.clear()
        pendingFonts.clear()
        embeddedFontUids.clear()
        embeddedFontBytes = 0
        embeddedFontCount = 0
        embeddedFontCandidateBytes = 0
        embeddedFontCandidateCount = 0
        mediaGeneration++
        if (assDelegate.isInitialized()) {
            ass.clearFont()
        }
        videoSize = Size.ZERO
        pixelAspectRatio = 1.0
        videoTime = -1
        subtitleRenderClock.reset()
        config.performanceStatsCollector?.reset()
        if (updatePlayer) renderCallback?.invoke(null)
    }

    /**
     * Handles changes to the tracks available in the current media.
     * Configures the selected ASS track if available.
     * @param tracks The selected tracks.
     */
    @Synchronized
    override fun onTracksChanged(tracks: Tracks) {
        Log.i("AssHandler", "onTracksChanged $tracks")

        val selectedVideoTrack = getSelectedVideoTrack(tracks)
        if (selectedVideoTrack != null) {
            updateVideoGeometry(
                selectedVideoTrack.width,
                selectedVideoTrack.height,
                selectedVideoTrack.pixelWidthHeightRatio.toValidPixelAspect(),
            )
        }

        format = getSelectedAssTrack(tracks)
        if (format == null) {
            Log.i("AssHandler", "subtitle track disabled")
            track = null
            render?.setTrack(null)
            overlayManager?.disable()
            return
        }

        updateTrack()
    }

    @Synchronized
    private fun updateTrack() {
        val track = availableTracks.firstNotNullOfOrNull {
            // When media without external subtitles, format id will not change.
            // When media with external subtitles, format will become like 1:1 .
            // So to compat both situation, we extract the actual id after the colon.
            if (format?.id == it.key || format?.id?.substringAfter(":") == it.key) {
                it.value
            } else {
                null
            }
        }
        if (track == null || this.track == track) return

        Log.i("AssHandler", "subtitle track changed to $format")
        this.track = track
        val render = requireNotNull(render)
        render.setStorageSize(videoSize.width, videoSize.height)
        render.setPixelAspect(pixelAspectRatio)
        if (renderType == AssRenderType.OVERLAY_CANVAS || renderType == AssRenderType.OVERLAY_OPEN_GL) {
            val renderSize = computeRenderSize(surfaceSize.width, surfaceSize.height)
            render.setFrameSize(renderSize.width, renderSize.height)
        } else {
            val renderSize = computeRenderSize(videoSize.width, videoSize.height)
            render.setFrameSize(renderSize.width, renderSize.height)
        }
        render.setTrack(track)

        // Player func call need in create thread.
        overlayManager?.let { overlayManager ->
            handler.post {
                synchronized(this) {
                    if (this.render === render && this.overlayManager === overlayManager) {
                        overlayManager.enable(render)
                    }
                }
            }
        }
    }

    /**
     * Handles changes to the surface size for video playback.
     * Notifies the callback if the size has changed.
     * @param width The new width of the surface.
     * @param height The new height of the surface.
     */
    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        super.onSurfaceSizeChanged(width, height)
        Log.i("AssHandler", "onSurfaceSizeChanged: width = $width, height = $height")
        if (surfaceSize.width == width && surfaceSize.height == height) return
        surfaceSize = Size(width, height)
        if ((renderType == AssRenderType.OVERLAY_CANVAS || renderType == AssRenderType.OVERLAY_OPEN_GL) && surfaceSize.isValid) {
            val renderSize = computeRenderSize(width, height)
            if (renderSize.width != width || renderSize.height != height) {
                Log.i("AssHandler", "Downscaling render: ${width}x${height} -> ${renderSize.width}x${renderSize.height} (maxPixels=${config.maxRenderPixels})")
            }
            render?.setFrameSize(renderSize.width, renderSize.height)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        updateVideoGeometry(
            videoSize.width,
            videoSize.height,
            videoSize.pixelWidthHeightRatio.toValidPixelAspect(),
        )
        Log.i(
            "AssHandler",
            "onVideoSizeChanged: width=${videoSize.width}, height=${videoSize.height}, " +
                "pixelAspect=$pixelAspectRatio",
        )
    }

    /**
     * Updates the video size for the ASS renderer. Called as soon as the video size is known in
     * order to properly render subtitles.
     * @param width The width of the video.
     * @param height The height of the video.
     */
    fun setVideoSize(width: Int, height: Int) {
        updateVideoGeometry(width, height, pixelAspectRatio)
    }

    private fun updateVideoGeometry(width: Int, height: Int, pixelAspect: Double) {
        Log.i(
            "AssHandler",
            "setVideoSize: width=$width, height=$height, pixelAspect=$pixelAspect",
        )
        videoSize = Size(width, height)
        pixelAspectRatio = pixelAspect
        render?.let { renderer ->
            if (videoSize.isValid) {
                renderer.setStorageSize(videoSize.width, videoSize.height)
            }
            renderer.setPixelAspect(pixelAspectRatio)
        }
    }

    fun resetPerformanceStats() {
        config.performanceStatsCollector?.reset()
    }

    internal fun renderFrame(timeMs: Long, type: AssTexType): AssFrame? {
        val render = render ?: return null
        val stats = config.performanceStatsCollector ?: return render.renderFrame(timeMs, type)
        val startedNs = System.nanoTime()
        val frame = render.renderFrame(timeMs, type)
        stats.record(System.nanoTime() - startedNs, frame)
        return frame
    }

    internal fun renderAtlasFrame(timeMs: Long, maxAtlasSize: Int): AssAtlasFrame? {
        val render = render ?: return null
        val stats = config.performanceStatsCollector
            ?: return render.renderAtlasFrame(timeMs, maxAtlasSize)
        val startedNs = System.nanoTime()
        val frame = render.renderAtlasFrame(timeMs, maxAtlasSize)
        stats.record(System.nanoTime() - startedNs, frame)
        return frame
    }

    /**
     * Returns true if the current media has ASS tracks, false otherwise.
     */
    @Synchronized
    fun hasTracks(): Boolean {
        return availableTracks.isNotEmpty()
    }

    /**
     * Adds a font to the ASS library. If no tracks have been created yet, the font is buffered
     * and will be added when the first track is created via [createTrack].
     */
    @Synchronized
    fun addFont(name: String, data: ByteArray) {
        addFontInternal(name, data)
    }

    @Synchronized
    internal fun reserveEmbeddedFont(uid: Long?, size: Int): EmbeddedFontReservation? {
        if (released || size <= 0) return null
        if (uid != null && uid in embeddedFontUids) return null
        if (size.toLong() > maxEmbeddedFontBytes) return null
        if (embeddedFontCandidateCount >= MAX_EMBEDDED_FONT_CANDIDATES) return null
        if (size.toLong() > maxCandidateBytes() - embeddedFontCandidateBytes) return null
        embeddedFontCandidateBytes += size
        embeddedFontCandidateCount++
        return EmbeddedFontReservation(mediaGeneration, size)
    }

    @Synchronized
    internal fun discardEmbeddedFont(reservation: EmbeddedFontReservation) {
        consumeReservation(reservation)
    }

    @Synchronized
    internal fun cancelEmbeddedFont(reservation: EmbeddedFontReservation) {
        if (!consumeReservation(reservation)) return
        embeddedFontCandidateBytes -= reservation.size
        embeddedFontCandidateCount--
    }

    internal fun addEmbeddedFont(uid: Long?, name: String, data: ByteArray) {
        val reservation = reserveEmbeddedFont(uid, data.size) ?: return
        addReservedEmbeddedFont(reservation, uid, name, data)
    }

    @Synchronized
    internal fun addReservedEmbeddedFont(
        reservation: EmbeddedFontReservation,
        uid: Long?,
        name: String,
        data: ByteArray
    ) {
        if (!consumeReservation(reservation)) return
        if (data.size != reservation.size) return
        if (uid != null && uid in embeddedFontUids) return
        if (embeddedFontCount >= MAX_EMBEDDED_FONTS) return
        if (data.size.toLong() > maxEmbeddedFontBytes - embeddedFontBytes) return
        if (uid != null) embeddedFontUids.add(uid)
        embeddedFontBytes += data.size
        embeddedFontCount++
        addFontInternal(name, data)
    }

    private fun consumeReservation(reservation: EmbeddedFontReservation): Boolean {
        if (reservation.generation != mediaGeneration || reservation.consumed) return false
        reservation.consumed = true
        return true
    }

    private fun maxCandidateBytes(): Long =
        if (maxEmbeddedFontBytes > Long.MAX_VALUE / CANDIDATE_BUDGET_MULTIPLIER) Long.MAX_VALUE
        else maxEmbeddedFontBytes * CANDIDATE_BUDGET_MULTIPLIER

    internal class EmbeddedFontReservation(
        val generation: Long,
        val size: Int,
        var consumed: Boolean = false
    )

    private fun addFontInternal(name: String, data: ByteArray) {
        if (hasTracks()) {
            ass.addFont(name, data)
        } else {
            pendingFonts.add(name to data)
        }
    }

    /**
     * Creates a new ASS track from the given format and saves it in the [availableTracks].
     * The renderer and libass are also created if needed.
     * @param format The format of the ASS track.
     * @return The created ASS track.
     */
    @Synchronized
    fun createTrack(format: Format): AssTrack {
        Log.i("AssHandler", "createTrack: format = $format")
        // Ensure the renderer is created before creating tracks.
        createRenderIfNeeded()

        // Flush any fonts that were buffered before the first track was created.
        if (pendingFonts.isNotEmpty()) {
            for ((name, data) in pendingFonts) {
                ass.addFont(name, data)
            }
            pendingFonts.clear()
        }

        val track = ass.createTrack()
        if (format.initializationData.size > 0) {
            val header = AssHeaderParser.parse(format, renderType != AssRenderType.CUES)
            track.readBuffer(header)
        }
        availableTracks[format.id!!] = track

        updateTrack()

        return track
    }

    /**
     * Ensures the ASS renderer is created if it does not already exist.
     */
    private fun createRenderIfNeeded() {
        if (render != null) return
        Log.i("AssHandler", "createRender")
        render = ass.createRender().also { render ->
            if (videoSize.isValid) {
                render.setStorageSize(videoSize.width, videoSize.height)
            }
            render.setPixelAspect(pixelAspectRatio)
            val frameSizeSource =
                if (
                    (renderType == AssRenderType.OVERLAY_CANVAS ||
                        renderType == AssRenderType.OVERLAY_OPEN_GL) &&
                    surfaceSize.isValid
                ) {
                    surfaceSize
                } else {
                    videoSize
                }
            if (frameSizeSource.isValid) {
                val renderSize = computeRenderSize(frameSizeSource.width, frameSizeSource.height)
                render.setFrameSize(renderSize.width, renderSize.height)
            }
            Log.i("AssHandler", "Ass cacheSize: ${config.cacheSize}MB")
            Log.i("AssHandler", "Ass glyphSize: ${config.glyphSize}")
            render.setCacheLimit(config.glyphSize, config.cacheSize)
        }
        renderCallback?.invoke(render)
    }

    /**
     * Reads a dialogue into the track of the given [trackId].
     * Thread-safe: AssTrack.readChunk internally acquires the shared libass lock.
     */
    fun readTrackDialogue(
        trackId: String?,
        start: Long,
        duration: Long,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size
    ) {
        val t = synchronized(this) { availableTracks[trackId] } ?: return
        t.readChunk(start, duration, data, offset, length)
    }

    /**
     * Retrieves the selected video track, if any.
     */
    private fun getSelectedVideoTrack(tracks: Tracks): Format? {
        return tracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
            .firstOrNull { track -> MimeTypes.isVideo(track.sampleMimeType) }
    }

    /**
     * Retrieves the ID of the selected ASS track, if any.
     * @param tracks The selected tracks.
     * @return The ID of the selected ASS track, or null if none.
     */
    private fun getSelectedAssTrack(tracks: Tracks): Format? {
        return tracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
            .firstOrNull { track -> track.sampleMimeType == TEXT_SSA || track.codecs == TEXT_SSA }
    }

    /**
     * Releases all native resources held by this handler.
     */
    @Synchronized
    fun release() {
        releaseInternal(updatePlayer = true)
    }

    @Synchronized
    internal fun releaseFromRenderer() {
        releaseInternal(updatePlayer = false)
    }

    private fun releaseInternal(updatePlayer: Boolean) {
        if (released) return
        released = true
        val rendererCallback = renderCallback.takeUnless { updatePlayer }
        videoTimeCallback = null
        resetInternal(updatePlayer)
        if (updatePlayer) player?.removeListener(this)
        player = null
        overlayManager = null
        renderCallback = null
        if (assDelegate.isInitialized()) {
            ass.release()
        }
        if (rendererCallback != null && ::handler.isInitialized) {
            handler.post { rendererCallback(null) }
        }
    }

    private fun Float.toValidPixelAspect(): Double =
        takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0

    /**
     * Checks if the size is valid (both width and height are greater than 0).
     */
    private val Size.isValid
        get() = width > 0 && height > 0

    private companion object {
        const val MAX_EMBEDDED_FONTS = 256
        const val MAX_EMBEDDED_FONT_CANDIDATES = 1024
        const val CANDIDATE_BUDGET_MULTIPLIER = 4L
    }

    /**
     * Computes the actual render size, downscaling proportionally if the frame
     * exceeds [AssHandlerConfig.maxRenderPixels].
     *
     * The result is aligned to even numbers for consistent libass internal layout.
     *
     * @param width  Target frame width (surface or video size)
     * @param height Target frame height
     * @return The (possibly downscaled) render size
     */
    fun computeRenderSize(width: Int, height: Int): Size {
        val max = config.maxRenderPixels
        if (max <= 0) return Size(width, height)
        val pixels = width.toLong() * height
        if (pixels <= max) return Size(width, height)
        val scale = Math.sqrt(max.toDouble() / pixels).toFloat()
        // Align to even numbers for libass internal layout
        val w = ((width * scale).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        val h = ((height * scale).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        return Size(w, h)
    }

}
