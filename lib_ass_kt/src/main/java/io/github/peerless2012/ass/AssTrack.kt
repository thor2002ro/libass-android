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
class AssTrack(private val ass: Long, private val lock: ReentrantLock) : AutoCloseable {

    companion object {

        @JvmStatic
        external fun nativeAssTrackInit(track: Long): Long

        @JvmStatic
        external fun nativeAssTrackGetWidth(track: Long): Int

        @JvmStatic
        external fun nativeAssTrackGetHeight(track: Long): Int

        @JvmStatic
        external fun nativeAssTrackGetYCbCrMatrix(track: Long): Int

        @JvmStatic
        external fun nativeAssTrackGetEvents(track: Long): Array<AssEvent>?

        @JvmStatic
        external fun nativeAssTrackClearEvents(track: Long)

        @JvmStatic
        external fun nativeAssTrackReadBuffer(
            track: Long,
            byteArray: ByteArray,
            offset: Int,
            length: Int,
        )

        @JvmStatic
        external fun nativeAssTrackReadChunk(
            track: Long,
            start: Long,
            duration: Long,
            byteArray: ByteArray,
            offset: Int,
            length: Int,
        )

        @JvmStatic
        external fun nativeAssTrackDeinit(track: Long)
    }

    var nativeAssTrack: Long = nativeAssTrackInit(ass)
        private set

    @Volatile
    var released = false
        private set

    fun getWidth(): Int = lock.withLock {
        if (released || nativeAssTrack == 0L) 0 else nativeAssTrackGetWidth(nativeAssTrack)
    }

    fun getHeight(): Int = lock.withLock {
        if (released || nativeAssTrack == 0L) 0 else nativeAssTrackGetHeight(nativeAssTrack)
    }

    fun getYCbCrMatrix(): AssYCbCrMatrix = lock.withLock {
        if (released || nativeAssTrack == 0L) {
            AssYCbCrMatrix.UNKNOWN
        } else {
            AssYCbCrMatrix.fromNative(nativeAssTrackGetYCbCrMatrix(nativeAssTrack))
        }
    }

    fun getEvents(): Array<AssEvent>? = lock.withLock {
        if (released || nativeAssTrack == 0L) null else nativeAssTrackGetEvents(nativeAssTrack)
    }

    fun clearEvent() = clearEvents()

    fun clearEvents() {
        lock.withLock {
            if (released || nativeAssTrack == 0L) return
            nativeAssTrackClearEvents(nativeAssTrack)
        }
    }

    fun readBuffer(array: ByteArray, offset: Int = 0, length: Int = array.size - offset) {
        checkRange(array, offset, length)
        lock.withLock {
            if (released || nativeAssTrack == 0L) return
            nativeAssTrackReadBuffer(nativeAssTrack, array, offset, length)
        }
    }

    fun readChunk(
        start: Long,
        duration: Long,
        array: ByteArray,
        offset: Int = 0,
        length: Int = array.size - offset,
    ) {
        checkRange(array, offset, length)
        lock.withLock {
            if (released || nativeAssTrack == 0L) return
            nativeAssTrackReadChunk(nativeAssTrack, start, duration, array, offset, length)
        }
    }

    fun release() {
        lock.withLock {
            if (released) return
            released = true
            if (nativeAssTrack != 0L) {
                nativeAssTrackDeinit(nativeAssTrack)
                nativeAssTrack = 0
            }
        }
    }

    override fun close() {
        release()
    }

    protected fun finalize() {
        release()
    }

    private fun checkRange(array: ByteArray, offset: Int, length: Int) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        require(offset <= array.size - length) { "offset + length must be <= array.size" }
    }

}
