package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame

/** Validates a complete native atlas payload before any persistent GL state changes. */
internal object AssAtlasFrameValidator {
    const val MAX_QUADS = 16_383

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun validate(
        frame: AssAtlasFrame,
        allowIncremental: Boolean,
        uploadedContentSerial: Long? = null,
    ): ValidationResult {
        if (!frame.hasValidPacketHeader()) return invalid("packet header")
        if (frame.hasActiveBounds && (0 until AssAtlasFrame.ACTIVE_BOUNDS_STRIDE).any {
                frame.activeBound(it) < 0
            }
        ) return invalid("active bounds")
        if (frame.contentSerial < 0L || frame.baseContentSerial < 0L || frame.copiedMaskBytes < 0L) {
            return invalid("negative generation or byte count")
        }
        validatePagesAndQuads(frame)?.let { return it }

        return when (frame.changed) {
            AssAtlasFrame.CHANGE_NONE -> {
                if (frame.pageCount != 0 || frame.patchCount != 0 || frame.imageCount != 0 ||
                    frame.hasActiveBounds
                ) invalid("unchanged payload") else ValidationResult.Valid
            }

            AssAtlasFrame.CHANGE_METADATA -> {
                if ((0 until frame.pageCount).any { frame.pageDataLength(it) != 0 } ||
                    frame.patchCount != 0
                ) {
                    invalid("metadata mask payload")
                } else if (frame.baseContentSerial != frame.contentSerial) {
                    invalid("metadata generation")
                } else if (uploadedContentSerial != null &&
                    frame.contentSerial != uploadedContentSerial
                ) {
                    invalid("metadata uploaded generation")
                } else ValidationResult.Valid
            }

            AssAtlasFrame.CHANGE_INCREMENTAL -> validateIncremental(
                frame,
                allowIncremental,
                uploadedContentSerial,
            )
            AssAtlasFrame.CHANGE_REPLACE -> validateReplacement(frame)
            else -> invalid("unknown change type")
        }
    }

    private fun validatePagesAndQuads(frame: AssAtlasFrame): ValidationResult? {
        if (frame.imageCount > MAX_QUADS) return invalid("too many quads")
        repeat(frame.pageCount) { page ->
            if (frame.pageWidth(page) <= 0 || frame.pageHeight(page) <= 0) {
                return invalid("non-positive page")
            }
        }
        repeat(frame.imageCount) { image ->
            val width = frame.quadValue(image, AssAtlasFrame.QUAD_WIDTH)
            val height = frame.quadValue(image, AssAtlasFrame.QUAD_HEIGHT)
            val page = frame.quadValue(image, AssAtlasFrame.QUAD_PAGE)
            val x = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_X)
            val y = frame.quadValue(image, AssAtlasFrame.QUAD_ATLAS_Y)
            if (width <= 0 || height <= 0 || page !in 0 until frame.pageCount || x < 0 || y < 0 ||
                x.toLong() + width > frame.pageWidth(page) ||
                y.toLong() + height > frame.pageHeight(page)
            ) return invalid("quad outside page")
        }
        return null
    }

    private fun validateReplacement(frame: AssAtlasFrame): ValidationResult {
        if (frame.patchCount != 0) return invalid("replacement patches")
        repeat(frame.pageCount) { page ->
            val required = frame.pageWidth(page).toLong() * frame.pageHeight(page)
            if (required > Int.MAX_VALUE || frame.pageDataLength(page) < required ||
                !frame.pageDataFits(page)
            ) {
                return invalid("short replacement page")
            }
        }
        return ValidationResult.Valid
    }

    private fun validateIncremental(
        frame: AssAtlasFrame,
        allowIncremental: Boolean,
        uploadedContentSerial: Long?,
    ): ValidationResult {
        if (!allowIncremental) return invalid("incremental disabled")
        if ((0 until frame.pageCount).any { frame.pageDataLength(it) != 0 }) {
            return invalid("incremental full pages")
        }
        if (frame.patchCount == 0) return invalid("missing patches")
        if (frame.baseContentSerial == Long.MAX_VALUE ||
            frame.contentSerial != frame.baseContentSerial + 1L
        ) return invalid("incremental generation")
        if (uploadedContentSerial != null && frame.baseContentSerial != uploadedContentSerial) {
            return invalid("incremental uploaded generation")
        }

        repeat(frame.patchCount) { patch ->
            val page = frame.patchValue(patch, 0)
            val left = frame.patchValue(patch, 1)
            val top = frame.patchValue(patch, 2)
            val width = frame.patchValue(patch, 3)
            val height = frame.patchValue(patch, 4)
            if (page !in 0 until frame.pageCount || left < 0 || top < 0 || width <= 0 || height <= 0 ||
                left.toLong() + width > frame.pageWidth(page) ||
                top.toLong() + height > frame.pageHeight(page)
            ) return invalid("patch outside page")
            val required = width.toLong() * height
            if (required > Int.MAX_VALUE || frame.patchDataLength(patch) < required ||
                !frame.patchDataFits(patch)
            ) {
                return invalid("short patch")
            }
        }
        return ValidationResult.Valid
    }

    private fun invalid(reason: String) = ValidationResult.Invalid(reason)
}
