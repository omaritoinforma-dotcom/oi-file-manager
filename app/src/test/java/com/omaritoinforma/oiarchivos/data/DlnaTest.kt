package com.omaritoinforma.oiarchivos.data

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Enviar a la TV: una TV DLNA falsa recibe las órdenes y descarga el archivo como haría una real. */
class DlnaTest {
    @get:Rule val temp = TemporaryFolder()

    /** Orden recibida por la TV: acción SOAP y sus argumentos. */
    data class Command(val action: String, val args: Map<String, String>)

    private class FakeTv : NanoHTTPD("127.0.0.1", 0) {
        val commands: MutableList<Command> = Collections.synchronizedList(ArrayList())
        @Volatile var fetched: ByteArray? = null

        init {
            start(SOCKET_READ_TIMEOUT, true)
        }

        val location
            get() = "http://127.0.0.1:$listeningPort/dlna/desc.xml"

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && session.uri == "/dlna/desc.xml")
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "text/xml",
                    """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">
                      <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                      <friendlyName>TV de prueba ñ</friendlyName><serviceList>
                      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <controlURL>control/rc</controlURL></service>
                      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>control/avt</controlURL></service>
                      </serviceList></device></root>""")
            if (session.method != Method.POST || session.uri != "/dlna/control/avt")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            val length = session.headers["content-length"]!!.toInt()
            val body = ByteArray(length).also { java.io.DataInputStream(session.inputStream).readFully(it) }
            val action = session.headers["soapaction"]!!.trim('"').substringAfter('#')
            val call = Dlna.parse(String(body, Charsets.UTF_8)).documentElement
            val element =
                call.getElementsByTagNameNS("urn:schemas-upnp-org:service:AVTransport:1", action).item(0)
            val args = LinkedHashMap<String, String>()
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is org.w3c.dom.Element) args[child.localName ?: child.nodeName] = child.textContent
            }
            commands += Command(action, args)
            // Como una TV real, descarga el archivo que se le indica.
            if (action == "SetAVTransportURI") fetched = URL(args.getValue("CurrentURI")).readBytes()
            val reply =
                if (action == "GetPositionInfo")
                    "<RelTime>0:01:05</RelTime><TrackDuration>0:10:00</TrackDuration>"
                else ""
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/xml",
                """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  $reply</u:${action}Response></s:Body></s:Envelope>""")
        }
    }

    /** Responde a las búsquedas SSDP como una TV, indicando dónde está su descripción. */
    private class FakeSsdp(location: String) : Thread() {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val queries: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val reply =
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        init {
            isDaemon = true
            start()
        }

        override fun run() {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { return }
                queries += String(packet.data, 0, packet.length)
                val answer = reply.toByteArray()
                socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            }
        }
    }

    private val tv = FakeTv()
    private val ssdp = FakeSsdp(tv.location)

    @After
    fun close() {
        CastSession.stop()
        StreamServer.stop()
        tv.stop()
        ssdp.socket.close()
    }

    private fun until(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    @Test
    fun searchFindsTheTvAndWhereToSendCommands() {
        val found = ArrayList<Dlna.Renderer>()
        Dlna.search(1500, InetSocketAddress("127.0.0.1", ssdp.socket.localPort)) { found += it }
        assertEquals("Una TV aunque responda a las dos búsquedas", 1, found.size)
        val renderer = found.single()
        assertEquals("TV de prueba ñ", renderer.name)
        assertEquals("http://127.0.0.1:${tv.listeningPort}/dlna/control/avt", renderer.controlUrl)
        assertEquals("127.0.0.1", renderer.host)
        val query = ssdp.queries.first()
        assertTrue(query, query.startsWith("M-SEARCH * HTTP/1.1"))
        assertTrue(query, "ST: urn:schemas-upnp-org:device:MediaRenderer:1" in query)
        // Añadir por dirección: por IP:puerto (SSDP) o por la URL de la descripción.
        assertEquals(renderer, Dlna.find("127.0.0.1:${ssdp.socket.localPort}", 1500))
        assertEquals(renderer, Dlna.find(tv.location))
    }

    @Test
    fun theTvPlaysTheFileAndObeysTheControls() {
        val data = ByteArray(2_000_000) { (it % 241).toByte() }
        val file = File(temp.root, "vídeo & prueba.mp4").apply { writeBytes(data) }
        val renderer = Dlna.describe(tv.location)
        CastSession.start(renderer, StreamServer.LocalSource(file))
        until("La TV no recibió Play") { tv.commands.any { it.action == "Play" } }
        assertArrayEquals("La TV no leyó el archivo completo", data, tv.fetched)
        val load = tv.commands.first { it.action == "SetAVTransportURI" }
        assertTrue(load.args.getValue("CurrentURI"), Regex("http://127\\.0\\.0\\.1:\\d+/s/[0-9a-f]{32}/").containsMatchIn(load.args.getValue("CurrentURI")))
        val didl = load.args.getValue("CurrentURIMetaData")
        assertTrue(didl, "<dc:title>vídeo &amp; prueba.mp4</dc:title>" in didl)
        assertTrue(didl, "<upnp:class>object.item.videoItem</upnp:class>" in didl)
        assertTrue(didl, "protocolInfo=\"http-get:*:video/mp4:*\"" in didl)
        assertEquals("1", tv.commands.first { it.action == "Play" }.args["Speed"])

        until("No se leyó la posición de la TV") { CastSession.state.value?.position == 65L }
        assertEquals(600L, CastSession.state.value?.duration)
        assertTrue(CastSession.state.value!!.playing)

        CastSession.pauseOrResume()
        until("La TV no recibió Pause") { tv.commands.any { it.action == "Pause" } }
        until("No quedó en pausa") { CastSession.state.value?.playing == false }
        CastSession.seek(30)
        until("La TV no recibió Seek") { tv.commands.any { it.action == "Seek" } }
        assertEquals("0:00:30", tv.commands.first { it.action == "Seek" }.args["Target"])
        assertEquals("REL_TIME", tv.commands.first { it.action == "Seek" }.args["Unit"])

        val url = load.args.getValue("CurrentURI")
        CastSession.stop()
        until("La TV no recibió Stop") { tv.commands.any { it.action == "Stop" } }
        until("El archivo se sigue sirviendo tras detener") { runCatching { URL(url).readBytes() }.isFailure }
    }

    @Test
    fun aLinkForTheTvIsRefusedToAnyoneElse() {
        val file = File(temp.root, "foto.jpg").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val forOther = StreamServer.castUrl(StreamServer.LocalSource(file), "127.0.0.1", "10.1.2.3")
        val c = URL(forOther).openConnection() as HttpURLConnection
        assertEquals(403, c.responseCode)
        c.disconnect()
        val forMe = StreamServer.castUrl(StreamServer.LocalSource(file), "127.0.0.1", "127.0.0.1")
        val ranged = URL(forMe).openConnection() as HttpURLConnection
        ranged.setRequestProperty("Range", "bytes=4000-")
        assertEquals(206, ranged.responseCode)
        assertEquals("image/jpeg", ranged.contentType)
        assertArrayEquals(file.readBytes().copyOfRange(4000, 5000), ranged.inputStream.readBytes())
        ranged.disconnect()
    }

    @Test
    fun hostileOrUselessDescriptionsAreRejected() {
        val secret = File(temp.root, "secreto.txt").apply { writeText("no debe salir") }
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file://${secret.path}">]>
               <root><device><friendlyName>&x;</friendlyName></device></root>"""
        assertThrows(IOException::class.java) { Dlna.parse(xxe) }
        val noTransport =
            """<root><device><friendlyName>Altavoz</friendlyName><serviceList><service>
               <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
               <controlURL>/rc</controlURL></service></serviceList></device></root>"""
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession) =
                    newFixedLengthResponse(Response.Status.OK, "text/xml", noTransport)
            }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val error =
                assertThrows(IOException::class.java) {
                    Dlna.describe("http://127.0.0.1:${server.listeningPort}/d.xml")
                }
            assertTrue(error.message, "Altavoz" in error.message!!)
        } finally {
            server.stop()
        }
        assertNull(Dlna.location("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n\r\n"))
    }

    @Test
    fun timesConvertBothWays() {
        assertEquals("1:02:05", Dlna.clock(3725))
        assertEquals(3725L, Dlna.seconds("01:02:05.000"))
        assertEquals(65L, Dlna.seconds("0:01:05"))
        assertNull(Dlna.seconds("NOT_IMPLEMENTED"))
    }
}
