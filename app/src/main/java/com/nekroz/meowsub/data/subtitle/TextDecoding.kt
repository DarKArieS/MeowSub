package com.nekroz.meowsub.data.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Detects the encoding of a subtitle file: BOM first, then strict UTF-8, Big5, GB18030. */
object TextDecoding {

    private val fallbacks: List<Charset> = listOf("UTF-8", "Big5", "GB18030")
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }

    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        when {
            startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        for (cs in fallbacks) {
            try {
                return cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                // try next
            }
        }
        return String(bytes, Charsets.ISO_8859_1)
    }
}
