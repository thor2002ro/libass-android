package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AssAtlasFrameValidatorTest {

    @Test
    fun acceptsMetadataOnlyFrameWithoutMaskPayload() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_METADATA,
                pages = null,
                patches = null,
                patchRects = IntArray(0),
                contentSerial = 7L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Valid)
    }

    @Test
    fun acceptsCompleteReplacementWithExactPageCapacity() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_REPLACE,
                pages = arrayOf(ByteBuffer.allocateDirect(64 * 32)),
                patches = null,
                patchRects = IntArray(0),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Valid)
    }

    @Test
    fun acceptsSequentialIncrementalPatch() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_INCREMENTAL,
                pages = null,
                patches = arrayOf(ByteBuffer.allocateDirect(8 * 6)),
                patchRects = intArrayOf(0, 1, 2, 8, 6),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Valid)
    }

    @Test
    fun rejectsIncrementalPatchWhenDisabled() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_INCREMENTAL,
                pages = null,
                patches = arrayOf(ByteBuffer.allocateDirect(8 * 6)),
                patchRects = intArrayOf(0, 1, 2, 8, 6),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = false,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    @Test
    fun rejectsPatchOutsideAtlasPage() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_INCREMENTAL,
                pages = null,
                patches = arrayOf(ByteBuffer.allocateDirect(8 * 6)),
                patchRects = intArrayOf(0, 60, 2, 8, 6),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    @Test
    fun rejectsIncrementalPatchAgainstDifferentUploadedGeneration() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_INCREMENTAL,
                pages = null,
                patches = arrayOf(ByteBuffer.allocateDirect(8 * 6)),
                patchRects = intArrayOf(0, 1, 2, 8, 6),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
            uploadedContentSerial = 6L,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    @Test
    fun rejectsMetadataAgainstDifferentUploadedGeneration() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_METADATA,
                pages = null,
                patches = null,
                patchRects = IntArray(0),
                contentSerial = 7L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
            uploadedContentSerial = 6L,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    @Test
    fun rejectsShortReplacementPage() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_REPLACE,
                pages = arrayOf(ByteBuffer.allocateDirect(63)),
                patches = null,
                patchRects = IntArray(0),
                contentSerial = 8L,
                baseContentSerial = 7L,
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    @Test
    fun rejectsInvalidActiveBoundsStride() {
        val result = AssAtlasFrameValidator.validate(
            frame = frame(
                changed = AssAtlasFrame.CHANGE_METADATA,
                pages = null,
                patches = null,
                patchRects = IntArray(0),
                contentSerial = 7L,
                baseContentSerial = 7L,
                activeBounds = intArrayOf(0, 0, 1),
            ),
            allowIncremental = true,
        )

        assertTrue(result is AssAtlasFrameValidator.ValidationResult.Invalid)
    }

    private fun frame(
        changed: Int,
        pages: Array<ByteBuffer>?,
        patches: Array<ByteBuffer>?,
        patchRects: IntArray,
        contentSerial: Long,
        baseContentSerial: Long,
        activeBounds: IntArray = intArrayOf(10, 20, 8, 6),
    ) = AssAtlasFrame(
        pages = pages,
        pageWidths = intArrayOf(64),
        pageHeights = intArrayOf(32),
        quads = intArrayOf(10, 20, 8, 6, 0x11223344, 0, 1, 2),
        changed = changed,
        dirtyRects = IntArray(0),
        patches = patches,
        patchRects = patchRects,
        activeBounds = activeBounds,
        contentSerial = contentSerial,
        baseContentSerial = baseContentSerial,
        copiedMaskBytes = 0L,
    )
}
