package com.nekroz.meowsub.ui.browser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekroz.meowsub.R
import com.nekroz.meowsub.data.files.DocEntry
import com.nekroz.meowsub.data.files.FolderListing
import com.nekroz.meowsub.data.files.MediaPair
import com.nekroz.meowsub.data.files.SavedFolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel, snackbar: SnackbarHostState, onOpen: (MediaPair) -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    BackHandler(enabled = ui.path.isNotEmpty()) { vm.navigateUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.path.joinToString(" / ") { it.name }.ifEmpty { stringResource(R.string.app_name) },
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                },
                navigationIcon = {
                    if (ui.path.isNotEmpty()) {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (ui.current != null) {
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (ui.path.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(null) },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    text = { Text(stringResource(R.string.browser_add_folder)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val listing = ui.listing
            when {
                ui.current == null -> FolderRoot(ui.folders, vm::openFolder, vm::removeFolder)
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.error != null -> CenterMessage(stringResource(R.string.browser_error, ui.error!!), null)
                listing != null -> FolderContent(listing, vm::openDirectory, onOpen)
            }
        }
    }
}

@Composable
private fun FolderRoot(folders: List<SavedFolder>, onOpen: (SavedFolder) -> Unit, onRemove: (SavedFolder) -> Unit) {
    if (folders.isEmpty()) {
        CenterMessage(stringResource(R.string.browser_empty_title), stringResource(R.string.browser_empty_body))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(folders, key = { it.treeUri.toString() }) { folder ->
            ListItem(
                modifier = Modifier.clickable { onOpen(folder) },
                headlineContent = { Text(folder.name) },
                supportingContent = {
                    Text(
                        folder.treeUri.lastPathSegment.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                trailingContent = {
                    IconButton(onClick = { onRemove(folder) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.browser_remove_folder))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FolderContent(listing: FolderListing, onDirectory: (DocEntry) -> Unit, onOpen: (MediaPair) -> Unit) {
    if (listing.directories.isEmpty() && listing.pairs.isEmpty()) {
        CenterMessage(stringResource(R.string.browser_folder_empty), null)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(listing.directories, key = { "d:" + it.documentId }) { dir ->
            ListItem(
                modifier = Modifier.clickable { onDirectory(dir) },
                headlineContent = { Text(dir.name) },
                leadingContent = { Icon(Icons.Outlined.Folder, null) },
            )
        }
        items(listing.pairs, key = { "p:" + it.baseName }) { pair ->
            ListItem(
                modifier = Modifier.clickable { onOpen(pair) },
                headlineContent = { Text(pair.baseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(
                            pair.audio?.let { stringResource(R.string.browser_audio_label, it.name) }
                                ?: stringResource(R.string.browser_no_audio),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitleLabel = if (pair.original != null) R.string.browser_translation_label else R.string.browser_subtitle_label
                        Text(
                            pair.subtitle?.let { stringResource(subtitleLabel, it.name) }
                                ?: stringResource(R.string.browser_no_subtitle, "${pair.baseName}.srt"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (pair.subtitle == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        pair.original?.let { original ->
                            Text(
                                stringResource(R.string.browser_original_label, original.name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(if (pair.audio != null) Icons.Outlined.AudioFile else Icons.Outlined.Subtitles, null)
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CenterMessage(title: String, body: String?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Icon(
                Icons.Outlined.Folder,
                null,
                modifier = Modifier.size(48.dp).padding(bottom = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
