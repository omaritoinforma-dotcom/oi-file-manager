package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun lists() = Playlists(File(temp.root, "listas"))

    @Test
    fun createAddMoveRemoveRenameDelete() {
        val p = lists()
        p.create("Viaje")
        assertEquals(listOf("Viaje"), p.names())
        assertEquals(2, p.add("Viaje", listOf("/m/a.mp3", "/m/b.mp3")))
        // Lo repetido y lo que no es una ruta absoluta no se añade.
        assertEquals(1, p.add("Viaje", listOf("/m/b.mp3", "relativa.mp3", "/m/c.mp3", "/m/c.mp3")))
        assertEquals(listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3"), p.read("Viaje").tracks)
        p.move("Viaje", 2, -1)
        assertEquals(listOf("/m/a.mp3", "/m/c.mp3", "/m/b.mp3"), p.read("Viaje").tracks)
        p.move("Viaje", 0, -1) // fuera de rango: no cambia
        assertEquals(listOf("/m/a.mp3", "/m/c.mp3", "/m/b.mp3"), p.read("Viaje").tracks)
        p.remove("Viaje", "/m/c.mp3")
        assertEquals(listOf("/m/a.mp3", "/m/b.mp3"), p.read("Viaje").tracks)
        p.rename("Viaje", "Coche")
        assertEquals(listOf("Coche"), p.names())
        assertEquals(listOf("/m/a.mp3", "/m/b.mp3"), p.read("Coche").tracks)
        p.delete("Coche")
        assertTrue(p.names().isEmpty())
    }

    @Test
    fun namesCannotEscapeTheFolder() {
        val p = lists()
        for (bad in listOf("", "  ", "../fuera", "a/b", ".oculta", "x".repeat(81)))
            assertThrows(IOException::class.java) { p.create(bad) }
        p.create("Rock")
        assertThrows(IOException::class.java) { p.create(" Rock ") }
        assertFalse(File(temp.root, "fuera.m3u8").exists())
    }

    @Test
    fun savesStandardM3uAndReadsOtherPlayersFiles() {
        val p = lists()
        p.add("Mía", listOf("/sdcard/Music/canción uno.mp3"))
        val text = File(temp.root, "listas/Mía.m3u8").readText()
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXTINF:-1,canción uno\n/sdcard/Music/canción uno.mp3\n"))
        val foreign = "﻿#EXTM3U\r\n#EXTINF:10,X\r\n/a/x.mp3\r\nhttp://no/se/usa.mp3\r\n\r\n/a/y.ogg\r\n"
        assertEquals(listOf("/a/x.mp3", "/a/y.ogg"), Playlists.parse(foreign))
    }
}
