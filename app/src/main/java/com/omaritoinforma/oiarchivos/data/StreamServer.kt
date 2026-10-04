package com.omaritoinforma.oiarchivos.data

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections

/**
 * Reproducir desde la red sin descargar y enviar a la TV, como ES: un servidor HTTP sirve el
 * archivo (del teléfono o de un servidor) por rangos de bytes, así el reproductor empieza enseguida
 * y puede saltar a cualquier punto.
 *
 * ES dejaba sus servidores (puertos 59777 y 8191) abiertos a toda la red y sin clave, y cualquiera
 * en la misma Wi-Fi podía leer archivos del teléfono (CVE-2019-6447). Aquí hay dos servidores:
 * - el de reproducción escucha solo en 127.0.0.1;
 * - el de la TV acepta solo la dirección IP de la TV elegida.
 * Y cada enlace lleva una clave aleatoria de 128 bits.
 */
object StreamServer {
    sealed interface Source {
        val name: String
        val size: Long
    }

    data class RemoteSource(
        val connection: Connection,
        val path: String,
        override val name: String,
        override val size: Long
    ) : Source

    data class LocalSource(val file: File) : Source {
        override val name: String
            get() = file.name

        override val size: Long
            get() = file.length()
    }

    /** [client] es la única IP que puede pedir el enlace, o null si vale cualquiera. */
    private class Link(val source: Source, val client: String?)

    private const val MAX_LINKS = 64
    private val random = SecureRandom()
    private var local: Server? = null
    private var cast: Server? = null

    private fun links(): MutableMap<String, Link> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Link>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Link>) =
                    size > MAX_LINKS
            })

    private fun token() = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun path(token: String, name: String) =
        "/s/$token/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** Enlace http://127.0.0.1 para reproducir [entry] en este teléfono. */
    @Synchronized
    fun url(connection: Connection, entry: RemoteEntry): String {
        val server = local ?: Server("127.0.0.1").also { local = it }
        val token = token()
        server.links[token] = Link(RemoteSource(connection, entry.path, entry.name, entry.size), null)
        return "http://127.0.0.1:${server.listeningPort}${path(token, entry.name)}"
    }

    /**
     * Enlace para que la TV en [client] reproduzca [source]. [localAddress] es la dirección de este
     * teléfono en la red de la TV. El servidor escucha en todas las interfaces, pero responde 403 a
     * cualquier otra dirección.
     */
    @Synchronized
    fun castUrl(source: Source, localAddress: String, client: String): String {
        val server = cast ?: Server(null).also { cast = it }
        val token = token()
        server.links[token] = Link(source, client)
        return "http://$localAddress:${server.listeningPort}${path(token, source.name)}"
    }

    /** Deja de servir a la TV (al detener el envío). */
    @Synchronized
    fun stopCast() {
        cast?.stop()
        cast = null
    }

    @Synchronized
    fun stop() {
        local?.stop()
        local = null
        stopCast()
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

    /** Abre [path] desde [offset]: con reanudación del servidor si la hay, si no saltando bytes. */
    internal fun open(fs: RemoteFs, path: String, offset: Long): InputStream {
        if (offset == 0L) return fs.read(path)
        fs.readFrom(path, offset)?.let { return it }
        return skipTo(fs.read(path), offset)
    }

    private fun skipTo(input: InputStream, offset: Long): InputStream {
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

    fun mime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }

    private class Server(host: String?) : NanoHTTPD(host, 0) {
        val links = links()

        init {
            start(SOCKET_READ_TIMEOUT, true)
        }

        override fun serve(session: IHTTPSession): Response {
            val parts = session.uri.trim('/').split('/')
            val link = if (parts.size >= 2 && parts[0] == "s") links[parts[1]] else null
            if (link == null) return text(Response.Status.NOT_FOUND, "No existe")
            if (link.client != null && session.remoteIpAddress != link.client)
                return text(Response.Status.FORBIDDEN, "Este enlace es solo para la TV elegida")
            if (session.method != Method.GET && session.method != Method.HEAD)
                return text(Response.Status.METHOD_NOT_ALLOWED, "Solo GET")
            val source = link.source
            val mime = mime(source.name)
            val size = source.size
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
            val body =
                try {
                    when (source) {
                        is LocalSource -> skipTo(source.file.inputStream(), start)
                        is RemoteSource -> remote(source, start)
                    }
                } catch (e: Exception) {
                    return text(Response.Status.INTERNAL_ERROR, e.message ?: "No se pudo leer")
                }
            val response =
                if (size > 0) newFixedLengthResponse(status(range), mime, body, length)
                else newChunkedResponse(Response.Status.OK, mime, body)
            response.addHeader("transferMode.dlna.org", "Streaming")
            return headers(response, range, size)
        }

        /** Al cerrar la respuesta (también si el reproductor salta a otro punto) se libera la conexión. */
        private fun remote(source: RemoteSource, start: Long): InputStream {
            val fs = RemoteFiles.connect(source.connection)
            val input =
                try {
                    open(fs, source.path, start)
                } catch (e: Exception) {
                    runCatching { fs.close() }
                    throw e
                }
            return object : FilterInputStream(input) {
                override fun close() {
                    try {
                        runCatching { super.close() }
                    } finally {
                        runCatching { fs.close() }
                    }
                }
            }
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
    }
}
