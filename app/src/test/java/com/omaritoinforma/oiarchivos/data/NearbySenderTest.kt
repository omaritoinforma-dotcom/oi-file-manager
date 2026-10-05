package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NearbySenderTest {
    @get:Rule val temp = TemporaryFolder()
    private val receivers = ArrayList<NearbyReceiver>()

    private fun receiver(
        destination: File,
        accept: Boolean,
        offers: MutableList<Nearby.Offer> = ArrayList(),
        received: MutableList<File> = ArrayList()
    ): Nearby.Peer {
        val r =
            NearbyReceiver(
                destination,
                "Teléfono de Ana",
                port = 0,
                decide = {
                    offers += it
                    accept
                },
                onReceived = { received += it })
        r.start(5000, true)
        receivers += r
        return Nearby.Peer("127.0.0.1", r.listeningPort, "Teléfono de Ana")
    }

    @After fun stop() = receivers.forEach { it.stop() }

    private fun request(url: String, method: String, body: ByteArray): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to text
    }

    @Test
    fun acceptedFilesArriveVerifiedWithoutReplacingExistingOnes() = runBlocking {
        val dest = temp.newFolder("Recibidos")
        File(dest, "foto.bin").writeText("ya estaba")
        val offers = Collections.synchronizedList(ArrayList<Nearby.Offer>())
        val received = Collections.synchronizedList(ArrayList<File>())
        val peer = receiver(dest, true, offers, received)
        assertEquals("Teléfono de Ana", Nearby.hello(peer.address, peer.port)?.name)
        val source = temp.newFolder("origen")
        val big = File(source, "foto.bin").apply { writeBytes(ByteArray(2_500_000) { (it % 247).toByte() }) }
        val small = File(source, "canción ñ.txt").apply { writeText("hola") }
        val progress = ArrayList<OpProgress>()
        assertTrue(Nearby.send(peer, "Teléfono de Omar", listOf(big, small)) { progress += it })
        assertEquals("Teléfono de Omar", offers.single().from)
        assertEquals(listOf("foto.bin", "canción ñ.txt"), offers.single().files.map { it.name })
        assertEquals("ya estaba", File(dest, "foto.bin").readText())
        assertArrayEquals(big.readBytes(), File(dest, "foto (1).bin").readBytes())
        assertEquals("hola", File(dest, "canción ñ.txt").readText())
        assertEquals(2, received.size)
        assertEquals(big.length() + small.length(), progress.last().doneBytes)
        assertTrue("Sin restos temporales", dest.list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun declinedOfferSendsNothing() = runBlocking {
        val dest = temp.newFolder("Recibidos")
        val peer = receiver(dest, false)
        val file = File(temp.newFolder(), "a.txt").apply { writeText("x") }
        assertFalse(Nearby.send(peer, "Yo", listOf(file)) {})
        assertTrue(dest.list()!!.isEmpty())
    }

    @Test
    fun dangerousNamesTamperedDataAndUnacceptedUploadsAreRejected() {
        val dest = temp.newFolder("Recibidos")
        val offers = Collections.synchronizedList(ArrayList<Nearby.Offer>())
        val peer = receiver(dest, true, offers)
        val base = "http://${peer.address}:${peer.port}${Nearby.PATH}"
        val good = "contenido".toByteArray()
        val sha = Nearby.sha256(File(temp.newFolder(), "x").apply { writeBytes(good) })
        val traversal =
            Nearby.offerJson(Nearby.Offer("Atacante", listOf(Nearby.OfferedFile("../fuera.txt", 9, sha))))
        assertEquals(400, request("$base/oferta", "POST", traversal.toByteArray()).first)
        assertTrue("No se pregunta por una oferta no válida", offers.isEmpty())
        assertEquals(403, request("$base/archivo/0?token=inventado", "PUT", good).first)
        val offer = Nearby.offerJson(Nearby.Offer("Yo", listOf(Nearby.OfferedFile("a.txt", 9, sha))))
        val (code, body) = request("$base/oferta", "POST", offer.toByteArray())
        assertEquals(200, code)
        val token = org.json.JSONObject(body).getString("token")
        val tampered = "contenidO".toByteArray()
        assertEquals(400, request("$base/archivo/0?token=$token", "PUT", tampered).first)
        assertFalse(File(dest, "a.txt").exists())
        // After a failed attempt the same file can be sent again correctly.
        val retry = request("$base/archivo/0?token=$token", "PUT", good)
        assertEquals(retry.second, 200, retry.first)
        assertEquals("contenido", File(dest, "a.txt").readText())
        assertFalse(File(temp.root, "fuera.txt").exists())
        assertTrue(dest.list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun helloIgnoresServersThatAreNotOiArchivos() {
        val other = ServerSocket(0)
        Thread {
            runCatching {
                other.accept().use {
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                }
            }
        }.start()
        assertNull(Nearby.hello("127.0.0.1", other.localPort))
        other.close()
    }
}
