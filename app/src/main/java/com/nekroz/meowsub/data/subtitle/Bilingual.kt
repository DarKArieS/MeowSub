package com.nekroz.meowsub.data.subtitle

/** Which of the two files of a bilingual pair a text belongs to. */
enum class Track {
    /** `<name>.srt` next to the audio. Also the only track when there is no original. */
    TRANSLATION,

    /** `rawSRT/<name>.srt`. Its timing is the reference for both files. */
    ORIGINAL;

    val other: Track get() = if (this == TRANSLATION) ORIGINAL else TRANSLATION
}

fun SubtitleLine.textOf(track: Track): String = if (track == Track.TRANSLATION) text else original

fun SubtitleLine.withText(track: Track, value: String): SubtitleLine =
    if (track == Track.TRANSLATION) copy(text = value) else copy(original = value)

/** The lines as one file sees them: shared timing with that file's text in [SubtitleLine.text]. */
fun List<SubtitleLine>.forTrack(track: Track): List<SubtitleLine> =
    if (track == Track.TRANSLATION) this else map { it.copy(text = it.original, original = "") }

/** What one SRT file contains for a line, ignoring in-memory ids. Used to tell if a file changed. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

fun List<SubtitleLine>.cues(track: Track): List<Cue> = map { Cue(it.startMs, it.endMs, it.textOf(track)) }

object Bilingual {

    /**
     * Result of [align]. [retimed] counts translation lines whose times were replaced by the
     * original's (including several lines joined into one); [filled] counts original lines that
     * got no translation and therefore an empty one.
     */
    data class Alignment(val lines: List<SubtitleLine>, val retimed: Int, val filled: Int)

    /**
     * Builds the shared line list from an [original] (whose timing wins) and a [translation].
     * Each translation line goes to the original line it overlaps most, or the nearest one when
     * it overlaps none; several translations of one original line are joined with line breaks.
     * No translated text is dropped. Ids are taken from [original].
     */
    fun align(original: List<SubtitleLine>, translation: List<SubtitleLine>): Alignment {
        if (original.isEmpty()) {
            return Alignment(translation.map { it.copy(original = "") }, retimed = 0, filled = 0)
        }
        val buckets = Array(original.size) { mutableListOf<SubtitleLine>() }
        for (t in translation) buckets[bestMatch(original, t)] += t

        var retimed = 0
        var filled = 0
        val lines = original.mapIndexed { i, o ->
            val matched = buckets[i]
            when {
                matched.isEmpty() -> if (translation.isNotEmpty()) filled++
                matched.size > 1 -> retimed += matched.size
                matched[0].startMs != o.startMs || matched[0].endMs != o.endMs -> retimed++
            }
            o.copy(text = matched.joinToString("\n") { it.text }, original = o.text)
        }
        return Alignment(lines, retimed, filled)
    }

    private fun bestMatch(original: List<SubtitleLine>, t: SubtitleLine): Int {
        var best = -1
        var bestOverlap = 0L
        original.forEachIndexed { i, o ->
            val overlap = minOf(o.endMs, t.endMs) - maxOf(o.startMs, t.startMs)
            if (overlap > bestOverlap) {
                best = i
                bestOverlap = overlap
            }
        }
        if (best >= 0) return best
        // No overlap (or zero-length lines): nearest by midpoint.
        val mid = (t.startMs + t.endMs) / 2.0
        return original.indices.minBy { i -> kotlin.math.abs((original[i].startMs + original[i].endMs) / 2.0 - mid) }
    }
}
