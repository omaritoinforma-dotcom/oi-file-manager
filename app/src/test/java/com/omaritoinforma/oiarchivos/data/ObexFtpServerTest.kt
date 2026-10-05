package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObexFtpServerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** El cliente OBEX de la app (el mismo que usa con Bluetooth) contra el servidor, por tuberías. */
    private fun <T> session(root: File, writable: Boolean, serverMtu: Int = 1024, block: (ObexFtpClient) -> T): T {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 70_000)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 70_000)
        var failure: Throwable? = null
        val server =
            thread(isDaemon = true) {
                try {
                    ObexFtpServer(root, writable, serverMtu).serve(serverIn, toClient)
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    toClient.close()
                }
            }
        try {
            return block(ObexFtpClient(clientIn, toServer) { toServer.close() })
        } finally {
            toServer.close()
            server.join(5000)
            failure?.let { if (it !is IOException) throw it }
        }
    }

    private fun shared(): File =
        tmp.newFolder("compartida").apply {
            File(this, "Fotos").mkdir()
            File(this, "Fotos/playa.jpg").writeBytes(ByteArray(5000) { (it * 31).toByte() })
            File(this, "nota & \"cita\" ñ.txt").writeText("hola")
        }

    @Test
    fun anotherDeviceBrowsesAndDownloads() {
        val root = shared()
        session(root, writable = false) { obex ->
            val top = obex.list("").associateBy { it.name }
            assertEquals(setOf("Fotos", "nota & \"cita\" ñ.txt"), top.keys)
            assertTrue(top.getValue("Fotos").directory)
            assertEquals(4L, top.getValue("nota & \"cita\" ñ.txt").size)
            assertEquals(listOf("playa.jpg"), obex.list("/Fotos").map { it.name })
            // 5000 bytes con paquetes de 1024: la respuesta va en varios trozos.
            assertArrayEquals(File(root, "Fotos/playa.jpg").readBytes(), obex.read("/Fotos/playa.jpg").use { it.readBytes() })
            assertEquals("hola", obex.read("/nota & \"cita\" ñ.txt").use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test
    fun readOnlySharingRefusesChanges() {
        val root = shared()
        session(root, writable = false) { obex ->
            assertThrows(IOException::class.java) { obex.write("", "nuevo.txt", "x".byteInputStream(), 1) }
            assertThrows(IOException::class.java) { obex.mkdir("", "Nueva") }
            assertThrows(IOException::class.java) { obex.delete(RemoteEntry("/Fotos/playa.jpg", "playa.jpg", false, 5000)) }
        }
        assertFalse(File(root, "nuevo.txt").exists())
        assertFalse(File(root, "Nueva").exists())
        assertTrue(File(root, "Fotos/playa.jpg").exists())
    }

    @Test
    fun withPermissionItUploadsCreatesRenamesAndDeletes() {
        val root = shared()
        val data = ByteArray(7000) { (it % 251).toByte() }
        session(root, writable = true) { obex ->
            obex.mkdir("", "Subidas")
            obex.write("/Subidas", "datos.bin", data.inputStream(), data.size.toLong())
            obex.rename(RemoteEntry("/Subidas/datos.bin", "datos.bin", false, 7000), "renombrado.bin")
            obex.delete(RemoteEntry("/nota & \"cita\" ñ.txt", "nota & \"cita\" ñ.txt", false, 4))
            assertEquals(listOf("renombrado.bin"), obex.list("/Subidas").map { it.name })
        }
        assertArrayEquals(data, File(root, "Subidas/renombrado.bin").readBytes())
        assertFalse(File(root, "nota & \"cita\" ñ.txt").exists())
        assertTrue("No quedan archivos a medias", root.walkTopDown().none { it.name.endsWith(".oi-parte") })
    }

    @Test
    fun nothingOutsideTheSharedFolderIsReachable() {
        val root = shared()
        val secret = tmp.newFile("secreto.txt").apply { writeText("privado") }
        java.nio.file.Files.createSymbolicLink(File(root, "fuera").toPath(), tmp.root.toPath())
        // Paquetes a mano: SETPATH con «..» y un GET a través del enlace simbólico.
        val out = java.io.ByteArrayOutputStream()
        val requests =
            ObexCodec.packet(0x80, byteArrayOf(0x10, 0, 0x04, 0) + ObexCodec.bytes(0x46, ObexFtpServer.FTP_TARGET)) +
                ObexCodec.packet(0x85, byteArrayOf(2, 0) + ObexCodec.name("..")) +
                ObexCodec.packet(0x85, byteArrayOf(2, 0) + ObexCodec.name("fuera")) +
                ObexCodec.packet(0x83, ObexCodec.name("fuera")) +
                ObexCodec.packet(0x85, byteArrayOf(1, 0)) + // «atrás» desde la raíz
                ObexCodec.packet(0x83, ObexCodec.name("a/b")) +
                ObexCodec.packet(0x81, ByteArray(0))
        ObexFtpServer(root, writable = true).serve(requests.inputStream(), out)
        val codes = ArrayList<Int>()
        val bytes = out.toByteArray()
        var i = 0
        while (i < bytes.size) {
            codes += bytes[i].toInt() and 255
            i += ObexCodec.ushort(bytes, i + 1)
        }
        assertEquals(
            listOf(ObexFtpServer.SUCCESS, ObexFtpServer.BAD_REQUEST, ObexFtpServer.BAD_REQUEST, ObexFtpServer.BAD_REQUEST,
                ObexFtpServer.NOT_FOUND, ObexFtpServer.BAD_REQUEST, ObexFtpServer.SUCCESS),
            codes)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("privado"))
        assertTrue(secret.exists())
    }

    @Test
    fun withoutTheFtpTargetTheConnectionIsRefused() {
        val out = java.io.ByteArrayOutputStream()
        ObexFtpServer(tmp.root, writable = false).serve(
            (ObexCodec.packet(0x80, byteArrayOf(0x10, 0, 0x04, 0)) + ObexCodec.packet(0x83, ObexCodec.name("x"))).inputStream(), out)
        val bytes = out.toByteArray()
        assertEquals(ObexFtpServer.UNAVAILABLE, bytes[0].toInt() and 255)
        assertEquals(ObexFtpServer.FORBIDDEN, bytes[ObexCodec.ushort(bytes, 1)].toInt() and 255)
    }

    @Test
    fun listingsAreReadWithoutDtdOrEntities() {
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE folder-listing SYSTEM "obex-folder-listing.dtd">
            <folder-listing version="1.0">
            <!-- <file name="comentado"/> -->
            <parent-folder/>
            <folder name='Mis &amp; fotos' modified="20240101T000000Z"/>
            <file name="a&lt;b&gt;&quot;c&apos;&#241;&#x4E2D;.txt" size="12"/>
            </folder-listing>
            """.trimIndent()
        assertEquals(
            listOf(ObexFolderListing.Item("Mis & fotos", true, -1), ObexFolderListing.Item("a<b>\"c'ñ中.txt", false, 12)),
            ObexFolderListing.parse(xml))
        assertThrows(IOException::class.java) { ObexFolderListing.parse("<!DOCTYPE x [<!ENTITY e \"boom\">]><file name=\"&e;\"/>") }
        // Lo que genera el servidor se lee igual.
        val dir = shared()
        assertEquals(setOf("Fotos", "nota & \"cita\" ñ.txt"), ObexFolderListing.parse(ObexFtpServer.folderListing(dir, true)).map { it.name }.toSet())
    }
}
