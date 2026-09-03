package io.github.peerless2012.ass.media.render

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.media.AssPerformanceStatsCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AssAtlasUploadBenchmarkInstrumentedTest {
    @Test
    fun compareDirectAndPboPatchSubmission() = withGles3Context {
        val rendererName = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        runMode(AssAtlasGlRenderer.UploadMode.DIRECT)
        runMode(AssAtlasGlRenderer.UploadMode.PBO)
        val directSamples = LongArray(ROUNDS)
        val pboSamples = LongArray(ROUNDS)
        val directSubmissionMs = DoubleArray(ROUNDS)
        val pboSubmissionMs = DoubleArray(ROUNDS)

        repeat(ROUNDS) { round ->
            if (round % 2 == 0) {
                runMode(AssAtlasGlRenderer.UploadMode.DIRECT).also {
                    directSamples[round] = it.elapsedNs
                    directSubmissionMs[round] = it.submissionMs
                }
                runMode(AssAtlasGlRenderer.UploadMode.PBO).also {
                    pboSamples[round] = it.elapsedNs
                    pboSubmissionMs[round] = it.submissionMs
                }
            } else {
                runMode(AssAtlasGlRenderer.UploadMode.PBO).also {
                    pboSamples[round] = it.elapsedNs
                    pboSubmissionMs[round] = it.submissionMs
                }
                runMode(AssAtlasGlRenderer.UploadMode.DIRECT).also {
                    directSamples[round] = it.elapsedNs
                    directSubmissionMs[round] = it.submissionMs
                }
            }
        }

        val directMedian = directSamples.sorted()[ROUNDS / 2]
        val pboMedian = pboSamples.sorted()[ROUNDS / 2]
        Log.i(
            TAG,
            "renderer=$rendererName directMedianNs=$directMedian pboMedianNs=$pboMedian " +
                "direct=${directSamples.contentToString()} pbo=${pboSamples.contentToString()} " +
                "directSubmissionMs=${directSubmissionMs.contentToString()} " +
                "pboSubmissionMs=${pboSubmissionMs.contentToString()}",
        )
        assertTrue(directMedian > 0L)
        assertTrue(pboMedian > 0L)
    }

    private fun runMode(mode: AssAtlasGlRenderer.UploadMode): Sample {
        val stats = AssPerformanceStatsCollector()
        val renderer = AssAtlasGlRenderer(statsCollector = stats, uploadMode = mode)
        renderer.initialize()
        try {
            assertEquals(
                AssAtlasGlRenderer.DrawResult.REDRAWN_CONTENT,
                renderer.render(replacementFrame(), SOURCE_WIDTH, SOURCE_HEIGHT, TARGET_WIDTH, TARGET_HEIGHT),
            )
            GLES30.glFinish()

            val frames = Array(FRAMES) { index -> incrementalFrame(index + 2L, index.toByte()) }
            val startedNs = System.nanoTime()
            frames.forEach { frame ->
                assertEquals(
                    AssAtlasGlRenderer.DrawResult.REDRAWN_CONTENT,
                    renderer.render(frame, SOURCE_WIDTH, SOURCE_HEIGHT, TARGET_WIDTH, TARGET_HEIGHT),
                )
                GLES30.glFinish()
            }
            return Sample(
                elapsedNs = System.nanoTime() - startedNs,
                submissionMs = stats.snapshot().glUploadSubmissionMs,
            )
        } finally {
            renderer.release()
        }
    }

    private fun replacementFrame(): AssAtlasFrame = AssAtlasFrame(
        pages = arrayOf(directBuffer(PAGE_WIDTH * PAGE_HEIGHT, 1)),
        pageWidths = intArrayOf(PAGE_WIDTH),
        pageHeights = intArrayOf(PAGE_HEIGHT),
        quads = quad(),
        changed = AssAtlasFrame.CHANGE_REPLACE,
        dirtyRects = IntArray(0),
        contentSerial = 1L,
        patches = null,
        patchRects = IntArray(0),
        activeBounds = intArrayOf(0, 0, PATCH_WIDTH, PATCH_HEIGHT),
        baseContentSerial = 0L,
        copiedMaskBytes = PAGE_WIDTH.toLong() * PAGE_HEIGHT,
    )

    private fun incrementalFrame(serial: Long, value: Byte): AssAtlasFrame = AssAtlasFrame(
        pages = null,
        pageWidths = intArrayOf(PAGE_WIDTH),
        pageHeights = intArrayOf(PAGE_HEIGHT),
        quads = quad(),
        changed = AssAtlasFrame.CHANGE_INCREMENTAL,
        dirtyRects = IntArray(0),
        contentSerial = serial,
        patches = arrayOf(directBuffer(PATCH_WIDTH * PATCH_HEIGHT, value)),
        patchRects = intArrayOf(0, 0, 0, PATCH_WIDTH, PATCH_HEIGHT),
        activeBounds = intArrayOf(0, 0, PATCH_WIDTH, PATCH_HEIGHT),
        baseContentSerial = serial - 1L,
        copiedMaskBytes = PATCH_WIDTH.toLong() * PATCH_HEIGHT,
    )

    private fun quad() = intArrayOf(
        0, 0, PATCH_WIDTH, PATCH_HEIGHT, 0xFFFFFF00.toInt(), 0, 0, 0,
    )

    private fun directBuffer(size: Int, value: Byte): ByteBuffer =
        ByteBuffer.allocateDirect(size).apply {
            repeat(size) { put(value) }
            flip()
        }

    private inline fun withGles3Context(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0))
        check(configCount[0] > 0)
        val config = requireNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, TARGET_WIDTH, EGL14.EGL_HEIGHT, TARGET_HEIGHT, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        try {
            block()
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private data class Sample(val elapsedNs: Long, val submissionMs: Double)

    private companion object {
        const val TAG = "AssAtlasUploadBench"
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 360
        const val TARGET_WIDTH = 128
        const val TARGET_HEIGHT = 64
        const val PAGE_WIDTH = 512
        const val PAGE_HEIGHT = 256
        const val PATCH_WIDTH = 128
        const val PATCH_HEIGHT = 32
        const val FRAMES = 240
        const val ROUNDS = 9
    }
}
