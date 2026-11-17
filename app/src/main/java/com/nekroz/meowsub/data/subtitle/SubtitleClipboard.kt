package com.nekroz.meowsub.data.subtitle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Copies subtitle lines to the system clipboard as SRT text, and reads them back. */
class SubtitleClipboard(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    fun copy(lines: List<SubtitleLine>) {
        clipboard.setPrimaryClip(ClipData.newPlainText("SRT", SrtFormat.serialize(lines, newline = "\n")))
    }

    /** Copies only the text of [lines] (no numbers or timings), one subtitle per line. */
    fun copyPlainText(lines: List<SubtitleLine>) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Subtitle text", plainText(lines)))
    }

    /** Parses the clipboard as SRT; empty when it holds no subtitle entries. */
    fun read(nextId: () -> Long): List<SubtitleLine> {
        val clip = clipboard.primaryClip ?: return emptyList()
        if (clip.itemCount == 0) return emptyList()
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return emptyList()
        return SrtFormat.parse(text, nextId)
    }

    companion object {
        /** Subtitle texts joined by newlines; multi-line subtitles keep their own line breaks. */
        fun plainText(lines: List<SubtitleLine>): String = lines.joinToString("\n") { it.text }
    }
}
