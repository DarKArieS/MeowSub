package com.nekroz.meowsub.ui.editor

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekroz.meowsub.R
import com.nekroz.meowsub.data.subtitle.SubtitleLine
import com.nekroz.meowsub.data.subtitle.TimeFormat
import com.nekroz.meowsub.data.subtitle.Track
import com.nekroz.meowsub.data.subtitle.textOf
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, snackbar: SnackbarHostState) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var showUnsavedDialog by remember { mutableStateOf(false) }
    // Caret position in the selected line's text, used by "split".
    var caret by remember { mutableIntStateOf(-1) }
    LaunchedEffect(ui.selectedId, ui.track) { caret = -1 }

    val requestClose = { if (ui.dirty) showUnsavedDialog = true else vm.close() }
    BackHandler(onBack = requestClose)
    // Registered later, so it wins while multi-select mode is active.
    BackHandler(enabled = ui.selectionMode, onBack = vm::clearSelection)

    val transcription by vm.transcription.collectAsStateWithLifecycle()
    if (transcription.settingsOpen) {
        val settings by vm.transcribeSettings.collectAsStateWithLifecycle()
        TranscribeSettingsDialog(
            initial = settings,
            loadedModel = transcription.loadedModel,
            onPickedModel = vm::modelFileName,
            onConfirm = vm::saveTranscribeSettings,
            onDismiss = vm::closeTranscribeSettings,
        )
    }

    Scaffold(
        topBar = {
            Column {
                EditorTopBar(
                    ui = ui,
                    transcription = transcription,
                    onBack = requestClose,
                    onSplit = { vm.splitSelected(if (caret >= 0) caret else ui.selected?.textOf(ui.track)?.length ?: 0) },
                    vm = vm,
                )
                TranscriptionStatus(transcription, onCancel = vm::cancelTranscription)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            val density = LocalDensity.current
            // Stored height (dp); 0 = default split. Clamped to what fits, e.g. while the keyboard is up.
            var waveHeightDp by rememberSaveable { mutableFloatStateOf(vm.savedWaveformHeightDp) }
            val minWave = 64.dp
            val maxWave = (maxHeight - MIN_HEIGHT_BELOW_WAVEFORM).coerceAtLeast(minWave)
            val shownWave = (if (waveHeightDp > 0f) waveHeightDp.dp else maxHeight * 0.4f).coerceIn(minWave, maxWave)
            Column(Modifier.fillMaxSize()) {
                WaveformPanel(vm, ui, Modifier.fillMaxWidth().height(shownWave))
                ResizeHandle(
                    onDrag = { deltaPx ->
                        val base = if (waveHeightDp > 0f) waveHeightDp.dp else shownWave
                        waveHeightDp = (base + with(density) { deltaPx.toDp() }).coerceIn(minWave, maxWave).value
                    },
                    onDragStopped = { vm.saveWaveformHeight(waveHeightDp) },
                    onReset = {
                        waveHeightDp = 0f
                        vm.saveWaveformHeight(0f)
                    },
                )
                PlaybackBar(
                    vm,
                    audioReady = ui.audio is AudioStatus.Ready,
                    track = ui.track.takeIf { ui.bilingual },
                )
                HorizontalDivider()
                LineEditor(
                    line = ui.selected,
                    track = ui.track,
                    bilingual = ui.bilingual,
                    onStart = vm::setStart,
                    onEnd = vm::setEnd,
                    onSetStartAtCursor = vm::setStartAtCursor,
                    onSetEndAtCursor = vm::setEndAtCursor,
                    onText = vm::updateText,
                    onClearText = vm::clearText,
                    onCaret = { caret = it },
                    onPrevious = vm::selectPrevious,
                    onNext = vm::selectNext,
                )
                HorizontalDivider()
                SubtitleList(
                    lines = ui.lines,
                    track = ui.track,
                    bilingual = ui.bilingual,
                    selectedId = ui.selectedId,
                    checkedIds = ui.checkedIds,
                    loading = ui.loading,
                    onClick = { if (ui.selectionMode) vm.toggleChecked(it) else vm.onLineClick(it) },
                    onLongClick = { if (ui.selectionMode) vm.toggleChecked(it) else vm.startSelection(it) },
                    onDoubleClick = { if (!ui.selectionMode) vm.playSelection() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body, ui.title)) },
            confirmButton = {
                TextButton(onClick = { showUnsavedDialog = false; vm.save(closeAfter = true) }) {
                    Text(stringResource(R.string.unsaved_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false; vm.close() }) {
                        Text(stringResource(R.string.unsaved_discard))
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

/** Width kept for the navigation icon, the title and the ⋮ button; the rest is for the scrolling icons. */
private val EditorBarReserved = 264.dp
private val SelectionBarReserved = 232.dp

/**
 * Name of the file being edited. When bilingual: "譯文 · X.srt" or "原文 · rawSRT/X.srt", with a
 * dot when the other file also has unsaved changes.
 */
@Composable
private fun editedFileLabel(ui: EditorUiState): String {
    val translationName = ui.subtitleName ?: stringResource(R.string.editor_new_subtitle)
    if (!ui.bilingual) return translationName
    val original = ui.track == Track.ORIGINAL
    val name = if (original) stringResource(R.string.original_file_name, ui.originalName.orEmpty()) else translationName
    val trackName = stringResource(if (original) R.string.track_original else R.string.track_translation)
    val otherDirty = if (original) ui.dirtyTranslation else ui.dirtyOriginal
    val otherHint = if (otherDirty) " " + stringResource(R.string.other_file_unsaved) else ""
    return "$trackName · $name$otherHint"
}

/** Binds every configurable toolbar action to the current editor state. */
private fun editorActions(
    ui: EditorUiState,
    transcription: TranscriptionUi,
    vm: EditorViewModel,
    onSplit: () -> Unit,
): Map<ToolbarAction, BarAction> {
    val hasSel = ui.selected != null
    val ready = !ui.loading
    fun a(action: ToolbarAction, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) =
        action to BarAction(action.icon(), action.label(), enabled, busy, onClick = onClick)
    return mapOf(
        a(ToolbarAction.UNDO, ui.canUndo, onClick = vm::undo),
        a(ToolbarAction.REDO, ui.canRedo, onClick = vm::redo),
        a(ToolbarAction.ADD, ready, onClick = vm::addLineAfter),
        a(ToolbarAction.INSERT_BEFORE, ready, onClick = vm::insertLineBefore),
        a(ToolbarAction.DELETE, hasSel, onClick = vm::deleteSelected),
        a(ToolbarAction.COPY, hasSel, onClick = vm::copySelection),
        a(ToolbarAction.COPY_TEXT, hasSel, onClick = vm::copyPlainText),
        a(ToolbarAction.CUT, hasSel, onClick = vm::cutSelection),
        a(ToolbarAction.PASTE, ready, onClick = vm::paste),
        a(ToolbarAction.SPLIT, hasSel, onClick = onSplit),
        a(ToolbarAction.MERGE, hasSel, onClick = vm::mergeWithNext),
        a(ToolbarAction.SORT, ui.lines.size > 1, onClick = vm::sortByTime),
        a(ToolbarAction.SAVE, ui.dirty && ready, busy = ui.saving, onClick = { vm.save() }),
        transcribeAction(ui, transcription, vm),
        unloadModelAction(transcription, vm),
    )
}

private fun transcribeAction(ui: EditorUiState, transcription: TranscriptionUi, vm: EditorViewModel) =
    ToolbarAction.TRANSCRIBE to BarAction(
        ToolbarAction.TRANSCRIBE.icon(), ToolbarAction.TRANSCRIBE.label(),
        enabled = ui.targetLines.isNotEmpty() && ui.audio is AudioStatus.Ready,
        busy = transcription.running,
        onBusyClick = vm::cancelTranscription,
        busyLabel = R.string.action_cancel_transcription,
        onClick = vm::transcribe,
    )

private fun unloadModelAction(transcription: TranscriptionUi, vm: EditorViewModel) =
    ToolbarAction.UNLOAD_MODEL to BarAction(
        ToolbarAction.UNLOAD_MODEL.icon(), ToolbarAction.UNLOAD_MODEL.label(),
        enabled = transcription.loadedModel != null,
        onClick = vm::unloadModel,
    )

/** ⋮-menu entries that open settings, shown on both the main and the multi-select bar. */
private fun settingsEntries(vm: EditorViewModel) = listOf(
    BarAction(Icons.Filled.SettingsVoice, R.string.transcribe_settings, onClick = vm::openTranscribeSettings),
)

/** Strip under the app bar while a model loads or lines are being transcribed. */
@Composable
private fun TranscriptionStatus(transcription: TranscriptionUi, onCancel: () -> Unit) {
    val loading = transcription.loadingModel
    if (loading == null && !transcription.running) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 4.dp)
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loading != null) stringResource(R.string.transcribe_loading_model, loading)
                    else stringResource(R.string.transcribe_progress, transcription.done + 1, transcription.total),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (transcription.running) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            if (loading != null || transcription.total <= 1) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { transcription.done.toFloat() / transcription.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    ui: EditorUiState,
    transcription: TranscriptionUi,
    onBack: () -> Unit,
    onSplit: () -> Unit,
    vm: EditorViewModel,
) {
    val toolbar by vm.toolbar.collectAsStateWithLifecycle()
    var editingToolbar by rememberSaveable { mutableStateOf(false) }
    if (editingToolbar) {
        ToolbarEditorDialog(
            initial = toolbar,
            onConfirm = { vm.setToolbar(it); editingToolbar = false },
            onDismiss = { editingToolbar = false },
        )
    }
    if (ui.selectionMode) {
        SelectionTopBar(ui, transcription, vm, toolbar, onEditToolbar = { editingToolbar = true })
        return
    }
    val bound = editorActions(ui, transcription, vm, onSplit)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val barWidth = maxWidth - EditorBarReserved
        TopAppBar(
            expandedHeight = 56.dp,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            },
            title = {
                Column {
                    Text(
                        text = if (ui.dirty) "${ui.title} •" else ui.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = editedFileLabel(ui),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                ScrollableActions(
                    bar = toolbar.filter { it.onBar }.mapNotNull { bound[it.action] },
                    menu = toolbar.filterNot { it.onBar }.mapNotNull { bound[it.action] },
                    maxBarWidth = barWidth,
                    onEditToolbar = { editingToolbar = true },
                    settings = settingsEntries(vm),
                )
            },
        )
    }
}

/**
 * Contextual app bar shown while lines are ticked in multi-select mode. Uses the same
 * arrangement as the main toolbar, limited to actions that work on several lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    ui: EditorUiState,
    transcription: TranscriptionUi,
    vm: EditorViewModel,
    toolbar: List<ToolbarItem>,
    onEditToolbar: () -> Unit,
) {
    fun bind(action: ToolbarAction): BarAction {
        when (action) {
            ToolbarAction.TRANSCRIBE -> return transcribeAction(ui, transcription, vm).second
            ToolbarAction.UNLOAD_MODEL -> return unloadModelAction(transcription, vm).second
            else -> Unit
        }
        val onClick: () -> Unit = when (action) {
            ToolbarAction.COPY -> vm::copySelection
            ToolbarAction.COPY_TEXT -> vm::copyPlainText
            ToolbarAction.CUT -> vm::cutSelection
            ToolbarAction.PASTE -> vm::paste
            else -> vm::deleteSelected
        }
        return BarAction(action.icon(), action.label(), onClick = onClick)
    }
    val items = toolbar.filter { it.action.inSelectionMode }
    val selectAll = BarAction(
        Icons.Filled.SelectAll, R.string.action_select_all,
        enabled = ui.checkedIds.size < ui.lines.size, onClick = vm::selectAll,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val barWidth = maxWidth - SelectionBarReserved
        TopAppBar(
            expandedHeight = 56.dp,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            navigationIcon = {
                IconButton(onClick = vm::clearSelection) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_exit_selection))
                }
            },
            title = { Text(stringResource(R.string.selection_count, ui.checkedIds.size), maxLines = 1) },
            actions = {
                ScrollableActions(
                    bar = listOf(selectAll) + items.filter { it.onBar }.map { bind(it.action) },
                    menu = items.filterNot { it.onBar }.map { bind(it.action) },
                    maxBarWidth = barWidth,
                    onEditToolbar = onEditToolbar,
                    settings = settingsEntries(vm),
                )
            },
        )
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun WaveformPanel(vm: EditorViewModel, ui: EditorUiState, modifier: Modifier) {
    val viewport by vm.viewport.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val snapGuide by vm.snapGuide.collectAsStateWithLifecycle()
    val audio = ui.audio
    Box(modifier) {
        Waveform(
            peaks = (audio as? AudioStatus.Ready)?.peaks,
            lines = ui.lines,
            selected = ui.selected,
            viewport = viewport,
            playback = playback,
            snapGuideMs = snapGuide,
            previousLabel = stringResource(R.string.waveform_previous),
            nextLabel = stringResource(R.string.waveform_next),
            onWidthChanged = vm::setViewportWidth,
            onTransform = vm::transformViewport,
            onTap = vm::onWaveformTap,
            onMarkerDragStart = vm::beginMarkerDrag,
            onMarkerDrag = vm::dragMarker,
            onMarkerDragEnd = vm::endMarkerDrag,
            modifier = Modifier.fillMaxSize(),
        )
        when (audio) {
            is AudioStatus.Loading -> Surface(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(16.dp).width(240.dp)) {
                    Text(
                        stringResource(R.string.audio_loading, "${(audio.progress * 100).toInt()}%"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { audio.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            AudioStatus.None -> StatusChip(stringResource(R.string.audio_none), Modifier.align(Alignment.TopEnd))
            is AudioStatus.Failed -> StatusChip(
                stringResource(R.string.audio_failed, audio.reason),
                Modifier.align(Alignment.TopEnd),
                error = true,
            )

            is AudioStatus.Ready -> Unit
        }
    }
}

/** Room kept below the waveform for the playback bar, line editor and some of the list. */
private val MIN_HEIGHT_BELOW_WAVEFORM = 220.dp

@Composable
private fun StatusChip(text: String, modifier: Modifier, error: Boolean = false) {
    Surface(
        modifier = modifier.padding(top = 24.dp, end = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
        )
    }
}

@Composable
private fun PlaybackBar(vm: EditorViewModel, audioReady: Boolean, track: Track?) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val viewport by vm.viewport.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val snapEnabled by vm.snapEnabled.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // Wide screens: sliders share the leftover width. Narrow screens: fixed sliders, row scrolls.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 880.dp
        val sliderWidth = if (wide) null else 128.dp
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier else Modifier.horizontalScroll(scroll))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sliderModifier = if (wide) Modifier.weight(1f) else Modifier
            BarIcon(
                if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                R.string.play_pause, audioReady, vm::togglePlay,
            )
            BarIcon(Icons.Filled.Stop, R.string.play_stop, playback.playing, vm::stopPlayback)
            Text(
                TimeFormat.display(if (playback.playing) playback.positionMs else playback.cursorMs),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (track != null) {
                TrackSwitch(track, vm::setTrack, Modifier.padding(end = 8.dp))
            }
            FilterChip(
                selected = snapEnabled,
                onClick = { vm.setSnapEnabled(!snapEnabled) },
                label = { Text(stringResource(R.string.snap)) },
                leadingIcon = if (snapEnabled) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
            )
            VerticalDivider(Modifier.padding(horizontal = 8.dp).size(width = 1.dp, height = 24.dp))
            LabeledSlider(Icons.AutoMirrored.Filled.VolumeUp, R.string.volume, volume, vm::setVolume, sliderModifier, sliderWidth)
            LabeledSlider(
                Icons.Filled.Height, R.string.amplitude,
                value = logPosition(viewport.amplitude.toDouble(), EditorViewModel.MIN_AMPLITUDE.toDouble(), EditorViewModel.MAX_AMPLITUDE.toDouble()),
                onChange = {
                    vm.setAmplitude(fromLogPosition(it, EditorViewModel.MIN_AMPLITUDE.toDouble(), EditorViewModel.MAX_AMPLITUDE.toDouble()).toFloat())
                },
                modifier = sliderModifier,
                sliderWidth = sliderWidth,
            )
            // Right = zoom in (fewer ms per pixel).
            LabeledSlider(
                Icons.Filled.ZoomIn, R.string.zoom,
                value = 1f - logPosition(viewport.msPerPx, EditorViewModel.MIN_MS_PER_PX, EditorViewModel.MAX_MS_PER_PX),
                onChange = { vm.setZoom(fromLogPosition(1f - it, EditorViewModel.MIN_MS_PER_PX, EditorViewModel.MAX_MS_PER_PX)) },
                modifier = sliderModifier,
                sliderWidth = sliderWidth,
            )
        }
    }
}

/** 譯文 / 原文 toggle: which file's text is shown and edited. */
@Composable
private fun TrackSwitch(track: Track, onTrack: (Track) -> Unit, modifier: Modifier = Modifier) {
    val tracks = listOf(Track.TRANSLATION, Track.ORIGINAL)
    SingleChoiceSegmentedButtonRow(modifier) {
        tracks.forEachIndexed { i, t ->
            SegmentedButton(
                selected = track == t,
                onClick = { onTrack(t) },
                shape = SegmentedButtonDefaults.itemShape(i, tracks.size),
                icon = {},
            ) {
                Text(stringResource(if (t == Track.TRANSLATION) R.string.track_translation else R.string.track_original))
            }
        }
    }
}

private fun logPosition(value: Double, min: Double, max: Double): Float =
    (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

private fun fromLogPosition(pos: Float, min: Double, max: Double): Double =
    min * (max / min).pow(pos.toDouble())

@Composable
private fun LabeledSlider(
    icon: ImageVector,
    label: Int,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    sliderWidth: Dp? = 128.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(end = 12.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = (if (sliderWidth != null) Modifier.width(sliderWidth) else Modifier.weight(1f)).padding(start = 4.dp),
        )
    }
}

@Composable
private fun LineEditor(
    line: SubtitleLine?,
    /** File whose text is edited; when [bilingual], the text field is labelled with it. */
    track: Track,
    bilingual: Boolean,
    onStart: (Long, Long) -> Unit,
    onEnd: (Long, Long) -> Unit,
    onSetStartAtCursor: () -> Unit,
    onSetEndAtCursor: () -> Unit,
    onText: (Long, String) -> Unit,
    onClearText: (Long) -> Unit,
    onCaret: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    if (line == null) {
        Text(
            stringResource(R.string.no_selection),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val current = line.textOf(track)
    var tfv by remember(line.id, track) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    // Sync external changes (undo/redo, split, merge) into the field.
    LaunchedEffect(line.id, track, current) {
        if (tfv.text != current) tfv = TextFieldValue(current, TextRange(current.length))
    }
    val trackLabel = @Composable { t: Track ->
        stringResource(if (t == Track.TRANSLATION) R.string.track_translation else R.string.track_original)
    }

    val times = @Composable {
        TimeField(
            label = stringResource(R.string.field_start),
            valueMs = line.startMs,
            onCommit = { onStart(line.id, it) },
            onUseCursor = onSetStartAtCursor,
            useCursorLabel = stringResource(R.string.set_start_at_cursor),
        )
        TimeField(
            label = stringResource(R.string.field_end_with_duration, "%.3f".format(line.durationMs / 1000.0)),
            valueMs = line.endMs,
            onCommit = { onEnd(line.id, it) },
            onUseCursor = onSetEndAtCursor,
            useCursorLabel = stringResource(R.string.set_end_at_cursor),
        )
    }
    val text = @Composable { modifier: Modifier ->
        OutlinedTextField(
            value = tfv,
            onValueChange = {
                tfv = it
                onCaret(it.selection.start)
                if (it.text != current) onText(line.id, it.text)
            },
            label = { Text(if (bilingual) trackLabel(track) else stringResource(R.string.field_text)) },
            trailingIcon = {
                IconButton(onClick = { onClearText(line.id) }, enabled = tfv.text.isNotEmpty()) {
                    Icon(Icons.Filled.Cancel, stringResource(R.string.action_clear_text))
                }
            },
            // One line tall, but still multi-line: Enter adds a line break and the text scrolls
            // vertically. (maxLines = 1 would make Compose treat it as single-line and drop Enter.)
            modifier = modifier.height(oneLineOutlinedFieldHeight()),
        )
    }
    val nav = @Composable {
        Column {
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.line_previous))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.line_next))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (maxWidth >= 600.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                times()
                text(Modifier.weight(1f))
                nav()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    times()
                    nav()
                }
                text(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Height of a one-line [OutlinedTextField] with a label: one line of body text plus the field's
 * 16dp vertical padding, plus the half label height the outline is pushed down by (the same
 * calculation Material 3 uses). Follows the user's font scale.
 */
@Composable
private fun oneLineOutlinedFieldHeight(): Dp {
    val typography = MaterialTheme.typography
    return with(LocalDensity.current) {
        val line = typography.bodyLarge.lineHeight.takeIf { it.isSp } ?: 24.sp
        val label = typography.bodySmall.lineHeight.takeIf { it.isSp } ?: 16.sp
        maxOf(line.toDp() + 32.dp, OutlinedTextFieldDefaults.MinHeight) + label.toDp() / 2
    }
}

@Composable
private fun TimeField(
    label: String,
    valueMs: Long,
    onCommit: (Long) -> Unit,
    onUseCursor: () -> Unit,
    useCursorLabel: String,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(valueMs) { mutableStateOf(TimeFormat.display(valueMs)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = TimeFormat.parse(text)
        if (parsed != null && parsed != valueMs) onCommit(parsed) else text = TimeFormat.display(valueMs)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        trailingIcon = {
            IconButton(onClick = onUseCursor) {
                Icon(Icons.Filled.MyLocation, useCursorLabel, Modifier.size(20.dp))
            }
        },
        modifier = Modifier
            .width(168.dp)
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
    )
}

@Composable
private fun SubtitleList(
    lines: List<SubtitleLine>,
    track: Track,
    bilingual: Boolean,
    selectedId: Long?,
    checkedIds: Set<Long>,
    loading: Boolean,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    /** Second tap on the same row; the first tap has already run [onClick]. */
    onDoubleClick: (Long) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberLazyListState()
    // Double-tap is detected by hand so a single tap selects immediately instead of waiting
    // for the double-tap timeout (as combinedClickable's onDoubleClick would).
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val lastTap = remember { longArrayOf(Long.MIN_VALUE, 0L) } // [line id, uptime ms]
    val selectedIndex = lines.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (selectedIndex <= first || selectedIndex >= last) state.animateScrollToItem(max(0, selectedIndex - 1))
    }
    Box(modifier) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            lines.isEmpty() -> Text(
                stringResource(R.string.subtitles_empty),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines, key = { _, l -> l.id }) { i, line ->
                    SubtitleRow(
                        number = i + 1,
                        line = line,
                        track = track,
                        bilingual = bilingual,
                        selected = line.id == selectedId,
                        checked = if (checkedIds.isEmpty()) null else line.id in checkedIds,
                        onClick = {
                            val now = SystemClock.uptimeMillis()
                            val isDouble = lastTap[0] == line.id && now - lastTap[1] <= doubleTapTimeout
                            lastTap[0] = if (isDouble) Long.MIN_VALUE else line.id
                            lastTap[1] = now
                            onClick(line.id)
                            if (isDouble) onDoubleClick(line.id)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick(line.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleRow(
    number: Int,
    line: SubtitleLine,
    track: Track,
    /** Also show the other file's text, smaller, under the edited one. */
    bilingual: Boolean,
    selected: Boolean,
    /** null outside multi-select mode. */
    checked: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val invalid = line.durationMs <= 0
    val background = when {
        checked == true -> cs.primaryContainer
        selected -> cs.secondaryContainer
        else -> cs.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = if (checked != null) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        }
        Text(
            number.toString(),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        Text(
            "${TimeFormat.display(line.startMs)} → ${TimeFormat.display(line.endMs)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (invalid) cs.error else cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        val textColor = when {
            checked == true -> cs.onPrimaryContainer
            selected -> cs.onSecondaryContainer
            else -> cs.onSurface
        }
        Column(Modifier.weight(1f)) {
            Text(
                line.textOf(track).replace("\n", " ⏎ "),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val other = line.textOf(track.other)
            if (bilingual && other.isNotBlank()) {
                Text(
                    other.replace("\n", " ⏎ "),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
