package io.github.peerless2012.ass.media.extractor

import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.text.AssSubtitleExtractorOutput
import io.github.peerless2012.ass.media.type.AssRenderType

@OptIn(UnstableApi::class)
open class AssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    flags: Int = 0
) : MatroskaExtractor(subtitleParserFactory, flags) {

    private var currentAttachment = MatroskaAttachment()

    internal val subtitleSample = subtitleSampleField.get(this) as ParsableByteArray

    override fun getElementType(id: Int): Int {
        return when (id) {
            ID_ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_FILE_NAME -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_MIME_TYPE -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
            ID_FILE_UID -> EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
            else -> super.getElementType(id)
        }
    }

    override fun isLevel1Element(id: Int): Boolean {
        return super.isLevel1Element(id) || id == ID_ATTACHMENTS
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_EBML -> {
                if (assHandler.renderType != AssRenderType.CUES) {
                    val currentExtractor = extractorOutput.get(this) as ExtractorOutput
                    if (currentExtractor !is AssSubtitleExtractorOutput) {
                        extractorOutput.set(
                            this,
                            AssSubtitleExtractorOutput(currentExtractor, assHandler, this)
                        )
                    }
                }
                super.startMasterElement(id, contentPosition, contentSize)
            }
            ID_ATTACHED_FILE -> {
                currentAttachment.reservation?.let(assHandler::discardEmbeddedFont)
                currentAttachment = MatroskaAttachment()
            }
            else -> super.startMasterElement(id, contentPosition, contentSize)
        }
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            ID_VIDEO -> {
                // We need to get the video dimensions very early
                val track = getCurrentTrack(id)
                assHandler.setVideoSize(track.width, track.height)
                super.endMasterElement(id)
            }
            ID_ATTACHED_FILE -> {
                val reservation = currentAttachment.reservation
                val font = currentAttachment.fontOrNull()
                if (reservation != null && font != null) {
                    assHandler.addReservedEmbeddedFont(
                        reservation,
                        currentAttachment.uid,
                        font.first,
                        font.second
                    )
                } else if (reservation != null) {
                    assHandler.discardEmbeddedFont(reservation)
                }
                currentAttachment = MatroskaAttachment()
            }
            else -> super.endMasterElement(id)
        }
    }

    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_FILE_NAME -> currentAttachment.name = value
            ID_FILE_MIME_TYPE -> currentAttachment.mime = value
            else -> super.stringElement(id, value)
        }
    }

    override fun integerElement(id: Int, value: Long) {
        when (id) {
            ID_FILE_UID -> currentAttachment.uid = value
            else -> super.integerElement(id, value)
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            ID_FILE_DATA -> {
                if (contentSize < 0) {
                    throw ParserException.createForMalformedContainer(
                        "Invalid attachment size: $contentSize",
                        null
                    )
                }

                if (currentAttachment.hasFileData) {
                    input.skipFully(contentSize)
                    return
                }
                currentAttachment.hasFileData = true

                val mayBeFont = currentAttachment.mayBeFont
                val reservation = if (mayBeFont) {
                    assHandler.reserveEmbeddedFont(currentAttachment.uid, contentSize)
                } else {
                    null
                }
                if (reservation != null) {
                    try {
                        val data = ByteArray(contentSize)
                        input.readFully(data, 0, contentSize)
                        currentAttachment.data = data
                        currentAttachment.reservation = reservation
                    } catch (error: Throwable) {
                        assHandler.cancelEmbeddedFont(reservation)
                        throw error
                    }
                } else {
                    input.skipFully(contentSize)
                }
            }
            else -> super.binaryElement(id, contentSize, input)
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        currentAttachment.reservation?.let(assHandler::discardEmbeddedFont)
        currentAttachment = MatroskaAttachment()
        super.seek(position, timeUs)
    }

    companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_VIDEO = 0xE0
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_NAME = 0x466E
        const val ID_FILE_MIME_TYPE = 0x4660
        const val ID_FILE_DATA = 0x465C
        const val ID_FILE_UID = 0x46AE

        val fontMimeTypes = listOf(
            "font/ttf",
            "font/otf",
            "font/sfnt",
            "font/collection",
            "font/woff",
            "font/woff2",
            "application/font-sfnt",
            "application/font-woff",
            "application/x-truetype-font",
            "application/vnd.ms-opentype",
            "application/x-font-ttf",
        )

        val extractorOutput = MatroskaExtractor::class.java.getDeclaredField("extractorOutput").apply {
            isAccessible = true
        }
        val subtitleSampleField = MatroskaExtractor::class.java.getDeclaredField("subtitleSample").apply {
            isAccessible = true
        }
    }
}

internal class MatroskaAttachment {
    var name: String? = null
    var mime: String? = null
    var data: ByteArray? = null
    var uid: Long? = null
    var hasFileData = false
    var reservation: AssHandler.EmbeddedFontReservation? = null

    val mayBeFont: Boolean
        get() = mime == null ||
            mime.equals("application/octet-stream", ignoreCase = true) && name == null ||
            isFont()

    fun fontOrNull(): Pair<String, ByteArray>? {
        val name = name ?: return null
        val data = data ?: return null
        return if (isFont()) name to data else null
    }

    private fun isFont(): Boolean {
        val mime = mime?.lowercase()
        return mime in AssMatroskaExtractor.fontMimeTypes ||
            mime == "application/octet-stream" && hasFontExtension()
    }

    private fun hasFontExtension() = name
        ?.substringAfterLast('.', "")
        ?.lowercase() in fontExtensions

    private companion object {
        val fontExtensions = setOf("ttf", "otf", "ttc")
    }
}
