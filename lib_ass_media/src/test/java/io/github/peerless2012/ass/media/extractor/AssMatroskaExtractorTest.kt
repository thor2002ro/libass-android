package io.github.peerless2012.ass.media.extractor

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.render.AssRenderer
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AssMatroskaExtractorTest {
    @Test
    fun `reads out of order attachment and deduplicates across extractors`() {
        val handler = AssHandler(AssRenderType.CUES)
        val data = byteArrayOf(1, 2, 3)
        val attachment = fontAttachment(data, mime = "FONT/TTF", uid = 7)

        readEbml(TestExtractor(handler), attachment)
        readEbml(TestExtractor(handler), attachment)

        val (name, registeredData) = handler.pendingFonts().single()
        assertEquals("font.ttf", name)
        assertArrayEquals(data, registeredData)
    }

    @Test
    fun `recognizes collection and legacy font attachments`() {
        val collection = MatroskaAttachment().apply {
            name = "font.ttc"
            mime = "font/collection"
            data = byteArrayOf(1)
        }
        val legacy = MatroskaAttachment().apply {
            name = "FONT.TTC"
            mime = "application/octet-stream"
            data = byteArrayOf(2)
        }

        assertEquals("font.ttc", collection.fontOrNull()?.first)
        assertEquals("FONT.TTC", legacy.fontOrNull()?.first)
        assertNull(legacy.apply { name = "cover.jpg" }.fontOrNull())
    }

    @Test
    fun `media transition resets pending fonts and deduplication`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 1
        }
        val format = AssHandler::class.java.getDeclaredField("format").apply {
            isAccessible = true
            set(handler, Format.Builder().setId("old").build())
        }
        handler.addEmbeddedFont(7, "old.ttf", byteArrayOf(1))

        handler.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        handler.addEmbeddedFont(7, "new.ttf", byteArrayOf(2))

        assertNull(format.get(handler))
        val (name, data) = handler.pendingFonts().single()
        assertEquals("new.ttf", name)
        assertArrayEquals(byteArrayOf(2), data)
    }

    @Test
    fun `skips font exceeding configured attachment limit`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 2
        }

        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(1, 2, 3)))

        assertTrue(handler.pendingFonts().isEmpty())
    }

    @Test
    fun `embedded font budget can be configured at construction`() {
        val handler = AssHandler(
            AssRenderType.CUES,
            AssHandlerConfig(maxEmbeddedFontBytes = 2)
        )

        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(1, 2, 3)))

        assertTrue(handler.pendingFonts().isEmpty())
    }

    @Test
    fun `limits cumulative embedded font bytes`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 4
        }

        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(1, 2, 3), uid = 1))
        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(4, 5, 6), uid = 2))

        val (_, data) = handler.pendingFonts().single()
        assertArrayEquals(byteArrayOf(1, 2, 3), data)
    }

    @Test
    fun `invalid candidate does not consume accepted font budget`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 3
        }

        readEbml(
            TestExtractor(handler),
            fontAttachment(byteArrayOf(1, 2, 3), mime = "image/png", uid = 1)
        )
        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(4, 5, 6), uid = 2))

        val (_, data) = handler.pendingFonts().single()
        assertArrayEquals(byteArrayOf(4, 5, 6), data)
    }

    @Test
    fun `ignores stale reservation after media reset`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 3
        }
        val stale = requireNotNull(handler.reserveEmbeddedFont(null, 3))

        handler.reset()
        val current = requireNotNull(handler.reserveEmbeddedFont(null, 3))
        handler.discardEmbeddedFont(stale)
        handler.addReservedEmbeddedFont(stale, 1, "stale.ttf", byteArrayOf(1, 2, 3))

        assertTrue(handler.pendingFonts().isEmpty())
        handler.addReservedEmbeddedFont(current, 2, "current.ttf", byteArrayOf(1, 2, 3))
        assertEquals("current.ttf", handler.pendingFonts().single().first)
    }

    @Test
    fun `limits accepted font and candidate counts`() {
        val acceptedHandler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 1024
        }
        repeat(300) { index ->
            acceptedHandler.addEmbeddedFont(index.toLong(), "$index.ttf", byteArrayOf(1))
        }
        assertEquals(256, acceptedHandler.pendingFonts().size)

        val candidateHandler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 1024
        }
        repeat(1024) {
            candidateHandler.discardEmbeddedFont(
                requireNotNull(candidateHandler.reserveEmbeddedFont(null, 1))
            )
        }
        assertNull(candidateHandler.reserveEmbeddedFont(null, 1))

        val byteHandler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 3
        }
        repeat(4) {
            byteHandler.discardEmbeddedFont(requireNotNull(byteHandler.reserveEmbeddedFont(null, 3)))
        }
        assertNull(byteHandler.reserveEmbeddedFont(null, 1))
    }

    @Test
    fun `seek invalidates unfinished attachment reservation`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 3
        }
        val extractor = TestExtractor(handler)
        extractor.beginAttachment()
        val input = DefaultExtractorInput(ByteArrayInputStream(byteArrayOf(1, 2, 3))::read, 0, 3)
        extractor.readAttachmentData(input, 3)
        val attachment = currentAttachmentField.get(extractor) as MatroskaAttachment
        val reservation = requireNotNull(attachment.reservation)

        try {
            extractor.seek(0, 0)
        } catch (error: RuntimeException) {
            assertTrue(error.message.orEmpty().contains("not mocked"))
        }
        handler.addReservedEmbeddedFont(reservation, 1, "font.ttf", byteArrayOf(1, 2, 3))

        assertTrue(handler.pendingFonts().isEmpty())
    }

    @Test
    fun `skips repeated file data and empty fonts`() {
        val handler = AssHandler(AssRenderType.CUES).apply {
            maxEmbeddedFontBytes = 6
        }
        val attachment = master(
            byteArrayOf(0x61, 0xA7.toByte()),
            element(byteArrayOf(0x46, 0x5C), byteArrayOf(1, 2, 3)) +
                element(byteArrayOf(0x46, 0x5C), byteArrayOf(4, 5, 6)) +
                element(byteArrayOf(0x46, 0x6E), "font.ttf".toByteArray()) +
                element(byteArrayOf(0x46, 0x60), "font/ttf".toByteArray())
        )

        readEbml(TestExtractor(handler), attachment)
        readEbml(TestExtractor(handler), fontAttachment(byteArrayOf(), uid = 2))

        val (_, data) = handler.pendingFonts().single()
        assertArrayEquals(byteArrayOf(1, 2, 3), data)
    }

    @Test
    fun `only owned renderer releases handler`() {
        val externalHandler = AssHandler(AssRenderType.CUES)
        AssRenderer(externalHandler).release()
        assertFalse(externalHandler.isReleased())

        val ownedHandler = AssHandler(AssRenderType.CUES)
        AssRenderer(ownedHandler, true).release()
        assertTrue(ownedHandler.isReleased())
    }

    private class TestExtractor(handler: AssHandler) : AssMatroskaExtractor(
        AssSubtitleParserFactory(handler),
        handler
    ) {
        fun beginAttachment() = startMasterElement(ID_ATTACHED_FILE, 0, 3)

        fun readAttachmentData(input: ExtractorInput, size: Int) =
            binaryElement(ID_FILE_DATA, size, input)
    }

    private fun readEbml(extractor: AssMatroskaExtractor, data: ByteArray) {
        val stream = ByteArrayInputStream(data)
        val input = DefaultExtractorInput(stream::read, 0, data.size.toLong())
        val reader = readerField.get(extractor)
        val read = reader.javaClass.getDeclaredMethod("read", ExtractorInput::class.java).apply {
            isAccessible = true
        }
        while (read.invoke(reader, input) as Boolean) {
            // Read until the fixture is exhausted.
        }
    }

    private fun element(id: ByteArray, data: ByteArray): ByteArray {
        require(data.size < 0x7F)
        return id + byteArrayOf((0x80 or data.size).toByte()) + data
    }

    private fun master(id: ByteArray, children: ByteArray): ByteArray = element(id, children)

    private fun fontAttachment(
        data: ByteArray,
        mime: String = "font/ttf",
        uid: Byte = 8
    ): ByteArray = master(
        byteArrayOf(0x61, 0xA7.toByte()),
        element(byteArrayOf(0x46, 0x5C), data) +
            element(byteArrayOf(0x46, 0x6E), "font.ttf".toByteArray()) +
            element(byteArrayOf(0x46, 0x60), mime.toByteArray()) +
            element(byteArrayOf(0x46, 0xAE.toByte()), byteArrayOf(uid))
    )

    @Suppress("UNCHECKED_CAST")
    private fun AssHandler.pendingFonts(): List<Pair<String, ByteArray>> {
        val field = AssHandler::class.java.getDeclaredField("pendingFonts").apply {
            isAccessible = true
        }
        return field.get(this) as List<Pair<String, ByteArray>>
    }

    private fun AssHandler.isReleased(): Boolean {
        val field = AssHandler::class.java.getDeclaredField("released").apply {
            isAccessible = true
        }
        return field.getBoolean(this)
    }

    private companion object {
        val readerField = MatroskaExtractor::class.java.getDeclaredField("reader").apply {
            isAccessible = true
        }
        val currentAttachmentField = AssMatroskaExtractor::class.java
            .getDeclaredField("currentAttachment")
            .apply { isAccessible = true }
    }
}
