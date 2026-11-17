package com.nekroz.meowsub.data.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Reads and writes whole documents via the ContentResolver. */
class DocumentIO(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException(uri.toString())
    }

    /** Overwrites [uri]; tries truncating modes first because plain "w" may not truncate. */
    suspend fun writeBytes(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (mode in listOf("wt", "rwt", "w")) {
            try {
                val stream = resolver.openOutputStream(uri, mode) ?: continue
                stream.use { it.write(bytes) }
                return@withContext
            } catch (e: Exception) {
                if (e is IllegalArgumentException || e is FileNotFoundException || e is UnsupportedOperationException) {
                    lastError = e
                } else throw e
            }
        }
        throw lastError ?: FileNotFoundException(uri.toString())
    }
}
