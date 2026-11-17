package com.nekroz.meowsub.ui.editor

import com.nekroz.meowsub.data.subtitle.SubtitleLine

data class Snapshot(val lines: List<SubtitleLine>, val selectedId: Long?)

/**
 * Snapshot-based undo/redo. Consecutive records with the same non-null coalesce key
 * (e.g. a typing burst on one line, or a single marker drag) collapse into one undo step.
 */
class EditHistory(private val limit: Int = 300) {
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastKey: String? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [before] (the state prior to a change). */
    fun record(before: Snapshot, coalesceKey: String?) {
        if (coalesceKey != null && coalesceKey == lastKey) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        lastKey = coalesceKey
    }

    fun undo(current: Snapshot): Snapshot? {
        val s = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastKey = null
        return s
    }

    fun redo(current: Snapshot): Snapshot? {
        val s = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastKey = null
        return s
    }

    fun breakCoalescing() {
        lastKey = null
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
    }
}
