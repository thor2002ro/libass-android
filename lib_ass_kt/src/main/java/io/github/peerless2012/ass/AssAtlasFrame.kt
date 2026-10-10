package io.github.peerless2012.ass

import java.nio.ByteBuffer

/**
 * Batched libass output.
 *
 * Alpha masks are packed into one or more atlas pages. [quads] contains
 * [QUAD_STRIDE] integers per image in libass composition order:
 *
 * `dstX, dstY, width, height, color, page, atlasX, atlasY`.
 *
 * [pages] is present only when [changed] is [CHANGE_REPLACE]. Incremental
 * changes carry tightly packed [patches], while metadata-only changes reuse
 * the previously uploaded mask textures.
 */
class AssAtlasFrame(
    val pages: Array<ByteBuffer>?,
    val pageWidths: IntArray,
    val pageHeights: IntArray,
    val quads: IntArray,
    val changed: Int,
    /** `left, top, width, height` for each page; empty means a full upload. */
    val dirtyRects: IntArray,
    /** Monotonic native content generation used to reject skipped dirty updates. */
    val contentSerial: Long,
    /** Tightly packed changed masks for [CHANGE_INCREMENTAL]. */
    val patches: Array<ByteBuffer>? = null,
    /** `page, left, top, width, height` for every entry in [patches]. */
    val patchRects: IntArray = IntArray(0),
    /** Clamped `left, top, width, height` union of visible subtitle images. */
    val activeBounds: IntArray = IntArray(0),
    /** Atlas generation required before applying an incremental frame. */
    val baseContentSerial: Long = contentSerial,
    /** Number of mask bytes copied into owned native buffers for this frame. */
    val copiedMaskBytes: Long = 0L,
) {
    val imageCount: Int
        get() = quads.size / QUAD_STRIDE

    val hasImages: Boolean
        get() = quads.isNotEmpty()

    companion object {
        const val CHANGE_NONE = 0
        const val CHANGE_METADATA = 1
        const val CHANGE_INCREMENTAL = 2
        const val CHANGE_REPLACE = 3

        @Deprecated("Use CHANGE_METADATA")
        const val CHANGE_POSITION = CHANGE_METADATA

        @Deprecated("Use CHANGE_REPLACE")
        const val CHANGE_CONTENT = CHANGE_REPLACE

        const val QUAD_STRIDE = 8
        const val QUAD_DST_X = 0
        const val QUAD_DST_Y = 1
        const val QUAD_WIDTH = 2
        const val QUAD_HEIGHT = 3
        const val QUAD_COLOR = 4
        const val QUAD_PAGE = 5
        const val QUAD_ATLAS_X = 6
        const val QUAD_ATLAS_Y = 7

        const val PATCH_RECT_STRIDE = 5
        const val ACTIVE_BOUNDS_STRIDE = 4

        fun unchanged(): AssAtlasFrame = AssAtlasFrame(
            pages = null,
            pageWidths = IntArray(0),
            pageHeights = IntArray(0),
            quads = IntArray(0),
            changed = CHANGE_NONE,
            dirtyRects = IntArray(0),
            contentSerial = 0L,
            patches = null,
            patchRects = IntArray(0),
            activeBounds = IntArray(0),
            baseContentSerial = 0L,
            copiedMaskBytes = 0L,
        )
    }
}
