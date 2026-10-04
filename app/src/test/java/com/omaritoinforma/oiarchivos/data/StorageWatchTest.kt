package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class StorageWatchTest {
    private val mb = 1024L * 1024L

    @Test
    fun lowSpaceWarnsOnceUntilSpaceRecovers() {
        // Por encima del umbral: nada.
        assertEquals(StorageWatch.SpaceCheck(false, false), StorageWatch.checkSpace(2000 * mb, 1024, false))
        // Baja del umbral: un aviso.
        assertEquals(StorageWatch.SpaceCheck(true, true), StorageWatch.checkSpace(900 * mb, 1024, false))
        // Sigue bajo: no se repite.
        assertEquals(StorageWatch.SpaceCheck(false, true), StorageWatch.checkSpace(800 * mb, 1024, true))
        // Apenas por encima (menos del 10 %): todavía no se rearma.
        assertEquals(StorageWatch.SpaceCheck(false, true), StorageWatch.checkSpace(1050 * mb, 1024, true))
        // Se recupera: se puede volver a avisar.
        assertEquals(StorageWatch.SpaceCheck(false, false), StorageWatch.checkSpace(1200 * mb, 1024, true))
    }

    @Test
    fun newFilesKeepOnlyChosenKindsAndVisibleFolders() {
        val rows =
            listOf(
                "/sdcard/DCIM/Camera/foto.jpg" to 10L,
                "/sdcard/Download/app.apk" to 30L,
                "/sdcard/Music/cancion.mp3" to 20L,
                "/sdcard/.oculta/foto.jpg" to 40L,
                "/sdcard/Android/data/com.x/cache.jpg" to 50L,
                "/sdcard/Download/nota.txt" to 60L,
                "/sdcard/Download/app.apk" to 30L,
            )
        assertEquals(
            listOf("/sdcard/Download/app.apk", "/sdcard/DCIM/Camera/foto.jpg"),
            StorageWatch.pickNew(rows, setOf(NewFileKind.IMAGES, NewFileKind.APK)))
        assertEquals(
            listOf("/sdcard/Download/nota.txt"),
            StorageWatch.pickNew(rows, setOf(NewFileKind.DOCUMENTS)))
    }

    @Test
    fun backupAcceptsOnlyKnownKinds() {
        val json =
            """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"new_files_kinds":"IMAGES,APK","low_space_mb":2048}}"""
        val parsed = SettingsBackup.parse(json)
        assertEquals("IMAGES,APK", parsed["new_files_kinds"])
        assertEquals(2048, parsed["low_space_mb"])
        val bad = json.replace("IMAGES,APK", "IMAGES,BORRAR_TODO")
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(bad) }
    }
}
