package com.nekroz.meowsub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekroz.meowsub.ui.browser.BrowserScreen
import com.nekroz.meowsub.ui.browser.BrowserViewModel
import com.nekroz.meowsub.ui.editor.EditorScreen
import com.nekroz.meowsub.ui.editor.EditorViewModel
import com.nekroz.meowsub.ui.theme.MeowSubTheme
import kotlinx.coroutines.flow.merge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeowSubTheme {
                MeowSubApp()
            }
        }
    }
}

@Composable
fun MeowSubApp() {
    val editorVm: EditorViewModel = viewModel()
    val browserVm: BrowserViewModel = viewModel()
    val editorState by editorVm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        merge(editorVm.messages, browserVm.messages).collect { m ->
            val text = if (m.arg != null) resources.getString(m.res, m.arg) else resources.getString(m.res)
            snackbar.showSnackbar(text)
        }
    }
    // Returning from the editor may have created a new .srt, so re-list the folder.
    LaunchedEffect(editorState.isOpen) {
        if (!editorState.isOpen) browserVm.refresh()
    }

    if (editorState.isOpen) {
        EditorScreen(editorVm, snackbar)
    } else {
        BrowserScreen(browserVm, snackbar, onOpen = { pair -> browserVm.requestFor(pair)?.let(editorVm::open) })
    }
}
