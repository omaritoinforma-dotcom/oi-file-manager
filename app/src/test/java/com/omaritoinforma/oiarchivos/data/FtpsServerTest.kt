package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Prueba el cliente FTPS de la app, con TLS implícito (rclone) y explícito (pyftpdlib), contra
 * servidores FTP reales con certificado de prueba (`scripts/remote_servers.py`). El cliente valida
 * el certificado y el nombre del servidor, así que la prueba arranca con un almacén de confianza que
 * incluye el del servidor (OI_REMOTE_TEST_TRUSTSTORE). Cada caso se omite si falta su servidor.
 */
class FtpsServerTest {
    @get:Rule val temp = TemporaryFolder()

    private val implicitPort = System.getenv("OI_REMOTE_TEST_FTPS_IMPLICIT_PORT")?.toIntOrNull()
    private val explicitPort = System.getenv("OI_REMOTE_TEST_FTPS_PORT")?.toIntOrNull()
    private val disk = System.getenv("OI_REMOTE_TEST_ROOT")?.let(::File)

    private fun connection(
        protocol: Protocol,
        host: String = "127.0.0.1",
        password: String = System.getenv("OI_REMOTE_TEST_PASSWORD").orEmpty()
    ) =
        Connection(
            "ftps", protocol.label, protocol, host,
            (if (protocol == Protocol.FTPS_IMPLICIT) implicitPort else explicitPort) ?: 0, "oi", password)

    private fun payload() = ByteArray(2_500_000) { ((it * 13 + 5) % 251).toByte() }

    @Test
    fun implicitFtpsWorksOnARealServer() {
        assumeTrue("Sin servidor FTPS implícito de prueba", implicitPort != null && disk != null)
        exercise(Protocol.FTPS_IMPLICIT)
    }

    @Test
    fun explicitFtpsWorksOnARealServer() {
        assumeTrue("Sin servidor FTPS explícito de prueba", explicitPort != null && disk != null)
        exercise(Protocol.FTPS)
    }

    private fun exercise(protocol: Protocol) {
        val name = "ftps-${System.nanoTime()}"
        RemoteFiles.connect(connection(protocol)).use { fs ->
            fs.mkdir("/", name)
            assertTrue(File(disk!!, name).isDirectory)
            val data = payload()
            fs.write("/$name", "año.bin", data.inputStream(), data.size.toLong())
            assertArrayEquals(data, File(disk, "$name/año.bin").readBytes())
            val listed = fs.list("/$name")
            assertEquals(listOf("año.bin"), listed.map { it.name })
            assertArrayEquals(data, fs.read(listed.single().path).use { it.readBytes() })
            // Reanudar una lectura por bytes (REST) también funciona dentro de TLS.
            val resumed = fs.readFrom(listed.single().path, 1_000_000)?.use { it.readBytes() }
            assertNotNull(resumed)
            assertArrayEquals(data.copyOfRange(1_000_000, data.size), resumed)
            fs.rename(listed.single(), "otro.bin")
            assertTrue(File(disk, "$name/otro.bin").exists())
            fs.delete(fs.list("/$name").single())
            assertTrue(fs.list("/$name").isEmpty())
        }
    }

    @Test
    fun theCertificateNameIsChecked() {
        assumeTrue("Sin servidor FTPS implícito de prueba", implicitPort != null)
        // El certificado de prueba es para «localhost» y 127.0.0.1; con otro nombre debe rechazarse.
        val error = runCatching { RemoteFiles.connect(connection(Protocol.FTPS_IMPLICIT, host = "127.0.0.2")).use { it.list("/") } }.exceptionOrNull()
        assertNotNull("Un servidor con un certificado que no es el suyo debe rechazarse", error)
    }

    @Test
    fun aWrongPasswordIsRejected() {
        assumeTrue("Sin servidor FTPS implícito de prueba", implicitPort != null)
        assertNotNull(runCatching { RemoteFiles.connect(connection(Protocol.FTPS_IMPLICIT, password = "no-es")).use { it.list("/") } }.exceptionOrNull())
    }
}
