package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class DrawerLayoutTest {
    @Test
    fun withoutSettingsTheFactoryOrderIsKept() {
        assertEquals(DrawerEntry.entries.toList(), DrawerLayout.order(emptyList()))
        assertEquals(DrawerEntry.entries.toList(), DrawerLayout.visible(emptyList(), emptySet()))
    }

    @Test
    fun storedOrderComesFirstAndNewEntriesAreAppended() {
        val order = DrawerLayout.order(listOf("HISTORY", "TRASH", "NO_EXISTE", "HISTORY"))
        assertEquals(listOf(DrawerEntry.HISTORY, DrawerEntry.TRASH), order.take(2))
        assertEquals(DrawerEntry.entries.size, order.size)
        assertEquals(order.size, order.toSet().size)
    }

    @Test
    fun hiddenEntriesDisappearButKeepTheirPlace() {
        val visible = DrawerLayout.visible(listOf("TRASH", "APPS"), setOf("TRASH", "INVENTADA"))
        assertFalse(DrawerEntry.TRASH in visible)
        assertEquals(DrawerEntry.APPS, visible.first())
        assertEquals(DrawerEntry.entries.size - 1, visible.size)
    }

    @Test
    fun movingStopsAtTheEnds() {
        val down = DrawerLayout.move(emptyList(), DrawerEntry.DOWNLOADS, 1)
        assertEquals(listOf("ROOT_DIR", "DOWNLOADS"), down.take(2))
        assertEquals(DrawerLayout.order(emptyList()).map { it.name }, DrawerLayout.move(emptyList(), DrawerEntry.DOWNLOADS, -1))
        assertEquals(DrawerLayout.order(emptyList()).map { it.name }, DrawerLayout.move(emptyList(), DrawerEntry.ROOT_TOOLS, 1))
    }

    @Test
    fun theBackupOnlyAcceptsKnownEntries() {
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"drawer_order":"HISTORY,TRASH","drawer_hidden":"APPS"}}"""
        assertEquals("HISTORY,TRASH", SettingsBackup.parse(ok)["drawer_order"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(ok.replace("APPS", "AJUSTES")) }
    }
}
