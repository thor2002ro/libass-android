package io.github.peerless2012.ass

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Batched libass output stored in one native-owned direct packet.
 *
 * The packet contains fixed-size page, quad, and patch records followed by
 * aligned alpha-mask payloads. Absolute reads leave its mutable position free
 * for the GL thread to select one payload without allocating buffer views.
 */
class AssAtlasFrame private constructor(
    private val packet: ByteBuffer?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    /** JNI entry point for native-produced packets. */
    constructor(packet: ByteBuffer) : this(
        packet = packet.order(ByteOrder.nativeOrder()),
        marker = Unit,
    )

    /** Compatibility constructor for tests and non-native callers. */
    constructor(
        pages: Array<ByteBuffer>?,
        pageWidths: IntArray,
        pageHeights: IntArray,
        quads: IntArray,
        changed: Int,
        dirtyRects: IntArray,
        contentSerial: Long,
        patches: Array<ByteBuffer>? = null,
        patchRects: IntArray = IntArray(0),
        activeBounds: IntArray = IntArray(0),
        baseContentSerial: Long = contentSerial,
        copiedMaskBytes: Long = 0L,
        @Suppress("UNUSED_PARAMETER") compatibilityMarker: Unit = Unit,
    ) : this(
        packet = buildPacket(
            pages = pages,
            pageWidths = pageWidths,
            pageHeights = pageHeights,
            quads = quads,
            changed = changed,
            dirtyRects = dirtyRects,
            contentSerial = contentSerial,
            patches = patches,
            patchRects = patchRects,
            activeBounds = activeBounds,
            baseContentSerial = baseContentSerial,
            copiedMaskBytes = copiedMaskBytes,
        ),
        marker = Unit,
    )

    val changed: Int get() = intAt(CHANGE_OFFSET)
    val pageCount: Int get() = intAt(PAGE_COUNT_OFFSET)
    val imageCount: Int get() = intAt(IMAGE_COUNT_OFFSET)
    val patchCount: Int get() = intAt(PATCH_COUNT_OFFSET)
    val contentSerial: Long get() = longAt(CONTENT_SERIAL_OFFSET)
    val baseContentSerial: Long get() = longAt(BASE_CONTENT_SERIAL_OFFSET)
    val copiedMaskBytes: Long get() = longAt(COPIED_MASK_BYTES_OFFSET)
    val hasImages: Boolean get() = imageCount > 0
    val hasActiveBounds: Boolean get() = intAt(FLAGS_OFFSET) and FLAG_ACTIVE_BOUNDS != 0
    val packetSize: Int get() = intAt(TOTAL_SIZE_OFFSET)

    fun pageWidth(index: Int): Int = recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, 0)
    fun pageHeight(index: Int): Int = recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, 4)
    fun pageDataLength(index: Int): Int = recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, 12)
    fun pageDataFits(index: Int): Boolean = payloadFits(
        recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, PAGE_DATA_OFFSET),
        pageDataLength(index),
    )
    fun pageDirtyValue(index: Int, component: Int): Int {
        require(component in 0 until DIRTY_RECT_STRIDE)
        return recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, 16 + component * Int.SIZE_BYTES)
    }

    fun quadValue(index: Int, component: Int): Int {
        require(component in 0 until QUAD_STRIDE)
        return recordInt(quadRecordsOffset(), index, QUAD_RECORD_SIZE, component * Int.SIZE_BYTES)
    }

    fun patchValue(index: Int, component: Int): Int {
        require(component in 0 until PATCH_RECT_STRIDE)
        return recordInt(patchRecordsOffset(), index, PATCH_RECORD_SIZE, component * Int.SIZE_BYTES)
    }

    fun patchDataLength(index: Int): Int =
        recordInt(patchRecordsOffset(), index, PATCH_RECORD_SIZE, PATCH_DATA_LENGTH_OFFSET)
    fun patchDataFits(index: Int): Boolean = payloadFits(
        recordInt(patchRecordsOffset(), index, PATCH_RECORD_SIZE, PATCH_DATA_OFFSET),
        patchDataLength(index),
    )

    fun activeBound(component: Int): Int {
        require(component in 0 until ACTIVE_BOUNDS_STRIDE)
        return intAt(ACTIVE_BOUNDS_OFFSET + component * Int.SIZE_BYTES)
    }

    /**
     * Positions and returns the packet itself for a synchronous GL upload.
     * Callers must consume it before selecting another payload.
     */
    fun positionPageData(index: Int): ByteBuffer = positionPayload(
        recordInt(pageRecordsOffset(), index, PAGE_RECORD_SIZE, PAGE_DATA_OFFSET),
        pageDataLength(index),
    )

    /** See [positionPageData]. */
    fun positionPatchData(index: Int): ByteBuffer = positionPayload(
        recordInt(patchRecordsOffset(), index, PATCH_RECORD_SIZE, PATCH_DATA_OFFSET),
        patchDataLength(index),
    )

    fun hasValidPacketHeader(): Boolean {
        val source = packet ?: return changed == CHANGE_NONE
        if (!source.isDirect || source.capacity() < HEADER_SIZE) return false
        if (intAt(MAGIC_OFFSET) != PACKET_MAGIC || intAt(VERSION_OFFSET) != PACKET_VERSION) return false
        val totalSize = packetSize
        if (totalSize < HEADER_SIZE || totalSize > source.capacity()) return false
        if (pageCount < 0 || imageCount < 0 || patchCount < 0) return false
        val pagesEnd = sectionEnd(HEADER_SIZE, pageCount, PAGE_RECORD_SIZE) ?: return false
        val quadsEnd = sectionEnd(pagesEnd, imageCount, QUAD_RECORD_SIZE) ?: return false
        val patchesEnd = sectionEnd(quadsEnd, patchCount, PATCH_RECORD_SIZE) ?: return false
        val payload = payloadOffset()
        return pageRecordsOffset() == HEADER_SIZE && quadRecordsOffset() == pagesEnd &&
            patchRecordsOffset() == quadsEnd && payload >= patchesEnd &&
            payload.toLong() <= totalSize.toLong()
    }

    fun payloadFits(offset: Int, length: Int): Boolean {
        if (offset < payloadOffset() || length < 0) return false
        return offset.toLong() + length <= packetSize.toLong()
    }

    // Compatibility accessors. Production rendering uses indexed packet reads.
    val pageWidths: IntArray get() = IntArray(pageCount.coerceAtLeast(0)) { pageWidth(it) }
    val pageHeights: IntArray get() = IntArray(pageCount.coerceAtLeast(0)) { pageHeight(it) }
    val quads: IntArray get() = IntArray(imageCount.coerceAtLeast(0) * QUAD_STRIDE) { offset ->
        quadValue(offset / QUAD_STRIDE, offset % QUAD_STRIDE)
    }
    val dirtyRects: IntArray get() = IntArray(pageCount.coerceAtLeast(0) * DIRTY_RECT_STRIDE) { offset ->
        pageDirtyValue(offset / DIRTY_RECT_STRIDE, offset % DIRTY_RECT_STRIDE)
    }
    val patchRects: IntArray get() = IntArray(patchCount.coerceAtLeast(0) * PATCH_RECT_STRIDE) { offset ->
        patchValue(offset / PATCH_RECT_STRIDE, offset % PATCH_RECT_STRIDE)
    }
    val activeBounds: IntArray get() = if (hasActiveBounds) {
        IntArray(ACTIVE_BOUNDS_STRIDE) { activeBound(it) }
    } else {
        IntArray(0)
    }
    val pages: Array<ByteBuffer>? get() = if (changed == CHANGE_REPLACE && pageCount > 0) {
        Array(pageCount) { index -> positionPageData(index).duplicate().order(ByteOrder.nativeOrder()) }
    } else {
        null
    }
    val patches: Array<ByteBuffer>? get() = if (changed == CHANGE_INCREMENTAL && patchCount > 0) {
        Array(patchCount) { index -> positionPatchData(index).duplicate().order(ByteOrder.nativeOrder()) }
    } else {
        null
    }

    private fun positionPayload(offset: Int, length: Int): ByteBuffer {
        val source = requireNotNull(packet) { "unchanged frame has no packet" }
        require(payloadFits(offset, length)) { "payload outside packet" }
        source.limit(offset + length)
        source.position(offset)
        return source
    }

    private fun pageRecordsOffset(): Int = intAt(PAGE_RECORDS_OFFSET)
    private fun quadRecordsOffset(): Int = intAt(QUAD_RECORDS_OFFSET)
    private fun patchRecordsOffset(): Int = intAt(PATCH_RECORDS_OFFSET)
    private fun payloadOffset(): Int = intAt(PAYLOAD_OFFSET)

    private fun intAt(offset: Int): Int = packet?.takeIf { offset >= 0 && offset <= it.capacity() - Int.SIZE_BYTES }
        ?.getInt(offset) ?: 0

    private fun longAt(offset: Int): Long = packet?.takeIf { offset >= 0 && offset <= it.capacity() - Long.SIZE_BYTES }
        ?.getLong(offset) ?: 0L

    private fun recordInt(base: Int, index: Int, stride: Int, fieldOffset: Int): Int {
        require(index >= 0)
        val offset = base.toLong() + index.toLong() * stride + fieldOffset
        require(offset <= Int.MAX_VALUE)
        return intAt(offset.toInt())
    }

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
        const val DIRTY_RECT_STRIDE = 4

        const val PACKET_MAGIC = 0x41544631
        const val PACKET_VERSION = 1
        const val HEADER_SIZE = 96
        const val PAGE_RECORD_SIZE = 32
        const val QUAD_RECORD_SIZE = 32
        const val PATCH_RECORD_SIZE = 28

        private const val MAGIC_OFFSET = 0
        private const val VERSION_OFFSET = 4
        private const val TOTAL_SIZE_OFFSET = 8
        private const val CHANGE_OFFSET = 12
        private const val PAGE_COUNT_OFFSET = 16
        private const val IMAGE_COUNT_OFFSET = 20
        private const val PATCH_COUNT_OFFSET = 24
        private const val FLAGS_OFFSET = 28
        private const val ACTIVE_BOUNDS_OFFSET = 32
        private const val CONTENT_SERIAL_OFFSET = 48
        private const val BASE_CONTENT_SERIAL_OFFSET = 56
        private const val COPIED_MASK_BYTES_OFFSET = 64
        private const val PAGE_RECORDS_OFFSET = 72
        private const val QUAD_RECORDS_OFFSET = 76
        private const val PATCH_RECORDS_OFFSET = 80
        private const val PAYLOAD_OFFSET = 84
        private const val PAGE_DATA_OFFSET = 8
        private const val PATCH_DATA_OFFSET = 20
        private const val PATCH_DATA_LENGTH_OFFSET = 24
        private const val FLAG_ACTIVE_BOUNDS = 1

        fun unchanged(): AssAtlasFrame = AssAtlasFrame(null, Unit)

        private fun sectionEnd(offset: Int, count: Int, stride: Int): Int? {
            val end = offset.toLong() + count.toLong() * stride
            return end.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        }

        private fun buildPacket(
            pages: Array<ByteBuffer>?,
            pageWidths: IntArray,
            pageHeights: IntArray,
            quads: IntArray,
            changed: Int,
            dirtyRects: IntArray,
            contentSerial: Long,
            patches: Array<ByteBuffer>?,
            patchRects: IntArray,
            activeBounds: IntArray,
            baseContentSerial: Long,
            copiedMaskBytes: Long,
        ): ByteBuffer {
            require(pageWidths.size == pageHeights.size)
            require(quads.size % QUAD_STRIDE == 0)
            require(patchRects.size % PATCH_RECT_STRIDE == 0)
            require(activeBounds.isEmpty() || activeBounds.size == ACTIVE_BOUNDS_STRIDE)
            val pageCount = pageWidths.size
            val imageCount = quads.size / QUAD_STRIDE
            val patchCount = patchRects.size / PATCH_RECT_STRIDE
            if (pages != null) require(pages.size == pageCount)
            if (patches != null) require(patches.size == patchCount)

            val pageRecords = HEADER_SIZE
            val quadRecords = pageRecords + pageCount * PAGE_RECORD_SIZE
            val patchRecords = quadRecords + imageCount * QUAD_RECORD_SIZE
            val payloadStart = align8(patchRecords + patchCount * PATCH_RECORD_SIZE)
            val pageBytes = pages?.sumOf { it.remaining() } ?: 0
            val patchBytes = patches?.sumOf { it.remaining() } ?: 0
            val packet = ByteBuffer.allocateDirect(payloadStart + pageBytes + patchBytes)
                .order(ByteOrder.nativeOrder())
            packet.putInt(MAGIC_OFFSET, PACKET_MAGIC)
            packet.putInt(VERSION_OFFSET, PACKET_VERSION)
            packet.putInt(TOTAL_SIZE_OFFSET, packet.capacity())
            packet.putInt(CHANGE_OFFSET, changed)
            packet.putInt(PAGE_COUNT_OFFSET, pageCount)
            packet.putInt(IMAGE_COUNT_OFFSET, imageCount)
            packet.putInt(PATCH_COUNT_OFFSET, patchCount)
            packet.putInt(FLAGS_OFFSET, if (activeBounds.isNotEmpty()) FLAG_ACTIVE_BOUNDS else 0)
            activeBounds.forEachIndexed { index, value ->
                packet.putInt(ACTIVE_BOUNDS_OFFSET + index * Int.SIZE_BYTES, value)
            }
            packet.putLong(CONTENT_SERIAL_OFFSET, contentSerial)
            packet.putLong(BASE_CONTENT_SERIAL_OFFSET, baseContentSerial)
            packet.putLong(COPIED_MASK_BYTES_OFFSET, copiedMaskBytes)
            packet.putInt(PAGE_RECORDS_OFFSET, pageRecords)
            packet.putInt(QUAD_RECORDS_OFFSET, quadRecords)
            packet.putInt(PATCH_RECORDS_OFFSET, patchRecords)
            packet.putInt(PAYLOAD_OFFSET, payloadStart)

            var payload = payloadStart
            repeat(pageCount) { index ->
                val record = pageRecords + index * PAGE_RECORD_SIZE
                val data = pages?.get(index)
                val length = data?.remaining() ?: 0
                packet.putInt(record, pageWidths[index])
                packet.putInt(record + 4, pageHeights[index])
                packet.putInt(record + PAGE_DATA_OFFSET, if (length > 0) payload else 0)
                packet.putInt(record + 12, length)
                repeat(DIRTY_RECT_STRIDE) { component ->
                    packet.putInt(
                        record + 16 + component * Int.SIZE_BYTES,
                        dirtyRects.getOrElse(index * DIRTY_RECT_STRIDE + component) { 0 },
                    )
                }
                if (data != null) {
                    copyPayload(packet, payload, data)
                    payload += length
                }
            }
            quads.forEachIndexed { index, value ->
                packet.putInt(quadRecords + index * Int.SIZE_BYTES, value)
            }
            repeat(patchCount) { index ->
                val record = patchRecords + index * PATCH_RECORD_SIZE
                repeat(PATCH_RECT_STRIDE) { component ->
                    packet.putInt(
                        record + component * Int.SIZE_BYTES,
                        patchRects[index * PATCH_RECT_STRIDE + component],
                    )
                }
                val data = patches?.get(index)
                val length = data?.remaining() ?: 0
                packet.putInt(record + PATCH_DATA_OFFSET, if (length > 0) payload else 0)
                packet.putInt(record + PATCH_DATA_LENGTH_OFFSET, length)
                if (data != null) {
                    copyPayload(packet, payload, data)
                    payload += length
                }
            }
            packet.clear()
            return packet
        }

        private fun copyPayload(target: ByteBuffer, offset: Int, source: ByteBuffer) {
            val copy = source.duplicate()
            var targetOffset = offset
            while (copy.hasRemaining()) target.put(targetOffset++, copy.get())
        }

        private fun align8(value: Int): Int = (value + 7) and -8
    }
}
