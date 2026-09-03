package io.github.peerless2012.ass.kt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssAtlasFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssAtlasIncrementalInstrumentedTest {

    @Test
    fun animatedColorReusesPreviouslyUploadedMasks() {
        withRenderer(
            "{\\c&H0000FF&\\t(0,1000,\\c&H00FF00&)}Color",
        ) { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val changed = requireNotNull(render.renderAtlasFrame(500, 512, allowIncremental = true))

            assertEquals(AssAtlasFrame.CHANGE_REPLACE, initial.changed)
            assertEquals(AssAtlasFrame.CHANGE_METADATA, changed.changed)
            assertEquals(initial.contentSerial, changed.contentSerial)
            assertNull(changed.pages)
            assertNull(changed.patches)
        }
    }

    @Test
    fun movementReusesPreviouslyUploadedMasks() {
        withRenderer("{\\move(100,100,300,100,0,1000)}Move") { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val moved = requireNotNull(render.renderAtlasFrame(500, 512, allowIncremental = true))

            assertEquals(AssAtlasFrame.CHANGE_REPLACE, initial.changed)
            assertEquals(
                "initial=${initial.quads.contentToString()} moved=${moved.quads.contentToString()}",
                AssAtlasFrame.CHANGE_METADATA,
                moved.changed,
            )
            assertEquals(initial.contentSerial, moved.contentSerial)
            assertNull(moved.pages)
            assertNull(moved.patches)
        }
    }

    @Test
    fun compatibleMaskChangeUsesIncrementalPatch() {
        withRenderer("{\\fscx100\\t(0,1000,\\fscx101)}Patch") { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val changed = requireNotNull(render.renderAtlasFrame(500, 512, allowIncremental = true))

            assertEquals(AssAtlasFrame.CHANGE_REPLACE, initial.changed)
            assertEquals(
                "initial=${initial.quads.contentToString()} changed=${changed.quads.contentToString()}",
                AssAtlasFrame.CHANGE_INCREMENTAL,
                changed.changed,
            )
            assertNull(changed.pages)
            assertNotNull(changed.patches)
            assertTrue(requireNotNull(changed.patches).isNotEmpty())
            assertEquals(initial.contentSerial, changed.baseContentSerial)
            assertEquals(initial.contentSerial + 1L, changed.contentSerial)
            assertTrue(changed.copiedMaskBytes > 0L)
        }
    }

    @Test
    fun incrementalRenderDoesNotMutatePreviouslyPublishedReplacementBuffers() {
        withRenderer("{\\fscx100\\t(0,1000,\\fscx101)}Patch") { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val publishedBytes = requireNotNull(initial.pages).map { page ->
                page.duplicate().apply { clear() }.let { bytes ->
                    ByteArray(bytes.remaining()).also(bytes::get)
                }
            }

            val changed = requireNotNull(render.renderAtlasFrame(500, 512, allowIncremental = true))

            assertEquals(AssAtlasFrame.CHANGE_INCREMENTAL, changed.changed)
            requireNotNull(initial.pages).forEachIndexed { index, page ->
                val currentBytes = page.duplicate().apply { clear() }.let { bytes ->
                    ByteArray(bytes.remaining()).also(bytes::get)
                }
                assertTrue(
                    "published atlas page $index was mutated by a later render",
                    publishedBytes[index].contentEquals(currentBytes),
                )
            }
        }
    }

    @Test
    fun dimensionChangeReplacesCompleteAtlas() {
        withRenderer("{\\blur0\\t(0,1000,\\blur0.5)}Replace") { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val changed = requireNotNull(render.renderAtlasFrame(500, 512, allowIncremental = true))

            assertEquals(AssAtlasFrame.CHANGE_REPLACE, initial.changed)
            assertEquals(AssAtlasFrame.CHANGE_REPLACE, changed.changed)
            assertNotNull(changed.pages)
            assertNull(changed.patches)
            assertEquals(initial.contentSerial + 1L, changed.contentSerial)
        }
    }

    @Test
    fun forcedReplacementPublishesCompleteAtlasWhenLibassReportsNoChange() {
        withRenderer("Static") { render ->
            val initial = requireNotNull(render.renderAtlasFrame(0, 512, allowIncremental = true))
            val replacement = requireNotNull(
                render.renderAtlasFrame(
                    0,
                    512,
                    allowIncremental = true,
                    forceReplacement = true,
                )
            )

            assertEquals(AssAtlasFrame.CHANGE_REPLACE, initial.changed)
            assertEquals(AssAtlasFrame.CHANGE_REPLACE, replacement.changed)
            assertNotNull(replacement.pages)
            assertEquals(initial.contentSerial + 1L, replacement.contentSerial)
        }
    }

    private fun withRenderer(text: String, block: (io.github.peerless2012.ass.AssRender) -> Unit) {
        Ass().use { ass ->
            ass.createTrack().use { track ->
                track.readBuffer(script(text).toByteArray(Charsets.UTF_8))
                ass.createRender().use { render ->
                    render.setStorageSize(640, 360)
                    render.setFrameSize(640, 360)
                    render.setTrack(track)
                    block(render)
                }
            }
        }
    }

    private fun script(text: String) = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,sans-serif,36,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,$text
    """.trimIndent()
}
