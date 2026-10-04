package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PinnedSortTest {
    private fun item(path: String, dir: Boolean = false, size: Long = 1, modified: Long = 0) =
        FileItem(File(path), File(path).name, path, dir, size, modified, false, if (dir) 0 else -1)

    private val a = item("/d/a.txt")
    private val b = item("/d/b.txt")
    private val c = item("/d/c.txt")
    private val folder = item("/d/carpeta", dir = true)

    @Test
    fun pinnedGoFirstEvenBeforeFolders() {
        val sorted = Sorter.sort(listOf(a, folder, c, b), SortBy.NAME, true, setOf(c.path))
        assertEquals(listOf("c.txt", "carpeta", "a.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun pinnedKeepTheChosenOrderAmongThemselves() {
        val pins = setOf(c.path, a.path)
        assertEquals(listOf("a.txt", "c.txt", "carpeta", "b.txt"), Sorter.sort(listOf(b, c, folder, a), SortBy.NAME, true, pins).map { it.name })
        // Al invertir el orden, lo fijado sigue arriba y se invierte entre sí.
        assertEquals(
            listOf("c.txt", "a.txt", "carpeta", "b.txt"),
            Sorter.sort(listOf(b, c, folder, a), SortBy.NAME, false, pins).map { it.name })
    }

    @Test
    fun withoutPinsTheOldOrderIsUnchanged() {
        assertEquals(listOf("carpeta", "a.txt", "b.txt"), Sorter.sort(listOf(b, a, folder), SortBy.NAME, true).map { it.name })
    }

    @Test
    fun pinnedListIsValidatedInTheSettingsBackup() {
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"pinned":"/sdcard/a.txt\n/sdcard/Docs"}}"""
        assertEquals("/sdcard/a.txt\n/sdcard/Docs", SettingsBackup.parse(ok)["pinned"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(ok.replace("/sdcard/Docs", "relativa")) }
    }
}
