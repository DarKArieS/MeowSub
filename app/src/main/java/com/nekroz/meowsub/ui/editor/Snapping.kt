package com.nekroz.meowsub.ui.editor

import com.nekroz.meowsub.data.subtitle.SubtitleLine
import kotlin.math.abs

/** Marker snapping to the boundaries of the lines adjacent to the selected one (like Aegisub). */
object Snapping {

    /** Start/end times of the previous and next line in list order. */
    fun adjacentBoundaries(lines: List<SubtitleLine>, selectedIndex: Int): List<Long> {
        if (selectedIndex < 0) return emptyList()
        return listOfNotNull(lines.getOrNull(selectedIndex - 1), lines.getOrNull(selectedIndex + 1))
            .flatMap { listOf(it.startMs, it.endMs) }
    }

    /** The candidate closest to [ms] within [radiusMs], or null if none is close enough. */
    fun nearest(ms: Long, candidates: List<Long>, radiusMs: Double): Long? =
        candidates.filter { abs(it - ms) <= radiusMs }.minByOrNull { abs(it - ms) }
}
