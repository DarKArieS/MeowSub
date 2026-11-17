package com.nekroz.meowsub.data.subtitle

/**
 * One subtitle entry. Times are in milliseconds. [id] is a stable in-memory key, not the SRT index.
 *
 * [text] is the text of the opened `<name>.srt` (the translation when a `rawSRT/<name>.srt`
 * original exists). [original] is that original's text; it is unused (empty) without one.
 * Both files share the same timing.
 */
data class SubtitleLine(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val original: String = "",
) {
    val durationMs: Long get() = endMs - startMs

    operator fun contains(ms: Long): Boolean = ms in startMs..endMs
}

/**
 * End time for a new line starting at [startMs]: [maxDurationMs] long, but cut short so it
 * does not run into the next line (the earliest other line starting at or after [startMs]).
 * With no gap at all the result is a zero-length line rather than an overlap.
 */
fun List<SubtitleLine>.freeEndAfter(startMs: Long, maxDurationMs: Long, excludeId: Long? = null): Long {
    val nextStart = filter { it.id != excludeId && it.startMs >= startMs }.minOfOrNull { it.startMs }
    return if (nextStart == null) startMs + maxDurationMs else minOf(startMs + maxDurationMs, nextStart)
}

/** Start time for a new line ending at [endMs] that does not overlap the previous line. */
fun List<SubtitleLine>.freeStartBefore(endMs: Long, maxDurationMs: Long, excludeId: Long? = null): Long {
    val prevEnd = filter { it.id != excludeId && it.endMs <= endMs }.maxOfOrNull { it.endMs } ?: 0L
    return maxOf(endMs - maxDurationMs, prevEnd, 0L)
}
