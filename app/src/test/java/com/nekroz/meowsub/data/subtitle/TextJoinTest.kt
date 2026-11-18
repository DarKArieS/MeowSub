package com.nekroz.meowsub.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class TextJoinTest {

    @Test
    fun cjkJoinsDirectly() {
        assertEquals("今日はいい天気ですね。明日は雨です。", TextJoin.join("今日はいい天気ですね。", "明日は雨です。"))
        assertEquals("我們明天見面，好嗎", TextJoin.join("我們明天見面，", "好嗎"))
        assertEquals("ラーメン食べたい", TextJoin.join("ラーメン", "食べたい"))
    }

    @Test
    fun spacedScriptsGetOneSpace() {
        assertEquals("The quick brown fox jumps.", TextJoin.join("The quick brown", "fox jumps."))
        assertEquals("Hello, world", TextJoin.join("Hello, ", " world"))
        assertEquals("안녕하세요 반갑습니다", TextJoin.join("안녕하세요", "반갑습니다"))
    }

    @Test
    fun mixedSeamFollowsTheCjkSide() {
        assertEquals("午後3時からです", TextJoin.join("午後3時", "からです"))
        assertEquals("OKです", TextJoin.join("OK", "です"))
    }

    @Test
    fun blankPartsAreSkipped() {
        assertEquals("abc", TextJoin.join("", "abc"))
        assertEquals("abc", TextJoin.join("abc", "  "))
        assertEquals("", TextJoin.join("", ""))
    }
}
