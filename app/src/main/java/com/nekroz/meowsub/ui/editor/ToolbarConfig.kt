package com.nekroz.meowsub.ui.editor

/** Editor actions the user can arrange on the toolbar or tuck into the ⋮ menu. */
enum class ToolbarAction {
    UNDO, REDO, ADD, INSERT_BEFORE, DELETE, COPY, COPY_TEXT, CUT, PASTE, SPLIT, MERGE, SORT, SAVE;

    /** Usable on the multi-select toolbar (acts on the ticked lines). */
    val inSelectionMode: Boolean get() = this in SELECTION_ACTIONS

    private companion object {
        val SELECTION_ACTIONS = setOf(COPY, COPY_TEXT, CUT, PASTE, DELETE)
    }
}

/** One toolbar entry: its position is its index in the list; [onBar] false = in the ⋮ menu. */
data class ToolbarItem(val action: ToolbarAction, val onBar: Boolean)

object ToolbarConfig {

    val DEFAULT: List<ToolbarItem> = listOf(
        ToolbarItem(ToolbarAction.UNDO, true),
        ToolbarItem(ToolbarAction.REDO, true),
        ToolbarItem(ToolbarAction.ADD, true),
        ToolbarItem(ToolbarAction.DELETE, true),
        ToolbarItem(ToolbarAction.COPY, true),
        ToolbarItem(ToolbarAction.COPY_TEXT, true),
        ToolbarItem(ToolbarAction.CUT, true),
        ToolbarItem(ToolbarAction.PASTE, true),
        ToolbarItem(ToolbarAction.SPLIT, true),
        ToolbarItem(ToolbarAction.MERGE, true),
        ToolbarItem(ToolbarAction.SAVE, true),
        ToolbarItem(ToolbarAction.INSERT_BEFORE, false),
        ToolbarItem(ToolbarAction.SORT, false),
    )

    /** e.g. "UNDO:1,REDO:1,SORT:0". */
    fun encode(items: List<ToolbarItem>): String =
        items.joinToString(",") { "${it.action.name}:${if (it.onBar) 1 else 0}" }

    /**
     * Parses [encode]'s output. Unknown or repeated entries are dropped, and actions missing
     * from the saved string (e.g. added in a later version) are appended with their defaults.
     */
    fun decode(saved: String?): List<ToolbarItem> {
        if (saved.isNullOrBlank()) return DEFAULT
        val seen = mutableSetOf<ToolbarAction>()
        val items = saved.split(',').mapNotNull { token ->
            val name = token.substringBefore(':').trim()
            val action = ToolbarAction.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            if (!seen.add(action)) return@mapNotNull null
            ToolbarItem(action, token.substringAfter(':', "1").trim() != "0")
        }
        return items + DEFAULT.filter { it.action !in seen }
    }

    /** Moves the entry at [from] to [to], shifting the ones in between. */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from == to || from !in indices || to !in indices) return this
        return toMutableList().apply { add(to, removeAt(from)) }
    }
}
