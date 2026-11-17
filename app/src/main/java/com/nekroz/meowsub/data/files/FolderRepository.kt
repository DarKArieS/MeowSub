package com.nekroz.meowsub.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

data class DocEntry(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = if ('.' in name) name.substringBeforeLast('.') else name
}

/** An audio file and/or SRT file sharing the same base name. */
data class MediaPair(val baseName: String, val audio: DocEntry?, val subtitle: DocEntry?)

data class FolderListing(val directories: List<DocEntry>, val pairs: List<MediaPair>)

data class SavedFolder(val treeUri: Uri, val name: String)

/** Folder access through the Storage Access Framework, with persisted tree permissions. */
class FolderRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("folders", Context.MODE_PRIVATE)

    fun savedFolders(): List<SavedFolder> {
        val json = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val persisted = resolver.persistedUriPermissions.map { it.uri }.toSet()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { SavedFolder(Uri.parse(it.getString("uri")), it.getString("name")) }
            .filter { it.treeUri in persisted }
    }

    suspend fun addFolder(treeUri: Uri): SavedFolder = withContext(Dispatchers.IO) {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = queryName(rootUri) ?: rootId.substringAfterLast(':').ifEmpty { rootId }
        val folder = SavedFolder(treeUri, name)
        save(listOf(folder) + savedFolders().filter { it.treeUri != treeUri })
        folder
    }

    fun removeFolder(treeUri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        save(savedFolders().filter { it.treeUri != treeUri })
    }

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    suspend fun list(treeUri: Uri, documentId: String): FolderListing = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val entries = ArrayList<DocEntry>()
        resolver.query(
            childrenUri,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith('.')) continue
                entries += DocEntry(
                    documentId = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    name = name,
                    mimeType = c.getString(2) ?: "",
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw FileNotFoundException(childrenUri.toString())

        val dirs = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = entries.filter { !it.isDirectory }
        val audioByBase = files.filter { isAudio(it) }
            .groupBy { it.baseName.lowercase() }
            .mapValues { (_, list) -> list.minBy { AUDIO_EXTENSIONS.indexOf(it.extension).let { i -> if (i < 0) 99 else i } } }
        val subtitleByBase = files.filter { it.extension == "srt" }
            .groupBy { it.baseName.lowercase() }
            .mapValues { (_, list) -> list.first() }
        val pairs = (audioByBase.keys + subtitleByBase.keys).map { key ->
            val audio = audioByBase[key]
            val sub = subtitleByBase[key]
            MediaPair((sub ?: audio)!!.baseName, audio, sub)
        }.sortedBy { it.baseName.lowercase() }
        FolderListing(dirs, pairs)
    }

    /** Creates an empty file named [name] inside [parentDocumentId]; returns its document URI. */
    suspend fun createFile(treeUri: Uri, parentDocumentId: String, name: String): Uri = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        // octet-stream keeps the display name exactly as given (no extension rewriting).
        DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw FileNotFoundException("Cannot create $name")
    }

    private fun queryName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun save(folders: List<SavedFolder>) {
        val arr = JSONArray()
        folders.forEach { arr.put(JSONObject().put("uri", it.treeUri.toString()).put("name", it.name)) }
        prefs.edit { putString(KEY_FOLDERS, arr.toString()) }
    }

    private fun isAudio(e: DocEntry): Boolean = e.extension in AUDIO_EXTENSIONS

    companion object {
        private const val KEY_FOLDERS = "folders"

        /** Ordered by preference when several audio files share a base name. */
        val AUDIO_EXTENSIONS = listOf("wav", "flac", "m4a", "mp3", "aac", "ogg", "oga", "opus", "amr", "mka")
    }
}
