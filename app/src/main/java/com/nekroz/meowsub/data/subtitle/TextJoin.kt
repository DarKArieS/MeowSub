package com.nekroz.meowsub.data.subtitle

/** Joins subtitle texts on one line, the way the language at the seam is written. */
object TextJoin {

    /**
     * [a] followed by [b] with no line break: directly for Chinese and Japanese (which are
     * written without spaces), with one space otherwise. Blank parts are skipped.
     */
    fun join(a: String, b: String): String {
        val left = a.trimEnd()
        val right = b.trimStart()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val sep = if (isUnspaced(left.last()) || isUnspaced(right.first())) "" else " "
        return left + sep + right
    }

    private fun isUnspaced(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        // Ideographic punctuation (。、「」), fullwidth forms (，！？) and the long-vowel mark.
        else -> c in '　'..'〿' || c in '＀'..'￯' || c == 'ー'
    }
}
