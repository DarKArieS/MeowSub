package com.nekroz.meowsub.data.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanupTest {

    private fun c(s: String) = TranscriptCleanup.collapseRepeats(s)

    @Test
    fun repeatedSentenceCollapses() {
        assertEquals("明日の会議は午後3時からです。", c("明日の会議は午後3時からです。明日の会議は午後3時からです。"))
        assertEquals("今日はとてもいい天気ですね。", c("今日はとてもいい天気ですね。今日はとてもいい天気ですね。今日はとてもいい天気ですね。"))
        assertEquals("我們明天早上九點在車站見面。", c("我們明天早上九點在車站見面,我們明天早上九點在車站見面。"))
        assertEquals("Thank you so much.", c(" Thank you so much. Thank you so much."))
    }

    @Test
    fun ordinaryTextIsKept() {
        assertEquals("今日はとてもいい天気ですね。", c(" 今日はとてもいい天気ですね。"))
        assertEquals("The quick brown fox jumps over the lazy dog.", c("The quick brown fox jumps over the lazy dog."))
        assertEquals("", c("  "))
    }

    @Test
    fun shortRepeatsMayBeGenuine() {
        assertEquals("はい、はい", c("はい、はい"))
        assertEquals("Yes. Yes.", c("Yes. Yes."))
        assertEquals("ははははは", c("ははははは"))
    }

    @Test
    fun partialRepeatIsNotARepeat() {
        assertEquals("明日の会議は、明日の会議は午後です。", c("明日の会議は、明日の会議は午後です。"))
    }
}
