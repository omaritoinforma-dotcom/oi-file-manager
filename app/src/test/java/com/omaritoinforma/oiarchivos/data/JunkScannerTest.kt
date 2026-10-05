package com.omaritoinforma.oiarchivos.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JunkScannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(path: String, bytes: Int = 10) =
        File(temp.root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }

    @Test
    fun findsEachKindOfJunkAndNothingElse() = runBlocking {
        val root = temp.root
        val tmp = file("Download/borrador.tmp", 5)
        val empty = file("Documents/vacio.txt", 0)
        val emptyDir = File(root, "Viejo/Nada").apply { mkdirs() }
        val thumb = file("DCIM/.thumbnails/123.jpg", 300)
        val oldApk = file("Download/app-vieja.apk", 1000)
        val newerApk = file("Download/app-nueva.apk", 1000)
        val unknownApk = file("Download/rota.apk", 1000)
        val leftover = File(root, "Android/media/com.desinstalada.app")
        file("Android/media/com.desinstalada.app/musica/a.mp3", 2000)
        file("Android/media/com.desinstalada.app/vacio.tmp", 0)
        file("Android/media/com.instalada.app/b.mp3", 2000)
        file("Android/media/no-es-paquete/c.mp3", 2000)
        val photo = file("DCIM/Camera/foto.jpg", 500)

        val installed = mapOf("com.instalada.app" to 5L, "com.ejemplo" to 7L)
        val apks =
            mapOf(
                oldApk.name to JunkScanner.ApkInfo("com.ejemplo", 6, "Ejemplo"),
                newerApk.name to JunkScanner.ApkInfo("com.ejemplo", 8, "Ejemplo"))
        val found =
            JunkScanner.scan(root, { installed[it] }, { apks[it.name] }).associate { it.file to it.kind }

        assertEquals(JunkScanner.Kind.TEMP, found[tmp])
        assertEquals(JunkScanner.Kind.TEMP, found[empty])
        assertEquals(JunkScanner.Kind.TEMP, found[emptyDir])
        assertEquals(JunkScanner.Kind.THUMBNAILS, found[thumb])
        assertEquals("Instalada la 7: el APK de la 6 sobra", JunkScanner.Kind.INSTALLED_APK, found[oldApk])
        assertNull("Un APK más nuevo que lo instalado no es basura", found[newerApk])
        assertNull("Un APK ilegible no se propone", found[unknownApk])
        assertEquals(JunkScanner.Kind.LEFTOVERS, found[leftover])
        assertNull("Lo de dentro de un resto va con su carpeta", found.keys.firstOrNull { it.path.startsWith(leftover.path + "/") })
        assertTrue(found.keys.none { "com.instalada.app" in it.path || "no-es-paquete" in it.path })
        assertNull(found[photo])
        assertFalse("La raíz nunca se propone", root in found)
    }

    @Test
    fun leftoverSizeCountsWhatIsInside() = runBlocking {
        file("Android/obb/com.juego.borrado/main.obb", 4096)
        val items = JunkScanner.scan(temp.root, { null }, { null })
        val leftover = items.single { it.kind == JunkScanner.Kind.LEFTOVERS }
        assertEquals(4096L, leftover.size)
    }
}
