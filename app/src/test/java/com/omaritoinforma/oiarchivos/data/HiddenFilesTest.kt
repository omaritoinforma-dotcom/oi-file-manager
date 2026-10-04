package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HiddenFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun namesGainAndLoseTheLeadingDot() {
        assertEquals(".foto.jpg", HiddenFiles.hiddenName("foto.jpg"))
        assertNull(HiddenFiles.hiddenName(".ya-oculto"))
        assertNull(HiddenFiles.hiddenName(""))
        assertEquals("foto.jpg", HiddenFiles.shownName(".foto.jpg"))
        assertNull(HiddenFiles.shownName("foto.jpg"))
        assertNull(HiddenFiles.shownName("."))
    }

    @Test
    fun hideAndShowRenameFilesAndFolders() {
        val file = File(temp.root, "secreto.txt").apply { writeText("datos") }
        val folder = File(temp.root, "privada").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        val hidden = HiddenFiles.hide(file)
        val hiddenFolder = HiddenFiles.hide(folder)
        assertEquals(".secreto.txt", hidden.name)
        assertFalse(file.exists())
        assertEquals("datos", hidden.readText())
        assertTrue(File(hiddenFolder, "a.txt").exists())
        assertEquals("secreto.txt", HiddenFiles.show(hidden).name)
        assertEquals("datos", file.readText())
        assertEquals("privada", HiddenFiles.show(hiddenFolder).name)
    }

    @Test
    fun neverReplacesAnExistingFileAndRejectsWrongStates() {
        val file = File(temp.root, "a.txt").apply { writeText("uno") }
        val taken = File(temp.root, ".a.txt").apply { writeText("otro") }
        assertThrows(IOException::class.java) { HiddenFiles.hide(file) }
        assertEquals("uno", file.readText())
        assertEquals("otro", taken.readText())
        assertThrows(IOException::class.java) { HiddenFiles.hide(taken) } // ya está oculto
        assertThrows(IOException::class.java) { HiddenFiles.show(file) } // no está oculto
        // Al mostrar, si ya existe el nombre normal, tampoco se pisa.
        assertThrows(IOException::class.java) { HiddenFiles.show(taken) }
        assertEquals("otro", taken.readText())
    }
}
