package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SubtitleTest {
    @Test
    fun srtBomCrLfMultilineAndOverlapsUseExactBoundaries() {
        val cues =
            Subtitles.parseSrt(
                "\uFEFF1\r\n00:00:01,100 --> 00:00:02,000\r\nhola\r\nmundo\r\n\r\n2\r\n00:00:01,500 --> 00:00:03,000\r\nsegunda\r\n")
        assertEquals("", Subtitles.textAt(cues, 1099))
        assertEquals("hola\nmundo", Subtitles.textAt(cues, 1100))
        assertEquals("hola\nmundo\nsegunda", Subtitles.textAt(cues, 1500))
        assertEquals("segunda", Subtitles.textAt(cues, 2000))
        assertEquals("", Subtitles.textAt(cues, 3000))
    }

    @Test
    fun invalidOrBackwardsSubtitleTimesAreRejected() {
        for (source in
            listOf(
                "1\n00:90:00,000 --> 00:91:00,000\nx",
                "1\n00:00:02,000 --> 00:00:01,000\nx",
                "not SRT")) {
            try {
                Subtitles.parseSrt(source)
                fail("Accepted invalid SRT")
            } catch (_: IOException) {}
        }
    }
}
