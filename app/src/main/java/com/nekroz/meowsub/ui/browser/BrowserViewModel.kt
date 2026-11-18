package com.nekroz.meowsub.ui.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekroz.meowsub.R
import com.nekroz.meowsub.data.files.DocEntry
import com.nekroz.meowsub.data.files.FolderListing
import com.nekroz.meowsub.data.files.FolderRepository
import com.nekroz.meowsub.data.files.MediaPair
import com.nekroz.meowsub.data.files.SavedFolder
import com.nekroz.meowsub.ui.UiMessage
import com.nekroz.meowsub.ui.editor.EditorRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PathNode(val treeUri: Uri, val documentId: String, val name: String)

data class BrowserUiState(
    val folders: List<SavedFolder> = emptyList(),
    /** Empty = the saved-folders root screen. */
    val path: List<PathNode> = emptyList(),
    val listing: FolderListing? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: PathNode? get() = path.lastOrNull()
}

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FolderRepository(app)

    private val _ui = MutableStateFlow(BrowserUiState(folders = repo.savedFolders()))
    val uiState: StateFlow<BrowserUiState> = _ui.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val prefs = app.getSharedPreferences("browser", Context.MODE_PRIVATE)

    private var listJob: Job? = null

    init {
        // Back where the app was left; a folder whose access was removed falls back to the root.
        savedPath()?.takeIf { path -> _ui.value.folders.any { it.treeUri == path.first().treeUri } }?.let(::navigate)
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            try {
                val folder = repo.addFolder(treeUri)
                _ui.update { it.copy(folders = repo.savedFolders()) }
                openFolder(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(UiMessage(R.string.msg_folder_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun removeFolder(folder: SavedFolder) {
        repo.removeFolder(folder.treeUri)
        _ui.update { it.copy(folders = repo.savedFolders()) }
    }

    fun openFolder(folder: SavedFolder) {
        navigate(listOf(PathNode(folder.treeUri, repo.rootDocumentId(folder.treeUri), folder.name)))
    }

    fun openDirectory(dir: DocEntry) {
        val cur = _ui.value.current ?: return
        navigate(_ui.value.path + PathNode(cur.treeUri, dir.documentId, dir.name))
    }

    /** Returns false when already at the root. */
    fun navigateUp(): Boolean {
        val path = _ui.value.path
        if (path.isEmpty()) return false
        navigate(path.dropLast(1))
        return true
    }

    fun refresh() {
        _ui.update { it.copy(folders = repo.savedFolders()) }
        navigate(_ui.value.path)
    }

    fun requestFor(pair: MediaPair): EditorRequest? {
        val cur = _ui.value.current ?: return null
        return EditorRequest(cur.treeUri, cur.documentId, pair.baseName, pair.audio, pair.subtitle, pair.original)
    }

    private fun navigate(path: List<PathNode>) {
        listJob?.cancel()
        savePath(path)
        val node = path.lastOrNull()
        _ui.update { it.copy(path = path, listing = null, error = null, loading = node != null) }
        if (node == null) return
        listJob = viewModelScope.launch {
            try {
                val listing = repo.list(node.treeUri, node.documentId)
                _ui.update { it.copy(listing = listing, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun savePath(path: List<PathNode>) = prefs.edit {
        if (path.isEmpty()) {
            remove(KEY_PATH)
        } else {
            val nodes = JSONArray()
            path.forEach { nodes.put(JSONObject().put("id", it.documentId).put("name", it.name)) }
            putString(KEY_PATH, JSONObject().put("tree", path.first().treeUri.toString()).put("nodes", nodes).toString())
        }
    }

    private fun savedPath(): List<PathNode>? = runCatching {
        val json = JSONObject(prefs.getString(KEY_PATH, null) ?: return null)
        val tree = json.getString("tree").toUri()
        val nodes = json.getJSONArray("nodes")
        (0 until nodes.length()).map { nodes.getJSONObject(it) }
            .map { PathNode(tree, it.getString("id"), it.getString("name")) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_PATH = "path"
    }
}
