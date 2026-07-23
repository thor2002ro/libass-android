package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import androidx.annotation.WorkerThread
import androidx.media3.common.C
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.executor.AssAtlasExecutor
import io.github.peerless2012.ass.media.render.AssAtlasGlRenderer
import java.util.concurrent.atomic.AtomicBoolean

/** TextureView renderer using persistent, batched libass alpha atlases. */
@UnstableApi
class AssSubtitleTextureView :
    TextureView,
    AssSubtitleRender,
    TextureView.SurfaceTextureListener {

    private val assHandler: AssHandler
    private var renderThread: AssRenderThread? = null

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        assHandler: AssHandler,
    ) : this(context, attrs, 0, assHandler)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        assHandler: AssHandler,
    ) : super(context, attrs, defStyleAttr) {
        this.assHandler = assHandler
        isOpaque = false
        surfaceTextureListener = this
    }

    interface Renderer {
        @WorkerThread
        fun onSurfaceCreated()

        fun onSurfaceChanged(width: Int, height: Int)

        @WorkerThread
        fun onDrawFrame(presentationTimeUs: Long): Boolean

        @WorkerThread
        fun onSurfaceDestroyed()
    }

    override fun requestRender(presentationTimeUs: Long) {
        renderThread?.requestRender(presentationTimeUs)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.release()
        AssRenderThread(
            surfaceTexture = surface,
            initialWidth = width,
            initialHeight = height,
            renderer = AtlasSurfaceRenderer(assHandler),
        ).also {
            renderThread = it
            it.start()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.release()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class AssRenderThread(
        private val surfaceTexture: SurfaceTexture,
        private val initialWidth: Int,
        private val initialHeight: Int,
        private val renderer: Renderer,
    ) : HandlerThread(THREAD_NAME), Handler.Callback {
        private lateinit var handler: Handler
        private val released = AtomicBoolean(false)

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var lastPresentationTimeUs: Long = C.TIME_UNSET

        override fun start() {
            super.start()
            handler = Handler(looper, this)
            handler.obtainMessage(MSG_INIT, Size(initialWidth, initialHeight)).sendToTarget()
        }

        fun requestRender(presentationTimeUs: Long) {
            if (released.get() || !::handler.isInitialized) return
            handler.removeMessages(MSG_DRAW)
            handler.obtainMessage(MSG_DRAW, presentationTimeUs).sendToTarget()
        }

        fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (released.get() || !::handler.isInitialized) return
            handler.removeMessages(MSG_SURFACE_SIZE_CHANGED)
            handler.obtainMessage(MSG_SURFACE_SIZE_CHANGED, Size(width, height)).sendToTarget()
        }

        fun release() {
            if (!released.compareAndSet(false, true) || !::handler.isInitialized) return
            handler.removeCallbacksAndMessages(null)
            handler.sendEmptyMessage(MSG_RELEASE)
        }

        override fun handleMessage(message: Message): Boolean {
            try {
                when (message.what) {
                    MSG_INIT -> {
                        val size = message.obj as Size
                        initInternal(size.width, size.height)
                    }

                    MSG_DRAW -> drawInternal(message.obj as Long)
                    MSG_SURFACE_SIZE_CHANGED -> {
                        val size = message.obj as Size
                        sizeChangedInternal(size.width, size.height)
                    }

                    MSG_RELEASE -> releaseInternal()
                }
            } catch (error: Exception) {
                Log.e(TAG, "GL render thread failed", error)
                releaseInternal()
            }
            return true
        }

        private fun initInternal(width: Int, height: Int) {
            try {
                eglDisplay = GlUtil.getDefaultEglDisplay()
                eglContext = GlUtil.createEglContext(eglDisplay)
                eglSurface = GlUtil.createEglSurface(
                    eglDisplay,
                    surfaceTexture,
                    C.COLOR_TRANSFER_SDR,
                    false,
                )
                check(
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        eglSurface,
                        eglSurface,
                        eglContext,
                    )
                ) { "eglMakeCurrent failed" }
                renderer.onSurfaceCreated()
                sizeChangedInternal(width, height)
            } catch (error: GlUtil.GlException) {
                throw IllegalStateException("EGL initialization failed", error)
            }
        }

        private fun sizeChangedInternal(width: Int, height: Int) {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            renderer.onSurfaceChanged(width, height)
            GlUtil.clearFocusedBuffers()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (lastPresentationTimeUs != C.TIME_UNSET) {
                drawInternal(lastPresentationTimeUs)
            }
        }

        private fun drawInternal(presentationTimeUs: Long) {
            lastPresentationTimeUs = presentationTimeUs
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            if (renderer.onDrawFrame(presentationTimeUs)) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        }

        private fun releaseInternal() {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    renderer.onSurfaceDestroyed()
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        GlUtil.destroyEglSurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        GlUtil.destroyEglContext(eglDisplay, eglContext)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to release EGL resources", error)
            } finally {
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
                quitSafely()
            }
        }

        private companion object {
            const val TAG = "AssTexRenderThread"
            const val THREAD_NAME = "AssTexRenderThread"
            const val MSG_INIT = 1
            const val MSG_DRAW = 2
            const val MSG_SURFACE_SIZE_CHANGED = 3
            const val MSG_RELEASE = 4
        }
    }

    private class AtlasSurfaceRenderer(
        private val assHandler: AssHandler,
    ) : Renderer {
        private var surfaceSize = Size.ZERO
        private var renderSize = Size.ZERO
        private var maxAtlasSize = 2_048
        private var forceNextRender = true

        private var atlasRenderer: AssAtlasGlRenderer? = null
        private var executor: AssAtlasExecutor? = null
        private var executorRender: AssRender? = null

        override fun onSurfaceCreated() {
            val deviceLimit = IntArray(1).also {
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0)
            }[0].takeIf { it > 0 } ?: 2_048
            val configuredLimit = assHandler.config.maxAtlasTextureSize
            maxAtlasSize = if (configuredLimit > 0) {
                minOf(deviceLimit, configuredLimit).coerceAtLeast(1)
            } else {
                deviceLimit
            }
            atlasRenderer = AssAtlasGlRenderer().also { it.initialize() }
            forceNextRender = true
        }

        override fun onSurfaceChanged(width: Int, height: Int) {
            surfaceSize = Size(width, height)
            renderSize = assHandler.computeRenderSize(width, height).let { size ->
                if (size.width > 0 && size.height > 0) size else Size(2, 2)
            }
            assHandler.render?.setFrameSize(renderSize.width, renderSize.height)

            // A frame-size change invalidates both packed masks and deferred work.
            replaceExecutor(assHandler.render)
            atlasRenderer?.clearCachedContent()
            forceNextRender = true
        }

        override fun onDrawFrame(presentationTimeUs: Long): Boolean {
            val activeRender = assHandler.render
            if (activeRender !== executorRender) {
                replaceExecutor(activeRender)
                atlasRenderer?.clearCachedContent()
                activeRender?.setFrameSize(renderSize.width, renderSize.height)
                forceNextRender = true
            }

            val force = forceNextRender
            forceNextRender = false
            val frame = executor?.renderFrame(presentationTimeUs)
            val result = atlasRenderer?.render(
                frame = frame,
                sourceWidth = renderSize.width,
                sourceHeight = renderSize.height,
                targetWidth = surfaceSize.width,
                targetHeight = surfaceSize.height,
                forceRedraw = force,
            ) ?: AssAtlasGlRenderer.DrawResult.UNCHANGED
            return result != AssAtlasGlRenderer.DrawResult.UNCHANGED
        }

        override fun onSurfaceDestroyed() {
            executor?.shutdown()
            executor = null
            executorRender = null
            atlasRenderer?.release()
            atlasRenderer = null
        }

        private fun replaceExecutor(render: AssRender?) {
            executor?.shutdown()
            executor = render?.let {
                AssAtlasExecutor(
                    frameRenderer = { timeMs -> assHandler.renderAtlasFrame(timeMs, maxAtlasSize) },
                    statsCollector = assHandler.config.performanceStatsCollector,
                )
            }
            executorRender = render
        }

    }
}
