package com.nekroz.meowsub.data.subtitle

/** Time formatting helpers. Display format is `H:MM:SS.mmm`, SRT format is `HH:MM:SS,mmm`. */
object TimeFormat {

    fun srt(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val milli = t % 1000
        return "${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)},${pad(milli, 3)}"
    }

    fun display(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val milli = t % 1000
        return "$h:${pad(m, 2)}:${pad(s, 2)}.${pad(milli, 3)}"
    }

    /** Short ruler label; [stepMs] decides how many decimals are needed. */
    fun ruler(ms: Long, stepMs: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val base = if (h > 0) "$h:${pad(m, 2)}:${pad(s, 2)}" else "$m:${pad(s, 2)}"
        return when {
            stepMs % 1000 == 0L -> base
            stepMs % 100 == 0L -> "$base.${t % 1000 / 100}"
            else -> "$base.${pad(t % 1000 / 10, 2)}"
        }
    }

    /**
     * Parses `[[h:]m:]s[.fff]` (`,` also accepted as decimal separator).
     * Returns null when the text is not a valid time.
     */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val sepIndex = trimmed.indexOfAny(charArrayOf('.', ','))
        val main = if (sepIndex >= 0) trimmed.substring(0, sepIndex) else trimmed
        val frac = if (sepIndex >= 0) trimmed.substring(sepIndex + 1) else ""
        if (frac.length > 3 || frac.any { !it.isDigit() }) return null
        val parts = main.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        val (h, m, s) = when (nums.size) {
            1 -> Triple(0L, 0L, nums[0])
            2 -> Triple(0L, nums[0], nums[1])
            else -> Triple(nums[0], nums[1], nums[2])
        }
        if (nums.size >= 2 && s >= 60) return null
        if (nums.size == 3 && m >= 60) return null
        val milli = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').toLong()
        return ((h * 60 + m) * 60 + s) * 1000 + milli
    }

    private fun pad(v: Long, width: Int) = v.toString().padStart(width, '0')
}
