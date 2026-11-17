package com.nekroz.meowsub.ui.editor

import com.nekroz.meowsub.data.subtitle.SubtitleLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnappingTest {
    private val lines = listOf(
        SubtitleLine(0, 0, 1000, "a"),
        SubtitleLine(1, 1500, 2500, "b"),
        SubtitleLine(2, 3000, 4000, "c"),
        SubtitleLine(3, 9000, 9500, "d"),
    )

    @Test
    fun adjacentBoundariesUsePreviousAndNextOnly() {
        assertEquals(listOf(0L, 1000L, 3000L, 4000L), Snapping.adjacentBoundaries(lines, 1))
        assertEquals(listOf(1500L, 2500L), Snapping.adjacentBoundaries(lines, 0))
        assertEquals(emptyList<Long>(), Snapping.adjacentBoundaries(lines, -1))
    }

    @Test
    fun nearestPicksClosestWithinRadius() {
        val c = Snapping.adjacentBoundaries(lines, 1)
        assertEquals(1000L, Snapping.nearest(1080, c, 100.0))
        assertEquals(3000L, Snapping.nearest(2950, c, 100.0))
        assertNull(Snapping.nearest(1200, c, 100.0))
    }
}
