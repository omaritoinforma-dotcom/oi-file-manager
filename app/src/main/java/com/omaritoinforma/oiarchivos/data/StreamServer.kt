package com.omaritoinforma.oiarchivos.data

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections

/**
 * Reproducir desde la red sin descargar, como ES: un servidor HTTP local sirve el archivo remoto por
 * rangos de bytes, así el reproductor empieza enseguida y puede saltar a cualquier punto.
 *
 * El de ES (ESHttpServer, puerto 59777) escuchaba en toda la red y sin clave, y cualquiera en la
 * misma Wi-Fi podía leer archivos del teléfono (CVE-2019-6447). Este escucha solo en 127.0.0.1 y
 * cada enlace lleva una clave aleatoria de 128 bits que solo conoce quien lo abre.
 */
object StreamServer {
    data class Item(val connection: Connection, val path: String, val name: String, val size: Long)

    private const val MAX_ITEMS = 64
    private val random = SecureRandom()
    private var server: Server? = null

    /** Enlaces activos, los más antiguos se olvidan primero. */
    private val items: MutableMap<String, Item> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Item>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>) =
                    size > MAX_ITEMS
            })

    /** Enlace http://127.0.0.1 para reproducir [entry]; arranca el servidor si hace falta. */
    @Synchronized
    fun url(connection: Connection, entry: RemoteEntry): String {
        val running = server ?: Server().also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = it
        }
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        items[token] = Item(connection, entry.path, entry.name, entry.size)
        val name = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:${running.listeningPort}/s/$token/$name"
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        items.clear()
    }

    /** Rango pedido en la cabecera Range, o null si se pide el archivo entero. */
    internal fun range(header: String?, size: Long): LongRange? {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=") ?: return null
        if (',' in spec) return null // Varios rangos: se sirve el archivo entero.
        val (from, to) = spec.split('-', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
        return when {
            from.isEmpty() -> {
                val suffix = to.toLongOrNull() ?: throw IllegalArgumentException("Rango no válido")
                if (suffix <= 0) throw IllegalArgumentException("Rango no válido")
                maxOf(0L, size - suffix) until size
            }
            else -> {
                val start = from.toLongOrNull() ?: throw IllegalArgumentException("Rango no válido")
                val end = if (to.isEmpty()) size - 1 else minOf(to.toLongOrNull() ?: -1, size - 1)
                if (start >= size || end < start) throw IllegalArgumentException("Rango no válido")
                start..end
            }
        }
    }

    /** Abre [item] desde [offset]: con reanudación del servidor si la hay, si no saltando bytes. */
    internal fun open(fs: RemoteFs, item: Item, offset: Long): InputStream {
        if (offset == 0L) return fs.read(item.path)
        fs.readFrom(item.path, offset)?.let { return it }
        val input = fs.read(item.path)
        var left = offset
        try {
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) left -= skipped
                else if (input.read() < 0) throw IOException("El archivo es más corto de lo esperado")
                else left--
            }
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return input
    }

    private class Server : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val parts = session.uri.trim('/').split('/')
            val item = if (parts.size >= 2 && parts[0] == "s") items[parts[1]] else null
            if (item == null) return text(Response.Status.NOT_FOUND, "No existe")
            if (session.method != Method.GET && session.method != Method.HEAD)
                return text(Response.Status.METHOD_NOT_ALLOWED, "Solo GET")
            val mime = URLConnection.guessContentTypeFromName(item.name) ?: mimeOf(item.name)
            val size = item.size
            val range =
                try {
                    if (size > 0) range(session.headers["range"], size) else null
                } catch (e: IllegalArgumentException) {
                    return text(Response.Status.RANGE_NOT_SATISFIABLE, "Rango no válido").apply {
                        addHeader("Content-Range", "bytes */$size")
                    }
                }
            val start = range?.first ?: 0L
            val length = range?.let { it.last - it.first + 1 } ?: size
            if (session.method == Method.HEAD) {
                // Solo cabeceras: el cuerpo vacío termina el envío aunque se anuncie el tamaño.
                val empty = java.io.ByteArrayInputStream(ByteArray(0))
                return headers(newFixedLengthResponse(status(range), mime, empty, maxOf(length, 0L)), range, size)
            }
            val fs =
                try {
                    RemoteFiles.connect(item.connection)
                } catch (e: Exception) {
                    return text(Response.Status.INTERNAL_ERROR, e.message ?: "No se pudo conectar")
                }
            val input =
                try {
                    open(fs, item, start)
                } catch (e: Exception) {
                    runCatching { fs.close() }
                    return text(Response.Status.INTERNAL_ERROR, e.message ?: "No se pudo leer")
                }
            // Al cerrar la respuesta (también si el reproductor salta a otro punto) se libera la conexión.
            val body =
                object : FilterInputStream(input) {
                    override fun close() {
                        try {
                            runCatching { super.close() }
                        } finally {
                            runCatching { fs.close() }
                        }
                    }
                }
            val response =
                if (size > 0) newFixedLengthResponse(status(range), mime, body, length)
                else newChunkedResponse(Response.Status.OK, mime, body)
            return headers(response, range, size)
        }

        private fun status(range: LongRange?) =
            if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK

        private fun headers(response: Response, range: LongRange?, size: Long): Response {
            if (size > 0) response.addHeader("Accept-Ranges", "bytes")
            if (range != null) response.addHeader("Content-Range", "bytes ${range.first}-${range.last}/$size")
            return response
        }

        private fun text(status: Response.Status, body: String) =
            newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

        private fun mimeOf(name: String) =
            when (name.substringAfterLast('.', "").lowercase()) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "ts" -> "video/mp2t"
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                "ogg", "oga", "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
    }
}
