package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoBackupTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(path: String) =
        File(temp.root, path).apply {
            parentFile!!.mkdirs()
            writeText(path)
        }

    @Test
    fun keepsThePathInsideTheStorage() {
        val root = "/storage/emulated/0"
        assertEquals("DCIM/Camera/a.jpg", AutoBackup.remoteRelative("$root/DCIM/Camera/a.jpg", root))
        assertEquals("1234-ABCD/Música/b.mp3", AutoBackup.remoteRelative("/storage/1234-ABCD/Música/b.mp3", root))
    }

    @Test
    fun plansOnlyNewOrChangedFilesOldestFirst() {
        val root = "/storage/emulated/0"
        val old = AutoBackup.LocalFile("$root/DCIM/a.jpg", 10, 100)
        val changed = AutoBackup.LocalFile("$root/DCIM/b.jpg", 20, 300)
        val fresh = AutoBackup.LocalFile("$root/DCIM/c.jpg", 30, 200)
        val hidden = AutoBackup.LocalFile("$root/DCIM/.oculta/d.jpg", 5, 50)
        val index = mapOf("DCIM/a.jpg" to "10:100", "DCIM/b.jpg" to "20:250")
        val plan = AutoBackup.plan(listOf(old, changed, fresh, hidden, fresh), index, root)
        assertEquals(listOf("DCIM/c.jpg", "DCIM/b.jpg"), plan.map { it.remote })
    }

    @Test
    fun collectsChosenKindsAndFoldersWithoutHiddenOnes() {
        val root = temp.root.path
        file("DCIM/Camera/foto.jpg")
        file("DCIM/Camera/video.mp4")
        file("DCIM/.thumbnails/mini.jpg")
        file("Pictures/captura.png")
        file("Music/cancion.mp3")
        file("Documentos/nota.txt")
        file("Documentos/.oculto.txt")
        val photos = AutoBackup.collect(setOf(BackupKind.PHOTOS), emptyList(), root).map { it.path.removePrefix("$root/") }
        assertEquals(setOf("DCIM/Camera/foto.jpg", "Pictures/captura.png"), photos.toSet())
        val all =
            AutoBackup.collect(
                    setOf(BackupKind.VIDEOS, BackupKind.MUSIC), listOf("$root/Documentos"), root)
                .map { it.path.removePrefix("$root/") }
        assertEquals(setOf("DCIM/Camera/video.mp4", "Music/cancion.mp3", "Documentos/nota.txt"), all.toSet())
    }

    @Test
    fun destinationFolderCannotEscape() {
        assertEquals(listOf("Copias", "Teléfono"), AutoBackup.checkFolder(" /Copias//Teléfono/ "))
        for (bad in listOf("", "/", "../fuera", "a/../b", "a\\b", "./a"))
            assertThrows(IOException::class.java) { AutoBackup.checkFolder(bad) }
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"auto_backup_folder":"Copias/Teléfono"}}"""
        assertEquals("Copias/Teléfono", SettingsBackup.parse(ok)["auto_backup_folder"])
        assertThrows(IOException::class.java) { SettingsBackup.parse(ok.replace("Copias/Teléfono", "../fuera")) }
    }
}
