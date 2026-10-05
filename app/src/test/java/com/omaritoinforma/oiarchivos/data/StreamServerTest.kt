package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproducir desde la red sin descargar: el servidor local sirve por rangos lo que leen los
 * clientes reales de SFTP, FTP y WebDAV (servidores de `scripts/remote_servers.py`).
 */
class StreamServerTest {
    private fun env(name: String) =
        System.getenv("OI_REMOTE_TEST_$name")
            ?: throw AssertionError(
                "Falta OI_REMOTE_TEST_$name: inicia scripts/remote_servers.py y exporta su salida")

    private fun connections(): List<Connection> {
        val password = env("PASSWORD")
        return listOf(
            Connection(
                "sftp", "SFTP", Protocol.SFTP, "127.0.0.1", env("SFTP_PORT").toInt(), "oi",
                password, fingerprint = env("SFTP_FINGERPRINT")),
            Connection(
                "ftp", "FTP", Protocol.FTP, "127.0.0.1", env("FTP_PORT").toInt(), "oi", password),
            Connection(
                "webdav", "WebDAV", Protocol.WEBDAV, "http://127.0.0.1:${env("WEBDAV_PORT")}", 0,
                "oi", password))
    }

    @After fun stop() = StreamServer.stop()

    private class Reply(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun get(url: String, range: String? = null): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            range?.let { c.setRequestProperty("Range", it) }
            c.connectTimeout = 5000
            c.readTimeout = 30_000
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers =
                c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues {
                    it.value.joinToString(",")
                }
            return Reply(code, headers, body)
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun rangeHeaderIsParsedLikeHttpSays() {
        assertNull(StreamServer.range(null, 100))
        assertNull(StreamServer.range("items=0-5", 100))
        assertNull(StreamServer.range("bytes=0-5,10-20", 100))
        assertEquals(0L..99L, StreamServer.range("bytes=0-", 100))
        assertEquals(10L..19L, StreamServer.range("bytes=10-19", 100))
        assertEquals(90L..99L, StreamServer.range("bytes=90-500", 100))
        assertEquals(80L..99L, StreamServer.range("bytes=-20", 100))
        assertEquals(0L..99L, StreamServer.range("bytes=-500", 100))
        for (bad in listOf("bytes=100-", "bytes=50-10", "bytes=-0", "bytes=x-1", "bytes=-y"))
            assertThrows(bad, IllegalArgumentException::class.java) { StreamServer.range(bad, 100) }
    }

    @Test
    fun streamsRealServersByRangesWithoutDownloading() {
        val disk = File(env("ROOT"))
        val data = ByteArray(3_000_000) { ((it * 7 + 3) % 253).toByte() }
        for (c in connections()) {
            try {
                val name = "video ${c.id} ñ.mp4"
                File(disk, name).writeBytes(data)
                val url = StreamServer.url(c, RemoteEntry("/$name", name, false, data.size.toLong()))
                assertTrue(url, url.startsWith("http://127.0.0.1:"))

                val full = get(url)
                assertEquals(200, full.code)
                assertEquals("bytes", full.headers["accept-ranges"])
                assertEquals("video/mp4", full.headers["content-type"])
                assertArrayEquals(data, full.body)

                val middle = get(url, "bytes=1000000-1000999")
                assertEquals(206, middle.code)
                assertEquals("bytes 1000000-1000999/3000000", middle.headers["content-range"])
                assertArrayEquals(data.copyOfRange(1_000_000, 1_001_000), middle.body)

                val tail = get(url, "bytes=-500")
                assertEquals(206, tail.code)
                assertArrayEquals(data.copyOfRange(data.size - 500, data.size), tail.body)

                val rest = get(url, "bytes=2999000-")
                assertArrayEquals(data.copyOfRange(2_999_000, data.size), rest.body)

                val outside = get(url, "bytes=3000000-")
                assertEquals(416, outside.code)
                assertEquals("bytes */3000000", outside.headers["content-range"])

                // Un reproductor que salta corta la respuesta a medias; la siguiente debe funcionar.
                val c2 = URL(url).openConnection() as HttpURLConnection
                c2.setRequestProperty("Range", "bytes=100-")
                c2.inputStream.use { it.read(ByteArray(10)) }
                c2.disconnect()
                assertArrayEquals(
                    data.copyOfRange(2_000_000, 2_000_100),
                    get(url, "bytes=2000000-2000099").body)
            } catch (e: Throwable) {
                throw AssertionError("${c.label}: ${e.message}", e)
            }
        }
    }

    @Test
    fun linksNeedTheirKeyAndOnlyThisPhoneCanConnect() {
        val c = connections().first()
        val url = StreamServer.url(c, RemoteEntry("/no-existe.bin", "no-existe.bin", false, 10))
        val port = URL(url).port
        val guessed = url.replace(Regex("/s/[0-9a-f]{32}/"), "/s/${"0".repeat(32)}/")
        assertEquals(404, get(guessed).code)
        assertEquals(404, get("http://127.0.0.1:$port/").code)
        // Desde otra dirección del equipo (como otro dispositivo de la red) no se puede conectar.
        val others =
            NetworkInterface.getNetworkInterfaces().toList().flatMap { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }
            }
        for (address in others) {
            val reached =
                runCatching {
                        Socket().use { it.connect(InetSocketAddress(address, port), 500) }
                    }
                    .isSuccess
            assertFalse("Se pudo conectar desde $address", reached)
        }
    }
}
