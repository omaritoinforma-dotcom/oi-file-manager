package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class ToolbarLayoutTest {
    private val factory = listOf("COPY", "CUT", "DELETE", "RENAME")

    @Test
    fun theFactoryBarIsCopyCutDeleteRename() {
        assertEquals(factory, ToolbarLayout.defaultNames)
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.actions(factory))
    }

    @Test
    fun unknownAndRepeatedNamesAreIgnoredAndTheBarIsCapped() {
        assertEquals(
            listOf(ToolbarAction.SHARE, ToolbarAction.COPY),
            ToolbarLayout.actions(listOf("SHARE", "NO_EXISTE", "COPY", "SHARE")))
        val many = ToolbarAction.entries.map { it.name }
        assertEquals(ToolbarLayout.MAX, ToolbarLayout.actions(many).size)
    }

    @Test
    fun anEmptyBarIsAllowedBecauseMoreKeepsEverything() {
        assertTrue(ToolbarLayout.actions(emptyList()).isEmpty())
    }

    @Test
    fun togglingAddsAtTheEndAndRemoves() {
        assertEquals(factory + "SHARE", ToolbarLayout.toggle(factory, ToolbarAction.SHARE))
        assertEquals(listOf("COPY", "DELETE", "RENAME"), ToolbarLayout.toggle(factory, ToolbarAction.CUT))
    }

    @Test
    fun aFullBarDoesNotTakeAnotherButton() {
        val full = factory + "SHARE"
        assertEquals(ToolbarLayout.MAX, full.size)
        assertEquals(full, ToolbarLayout.toggle(full, ToolbarAction.PIN))
        // Pero se puede quitar uno.
        assertEquals(factory, ToolbarLayout.toggle(full, ToolbarAction.SHARE))
    }

    @Test
    fun movingStopsAtTheEndsAndIgnoresButtonsNotInTheBar() {
        assertEquals(listOf("CUT", "COPY", "DELETE", "RENAME"), ToolbarLayout.move(factory, ToolbarAction.COPY, 1))
        assertEquals(factory, ToolbarLayout.move(factory, ToolbarAction.COPY, -1))
        assertEquals(factory, ToolbarLayout.move(factory, ToolbarAction.RENAME, 1))
        assertEquals(factory, ToolbarLayout.move(factory, ToolbarAction.PIN, 1))
    }

    @Test
    fun theBackupKeepsOrderAndAcceptsAnEmptyBarButNotUnknownNames() {
        fun backup(value: String) =
            """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"toolbar_actions":"$value"}}"""
        assertEquals("SHARE,COPY", SettingsBackup.parse(backup("SHARE,COPY"))["toolbar_actions"])
        assertEquals("", SettingsBackup.parse(backup(""))["toolbar_actions"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(backup("COPY,PEGAR")) }
    }
}
