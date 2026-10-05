package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompressionLevelTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source() =
        File(temp.root, "datos.txt").apply { writeText("linea repetida de prueba\n".repeat(20000)) }

    private fun zip(name: String, level: CompressionLevel, password: String = ""): File {
        val target = File(temp.root, name)
        runBlocking { ArchiveTools.compress(listOf(source()), target, password, level) {} }
        return target
    }

    @Test
    fun storeKeepsTheBytesAndMaximumShrinksThem() {
        val stored = zip("sin.zip", CompressionLevel.STORE)
        val max = zip("max.zip", CompressionLevel.MAXIMUM)
        ZipFile(stored).use { z ->
            val e = z.getEntry("datos.txt")
            assertEquals(ZipEntry.STORED, e.method)
            assertEquals(e.size, e.compressedSize)
        }
        ZipFile(max).use { z -> assertEquals(ZipEntry.DEFLATED, z.getEntry("datos.txt").method) }
        assertTrue("Sin compresión no debe achicar", stored.length() > 400_000)
        assertTrue("Máxima debe achicar mucho", max.length() < 20_000)
    }

    @Test
    fun fasterLevelsAreNeverSmallerThanMaximum() {
        val fast = zip("rapida.zip", CompressionLevel.FAST)
        val normal = zip("normal.zip", CompressionLevel.NORMAL)
        val max = zip("max.zip", CompressionLevel.MAXIMUM)
        assertTrue(fast.length() >= max.length())
        assertTrue(normal.length() >= max.length())
    }

    @Test
    fun theContentSurvivesEveryLevelAndEncryption() {
        for (level in CompressionLevel.entries) {
            val file = zip("n-${level.name}.zip", level)
            ZipFile(file).use { z ->
                assertEquals(source().readText(), z.getInputStream(z.getEntry("datos.txt")).readBytes().decodeToString())
            }
        }
        // Con contraseña y sin comprimir el archivo se crea y se puede listar.
        val secret = zip("clave.zip", CompressionLevel.STORE, password = "clave123")
        assertEquals(listOf("datos.txt"), ArchiveTools.list(secret, "clave123").map { it.name })
    }

    @Test
    fun tarGzHonoursTheLevel() {
        fun tgz(level: CompressionLevel): File {
            val target = File(temp.root, "x-${level.name}.tar.gz")
            runBlocking { ArchiveTools.compress(listOf(source()), target, "", level) {} }
            return target
        }
        assertTrue(tgz(CompressionLevel.STORE).length() > tgz(CompressionLevel.MAXIMUM).length())
    }
}
