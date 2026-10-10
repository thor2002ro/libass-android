package io.github.peerless2012.ass.media.render

import io.github.peerless2012.ass.AssAtlasFrame

/** Validates a complete native atlas payload before any persistent GL state changes. */
internal object AssAtlasFrameValidator {
    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun validate(
        frame: AssAtlasFrame,
        allowIncremental: Boolean,
        uploadedContentSerial: Long? = null,
    ): ValidationResult {
        if (frame.pageWidths.size != frame.pageHeights.size) return invalid("page layout mismatch")
        if (frame.quads.size % AssAtlasFrame.QUAD_STRIDE != 0) return invalid("quad stride")
        if (frame.activeBounds.isNotEmpty() &&
            (frame.activeBounds.size != AssAtlasFrame.ACTIVE_BOUNDS_STRIDE ||
                frame.activeBounds.any { it < 0 })
        ) return invalid("active bounds")
        if (frame.contentSerial < 0L || frame.baseContentSerial < 0L || frame.copiedMaskBytes < 0L) {
            return invalid("negative generation or byte count")
        }
        validatePagesAndQuads(frame)?.let { return it }

        return when (frame.changed) {
            AssAtlasFrame.CHANGE_NONE -> {
                if (frame.pages != null || frame.patches != null || frame.patchRects.isNotEmpty() ||
                    frame.quads.isNotEmpty() || frame.activeBounds.isNotEmpty()
                ) invalid("unchanged payload") else ValidationResult.Valid
            }

            AssAtlasFrame.CHANGE_METADATA -> {
                if (frame.pages != null || frame.patches != null || frame.patchRects.isNotEmpty()) {
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
        frame.pageWidths.indices.forEach { page ->
            if (frame.pageWidths[page] <= 0 || frame.pageHeights[page] <= 0) {
                return invalid("non-positive page")
            }
        }
        var offset = 0
        while (offset < frame.quads.size) {
            val width = frame.quads[offset + AssAtlasFrame.QUAD_WIDTH]
            val height = frame.quads[offset + AssAtlasFrame.QUAD_HEIGHT]
            val page = frame.quads[offset + AssAtlasFrame.QUAD_PAGE]
            val x = frame.quads[offset + AssAtlasFrame.QUAD_ATLAS_X]
            val y = frame.quads[offset + AssAtlasFrame.QUAD_ATLAS_Y]
            if (width <= 0 || height <= 0 || page !in frame.pageWidths.indices || x < 0 || y < 0 ||
                x.toLong() + width > frame.pageWidths[page] ||
                y.toLong() + height > frame.pageHeights[page]
            ) return invalid("quad outside page")
            offset += AssAtlasFrame.QUAD_STRIDE
        }
        return null
    }

    private fun validateReplacement(frame: AssAtlasFrame): ValidationResult {
        if (frame.patches != null || frame.patchRects.isNotEmpty()) return invalid("replacement patches")
        val pages = frame.pages ?: return if (frame.quads.isEmpty() && frame.pageWidths.isEmpty()) {
            ValidationResult.Valid
        } else invalid("missing replacement pages")
        if (pages.size != frame.pageWidths.size) return invalid("replacement page count")
        pages.indices.forEach { page ->
            val required = frame.pageWidths[page].toLong() * frame.pageHeights[page]
            if (required > Int.MAX_VALUE || pages[page].capacity() < required.toInt()) {
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
        if (frame.pages != null) return invalid("incremental full pages")
        val patches = frame.patches ?: return invalid("missing patches")
        if (frame.patchRects.size != patches.size * AssAtlasFrame.PATCH_RECT_STRIDE) {
            return invalid("patch rectangle stride")
        }
        if (frame.baseContentSerial == Long.MAX_VALUE ||
            frame.contentSerial != frame.baseContentSerial + 1L
        ) return invalid("incremental generation")
        if (uploadedContentSerial != null && frame.baseContentSerial != uploadedContentSerial) {
            return invalid("incremental uploaded generation")
        }

        patches.indices.forEach { patch ->
            val offset = patch * AssAtlasFrame.PATCH_RECT_STRIDE
            val page = frame.patchRects[offset]
            val left = frame.patchRects[offset + 1]
            val top = frame.patchRects[offset + 2]
            val width = frame.patchRects[offset + 3]
            val height = frame.patchRects[offset + 4]
            if (page !in frame.pageWidths.indices || left < 0 || top < 0 || width <= 0 || height <= 0 ||
                left.toLong() + width > frame.pageWidths[page] ||
                top.toLong() + height > frame.pageHeights[page]
            ) return invalid("patch outside page")
            val required = width.toLong() * height
            if (required > Int.MAX_VALUE || patches[patch].capacity() < required.toInt()) {
                return invalid("short patch")
            }
        }
        return ValidationResult.Valid
    }

    private fun invalid(reason: String) = ValidationResult.Invalid(reason)
}
