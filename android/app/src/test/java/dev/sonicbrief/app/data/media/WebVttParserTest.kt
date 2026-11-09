package dev.sonicbrief.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttParserTest {
    @Test
    fun parsesTimestampsAndText() {
        val parsed = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.250 --> 00:00:03.500
            <c>Hello &amp; welcome</c>

            00:00:04.000 --> 00:00:06.125
            Second line
            continues here
            """.trimIndent(),
        )

        assertEquals(2, parsed.cues.size)
        assertEquals(1250L, parsed.cues[0].startMs)
        assertEquals(3500L, parsed.cues[0].endMs)
        assertEquals("Hello & welcome", parsed.cues[0].text)
        assertEquals(4000L, parsed.cues[1].startMs)
        assertEquals(6125L, parsed.cues[1].endMs)
        assertEquals("Second line continues here", parsed.cues[1].text)
        assertTrue(parsed.fullText.contains("Hello & welcome"))
    }

    @Test
    fun mergesOverlappingDuplicateCues() {
        val parsed = WebVttParser.parse(
            """
            WEBVTT

            00:01.000 --> 00:03.000
            Same text

            00:02.500 --> 00:04.500
            Same text
            """.trimIndent(),
        )

        assertEquals(1, parsed.cues.size)
        assertEquals(1000L, parsed.cues[0].startMs)
        assertEquals(4500L, parsed.cues[0].endMs)
        assertEquals("Same text", parsed.fullText)
    }
}
