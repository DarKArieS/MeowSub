package com.nekroz.meowsub.ui.editor

import android.net.Uri
import com.nekroz.meowsub.data.audio.WaveformPeaks
import com.nekroz.meowsub.data.files.DocEntry
import com.nekroz.meowsub.data.subtitle.SubtitleLine
import com.nekroz.meowsub.data.subtitle.Track

/**
 * What to open: an audio file and/or SRT that share [baseName] inside a folder, plus the
 * original from `rawSRT/` that [subtitle] translates, if there is one.
 */
data class EditorRequest(
    val treeUri: Uri,
    val parentDocumentId: String,
    val baseName: String,
    val audio: DocEntry?,
    val subtitle: DocEntry?,
    val original: DocEntry? = null,
)

sealed interface AudioStatus {
    data object None : AudioStatus
    data class Loading(val progress: Float) : AudioStatus
    data class Ready(val peaks: WaveformPeaks, val durationMs: Long) : AudioStatus
    data class Failed(val reason: String) : AudioStatus
}

data class EditorUiState(
    val isOpen: Boolean = false,
    val title: String = "",
    val subtitleName: String? = null,
    /** File name of the `rawSRT/` original; non-null means bilingual editing. */
    val originalName: String? = null,
    /** Which file's text is being edited (always [Track.TRANSLATION] when not bilingual). */
    val track: Track = Track.TRANSLATION,
    val lines: List<SubtitleLine> = emptyList(),
    /** The active line: edited in the line editor and on the waveform. */
    val selectedId: Long? = null,
    /** Lines ticked in multi-select mode (mode is active while non-empty). */
    val checkedIds: Set<Long> = emptySet(),
    val loading: Boolean = false,
    /** `<name>.srt` differs from what is on disk. */
    val dirtyTranslation: Boolean = false,
    /** `rawSRT/<name>.srt` differs from what is on disk. */
    val dirtyOriginal: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val audio: AudioStatus = AudioStatus.None,
) {
    val selectedIndex: Int get() = lines.indexOfFirst { it.id == selectedId }
    val selected: SubtitleLine? get() = lines.getOrNull(selectedIndex)
    val selectionMode: Boolean get() = checkedIds.isNotEmpty()
    val bilingual: Boolean get() = originalName != null
    val dirty: Boolean get() = dirtyTranslation || dirtyOriginal

    /** Lines that copy/cut/delete act on: the ticked lines, or else the active line. */
    val targetLines: List<SubtitleLine>
        get() = if (selectionMode) lines.filter { it.id in checkedIds } else listOfNotNull(selected)
}

/** Visible waveform window: left edge time, horizontal scale and vertical amplitude gain. */
data class ViewportState(
    val startMs: Double = 0.0,
    val msPerPx: Double = 10.0,
    val amplitude: Float = 1f,
)

data class PlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** Where playback starts from; set by tapping the waveform. */
    val cursorMs: Long = 0,
    /** Line being played on its own (double-tap in the list); null for any other playback. */
    val lineId: Long? = null,
)

enum class Marker { Start, End }
