package io.github.peerless2012.ass

import io.github.peerless2012.ass.kt.BuildConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Owns one native libass library instance. */
class Ass : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("asskt")
        }

        @JvmStatic
        val version: String = BuildConfig.VERSION_NAME

        @JvmStatic
        val libraryVersion: String
            get() = nativeAssLibraryVersion().toLibassVersion()

        @JvmStatic
        external fun nativeAssInit(): Long

        @JvmStatic
        external fun nativeAssLibraryVersion(): Int

        @JvmStatic
        external fun nativeAssAddFont(ptr: Long, name: String, buffer: ByteArray)

        @JvmStatic
        external fun nativeAssClearFont(ptr: Long)

        @JvmStatic
        external fun nativeAssDeinit(ptr: Long)

    }

    /** Single lock for all libass calls on this library instance. */
    val lock = ReentrantLock()

    private var nativeAss: Long = nativeAssInit()

    @Volatile
    var released = false
        private set

    fun createTrack(): AssTrack = lock.withLock {
        check(!released && nativeAss != 0L) { "Ass already released" }
        AssTrack(nativeAss, lock)
    }

    fun createRender(): AssRender = lock.withLock {
        check(!released && nativeAss != 0L) { "Ass already released" }
        AssRender(nativeAss, lock)
    }

    fun addFont(name: String, buffer: ByteArray) {
        lock.withLock {
            if (released || nativeAss == 0L) return
            nativeAssAddFont(nativeAss, name, buffer)
        }
    }

    fun clearFont() {
        lock.withLock {
            if (released || nativeAss == 0L) return
            nativeAssClearFont(nativeAss)
        }
    }

    fun release() {
        lock.withLock {
            if (released) return
            released = true
            if (nativeAss != 0L) {
                nativeAssDeinit(nativeAss)
                nativeAss = 0
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

private fun Int.toLibassVersion(): String {
    val version = toUInt().toString(16).padStart(8, '0')
    return "${version[0]}.${version.substring(1, 3).toInt()}.${version.substring(3, 5).toInt()}"
}
