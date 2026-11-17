package com.nekroz.meowsub.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class BilingualTest {

    private fun line(id: Long, start: Long, end: Long, text: String) = SubtitleLine(id, start, end, text)

    private val original = listOf(
        line(1, 0, 1000, "おはよう"),
        line(2, 2000, 3000, "こんにちは"),
        line(3, 4000, 5000, "こんばんは"),
    )

    @Test
    fun matchingFilesKeepEverythingAndReportNothing() {
        val translation = listOf(
            line(10, 0, 1000, "早安"),
            line(11, 2000, 3000, "午安"),
            line(12, 4000, 5000, "晚安"),
        )
        val result = Bilingual.align(original, translation)
        assertEquals(listOf("早安", "午安", "晚安"), result.lines.map { it.text })
        assertEquals(listOf("おはよう", "こんにちは", "こんばんは"), result.lines.map { it.original })
        assertEquals(listOf(1L, 2L, 3L), result.lines.map { it.id })
        assertEquals(0, result.retimed)
        assertEquals(0, result.filled)
    }

    @Test
    fun originalTimingWinsAndSlightlyOffLinesAreRetimed() {
        val translation = listOf(
            line(10, 50, 1100, "早安"),
            line(11, 2000, 3000, "午安"),
            line(12, 3900, 4800, "晚安"),
        )
        val result = Bilingual.align(original, translation)
        assertEquals(original.map { it.startMs to it.endMs }, result.lines.map { it.startMs to it.endMs })
        assertEquals(listOf("早安", "午安", "晚安"), result.lines.map { it.text })
        assertEquals(2, result.retimed)
    }

    @Test
    fun splitTranslationsAreJoinedAndMissingOnesLeftEmpty() {
        val translation = listOf(
            line(10, 0, 500, "早"),
            line(11, 500, 1000, "安"),
            line(12, 4000, 5000, "晚安"),
        )
        val result = Bilingual.align(original, translation)
        assertEquals(listOf("早\n安", "", "晚安"), result.lines.map { it.text })
        assertEquals(2, result.retimed)
        assertEquals(1, result.filled)
    }

    @Test
    fun translationWithoutOverlapGoesToNearestLine() {
        val translation = listOf(line(10, 3200, 3600, "嗯"))
        val result = Bilingual.align(original, translation)
        assertEquals(listOf("", "嗯", ""), result.lines.map { it.text })
    }

    @Test
    fun noTranslationFileGivesEmptyTextsWithoutReporting() {
        val result = Bilingual.align(original, emptyList())
        assertEquals(listOf("", "", ""), result.lines.map { it.text })
        assertEquals(0, result.filled)
    }

    @Test
    fun emptyOriginalKeepsTheTranslation() {
        val translation = listOf(line(10, 0, 1000, "早安"))
        assertEquals(translation, Bilingual.align(emptyList(), translation).lines)
    }

    @Test
    fun tracksAndCues() {
        val l = SubtitleLine(1, 0, 1000, text = "譯", original = "原")
        assertEquals("原", l.textOf(Track.ORIGINAL))
        assertEquals("新", l.withText(Track.ORIGINAL, "新").original)
        assertEquals("原", listOf(l).forTrack(Track.ORIGINAL).single().text)
        assertEquals(listOf(Cue(0, 1000, "譯")), listOf(l).cues(Track.TRANSLATION))
    }
}
