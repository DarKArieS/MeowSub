package com.nekroz.meowsub.data.subtitle

/**
 * Minimal SRT reader/writer. Styling tags are kept verbatim in the text.
 *
 * Like Aegisub's SRT reader, an entry's text runs until the next timing line, so blank lines
 * inside a subtitle do not break parsing; the numeric index right before the next timing
 * line is dropped.
 */
object SrtFormat {

    private val TIMING = Regex(
        """^\s*(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?.*$"""
    )
    private val BOM = Char(0xFEFF).toString()
    private val INDEX = Regex("""^\s*\d+\s*$""")

    fun parse(content: String, nextId: () -> Long): List<SubtitleLine> {
        val rows = content.removePrefix(BOM)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val timingRows = rows.indices.filter { TIMING.matches(rows[it]) }
        val result = ArrayList<SubtitleLine>(timingRows.size)
        for ((k, row) in timingRows.withIndex()) {
            val g = TIMING.matchEntire(rows[row])!!.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            val hasNext = k + 1 < timingRows.size
            val stop = if (hasNext) timingRows[k + 1] else rows.size
            val body = rows.subList(row + 1, stop).toMutableList()
            if (hasNext && body.isNotEmpty() && INDEX.matches(body.last())) body.removeAt(body.lastIndex)
            while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
            while (body.isNotEmpty() && body.first().isBlank()) body.removeAt(0)
            result += SubtitleLine(nextId(), start, maxOf(start, end), body.joinToString("\n"))
        }
        return result
    }

    fun serialize(lines: List<SubtitleLine>, newline: String = "\r\n"): String = buildString {
        lines.forEachIndexed { i, line ->
            append(i + 1).append(newline)
            append(TimeFormat.srt(line.startMs)).append(" --> ").append(TimeFormat.srt(line.endMs)).append(newline)
            // Blank lines inside the text would terminate the entry for most players.
            line.text.lines().filter { it.isNotBlank() }.forEach { append(it).append(newline) }
            append(newline)
        }
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val milli = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + milli
    }
}
