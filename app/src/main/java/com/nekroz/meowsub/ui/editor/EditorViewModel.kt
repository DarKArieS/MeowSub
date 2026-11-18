package com.nekroz.meowsub.ui.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekroz.meowsub.R
import com.nekroz.meowsub.data.audio.AudioRepository
import com.nekroz.meowsub.data.audio.AudioSource
import com.nekroz.meowsub.data.audio.LoadedAudio
import com.nekroz.meowsub.data.audio.PcmPlayer
import com.nekroz.meowsub.data.files.DocEntry
import com.nekroz.meowsub.data.files.DocumentIO
import com.nekroz.meowsub.data.files.FolderRepository
import com.nekroz.meowsub.data.subtitle.Bilingual
import com.nekroz.meowsub.data.subtitle.Cue
import com.nekroz.meowsub.data.subtitle.SrtFormat
import com.nekroz.meowsub.data.subtitle.SubtitleClipboard
import com.nekroz.meowsub.data.subtitle.SubtitleLine
import com.nekroz.meowsub.data.subtitle.TextDecoding
import com.nekroz.meowsub.data.subtitle.Track
import com.nekroz.meowsub.data.subtitle.cues
import com.nekroz.meowsub.data.subtitle.forTrack
import com.nekroz.meowsub.data.subtitle.freeEndAfter
import com.nekroz.meowsub.data.subtitle.freeStartBefore
import com.nekroz.meowsub.data.subtitle.textOf
import com.nekroz.meowsub.data.subtitle.withText
import com.nekroz.meowsub.data.transcribe.AudioClip
import com.nekroz.meowsub.data.transcribe.ModelLoadException
import com.nekroz.meowsub.data.transcribe.TranscribeSettings
import com.nekroz.meowsub.data.transcribe.TranscribeSettingsStore
import com.nekroz.meowsub.data.transcribe.WhisperEngine
import com.nekroz.meowsub.ui.UiMessage
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val folders = FolderRepository(app)
    private val docs = DocumentIO(app)
    private val audioRepo = AudioRepository(app)
    private val clipboard = SubtitleClipboard(app)
    private val prefs = app.getSharedPreferences("editor", Context.MODE_PRIVATE)
    private val transcribeStore = TranscribeSettingsStore(app)
    private val whisper = WhisperEngine(app)

    private val _ui = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _ui.asStateFlow()

    private val _viewport = MutableStateFlow(
        ViewportState(
            msPerPx = prefs.getFloat(KEY_MS_PER_PX, 10f).toDouble().coerceIn(MIN_MS_PER_PX, MAX_MS_PER_PX),
            amplitude = prefs.getFloat(KEY_AMPLITUDE, 1f).coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE),
        )
    )
    val viewport: StateFlow<ViewportState> = _viewport.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val _volume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f))
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _snapEnabled = MutableStateFlow(prefs.getBoolean(KEY_SNAP, true))
    val snapEnabled: StateFlow<Boolean> = _snapEnabled.asStateFlow()

    private val _toolbar = MutableStateFlow(ToolbarConfig.decode(prefs.getString(KEY_TOOLBAR, null)))
    /** User-arranged toolbar: order, and which actions sit on the bar vs. in the ⋮ menu. */
    val toolbar: StateFlow<List<ToolbarItem>> = _toolbar.asStateFlow()

    /** Boundary the dragged marker is currently snapped to, for drawing a guide. */
    private val _snapGuide = MutableStateFlow<Long?>(null)
    val snapGuide: StateFlow<Long?> = _snapGuide.asStateFlow()

    private val _transcribeSettings = MutableStateFlow(transcribeStore.load())
    val transcribeSettings: StateFlow<TranscribeSettings> = _transcribeSettings.asStateFlow()

    private val _transcription = MutableStateFlow(TranscriptionUi())
    val transcription: StateFlow<TranscriptionUi> = _transcription.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var request: EditorRequest? = null
    private var subtitleUri: Uri? = null
    private var originalUri: Uri? = null
    /** What each file holds on disk, to tell which ones have unsaved changes. */
    private var savedTranslation: List<Cue> = emptyList()
    private var savedOriginal: List<Cue> = emptyList()
    private val history = EditHistory()
    private val ids = AtomicLong(1)
    private var dragKey: String? = null
    private var dragSeq = 0
    private var viewportWidthPx = 1
    private var audio: LoadedAudio? = null
    private var player: PcmPlayer? = null
    private val loadJobs = mutableListOf<Job>()
    private var tickerJob: Job? = null
    private var transcribeJob: Job? = null
    private var transcribeRun = 0

    init {
        viewModelScope.launch {
            whisper.state.collect { model -> _transcription.update { it.copy(model = model) } }
        }
    }

    // region Open / close / save

    fun open(req: EditorRequest) {
        closeInternal()
        request = req
        subtitleUri = req.subtitle?.uri
        originalUri = req.original?.uri
        savedTranslation = emptyList()
        savedOriginal = emptyList()
        _ui.value = EditorUiState(
            isOpen = true,
            title = req.baseName,
            subtitleName = req.subtitle?.name,
            originalName = req.original?.name,
            track = if (req.original != null) savedTrack() else Track.TRANSLATION,
            loading = req.subtitle != null || req.original != null,
            audio = if (req.audio != null) AudioStatus.Loading(0f) else AudioStatus.None,
        )
        _viewport.update { it.copy(startMs = 0.0) }
        _playback.value = PlaybackState()

        if (req.subtitle != null || req.original != null) {
            loadJobs += viewModelScope.launch { loadSubtitles(req) }
        }
        req.audio?.let { a -> loadJobs += viewModelScope.launch { loadAudio(a) } }
    }

    private suspend fun readLines(entry: DocEntry): List<SubtitleLine> {
        val bytes = docs.readBytes(entry.uri)
        return withContext(Dispatchers.Default) {
            SrtFormat.parse(TextDecoding.decode(bytes)) { ids.getAndIncrement() }
        }
    }

    /**
     * Loads `<name>.srt` and, when present, its `rawSRT/` original. With an original, the
     * translation is aligned to the original's timing (see [Bilingual.align]).
     */
    private suspend fun loadSubtitles(req: EditorRequest) {
        try {
            val translation = req.subtitle?.let { readLines(it) }
            var original = req.original?.let { readLines(it) }
            if (original != null && original.isEmpty()) {
                // Nothing to align to (or unreadable): edit the translation alone rather than
                // risk overwriting the original with blank entries.
                original = null
                originalUri = null
                _ui.update { it.copy(originalName = null, track = Track.TRANSLATION) }
                message(R.string.msg_original_empty)
            }
            val lines = if (original == null) {
                translation.orEmpty().also { savedTranslation = it.cues(Track.TRANSLATION) }
            } else {
                val aligned = withContext(Dispatchers.Default) { Bilingual.align(original, translation.orEmpty()) }
                // Parsed files keep their text in `text`, hence the TRANSLATION projection.
                savedOriginal = original.cues(Track.TRANSLATION)
                savedTranslation = translation?.cues(Track.TRANSLATION) ?: aligned.lines.cues(Track.TRANSLATION)
                if (translation != null && (aligned.retimed > 0 || aligned.filled > 0)) {
                    message(R.string.msg_aligned, aligned.retimed.toString(), aligned.filled.toString())
                }
                aligned.lines
            }
            _ui.update { it.copy(lines = lines, selectedId = lines.firstOrNull()?.id, loading = false) }
            refreshDirty()
            lines.firstOrNull()?.let { ensureVisible(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave an editor open on a file we failed to read: saving would wipe it.
            close()
            message(R.string.msg_subtitle_load_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun loadAudio(entry: DocEntry) {
        try {
            val loaded = audioRepo.load(AudioSource(entry.uri, entry.size, entry.lastModified)) { p ->
                _ui.update { s -> if (s.audio is AudioStatus.Loading) s.copy(audio = AudioStatus.Loading(p)) else s }
            }
            audio = loaded
            player = PcmPlayer(loaded.pcmFile, loaded.sampleRate, loaded.sampleCount).also { it.volume = _volume.value }
            _ui.update { it.copy(audio = AudioStatus.Ready(loaded.peaks, loaded.durationMs)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(audio = AudioStatus.Failed(e.message ?: e.javaClass.simpleName)) }
        }
    }

    fun close() {
        closeInternal()
        _ui.value = EditorUiState()
    }

    /** Switches which file's text is shown and edited. Timing is shared, so nothing else changes. */
    fun setTrack(track: Track) {
        if (!_ui.value.bilingual || _ui.value.track == track) return
        history.breakCoalescing()
        _ui.update { it.copy(track = track) }
        prefs.edit { putString(KEY_TRACK, track.name) }
    }

    private fun savedTrack(): Track =
        Track.entries.firstOrNull { it.name == prefs.getString(KEY_TRACK, null) } ?: Track.TRANSLATION

    private fun dirtyFlags(lines: List<SubtitleLine>, bilingual: Boolean): Pair<Boolean, Boolean> =
        (lines.cues(Track.TRANSLATION) != savedTranslation) to
            (bilingual && lines.cues(Track.ORIGINAL) != savedOriginal)

    private fun refreshDirty() {
        _ui.update {
            val (translation, original) = dirtyFlags(it.lines, it.bilingual)
            it.copy(dirtyTranslation = translation, dirtyOriginal = original)
        }
    }

    private fun closeInternal() {
        abandonTranscription()
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
        stopPlayback()
        player?.release()
        player = null
        audio = null
        history.clear()
        request = null
        persistViewPrefs()
    }

    /**
     * Writes every changed file back as UTF-8: `<base>.srt` (created in the folder when needed)
     * and, when bilingual, the `rawSRT/` original.
     */
    fun save(closeAfter: Boolean = false) {
        val req = request ?: return
        val state = _ui.value
        if (state.saving || state.loading) return
        if (!state.dirty) {
            if (closeAfter) close()
            return
        }
        val lines = state.lines
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                if (state.dirtyTranslation) {
                    val uri = subtitleUri ?: folders.createFile(req.treeUri, req.parentDocumentId, "${req.baseName}.srt")
                        .also { created ->
                            subtitleUri = created
                            _ui.update { it.copy(subtitleName = "${req.baseName}.srt") }
                        }
                    write(uri, lines.forTrack(Track.TRANSLATION))
                    savedTranslation = lines.cues(Track.TRANSLATION)
                }
                if (state.dirtyOriginal) {
                    write(checkNotNull(originalUri), lines.forTrack(Track.ORIGINAL))
                    savedOriginal = lines.cues(Track.ORIGINAL)
                }
                _ui.update { it.copy(saving = false) }
                refreshDirty()
                if (closeAfter && request === req) close() else message(R.string.msg_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false) }
                refreshDirty()
                message(R.string.msg_save_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun write(uri: Uri, lines: List<SubtitleLine>) {
        val bytes = withContext(Dispatchers.Default) { SrtFormat.serialize(lines).toByteArray(Charsets.UTF_8) }
        docs.writeBytes(uri, bytes)
    }

    // endregion

    // region Line editing

    private fun commit(lines: List<SubtitleLine>, selectedId: Long? = _ui.value.selectedId, coalesceKey: String? = null) {
        val cur = _ui.value
        if (lines == cur.lines && selectedId == cur.selectedId) return
        history.record(Snapshot(cur.lines, cur.selectedId), coalesceKey)
        apply(lines, selectedId)
    }

    private fun apply(lines: List<SubtitleLine>, selectedId: Long?) {
        _ui.update {
            val ids = if (it.checkedIds.isEmpty()) emptySet() else lines.mapTo(HashSet()) { l -> l.id }
            val (dirtyTranslation, dirtyOriginal) = dirtyFlags(lines, it.bilingual)
            it.copy(
                lines = lines,
                selectedId = selectedId,
                checkedIds = it.checkedIds.filterTo(HashSet()) { id -> id in ids },
                dirtyTranslation = dirtyTranslation,
                dirtyOriginal = dirtyOriginal,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
    }

    private fun replace(line: SubtitleLine, coalesceKey: String? = null) {
        commit(_ui.value.lines.map { if (it.id == line.id) line else it }, coalesceKey = coalesceKey)
    }

    fun select(id: Long, scroll: Boolean = true) {
        history.breakCoalescing()
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(selectedId = id) }
        if (scroll) ensureVisible(line)
    }

    fun selectNext() {
        val s = _ui.value
        val next = s.lines.getOrNull(s.selectedIndex + 1)
        // Like Aegisub's "commit": on the last line, continue by creating a new one.
        if (next != null) select(next.id) else addLineAfter()
    }

    fun selectPrevious() {
        val s = _ui.value
        s.lines.getOrNull(s.selectedIndex - 1)?.let { select(it.id) }
    }

    /** Sets the text of the file being edited ([EditorUiState.track]). */
    fun updateText(id: Long, text: String) {
        val track = _ui.value.track
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        if (line.textOf(track) != text) replace(line.withText(track, text), coalesceKey = "text:$track:$id")
    }

    fun setStart(id: Long, ms: Long) {
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        val start = ms.coerceAtLeast(0)
        replace(line.copy(startMs = start, endMs = max(line.endMs, start)))
    }

    fun setEnd(id: Long, ms: Long) {
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        val end = ms.coerceAtLeast(0)
        replace(line.copy(startMs = min(line.startMs, end), endMs = end))
    }

    fun setStartAtCursor() {
        _ui.value.selected?.let { setStart(it.id, currentTimeMs()) }
    }

    fun setEndAtCursor() {
        _ui.value.selected?.let { setEnd(it.id, currentTimeMs()) }
    }

    fun beginMarkerDrag() {
        history.breakCoalescing()
        dragKey = "drag:${dragSeq++}"
    }

    /**
     * Moves a marker of the selected line to [ms]. With snapping on, it sticks to a boundary
     * of the previous/next line when within [snapRadiusMs].
     */
    fun dragMarker(marker: Marker, ms: Long, snapRadiusMs: Double) {
        val s = _ui.value
        val sel = s.selected ?: return
        val snapped = if (_snapEnabled.value) {
            Snapping.nearest(ms, Snapping.adjacentBoundaries(s.lines, s.selectedIndex), snapRadiusMs)
        } else null
        _snapGuide.value = snapped
        val target = snapped ?: ms
        val updated = when (marker) {
            Marker.Start -> sel.copy(startMs = target.coerceIn(0, sel.endMs))
            Marker.End -> sel.copy(endMs = target.coerceAtLeast(sel.startMs))
        }
        if (updated != sel) replace(updated, coalesceKey = dragKey)
    }

    fun endMarkerDrag() {
        dragKey = null
        _snapGuide.value = null
        history.breakCoalescing()
    }

    fun setToolbar(items: List<ToolbarItem>) {
        _toolbar.value = items
        prefs.edit { putString(KEY_TOOLBAR, ToolbarConfig.encode(items)) }
    }

    fun setSnapEnabled(enabled: Boolean) {
        _snapEnabled.value = enabled
        prefs.edit { putBoolean(KEY_SNAP, enabled) }
    }

    fun addLineAfter() {
        val s = _ui.value
        val sel = s.selected
        val start = sel?.endMs ?: currentTimeMs()
        val end = s.lines.freeEndAfter(start, DEFAULT_DURATION_MS, excludeId = sel?.id)
        val line = SubtitleLine(ids.getAndIncrement(), start, end, "")
        val index = if (sel != null) s.selectedIndex + 1
        else s.lines.indexOfFirst { it.startMs > start }.let { if (it < 0) s.lines.size else it }
        commit(s.lines.toMutableList().apply { add(index, line) }, line.id)
        ensureVisible(line)
    }

    fun insertLineBefore() {
        val s = _ui.value
        val sel = s.selected ?: return addLineAfter()
        val end = sel.startMs
        val start = s.lines.freeStartBefore(end, DEFAULT_DURATION_MS, excludeId = sel.id)
        val line = SubtitleLine(ids.getAndIncrement(), start, end, "")
        commit(s.lines.toMutableList().apply { add(s.selectedIndex, line) }, line.id)
        ensureVisible(line)
    }

    /** Deletes the ticked lines (or the active line) and leaves multi-select mode. */
    fun deleteSelected() {
        val s = _ui.value
        val removeIds = s.targetLines.mapTo(HashSet()) { it.id }
        if (removeIds.isEmpty()) return
        val lastIndex = s.lines.indexOfLast { it.id in removeIds }
        val newSel = s.lines.drop(lastIndex + 1).firstOrNull { it.id !in removeIds }
            ?: s.lines.take(lastIndex).lastOrNull { it.id !in removeIds }
        _ui.update { it.copy(checkedIds = emptySet()) }
        commit(s.lines.filter { it.id !in removeIds }, newSel?.id)
    }

    // region Multi-select and clipboard

    /** Long-press on a row: enter multi-select mode with that line ticked. */
    fun startSelection(id: Long) {
        history.breakCoalescing()
        _ui.update { it.copy(checkedIds = setOf(id), selectedId = id) }
    }

    /** Tap on a row in multi-select mode; un-ticking the last line leaves the mode. */
    fun toggleChecked(id: Long) {
        history.breakCoalescing()
        _ui.update {
            val checked = if (id in it.checkedIds) it.checkedIds - id else it.checkedIds + id
            it.copy(checkedIds = checked, selectedId = if (id in checked) id else it.selectedId)
        }
        _ui.value.selected?.let { ensureVisible(it) }
    }

    fun selectAll() {
        _ui.update { s -> s.copy(checkedIds = s.lines.mapTo(HashSet()) { it.id }) }
    }

    fun clearSelection() {
        _ui.update { it.copy(checkedIds = emptySet()) }
    }

    /** Target lines as the file being edited sees them (its text, shared timing). */
    private fun targetsForClipboard(): List<SubtitleLine> = _ui.value.let { it.targetLines.forTrack(it.track) }

    fun copySelection() {
        val lines = targetsForClipboard()
        if (lines.isEmpty()) return
        clipboard.copy(lines)
        // Android 13+ shows its own "copied" confirmation, so no snackbar here.
    }

    /** Copies just the subtitle text of the ticked lines (or the active line). */
    fun copyPlainText() {
        val lines = targetsForClipboard()
        if (lines.isEmpty()) return
        clipboard.copyPlainText(lines)
    }

    fun cutSelection() {
        val lines = targetsForClipboard()
        if (lines.isEmpty()) return
        clipboard.copy(lines)
        deleteSelected()
    }

    /**
     * Pastes SRT text from the clipboard, keeping each line's original times. Every pasted
     * line is inserted at its place in time order (after existing lines with the same start).
     * The text goes into the file being edited; the other file gets empty lines at those times.
     */
    fun paste() {
        val track = _ui.value.track
        val pasted = clipboard.read { ids.getAndIncrement() }
            .map { if (track == Track.TRANSLATION) it else it.copy(text = "", original = it.text) }
        if (pasted.isEmpty()) return message(R.string.msg_clipboard_empty)
        val lines = _ui.value.lines.toMutableList()
        for (line in pasted) {
            val index = lines.indexOfFirst { it.startMs > line.startMs }
            if (index < 0) lines.add(line) else lines.add(index, line)
        }
        val first = pasted.minBy { it.startMs }
        _ui.update { it.copy(checkedIds = emptySet()) }
        commit(lines, first.id)
        ensureVisible(first)
        message(R.string.msg_pasted, pasted.size.toString())
    }

    // endregion

    /**
     * Splits the selected line in two. The edited file's text is split at [caret]; the time at
     * the play cursor when it lies inside the line, else proportionally to the caret, else in the
     * middle. When bilingual, the other file's text stays with the first half.
     */
    fun splitSelected(caret: Int) {
        val s = _ui.value
        val sel = s.selected ?: return
        val index = s.selectedIndex
        val cursor = currentTimeMs()
        val text = sel.textOf(s.track)
        val textLen = text.length
        val splitMs = when {
            cursor > sel.startMs && cursor < sel.endMs -> cursor
            caret in 1 until textLen -> sel.startMs + (sel.durationMs * caret.toDouble() / textLen).roundToLong()
            else -> sel.startMs + sel.durationMs / 2
        }
        val c = caret.coerceIn(0, textLen)
        val first = sel.copy(endMs = splitMs).withText(s.track, text.substring(0, c).trimEnd())
        val second = SubtitleLine(ids.getAndIncrement(), splitMs, sel.endMs, "")
            .withText(s.track, text.substring(c).trimStart())
        val lines = s.lines.toMutableList().apply {
            set(index, first)
            add(index + 1, second)
        }
        commit(lines, first.id)
    }

    fun mergeWithNext() {
        val s = _ui.value
        val sel = s.selected ?: return
        val index = s.selectedIndex
        val next = s.lines.getOrNull(index + 1) ?: return message(R.string.msg_no_next_line)
        fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString("\n")
        val merged = sel.copy(
            startMs = min(sel.startMs, next.startMs),
            endMs = max(sel.endMs, next.endMs),
            text = join(sel.text, next.text),
            original = join(sel.original, next.original),
        )
        val lines = s.lines.toMutableList().apply {
            set(index, merged)
            removeAt(index + 1)
        }
        commit(lines, merged.id)
    }

    fun sortByTime() {
        val s = _ui.value
        val sorted = s.lines.sortedBy { it.startMs }
        if (sorted == s.lines) return message(R.string.msg_already_sorted)
        commit(sorted)
        s.selected?.let { ensureVisible(it) }
    }

    fun undo() {
        val cur = _ui.value
        val snap = history.undo(Snapshot(cur.lines, cur.selectedId)) ?: return
        apply(snap.lines, snap.selectedId)
        _ui.value.selected?.let { ensureVisible(it) }
    }

    fun redo() {
        val cur = _ui.value
        val snap = history.redo(Snapshot(cur.lines, cur.selectedId)) ?: return
        apply(snap.lines, snap.selectedId)
        _ui.value.selected?.let { ensureVisible(it) }
    }

    // endregion

    // region Viewport

    fun setViewportWidth(px: Int) {
        val first = viewportWidthPx <= 1
        viewportWidthPx = max(1, px)
        if (first) _ui.value.selected?.let { ensureVisible(it) }
    }

    /** Pinch/pan on the waveform: zoom around [anchorX], then pan by [panX] pixels. */
    fun transformViewport(zoom: Float, anchorX: Float, panX: Float) {
        _viewport.update { v ->
            val mpp = (v.msPerPx / zoom).coerceIn(MIN_MS_PER_PX, MAX_MS_PER_PX)
            val anchorMs = v.startMs + anchorX * v.msPerPx
            val start = anchorMs - anchorX * mpp - panX * mpp
            v.copy(startMs = clampStart(start, mpp), msPerPx = mpp)
        }
    }

    fun setZoom(msPerPx: Double) {
        _viewport.update { v ->
            val mpp = msPerPx.coerceIn(MIN_MS_PER_PX, MAX_MS_PER_PX)
            // Keep the selected line (or the view centre) in place while zooming.
            val anchorMs = _ui.value.selected?.startMs?.toDouble()
                ?.takeIf { it >= v.startMs && it <= v.startMs + viewportWidthPx * v.msPerPx }
                ?: (v.startMs + viewportWidthPx * v.msPerPx / 2)
            val anchorX = (anchorMs - v.startMs) / v.msPerPx
            v.copy(startMs = clampStart(anchorMs - anchorX * mpp, mpp), msPerPx = mpp)
        }
    }

    fun setAmplitude(amplitude: Float) {
        _viewport.update { it.copy(amplitude = amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)) }
    }

    private fun totalMs(): Double {
        val audioMs = audio?.durationMs ?: 0L
        val lastEnd = _ui.value.lines.maxOfOrNull { it.endMs } ?: 0L
        return maxOf(audioMs, lastEnd + 5_000, 10_000).toDouble()
    }

    private fun clampStart(start: Double, mpp: Double): Double =
        start.coerceIn(0.0, max(0.0, totalMs() - viewportWidthPx * mpp * 0.5))

    private fun ensureVisible(line: SubtitleLine) {
        _viewport.update { v ->
            val widthMs = viewportWidthPx * v.msPerPx
            if (line.startMs >= v.startMs && line.endMs <= v.startMs + widthMs) return@update v
            val len = line.durationMs.toDouble()
            val start = if (len < widthMs * 0.8) line.startMs - (widthMs - len) / 2 else line.startMs - widthMs * 0.1
            v.copy(startMs = clampStart(start, v.msPerPx))
        }
    }

    // endregion

    // region Playback

    private fun currentTimeMs(): Long {
        val p = _playback.value
        return if (p.playing) p.positionMs else p.cursorMs
    }

    fun onWaveformTap(ms: Long) {
        val t = ms.coerceAtLeast(0)
        val s = _ui.value
        val sel = s.selected
        if (sel == null || t !in sel) {
            s.lines.lastOrNull { t in it }?.let { select(it.id, scroll = false) }
        }
        if (_playback.value.playing) play(t, null) else _playback.update { it.copy(cursorMs = t) }
    }

    /** Plays the selected line; when it finishes, the cursor moves to the line's end time. */
    fun playSelection() {
        _ui.value.selected?.let { play(it.startMs, it.endMs, moveCursorOnEnd = true, lineId = it.id) }
    }

    /** Tap on a list row: pauses if that line is the one playing on its own, otherwise selects it. */
    fun onLineClick(id: Long) {
        val p = _playback.value
        if (p.playing && p.lineId == id) pause() else select(id)
    }

    /** Play from the cursor to the end, or pause and move the cursor to where playback stopped. */
    fun togglePlay() {
        if (_playback.value.playing) pause() else play(_playback.value.cursorMs, null)
    }

    /** Stops playback and leaves the cursor where it stopped. */
    private fun pause() {
        val position = _playback.value.positionMs
        stopPlayback()
        _playback.update { it.copy(cursorMs = position) }
    }

    fun stopPlayback() {
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        _playback.update { it.copy(playing = false, lineId = null) }
    }

    /**
     * @param moveCursorOnEnd when playback reaches its end by itself (not stopped or replaced),
     *   move the cursor to where it ended.
     * @param lineId the line being played on its own, if any (see [PlaybackState.lineId]).
     */
    private fun play(fromMs: Long, toMs: Long?, moveCursorOnEnd: Boolean = false, lineId: Long? = null) {
        val p = player ?: return message(R.string.msg_no_audio)
        tickerJob?.cancel()
        val from = fromMs.coerceAtLeast(0)
        p.play(from, toMs)
        _playback.update { it.copy(playing = true, positionMs = from, lineId = lineId) }
        tickerJob = viewModelScope.launch {
            var last = from
            while (true) {
                delay(16)
                val pos = p.positionMs() ?: break
                last = pos
                _playback.update { it.copy(positionMs = pos) }
                followPlayback(pos)
            }
            // Only reached on a natural end: stopPlayback()/play() cancel this job first.
            _playback.update {
                if (moveCursorOnEnd) {
                    val end = toMs ?: last
                    it.copy(playing = false, positionMs = end, cursorMs = end, lineId = null)
                } else {
                    it.copy(playing = false, lineId = null)
                }
            }
        }
    }

    private fun followPlayback(pos: Long) {
        val v = _viewport.value
        val widthMs = viewportWidthPx * v.msPerPx
        if (pos < v.startMs || pos > v.startMs + widthMs) {
            _viewport.update { it.copy(startMs = clampStart(pos - widthMs * 0.05, it.msPerPx)) }
        }
    }

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        _volume.value = vol
        player?.volume = vol
    }

    /** Saved waveform panel height in dp; 0 means "not set", i.e. use the default split. */
    val savedWaveformHeightDp: Float get() = prefs.getFloat(KEY_WAVE_HEIGHT, 0f)

    fun saveWaveformHeight(dp: Float) {
        prefs.edit { putFloat(KEY_WAVE_HEIGHT, dp) }
    }

    // endregion

    // region Transcription

    fun openTranscribeSettings() = _transcription.update { it.copy(settingsOpen = true) }

    fun closeTranscribeSettings() = _transcription.update { it.copy(settingsOpen = false) }

    fun saveTranscribeSettings(settings: TranscribeSettings) {
        _transcribeSettings.value = settings
        transcribeStore.save(settings)
        closeTranscribeSettings()
    }

    /** Keeps read access to a model file the user picked and returns its display name. */
    fun modelFileName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    /**
     * Transcribes the ticked lines, or else the active one, into the text being edited, loading
     * the model first if needed. A run is one undo step; lines with no speech keep their text.
     */
    fun transcribe() {
        if (transcribeJob?.isActive == true) return
        val s = _ui.value
        val targets = s.targetLines.sortedBy { it.startMs }
        if (targets.isEmpty()) return
        val source = audio ?: return message(R.string.msg_transcribe_no_audio)
        val settings = _transcribeSettings.value
        if (settings.modelUri == null) {
            message(R.string.msg_transcribe_no_model)
            openTranscribeSettings()
            return
        }
        val track = s.track
        val run = ++transcribeRun
        val key = "transcribe:$run"
        history.breakCoalescing()
        _transcription.update { it.copy(done = 0, total = targets.size) }
        transcribeJob = viewModelScope.launch {
            var empty = 0
            try {
                targets.forEachIndexed { i, line ->
                    val clip = withContext(Dispatchers.IO) {
                        AudioClip.read(source, line.startMs - settings.paddingMs, line.endMs + settings.paddingMs)
                    }
                    val text = whisper.transcribe(clip, settings)
                    if (text.isEmpty()) empty++ else setTranscribedText(line.id, track, text, key)
                    _transcription.update { it.copy(done = i + 1) }
                }
                when {
                    targets.size > 1 -> message(R.string.msg_transcribed, (targets.size - empty).toString(), targets.size.toString())
                    empty > 0 -> message(R.string.msg_transcribe_empty)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelLoadException) {
                message(R.string.msg_model_load_failed, e.message.orEmpty())
            } catch (e: Exception) {
                message(R.string.msg_transcribe_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                // A cancelled run ends only once whisper.cpp returns; by then a newer run may own the progress.
                if (run == transcribeRun) clearTranscribeProgress()
            }
        }
    }

    private fun clearTranscribeProgress() = _transcription.update { it.copy(done = 0, total = 0) }

    private fun setTranscribedText(id: Long, track: Track, text: String, coalesceKey: String) {
        val lines = _ui.value.lines
        val line = lines.firstOrNull { it.id == id } ?: return // deleted meanwhile
        if (line.textOf(track) == text) return
        commit(lines.map { if (it.id == id) it.withText(track, text) else it }, coalesceKey = coalesceKey)
    }

    /**
     * Stops transcribing. whisper.cpp only notices between computation passes, so the line in
     * progress may run a few more seconds in the background; the UI is released right away and
     * a new run simply waits for it.
     */
    fun cancelTranscription() {
        if (abandonTranscription()) message(R.string.msg_transcribe_cancelled)
    }

    /** Cancels a running transcription and releases the UI at once; false if none was running. */
    private fun abandonTranscription(): Boolean {
        if (transcribeJob?.isActive != true) return false
        transcribeJob?.cancel()
        transcribeRun++ // its late `finally` must not touch a newer run's progress
        clearTranscribeProgress()
        return true
    }

    /** Frees the model's memory; a running transcription is cancelled first. */
    fun unloadModel() {
        abandonTranscription()
        viewModelScope.launch {
            whisper.unload()
            message(R.string.msg_model_unloaded)
        }
    }

    // endregion

    private fun message(res: Int, vararg args: String) {
        _messages.trySend(UiMessage(res, *args))
    }

    private fun persistViewPrefs() {
        prefs.edit {
            putFloat(KEY_MS_PER_PX, _viewport.value.msPerPx.toFloat())
            putFloat(KEY_AMPLITUDE, _viewport.value.amplitude)
            putFloat(KEY_VOLUME, _volume.value)
        }
    }

    override fun onCleared() {
        closeInternal()
        // viewModelScope is gone: free the model once any aborted run has returned.
        CoroutineScope(Dispatchers.IO).launch { whisper.unload() }
    }

    companion object {
        const val MIN_MS_PER_PX = 1.0
        const val MAX_MS_PER_PX = 200.0
        const val MIN_AMPLITUDE = 0.5f
        const val MAX_AMPLITUDE = 20f
        const val DEFAULT_DURATION_MS = 2_000L
        private const val KEY_MS_PER_PX = "msPerPx"
        private const val KEY_AMPLITUDE = "amplitude"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SNAP = "snap"
        private const val KEY_WAVE_HEIGHT = "waveformHeightDp"
        private const val KEY_TOOLBAR = "toolbar"
        private const val KEY_TRACK = "track"
    }
}
