package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Prueba el cliente NFS de la app contra un servidor NFS real (nfs-ganesha, versión 3). Se omite si no
 * hay servidor: define OI_REMOTE_TEST_NFS_HOST (la dirección) y OI_REMOTE_TEST_NFS_EXPORT (la ruta
 * exportada, que además es la carpeta del disco del servidor).
 */
class NfsServerTest {
    @get:Rule val temp = TemporaryFolder()

    private val host = System.getenv("OI_REMOTE_TEST_NFS_HOST")
    private val export = System.getenv("OI_REMOTE_TEST_NFS_EXPORT")

    private fun connection() =
        Connection("nfs", "NFS", Protocol.NFS, host!!, 2049, "0:0", "", root = export!!)

    private fun payload() = ByteArray(3_000_000) { ((it * 13 + 5) % 251).toByte() }

    @Test
    fun idsAreParsedAndFallBackToNobody() {
        assertEquals(1000 to 1001, NfsFs.parseIds("1000:1001"))
        assertEquals(0 to 0, NfsFs.parseIds(" 0:0 "))
        for (bad in listOf("", "usuario", "1000", "1000:", ":5", "a:b", "1:2:3", "-1:5"))
            assertEquals(bad, 65534 to 65534, NfsFs.parseIds(bad))
    }

    @Test
    fun nfsClientListsReadsWritesRenamesAndDeletesOnARealServer() {
        assumeTrue("Sin servidor NFS de prueba", host != null && export != null)
        val disk = File(export!!)
        val name = "prueba-${System.nanoTime()}"
        RemoteFiles.connect(connection()).use { fs ->
            fs.mkdir("/", name)
            assertTrue(File(disk, name).isDirectory)
            val data = payload()
            val sub = fs.mkdir("/$name", "sub carpeta")
            fs.write(sub, "año.bin", data.inputStream(), data.size.toLong())
            assertArrayEquals(data, File(disk, "$name/sub carpeta/año.bin").readBytes())
            // Lo escrito se lista con su tamaño y se lee de vuelta.
            val listed = fs.list(sub)
            assertEquals(listOf("año.bin"), listed.map { it.name })
            assertEquals(data.size.toLong(), listed.single().size)
            assertFalse(listed.single().directory)
            assertArrayEquals(data, fs.read(listed.single().path).use { it.readBytes() })
            // Leer desde un desplazamiento (reanudar una descarga).
            val offset = 1_234_567L
            assertArrayEquals(
                data.copyOfRange(offset.toInt(), data.size),
                fs.readFrom(listed.single().path, offset)!!.use { it.readBytes() })
            // Las carpetas se ven como carpetas.
            val top = fs.list("/$name")
            assertEquals(listOf("sub carpeta"), top.map { it.name })
            assertTrue(top.single().directory)
            // Sustituir un archivo existente (el renombrado de NFS lo reemplaza de una vez).
            val smaller = ByteArray(1000) { 7 }
            fs.write(sub, "año.bin", smaller.inputStream(), smaller.size.toLong())
            assertArrayEquals(smaller, File(disk, "$name/sub carpeta/año.bin").readBytes())
            // Renombrar y borrar.
            fs.rename(fs.list(sub).single(), "otro nombre.bin")
            assertTrue(File(disk, "$name/sub carpeta/otro nombre.bin").exists())
            assertFalse(File(disk, "$name/sub carpeta/año.bin").exists())
            fs.delete(fs.list(sub).single())
            assertTrue(fs.list(sub).isEmpty())
            // Ni una subida deja el archivo temporal.
            val leftovers = File(disk, name).walkTopDown().filter { it.name.startsWith(".oi-") }.toList()
            assertTrue("Archivos temporales: $leftovers", leftovers.isEmpty())
            // Borrar una carpeta vacía, y al final la de la prueba.
            fs.delete(fs.list("/$name").single())
            assertTrue(fs.list("/$name").isEmpty())
            fs.delete(RemoteEntry("/$name", name, true, 0))
            assertFalse(File(disk, name).exists())
        }
    }

    @Test
    fun anExportThatDoesNotExistIsRejected() {
        assumeTrue("Sin servidor NFS de prueba", host != null && export != null)
        val wrong = connection().copy(root = "/no/existe/esta/exportacion")
        val error = runCatching { RemoteFiles.connect(wrong).use { it.list("/") } }.exceptionOrNull()
        assertNotNull("Debe fallar con una exportación inexistente", error)
    }
}
