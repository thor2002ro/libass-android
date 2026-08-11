package io.github.peerless2012.ass

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * @Author peerless2012
 * @Email peerless2012@126.com
 * @DateTime 2025/Jan/05 14:18
 * @Version V1.0
 * @Description
 */
class AssRender(nativeAss: Long, private val lock: ReentrantLock) : AutoCloseable {

    companion object {

        @JvmStatic
        external fun nativeAssRenderInit(ass: Long): Long

        @JvmStatic
        external fun nativeAssRenderSetFontScale(render: Long, scale: Float)

        @JvmStatic
        external fun nativeAssRenderSetCacheLimit(render: Long, glyphMax: Int, bitmapMaxSize: Int)

        @JvmStatic
        external fun nativeAssRenderSetStorageSize(render: Long, width: Int, height: Int)

        @JvmStatic
        external fun nativeAssRenderSetFrameSize(render: Long, width: Int, height: Int)

        @JvmStatic
        external fun nativeAssRenderSetPixelAspect(render: Long, pixelAspect: Double)

        @JvmStatic
        external fun nativeAssRenderFrame(
            render: Long,
            track: Long,
            time: Long,
            type: Int,
        ): AssFrame?

        @JvmStatic
        external fun nativeAssRenderAtlasFrame(
            render: Long,
            track: Long,
            time: Long,
            maxAtlasSize: Int,
        ): AssAtlasFrame?

        @JvmStatic
        external fun nativeAssRenderDeinit(render: Long)
    }

    private var nativeRender: Long = nativeAssRenderInit(nativeAss)

    @Volatile
    var released = false
        private set

    private var track: AssTrack? = null

    fun setTrack(track: AssTrack?) {
        lock.withLock {
            this.track = track
        }
    }

    fun setFontScale(scale: Float) {
        lock.withLock {
            if (released || nativeRender == 0L) return
            nativeAssRenderSetFontScale(nativeRender, scale)
        }
    }

    fun setCacheLimit(glyphMax: Int, bitmapMaxSize: Int) {
        lock.withLock {
            if (released || nativeRender == 0L) return
            nativeAssRenderSetCacheLimit(nativeRender, glyphMax, bitmapMaxSize)
        }
    }

    fun setStorageSize(width: Int, height: Int) {
        lock.withLock {
            if (released || nativeRender == 0L) return
            nativeAssRenderSetStorageSize(nativeRender, width, height)
        }
    }

    fun setFrameSize(width: Int, height: Int) {
        lock.withLock {
            if (released || nativeRender == 0L) return
            nativeAssRenderSetFrameSize(nativeRender, width, height)
        }
    }

    fun setPixelAspect(pixelAspect: Double) {
        lock.withLock {
            if (released || nativeRender == 0L) return
            nativeAssRenderSetPixelAspect(nativeRender, pixelAspect)
        }
    }

    fun renderFrame(time: Long, type: AssTexType): AssFrame? = lock.withLock {
        if (released || nativeRender == 0L) return null
        val selectedTrack = track ?: return null
        if (selectedTrack.released || selectedTrack.nativeAssTrack == 0L) return null
        nativeAssRenderFrame(nativeRender, selectedTrack.nativeAssTrack, time, type.ordinal)
    }

    /**
     * Renders all masks into atlas pages. [time] is in milliseconds.
     *
     * Returns `null` when libass reports no change. A position-only frame
     * contains no page bytes; callers must reuse the textures from the last
     * content-changing frame.
     */
    fun renderAtlasFrame(time: Long, maxAtlasSize: Int): AssAtlasFrame? = lock.withLock {
        if (released || nativeRender == 0L || maxAtlasSize <= 0) return null
        val selectedTrack = track ?: return null
        if (selectedTrack.released || selectedTrack.nativeAssTrack == 0L) return null
        nativeAssRenderAtlasFrame(
            nativeRender,
            selectedTrack.nativeAssTrack,
            time,
            maxAtlasSize,
        )
    }

    fun release() {
        lock.withLock {
            if (released) return
            released = true
            track = null
            if (nativeRender != 0L) {
                nativeAssRenderDeinit(nativeRender)
                nativeRender = 0
            }
        }
    }

    override fun close() {
        release()
    }

    protected fun finalize() {
        release()
    }

}
