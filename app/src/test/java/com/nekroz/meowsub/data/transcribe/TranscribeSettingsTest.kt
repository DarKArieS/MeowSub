package com.nekroz.meowsub.data.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscribeSettingsTest {

    @Test
    fun audioContextFitsTheClipWithAFloor() {
        val s = TranscribeSettings(fitContextToClip = true)
        assertEquals(256, s.audioCtx(16_000))          // 1 s -> floor
        assertEquals(10 * 50 + 64, s.audioCtx(160_000)) // 10 s -> 50 frames/s + slack
        assertEquals(0, s.audioCtx(16_000 * 30))        // 30 s -> whole window
        assertEquals(0, s.audioCtx(16_000 * 60))
    }

    @Test
    fun fullContextWhenDisabled() {
        assertEquals(0, TranscribeSettings(fitContextToClip = false).audioCtx(16_000))
    }
}
