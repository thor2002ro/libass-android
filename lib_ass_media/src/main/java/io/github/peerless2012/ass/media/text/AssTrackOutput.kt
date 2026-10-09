package io.github.peerless2012.ass.media.text

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.TrackOutput
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.util.regex.Pattern

/**
 * This class is only used by the overlay renderer. It's needed to get the start time of the subtitles.
 */
@UnstableApi
class AssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: AssMatroskaExtractor,
) : TrackOutput by delegate {

    private var isAss = false

    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAss = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs.isValidTs) {
            val sample = extractor.subtitleSample
            val sampleLimit = sample.limit()
            val endIndex = findTokenIndex(sample.data, sampleLimit, 1)
            val lineIndex = findTokenIndex(sample.data, sampleLimit, 2)
            if (endIndex == 0 || lineIndex == 0) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }

            val rawDuration = sample.data.decodeToString(endIndex, lineIndex - 1)
            val durationUs = parseTimecodeUs(rawDuration)
            val payloadLength = sampleLimit - lineIndex
            // Media3 truncates subtitleSample.limit() at NUL bytes, which can appear inside zlib data.
            val inflatedPayload = sample.data.inflateZlib(lineIndex, sample.data.size - lineIndex)
            val data = inflatedPayload ?: sample.data
            val dataOffset = if (inflatedPayload == null) lineIndex else 0
            val dataLength = inflatedPayload?.size ?: payloadLength

            assHandler.readTrackDialogue(
                trackId = trackId,
                start = timeUs / 1000,
                duration = durationUs / 1000,
                data = data,
                offset = dataOffset,
                length = dataLength
            )
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) {
            return C.TIME_UNSET
        }
        var timestampUs =
            Util.castNonNull(matcher.group(1)).toLong() * 60 * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(2)).toLong() * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(3)).toLong() * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(4)).toLong() * 10000
        return timestampUs
    }

    private fun findTokenIndex(array: ByteArray, limit: Int, tokenNumber: Int): Int {
        if (tokenNumber == 0) return 0
        var tokensFound = 0
        for (index in 0 until limit) {
            if (array[index] == COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return 0
    }

    private val Long.isValidTs
        get() = this != C.TIME_UNSET

    private companion object {
        val SSA_TIMECODE_PATTERN: Pattern =
            Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")

        const val COMMA = ','.code.toByte()

        private fun ByteArray.inflateZlib(offset: Int, length: Int): ByteArray? {
            if (!hasZlibHeader(offset, length)) return null

            return runCatching {
                InflaterInputStream(ByteArrayInputStream(this, offset, length)).use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toByteArray()
                }
            }.getOrNull()
        }

        private fun ByteArray.hasZlibHeader(offset: Int, length: Int): Boolean {
            if (length < 2 || offset < 0 || offset + 1 >= size) return false

            val cmf = this[offset].toInt() and 0xFF
            val flg = this[offset + 1].toInt() and 0xFF
            return (cmf and 0x0F) == 8 && (((cmf shl 8) + flg) % 31) == 0
        }
    }
}
