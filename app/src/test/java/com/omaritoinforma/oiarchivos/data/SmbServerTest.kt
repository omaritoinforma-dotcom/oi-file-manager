package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Prueba el cliente SMB de la app contra un servidor SMB real (Samba, `scripts/smb_samba.sh`)
 * con SMB 2/3 y firma obligatoria. Se omite si no hay servidor: define OI_REMOTE_TEST_SMB_FOLDER
 * (la carpeta compartida como «datos»), OI_REMOTE_TEST_SMB_USER y OI_REMOTE_TEST_SMB_PASSWORD.
 */
class SmbServerTest {
    @get:Rule val temp = TemporaryFolder()

    private val folder = System.getenv("OI_REMOTE_TEST_SMB_FOLDER")

    private fun connection() =
        Connection(
            "smb", "SMB", Protocol.SMB, "127.0.0.1", 445,
            System.getenv("OI_REMOTE_TEST_SMB_USER") ?: "oi",
            System.getenv("OI_REMOTE_TEST_SMB_PASSWORD").orEmpty(), root = "/datos")

    private fun payload() = ByteArray(2_500_000) { ((it * 17 + 3) % 251).toByte() }

    @Test
    fun smbClientListsReadsWritesRenamesAndDeletesOnARealServer() {
        assumeTrue("Sin servidor SMB de prueba", folder != null)
        val disk = File(folder!!)
        val base = "/datos/prueba-${System.nanoTime()}"
        RemoteFiles.connect(connection()).use { fs ->
            fs.mkdir("/datos", base.substringAfterLast('/'))
            assertTrue(File(disk, base.substringAfterLast('/')).isDirectory)
            val data = payload()
            val sub = fs.mkdir(base, "sub carpeta")
            fs.write(sub, "año.bin", data.inputStream(), data.size.toLong())
            assertArrayEquals(data, File(disk, "${base.substringAfterLast('/')}/sub carpeta/año.bin").readBytes())
            // Lo que se escribió se puede leer de vuelta por SMB y sale en el listado con su tamaño.
            val listed = fs.list(sub)
            assertEquals(listOf("año.bin"), listed.map { it.name })
            assertEquals(data.size.toLong(), listed.single().size)
            assertArrayEquals(data, fs.read(listed.single().path).use { it.readBytes() })
            // Renombrar y borrar.
            fs.rename(listed.single(), "otro nombre.bin")
            assertTrue(File(disk, "${base.substringAfterLast('/')}/sub carpeta/otro nombre.bin").exists())
            fs.delete(fs.list(sub).single())
            assertTrue(fs.list(sub).isEmpty())
            // Una subida nunca deja el archivo temporal a medias.
            val leftovers = File(disk, base.substringAfterLast('/')).walkTopDown().filter { it.name.startsWith(".oi-") }.toList()
            assertTrue("Archivos temporales: $leftovers", leftovers.isEmpty())
        }
    }

    @Test
    fun aWrongPasswordIsRejected() {
        assumeTrue("Sin servidor SMB de prueba", folder != null)
        val wrong = connection().copy(secret = "no-es-la-clave")
        val error = runCatching { RemoteFiles.connect(wrong).use { it.list("/datos") } }.exceptionOrNull()
        assertNotNull("Debe fallar con una contraseña incorrecta", error)
    }
}
