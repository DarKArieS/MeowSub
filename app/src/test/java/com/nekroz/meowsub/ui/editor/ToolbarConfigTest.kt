package com.nekroz.meowsub.ui.editor

import com.nekroz.meowsub.ui.editor.ToolbarConfig.moved
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarConfigTest {

    @Test
    fun roundTrips() {
        val custom = ToolbarConfig.DEFAULT.reversed().mapIndexed { i, item -> item.copy(onBar = i % 2 == 0) }
        assertEquals(custom, ToolbarConfig.decode(ToolbarConfig.encode(custom)))
    }

    @Test
    fun emptyOrMissingGivesDefault() {
        assertEquals(ToolbarConfig.DEFAULT, ToolbarConfig.decode(null))
        assertEquals(ToolbarConfig.DEFAULT, ToolbarConfig.decode(""))
    }

    @Test
    fun dropsUnknownAndDuplicatesAndAppendsMissing() {
        val items = ToolbarConfig.decode("SAVE:0,BOGUS:1,SAVE:1,UNDO:1")
        assertEquals(ToolbarItem(ToolbarAction.SAVE, false), items[0])
        assertEquals(ToolbarItem(ToolbarAction.UNDO, true), items[1])
        assertEquals(ToolbarAction.entries.size, items.size)
        assertEquals(ToolbarAction.entries.toSet(), items.map { it.action }.toSet())
        // Missing ones keep their default visibility, e.g. SORT stays in the menu.
        assertEquals(false, items.first { it.action == ToolbarAction.SORT }.onBar)
    }

    @Test
    fun movesItems() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(list, list.moved(1, 9))
    }
}
