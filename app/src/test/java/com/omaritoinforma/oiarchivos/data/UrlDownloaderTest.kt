package com.omaritoinforma.oiarchivos.data

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gestor de descargas: un servidor HTTP con rangos, como los de Internet. */
class UrlDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private class Site : NanoHTTPD("127.0.0.1", 0) {
        @Volatile var data = ByteArray(3_000_000) { (it % 239).toByte() }
        @Volatile var etag = "\"v1\""
        val ranges: MutableList<String?> = Collections.synchronizedList(ArrayList())

        init {
            start(SOCKET_READ_TIMEOUT, true)
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/falta") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            val range = session.headers["range"]
            ranges += range
            val ifRange = session.headers["if-range"]
            val start =
                if (range != null && (ifRange == null || ifRange == etag))
                    range.removePrefix("bytes=").substringBefore('-').toLong()
                else 0L
            val body = ByteArrayInputStream(data, start.toInt(), data.size - start.toInt())
            val response =
                newFixedLengthResponse(
                    if (start > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    "application/octet-stream",
                    body,
                    data.size - start)
            response.addHeader("ETag", etag)
            if (start > 0) response.addHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
            if (session.uri == "/descargar")
                response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''informe%20a%C3%B1o.pdf")
            return response
        }

        fun url(path: String) = "http://127.0.0.1:$listeningPort$path"
    }

    private val site = Site()

    @After fun stop() = site.stop()

    /** Corta la descarga en el primer trozo recibido (el progreso se informa como mucho cada 150 ms). */
    private suspend fun interrupted(url: String) {
        try {
            UrlDownloader.download(url, temp.root) { if (it.doneBytes > 0) throw CancellationException("corte") }
            fail("Debía interrumpirse")
        } catch (_: CancellationException) {}
    }

    @Test
    fun anInterruptedDownloadContinuesWhereItStopped() = runBlocking {
        val url = site.url("/carpeta/video.mp4")
        interrupted(url)
        val part = UrlDownloader.partFile(temp.root, url)
        val partial = part.length()
        assertTrue("Debe quedar el resto parcial", partial in 1L until 3_000_000L)
        val file = UrlDownloader.download(url, temp.root) {}
        assertEquals("video.mp4", file.name)
        assertArrayEquals(site.data, file.readBytes())
        assertEquals("Debe pedir solo lo que faltaba", "bytes=$partial-", site.ranges.last())
        assertFalse(part.exists())
        assertEquals(listOf("video.mp4"), temp.root.list()!!.toList())
    }

    @Test
    fun ifTheFileChangedItStartsAgainInsteadOfMixingVersions() = runBlocking {
        val url = site.url("/datos.bin")
        interrupted(url)
        site.data = ByteArray(2_000_000) { (it % 97).toByte() }
        site.etag = "\"v2\""
        val file = UrlDownloader.download(url, temp.root) {}
        assertArrayEquals(site.data, file.readBytes())
    }

    @Test
    fun namesComeFromTheServerAndAreSafe() = runBlocking {
        val file = UrlDownloader.download(site.url("/descargar"), temp.root) {}
        assertEquals("informe año.pdf", file.name)
        val again = UrlDownloader.download(site.url("/descargar"), temp.root) {}
        assertEquals("No se sustituye lo ya descargado", "informe año (1).pdf", again.name)
        assertEquals("passwd", UrlDownloader.fileName("http://x/a", "attachment; filename=\"../../etc/passwd\""))
        assertEquals("descarga", UrlDownloader.fileName("http://x/", null))
        assertEquals("a b.txt", UrlDownloader.fileName("http://x/a%20b.txt?x=1", null))
    }

    @Test
    fun errorsAreReportedInSpanish() = runBlocking {
        val e = runCatching { UrlDownloader.download(site.url("/falta"), temp.root) {} }.exceptionOrNull()
        assertEquals("El servidor respondió 404", e?.message)
        assertNotNull(UrlDownloader.problem("ftp://x"))
        assertNotNull(UrlDownloader.problem("no es una url"))
        assertNull(UrlDownloader.problem("https://ejemplo.org/a.zip"))
    }
}
