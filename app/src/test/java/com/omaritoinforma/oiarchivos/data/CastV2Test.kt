package com.omaritoinforma.oiarchivos.data

import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CastV2Test {
    @Test
    fun messagesUseTheCastMessageWireFormat() {
        // protocol_version=0, source_id, destination_id, namespace, payload_type=STRING, payload_utf8.
        val bytes = CastV2.encode(CastV2.Message("a", "b", "c", "d"))
        val expected = intArrayOf(0x08, 0x00, 0x12, 0x01, 0x61, 0x1A, 0x01, 0x62, 0x22, 0x01, 0x63, 0x28, 0x00, 0x32, 0x01, 0x64)
        assertArrayEquals(expected.map { it.toByte() }.toByteArray(), bytes)
        val long = CastV2.Message("sender-0", "receiver-0", CastV2.NS_MEDIA, "x".repeat(300) + " ñ")
        assertEquals(long, CastV2.decode(CastV2.encode(long)))
    }

    @Test
    fun unknownFieldsAreSkippedAndBrokenMessagesRejected() {
        // Campo 7 (carga binaria) y un entero de 32 bits (tipo 5) que no se usan.
        val extra = CastV2.encode(CastV2.Message("s", "d", "n", "{}")) + byteArrayOf(0x3A, 0x02, 0x01, 0x02, 0x45, 1, 2, 3, 4)
        assertEquals("{}", CastV2.decode(extra).payload)
        assertThrows(IOException::class.java) { CastV2.decode(byteArrayOf(0x12, 0x7F, 0x61)) }
    }

    /** Receptor falso: responde como el reproductor por omisión de un Chromecast. */
    private class FakeReceiver(private val failLaunch: Boolean = false) : AutoCloseable {
        val server = ServerSocket(0)
        val received: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        @Volatile var pong = false
        private val worker =
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        fun reply(to: CastV2.Message, namespace: String, payload: JSONObject) =
                            CastV2.write(output, CastV2.Message(to.destination, to.source, namespace, payload.toString()))
                        while (true) {
                            val m = CastV2.read(input)
                            val json = JSONObject(m.payload)
                            received += json.put("_ns", m.namespace).put("_to", m.destination)
                            val id = json.optInt("requestId")
                            fun status(state: String, time: Double) =
                                JSONObject().put("type", "MEDIA_STATUS").put("requestId", id).put(
                                    "status",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("mediaSessionId", 3)
                                            .put("playerState", state)
                                            .put("currentTime", time)
                                            .put("media", JSONObject().put("duration", 600.0))))
                            when (json.optString("type")) {
                                "PONG" -> pong = true
                                "LAUNCH" -> {
                                    // Antes de contestar, el Chromecast puede mandar un PING: hay que responderlo.
                                    reply(m, CastV2.NS_HEARTBEAT, JSONObject().put("type", "PING"))
                                    if (failLaunch) {
                                        reply(m, CastV2.NS_RECEIVER, JSONObject().put("type", "LAUNCH_ERROR").put("requestId", id))
                                    } else {
                                        val app =
                                            JSONObject().put("appId", CastV2.DEFAULT_RECEIVER).put("transportId", "web-7").put("sessionId", "s1")
                                        reply(
                                            m, CastV2.NS_RECEIVER,
                                            JSONObject().put("type", "RECEIVER_STATUS").put("requestId", id).put(
                                                "status", JSONObject().put("applications", JSONArray().put(app))))
                                    }
                                }
                                "LOAD" -> reply(m, CastV2.NS_MEDIA, status("PLAYING", 0.0))
                                "GET_STATUS" -> reply(m, CastV2.NS_MEDIA, status("PLAYING", 65.4))
                                "PAUSE" -> reply(m, CastV2.NS_MEDIA, status("PAUSED", 65.4))
                                "PLAY", "SEEK" -> reply(m, CastV2.NS_MEDIA, status("PLAYING", 120.0))
                                "STOP" -> reply(m, CastV2.NS_MEDIA, status("IDLE", 0.0))
                            }
                        }
                    }
                }
            }

        val device = CastV2.Device("Chromecast de prueba", "127.0.0.1", server.localPort)

        fun types() = received.map { it.optString("type") }

        override fun close() {
            server.close()
            worker.join(2000)
        }
    }

    private fun plain(host: String, port: Int, timeout: Int) = Socket(host, port).apply { soTimeout = timeout }

    @Test
    fun aFileIsLoadedOnTheDefaultReceiverAndControlled() {
        FakeReceiver().use { tv ->
            CastV2.Client(tv.device, 5000, ::plain).use { cast ->
                cast.launch()
                cast.load("http://192.168.1.20:8090/c/abc/tono.wav", "tono.wav", "audio/wav")
                assertEquals(65L to 600L, cast.position())
                cast.pause()
                cast.seek(120)
                cast.play()
                cast.stop()
            }
            Thread.sleep(200)
            assertTrue("El teléfono no contestó al PING", tv.pong)
            assertEquals(
                listOf("CONNECT", "LAUNCH", "PONG", "CONNECT", "LOAD", "GET_STATUS", "PAUSE", "SEEK", "PLAY", "STOP", "CLOSE", "CLOSE"),
                tv.types())
            val launch = tv.received.first { it.optString("type") == "LAUNCH" }
            assertEquals(CastV2.DEFAULT_RECEIVER, launch.getString("appId"))
            assertEquals("receiver-0", launch.getString("_to"))
            val load = tv.received.first { it.optString("type") == "LOAD" }
            assertEquals("web-7", load.getString("_to")) // al reproductor abierto, no al receptor
            assertEquals(CastV2.NS_MEDIA, load.getString("_ns"))
            val media = load.getJSONObject("media")
            assertEquals("http://192.168.1.20:8090/c/abc/tono.wav", media.getString("contentId"))
            assertEquals("audio/wav", media.getString("contentType"))
            assertEquals("tono.wav", media.getJSONObject("metadata").getString("title"))
            val pause = tv.received.first { it.optString("type") == "PAUSE" }
            assertEquals(3, pause.getInt("mediaSessionId"))
            assertEquals(120, tv.received.first { it.optString("type") == "SEEK" }.getInt("currentTime"))
            // Cada orden lleva su propio número.
            val ids = tv.received.filter { it.has("requestId") }.map { it.getInt("requestId") }
            assertEquals(ids.distinct(), ids)
        }
    }

    @Test
    fun aRejectedLaunchIsReportedAndControlsNeedAPlayingFile() {
        FakeReceiver(failLaunch = true).use { tv ->
            CastV2.Client(tv.device, 5000, ::plain).use { cast ->
                val error = assertThrows(IOException::class.java) { cast.launch() }
                assertTrue(error.message, error.message!!.contains("LAUNCH_ERROR"))
                assertThrows(IOException::class.java) { cast.pause() }
            }
        }
    }

    @Test
    fun onlyLocalNetworkAddressesAreContacted() {
        val error = assertThrows(IOException::class.java) { CastV2.tlsSocket("8.8.8.8", CastV2.PORT, 500) }
        assertTrue(error.message!!.contains("red local"))
    }

    @Test
    fun theFriendlyNameComesFromTheAnnouncement() {
        assertEquals("Salón", CastDiscovery.friendlyName(mapOf("fn" to "Salón".toByteArray()), "Chromecast-abc123"))
        assertEquals("Chromecast", CastDiscovery.friendlyName(emptyMap(), "Chromecast-abc123"))
    }
}
