package com.nekroz.meowsub.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SrtFormatTest {

    private fun parse(s: String): List<SubtitleLine> {
        var id = 0L
        return SrtFormat.parse(s) { id++ }
    }

    @Test
    fun parsesBasicFileWithCrlfAndBom() {
        val lines = parse("﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nWorld\r\n\r\n2\r\n00:01:00,100 --> 00:01:02,000\r\nNext\r\n")
        assertEquals(2, lines.size)
        assertEquals(SubtitleLine(0, 1000, 2500, "Hello\nWorld"), lines[0])
        assertEquals(SubtitleLine(1, 60100, 62000, "Next"), lines[1])
    }

    @Test
    fun keepsBlankLinesInsideTextAndDropsIndex() {
        val lines = parse("1\n00:00:01,000 --> 00:00:02,000\nA\n\nB\n\n2\n00:00:03,000 --> 00:00:04,000\nC\n")
        assertEquals("A\n\nB", lines[0].text)
        assertEquals("C", lines[1].text)
    }

    @Test
    fun toleratesDotsShortFractionsAndMissingIndex() {
        val lines = parse("00:00:01.5 --> 00:00:02.25\nX\n\n00:00:03,000 --> 00:00:04,000 X1:10\nY")
        assertEquals(1500L, lines[0].startMs)
        assertEquals(2250L, lines[0].endMs)
        assertEquals("Y", lines[1].text)
    }

    @Test
    fun serializeRoundTrips() {
        val src = listOf(
            SubtitleLine(0, 0, 1234, "one"),
            SubtitleLine(1, 3_723_456, 3_724_000, "two\nlines"),
            SubtitleLine(2, 5000, 6000, ""),
        )
        val out = SrtFormat.serialize(src)
        assertEquals(
            "1\r\n00:00:00,000 --> 00:00:01,234\r\none\r\n\r\n" +
                "2\r\n01:02:03,456 --> 01:02:04,000\r\ntwo\r\nlines\r\n\r\n" +
                "3\r\n00:00:05,000 --> 00:00:06,000\r\n\r\n",
            out,
        )
        assertEquals(src, parse(out))
    }

    @Test
    fun newLinesDoNotOverlapNeighbours() {
        val lines = listOf(
            SubtitleLine(0, 0, 1000, "a"),
            SubtitleLine(1, 1500, 2500, "b"),
            SubtitleLine(2, 2500, 4000, "c"),
        )
        // After "a": only 500 ms until "b" starts.
        assertEquals(1500L, lines.freeEndAfter(1000, 2000, excludeId = 0))
        // After "b": "c" starts right away, so the new line is zero-length instead of overlapping.
        assertEquals(2500L, lines.freeEndAfter(2500, 2000, excludeId = 1))
        // After the last line: full default duration.
        assertEquals(6000L, lines.freeEndAfter(4000, 2000, excludeId = 2))
        // Before "b": cannot start before "a" ends.
        assertEquals(1000L, lines.freeStartBefore(1500, 2000, excludeId = 1))
        // Before "a": clamped at 0.
        assertEquals(0L, lines.freeStartBefore(0, 2000, excludeId = 0))
    }

    @Test
    fun plainTextDropsNumbersAndTimings() {
        val lines = listOf(SubtitleLine(0, 0, 1000, "第一句"), SubtitleLine(1, 2000, 3000, "兩行\n字幕"))
        assertEquals("第一句\n兩行\n字幕", SubtitleClipboard.plainText(lines))
    }

    @Test
    fun timeFormatParseAndDisplay() {
        assertEquals("1:02:03.456", TimeFormat.display(3_723_456))
        assertEquals(3_723_456L, TimeFormat.parse("1:02:03.456"))
        assertEquals(63_500L, TimeFormat.parse("1:03,5"))
        assertEquals(7_000L, TimeFormat.parse("7"))
        assertNull(TimeFormat.parse("1:75"))
        assertNull(TimeFormat.parse("abc"))
        assertEquals("0:05.50", TimeFormat.ruler(5_500, 50))
        assertEquals("1:00:00", TimeFormat.ruler(3_600_000, 60_000))
    }

    @Test
    fun decodesBig5AndUtf8() {
        val text = "中文字幕"
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextDecoding.decode(text.toByteArray(charset("Big5"))))
        assertEquals(text, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }
}
