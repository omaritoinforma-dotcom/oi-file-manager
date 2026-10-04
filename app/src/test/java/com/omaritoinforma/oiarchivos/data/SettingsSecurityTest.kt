package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contraseña de la app y copia de ajustes (Ajustes → Seguridad). */
class SettingsSecurityTest {
    @Test
    fun passwordIsStoredAsSaltedHashAndChecked() {
        val stored = AppLock.encode("clave ñ 123", iterations = 1000)
        assertFalse("No debe guardarse la contraseña en claro", "clave" in stored)
        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"))
        assertTrue(AppLock.matches("clave ñ 123", stored))
        assertFalse(AppLock.matches("clave ñ 124", stored))
        assertFalse(AppLock.matches("", stored))
        // Misma contraseña, distinta sal: no se puede saber si dos personas usan la misma.
        assertNotEquals(stored, AppLock.encode("clave ñ 123", iterations = 1000))
        // La configuración por defecto usa muchas iteraciones.
        val strong = AppLock.encode("x")
        assertTrue(strong.split('$')[1].toInt() >= 100_000)
        assertTrue(AppLock.matches("x", strong))
    }

    @Test
    fun damagedOrForeignHashesNeverMatch() {
        val stored = AppLock.encode("clave", iterations = 1000)
        val parts = stored.split('$').toMutableList()
        val tampered = parts.also { it[3] = it[3].reversed() }.joinToString("$")
        for (bad in listOf("", "clave", "md5\$1\$a\$b", "pbkdf2-sha256\$0\$a\$b", "pbkdf2-sha256\$x\$a\$b", tampered))
            assertFalse(bad, AppLock.matches("clave", bad))
        assertThrows(IllegalArgumentException::class.java) { AppLock.encode("") }
    }

    @Test
    fun backupKeepsKnownSettingsAndNeverThePassword() {
        val values =
            mapOf(
                "theme" to "DARK",
                "grid_size" to 120,
                "show_hidden" to true,
                "start_window" to "HOME_FOLDER",
                "home_folder" to "/storage/emulated/0/Música ñ",
                "bookmarks" to "/storage/emulated/0/A\n/storage/emulated/0/B",
                "lock_hash" to AppLock.encode("secreta", iterations = 1000),
                "lock_start" to true,
                "history" to "/storage/emulated/0/privado",
                "desconocido" to "x")
        val json = SettingsBackup.export(values)
        assertFalse("La copia no debe llevar la contraseña", "pbkdf2" in json)
        assertFalse("La copia no debe llevar las protecciones", "lock_start" in json)
        assertFalse("La copia no debe llevar el historial", "privado" in json)
        val restored = SettingsBackup.parse(json)
        assertEquals(
            mapOf(
                "theme" to "DARK",
                "grid_size" to 120,
                "show_hidden" to true,
                "start_window" to "HOME_FOLDER",
                "home_folder" to "/storage/emulated/0/Música ñ",
                "bookmarks" to "/storage/emulated/0/A\n/storage/emulated/0/B"),
            restored)
    }

    @Test
    fun invalidBackupsAreRejectedWithoutPartialChanges() {
        fun backup(settings: JSONObject, version: Int = 1, format: String = SettingsBackup.FORMAT) =
            JSONObject().put("formato", format).put("version", version).put("ajustes", settings).toString()

        val bad =
            listOf(
                "no es json",
                backup(JSONObject().put("theme", "DARK"), format = "otra app"),
                backup(JSONObject().put("theme", "DARK"), version = 99),
                backup(JSONObject().put("theme", "MORADO")),
                backup(JSONObject().put("grid_size", 5000)),
                backup(JSONObject().put("show_hidden", "sí")),
                backup(JSONObject().put("download_folder", "relativa/carpeta")),
                backup(JSONObject().put("home_folder", "/a\u0000b")),
                backup(JSONObject().put("bookmarks", "/bien\nmal")))
        for (json in bad)
            assertThrows(json, IOException::class.java) { SettingsBackup.parse(json) }
        // Las claves desconocidas (de versiones futuras) se ignoran; los números se normalizan.
        assertEquals(
            mapOf("grid_size" to 100),
            SettingsBackup.parse(backup(JSONObject().put("grid_size", 100L).put("futuro", 1))))
    }
}
