package com.nekroz.meowsub.data.transcribe

/** Fixes known Whisper output artefacts before the text goes into a subtitle. */
object TranscriptCleanup {

    private const val SEPARATORS = " \t\n,，、。.!！?？…"

    /** Shortest sentence (in non-separator characters) whose repetition is treated as a glitch. */
    private const val MIN_UNIT = 5

    /**
     * With a shortened audio context, Whisper sometimes outputs the whole sentence twice or more
     * ("A。A。"). Returns a single copy (keeping the final punctuation) when the text is nothing
     * but repeats of one sentence of at least [MIN_UNIT] characters; short repeats such as
     * "はい、はい" may be genuine and are left alone.
     */
    fun collapseRepeats(text: String): String {
        val t = text.trim()
        val core = t.trimEnd { it in SEPARATORS }
        val tail = t.substring(core.length)
        for (len in 1..core.length / 2) {
            val unit = core.substring(0, len)
            if (unit.last() in SEPARATORS || unit.count { it !in SEPARATORS } < MIN_UNIT) continue
            if (isRepeatOf(core, unit)) return unit + tail
        }
        return t
    }

    /** [core] is [unit] followed by one or more "separators + [unit]". */
    private fun isRepeatOf(core: String, unit: String): Boolean {
        var i = unit.length
        while (i < core.length) {
            var j = i
            while (j < core.length && core[j] in SEPARATORS) j++
            if (!core.startsWith(unit, j)) return false
            i = j + unit.length
        }
        return true
    }
}
