package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El servidor FTP del teléfono contra un cliente FTP real (commons-net): modos pasivo y activo,
 * codificación de los nombres, puerto fijo y que no se deje usar para atacar a un tercero.
 */
class LocalFtpTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private val servers = mutableListOf<LocalFtp>()
    private val clients = mutableListOf<FTPClient>()

    @Before
    fun setUp() {
        root = temp.newFolder("compartida").canonicalFile
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { if (it.isConnected) it.disconnect() } }
        servers.forEach { runCatching { it.close() } }
    }

    private fun server(port: Int = 0, charset: java.nio.charset.Charset = Charsets.UTF_8): LocalFtp =
        LocalFtp(root, "127.0.0.1", "clave", port, charset).apply {
            start()
            servers += this
        }

    private fun client(server: LocalFtp, encoding: String = "UTF-8"): FTPClient =
        FTPClient().apply {
            controlEncoding = encoding
            connectTimeout = 5000
            defaultTimeout = 10000
            connect("127.0.0.1", server.port)
            assertTrue("login", login("oi", "clave"))
            setFileType(FTP.BINARY_FILE_TYPE)
            clients += this
        }

    @Test
    fun passiveListsUploadsAndDownloads() {
        File(root, "hola.txt").writeText("hola")
        val ftp = client(server())
        ftp.enterLocalPassiveMode()
        assertEquals(listOf("hola.txt"), ftp.listNames().toList())
        assertTrue(ftp.storeFile("subido.bin", ByteArrayInputStream(ByteArray(70_000) { it.toByte() })))
        assertArrayEquals(ByteArray(70_000) { it.toByte() }, File(root, "subido.bin").readBytes())
        val out = java.io.ByteArrayOutputStream()
        assertTrue(ftp.retrieveFile("hola.txt", out))
        assertEquals("hola", out.toString("UTF-8"))
    }

    @Test
    fun activeModeListsUploadsAndDownloads() {
        File(root, "hola.txt").writeText("hola activo")
        val ftp = client(server())
        ftp.enterLocalActiveMode()
        assertEquals(listOf("hola.txt"), ftp.listNames().toList())
        val out = java.io.ByteArrayOutputStream()
        assertTrue("RETR activo: ${ftp.replyString}", ftp.retrieveFile("hola.txt", out))
        assertEquals("hola activo", out.toString("UTF-8"))
        assertTrue("STOR activo: ${ftp.replyString}", ftp.storeFile("nuevo.txt", ByteArrayInputStream("subido".toByteArray())))
        assertEquals("subido", File(root, "nuevo.txt").readText())
        // Cada transferencia pide su PORT: una segunda lista también funciona.
        assertEquals(setOf("hola.txt", "nuevo.txt"), ftp.listNames().toSet())
    }

    @Test
    fun activeModeRefusesToConnectToAnotherAddressOrALowPort() {
        val ftp = client(server())
        // Otra dirección: sería usar el servidor para atacar a un tercero.
        assertEquals(504, ftp.sendCommand("PORT 10,1,2,3,200,10"))
        // Puerto bajo en la propia dirección.
        assertEquals(504, ftp.sendCommand("PORT 127,0,0,1,0,21"))
        assertEquals(504, ftp.sendCommand("EPRT |1|8.8.8.8|50000|"))
        assertEquals(504, ftp.sendCommand("EPRT |1|127.0.0.1|80|"))
        // Mal escrito, o IPv6.
        assertEquals(501, ftp.sendCommand("PORT 1,2,3"))
        assertEquals(501, ftp.sendCommand("PORT 127,0,0,1,300,10"))
        assertEquals(501, ftp.sendCommand("EPRT"))
        assertEquals(522, ftp.sendCommand("EPRT |2|::1|50000|"))
        // Sin un PORT o PASV válido no hay datos.
        assertEquals(550, ftp.sendCommand("LIST"))
        // Lo válido sigue funcionando después de los rechazos.
        assertEquals(200, ftp.sendCommand("PORT 127,0,0,1,200,10"))
    }

    /** Pide un archivo en modo activo a mano y devuelve lo que el servidor envió a nuestro puerto. */
    private fun activeDownload(announce: (Int) -> String): String {
        File(root, "dato.txt").writeText("contenido")
        val server = server()
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val control = Socket("127.0.0.1", server.port)
            control.soTimeout = 10000
            val reader = control.getInputStream().bufferedReader()
            val writer = control.getOutputStream().bufferedWriter()
            fun send(line: String): String {
                writer.write("$line\r\n")
                writer.flush()
                return reader.readLine()
            }
            assertTrue(reader.readLine().startsWith("220"))
            assertTrue(send("USER oi").startsWith("331"))
            assertTrue(send("PASS clave").startsWith("230"))
            assertTrue(send(announce(listener.localPort)).startsWith("200"))
            writer.write("RETR dato.txt\r\n")
            writer.flush()
            // El servidor conecta con nosotros, y no al revés.
            listener.soTimeout = 10000
            val data = listener.accept()
            val received = data.getInputStream().bufferedReader().readText()
            assertTrue(reader.readLine().startsWith("150"))
            assertTrue(reader.readLine().startsWith("226"))
            control.close()
            return received
        } finally {
            listener.close()
        }
    }

    @Test
    fun portMakesTheServerConnectBackToTheClientsPort() {
        assertEquals("contenido", activeDownload { p -> "PORT 127,0,0,1,${p / 256},${p % 256}" })
    }

    @Test
    fun eprtDoesTheSameWithTheExtendedCommand() {
        assertEquals("contenido", activeDownload { p -> "EPRT |1|127.0.0.1|$p|" })
    }

    @Test
    fun namesTravelInTheChosenEncoding() {
        File(root, "año-ñandú.txt").writeText("x")
        // Servidor en ISO-8859-1 y cliente en ISO-8859-1: el nombre llega bien.
        val latin = server(charset = Charsets.ISO_8859_1)
        val ok = client(latin, "ISO-8859-1")
        ok.enterLocalPassiveMode()
        assertEquals(listOf("año-ñandú.txt"), ok.listNames().toList())
        // Y se puede descargar por ese nombre.
        val out = java.io.ByteArrayOutputStream()
        assertTrue(ok.retrieveFile("año-ñandú.txt", out))
        // Cliente en UTF-8 contra servidor en ISO-8859-1: los acentos salen rotos, como pasaría de verdad.
        val broken = client(latin, "UTF-8")
        broken.enterLocalPassiveMode()
        assertNotEquals(listOf("año-ñandú.txt"), broken.listNames().toList())
        // Servidor UTF-8 con cliente UTF-8: bien.
        val utf = client(server(), "UTF-8")
        utf.enterLocalPassiveMode()
        assertEquals(listOf("año-ñandú.txt"), utf.listNames().toList())
    }

    @Test
    fun gbkNamesRoundTrip() {
        val gbk = java.nio.charset.Charset.forName("GBK")
        File(root, "文件.txt").writeText("x")
        val ftp = client(server(charset = gbk), "GBK")
        ftp.enterLocalPassiveMode()
        assertEquals(listOf("文件.txt"), ftp.listNames().toList())
        assertTrue(ftp.storeFile("新建.txt", ByteArrayInputStream("y".toByteArray())))
        assertTrue(File(root, "新建.txt").isFile)
    }

    @Test
    fun utf8IsOnlyAdvertisedWhenTheServerReallyUsesIt() {
        val utf = client(server())
        ftpFeatures(utf).let { assertTrue(it.contains("UTF8")); assertTrue(it.contains("EPRT")) }
        assertEquals(200, utf.sendCommand("OPTS UTF8 ON"))
        val latin = client(server(charset = Charsets.ISO_8859_1), "ISO-8859-1")
        assertFalse(ftpFeatures(latin).contains("UTF8"))
        assertEquals(504, latin.sendCommand("OPTS UTF8 ON"))
    }

    private fun ftpFeatures(ftp: FTPClient): String {
        ftp.sendCommand("FEAT")
        return ftp.replyString
    }

    @Test
    fun aFixedPortIsUsedAndABusyOneIsReported() {
        val free = ServerSocket(0).use { it.localPort }
        val ftp = client(server(port = free))
        assertEquals(free, ftp.remotePort)
        try {
            server(port = free)
            fail("El segundo servidor no debería poder usar el mismo puerto")
        } catch (e: BindException) {
            // ShareService lo convierte en «El puerto … está ocupado».
        }
    }

    @Test
    fun theServerStaysInsideItsFolderAndNeedsTheRightPassword() {
        val s = server()
        val wrong = FTPClient().apply {
            connect("127.0.0.1", s.port)
            clients += this
        }
        assertFalse(wrong.login("oi", "mala"))
        val ftp = client(s)
        ftp.enterLocalActiveMode()
        assertFalse("fuera de la carpeta", ftp.retrieveFile("../fuera.txt", java.io.ByteArrayOutputStream()))
        ftp.changeWorkingDirectory("/")
        assertFalse(ftp.changeWorkingDirectory("../.."))
    }
}
