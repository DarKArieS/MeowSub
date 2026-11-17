package com.nekroz.meowsub.ui.browser

import android.app.Application
import android.net.Uri
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

    private var listJob: Job? = null

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
}
