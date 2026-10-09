package io.github.peerless2012.ass

/**
 * Batched libass output.
 *
 * Alpha masks are packed into one or more atlas pages. [quads] contains
 * [QUAD_STRIDE] integers per image in libass composition order:
 *
 * `dstX, dstY, width, height, color, page, atlasX, atlasY`.
 *
 * [pages] is present only when [changed] is [CHANGE_CONTENT]. For
 * [CHANGE_POSITION], callers reuse the previous page textures and update only
 * the quad metadata.
 */
class AssAtlasFrame(
    val pages: Array<ByteArray>?,
    val pageWidths: IntArray,
    val pageHeights: IntArray,
    val quads: IntArray,
    val changed: Int,
) {
    val imageCount: Int
        get() = quads.size / QUAD_STRIDE

    val hasImages: Boolean
        get() = quads.isNotEmpty()

    companion object {
        const val CHANGE_NONE = 0
        const val CHANGE_POSITION = 1
        const val CHANGE_CONTENT = 2

        const val QUAD_STRIDE = 8
        const val QUAD_DST_X = 0
        const val QUAD_DST_Y = 1
        const val QUAD_WIDTH = 2
        const val QUAD_HEIGHT = 3
        const val QUAD_COLOR = 4
        const val QUAD_PAGE = 5
        const val QUAD_ATLAS_X = 6
        const val QUAD_ATLAS_Y = 7

        fun unchanged(): AssAtlasFrame = AssAtlasFrame(
            pages = null,
            pageWidths = IntArray(0),
            pageHeights = IntArray(0),
            quads = IntArray(0),
            changed = CHANGE_NONE,
        )
    }
}
