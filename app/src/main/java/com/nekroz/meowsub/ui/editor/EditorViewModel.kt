package com.nekroz.meowsub.ui.editor

import android.app.Application
import android.content.Context
import android.net.Uri
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
import com.nekroz.meowsub.data.subtitle.SrtFormat
import com.nekroz.meowsub.data.subtitle.SubtitleClipboard
import com.nekroz.meowsub.data.subtitle.SubtitleLine
import com.nekroz.meowsub.data.subtitle.freeEndAfter
import com.nekroz.meowsub.data.subtitle.freeStartBefore
import com.nekroz.meowsub.data.subtitle.TextDecoding
import com.nekroz.meowsub.ui.UiMessage
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val folders = FolderRepository(app)
    private val docs = DocumentIO(app)
    private val audioRepo = AudioRepository(app)
    private val clipboard = SubtitleClipboard(app)
    private val prefs = app.getSharedPreferences("editor", Context.MODE_PRIVATE)

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

    /** Boundary the dragged marker is currently snapped to, for drawing a guide. */
    private val _snapGuide = MutableStateFlow<Long?>(null)
    val snapGuide: StateFlow<Long?> = _snapGuide.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var request: EditorRequest? = null
    private var subtitleUri: Uri? = null
    private var savedLines: List<SubtitleLine> = emptyList()
    private val history = EditHistory()
    private val ids = AtomicLong(1)
    private var dragKey: String? = null
    private var dragSeq = 0
    private var viewportWidthPx = 1
    private var audio: LoadedAudio? = null
    private var player: PcmPlayer? = null
    private val loadJobs = mutableListOf<Job>()
    private var tickerJob: Job? = null

    // region Open / close / save

    fun open(req: EditorRequest) {
        closeInternal()
        request = req
        subtitleUri = req.subtitle?.uri
        savedLines = emptyList()
        _ui.value = EditorUiState(
            isOpen = true,
            title = req.baseName,
            subtitleName = req.subtitle?.name,
            loading = req.subtitle != null,
            audio = if (req.audio != null) AudioStatus.Loading(0f) else AudioStatus.None,
        )
        _viewport.update { it.copy(startMs = 0.0) }
        _playback.value = PlaybackState()

        req.subtitle?.let { sub -> loadJobs += viewModelScope.launch { loadSubtitle(sub) } }
        req.audio?.let { a -> loadJobs += viewModelScope.launch { loadAudio(a) } }
    }

    private suspend fun loadSubtitle(sub: DocEntry) {
        try {
            val bytes = docs.readBytes(sub.uri)
            val lines = withContext(Dispatchers.Default) {
                SrtFormat.parse(TextDecoding.decode(bytes)) { ids.getAndIncrement() }
            }
            savedLines = lines
            _ui.update { it.copy(lines = lines, selectedId = lines.firstOrNull()?.id, loading = false, dirty = false) }
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

    private fun closeInternal() {
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

    /** Writes the subtitles back as UTF-8, creating `<base>.srt` in the folder when needed. */
    fun save(closeAfter: Boolean = false) {
        val req = request ?: return
        val state = _ui.value
        if (state.saving || state.loading) return
        val lines = state.lines
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val uri = subtitleUri ?: folders.createFile(req.treeUri, req.parentDocumentId, "${req.baseName}.srt")
                    .also { created ->
                        subtitleUri = created
                        _ui.update { it.copy(subtitleName = "${req.baseName}.srt") }
                    }
                val bytes = withContext(Dispatchers.Default) { SrtFormat.serialize(lines).toByteArray(Charsets.UTF_8) }
                docs.writeBytes(uri, bytes)
                savedLines = lines
                _ui.update { it.copy(saving = false, dirty = it.lines !== lines) }
                if (closeAfter && request === req) close() else message(R.string.msg_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false) }
                message(R.string.msg_save_failed, e.message ?: e.javaClass.simpleName)
            }
        }
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
            it.copy(
                lines = lines,
                selectedId = selectedId,
                checkedIds = it.checkedIds.filterTo(HashSet()) { id -> id in ids },
                dirty = lines !== savedLines,
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

    fun updateText(id: Long, text: String) {
        val line = _ui.value.lines.firstOrNull { it.id == id } ?: return
        if (line.text != text) replace(line.copy(text = text), coalesceKey = "text:$id")
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

    fun copySelection() {
        val lines = _ui.value.targetLines
        if (lines.isEmpty()) return
        clipboard.copy(lines)
        // Android 13+ shows its own "copied" confirmation, so no snackbar here.
    }

    /** Copies just the subtitle text of the ticked lines (or the active line). */
    fun copyPlainText() {
        val lines = _ui.value.targetLines
        if (lines.isEmpty()) return
        clipboard.copyPlainText(lines)
    }

    fun cutSelection() {
        val lines = _ui.value.targetLines
        if (lines.isEmpty()) return
        clipboard.copy(lines)
        deleteSelected()
    }

    /**
     * Pastes SRT text from the clipboard, keeping each line's original times. Every pasted
     * line is inserted at its place in time order (after existing lines with the same start).
     */
    fun paste() {
        val pasted = clipboard.read { ids.getAndIncrement() }
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
     * Splits the selected line in two. The text is split at [caret]; the time at the play
     * cursor when it lies inside the line, else proportionally to the caret, else in the middle.
     */
    fun splitSelected(caret: Int) {
        val s = _ui.value
        val sel = s.selected ?: return
        val index = s.selectedIndex
        val cursor = currentTimeMs()
        val textLen = sel.text.length
        val splitMs = when {
            cursor > sel.startMs && cursor < sel.endMs -> cursor
            caret in 1 until textLen -> sel.startMs + (sel.durationMs * caret.toDouble() / textLen).roundToLong()
            else -> sel.startMs + sel.durationMs / 2
        }
        val c = caret.coerceIn(0, textLen)
        val first = sel.copy(endMs = splitMs, text = sel.text.substring(0, c).trimEnd())
        val second = SubtitleLine(ids.getAndIncrement(), splitMs, sel.endMs, sel.text.substring(c).trimStart())
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
        val merged = sel.copy(
            startMs = min(sel.startMs, next.startMs),
            endMs = max(sel.endMs, next.endMs),
            text = listOf(sel.text, next.text).filter { it.isNotBlank() }.joinToString("\n"),
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

    fun playSelection() {
        _ui.value.selected?.let { play(it.startMs, it.endMs) }
    }

    fun playBeforeStart() {
        _ui.value.selected?.let { play(it.startMs - EDGE_PREVIEW_MS, it.startMs) }
    }

    fun playAfterEnd() {
        _ui.value.selected?.let { play(it.endMs, it.endMs + EDGE_PREVIEW_MS) }
    }

    /** Play from the cursor to the end, or pause and move the cursor to where playback stopped. */
    fun togglePlay() {
        val p = _playback.value
        if (p.playing) {
            stopPlayback()
            _playback.update { it.copy(cursorMs = p.positionMs) }
        } else {
            play(p.cursorMs, null)
        }
    }

    fun stopPlayback() {
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        _playback.update { it.copy(playing = false) }
    }

    private fun play(fromMs: Long, toMs: Long?) {
        val p = player ?: return message(R.string.msg_no_audio)
        tickerJob?.cancel()
        val from = fromMs.coerceAtLeast(0)
        p.play(from, toMs)
        _playback.update { it.copy(playing = true, positionMs = from) }
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(16)
                val pos = p.positionMs() ?: break
                _playback.update { it.copy(positionMs = pos) }
                followPlayback(pos)
            }
            _playback.update { it.copy(playing = false) }
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

    // endregion

    private fun message(res: Int, arg: String? = null) {
        _messages.trySend(UiMessage(res, arg))
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
    }

    companion object {
        const val MIN_MS_PER_PX = 1.0
        const val MAX_MS_PER_PX = 200.0
        const val MIN_AMPLITUDE = 0.5f
        const val MAX_AMPLITUDE = 20f
        const val DEFAULT_DURATION_MS = 2_000L
        const val EDGE_PREVIEW_MS = 500L
        private const val KEY_MS_PER_PX = "msPerPx"
        private const val KEY_AMPLITUDE = "amplitude"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SNAP = "snap"
    }
}
