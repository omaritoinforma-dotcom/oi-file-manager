package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Servidor OBEX FTP (el «servidor OBEX» de ES): otro equipo emparejado por Bluetooth explora la
 * carpeta compartida, descarga archivos y, si se permite, sube, crea carpetas, renombra y borra.
 * No depende de Bluetooth: [serve] atiende una sesión sobre cualquier par de flujos, así se prueba
 * en la JVM. Todo queda dentro de [root]; los nombres con «/», «..» o caracteres de control se rechazan.
 */
class ObexFtpServer(root: File, private val writable: Boolean, private val mtu: Int = 32767) {
    private val root = root.canonicalFile

    companion object {
        val FTP_TARGET =
            byteArrayOf(
                0xf9.toByte(), 0xec.toByte(), 0x7b, 0xc4.toByte(), 0x95.toByte(), 0x3c, 0x11, 0xd2.toByte(),
                0x98.toByte(), 0x4e, 0x52, 0x54, 0, 0xdc.toByte(), 0x9e.toByte(), 0x09)

        // Códigos de respuesta OBEX (con el bit final).
        const val CONTINUE = 0x90
        const val SUCCESS = 0xA0
        const val BAD_REQUEST = 0xC0
        const val FORBIDDEN = 0xC3
        const val NOT_FOUND = 0xC4
        const val CONFLICT = 0xC9
        const val INTERNAL = 0xD0
        const val NOT_IMPLEMENTED = 0xD1
        const val UNAVAILABLE = 0xD3

        private const val CONNECTION_ID = 1

        /** Listado de carpeta en el formato x-obex/folder-listing. */
        fun folderListing(dir: File, isRoot: Boolean): String {
            val time = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val out = StringBuilder()
            out.append("<?xml version=\"1.0\"?>\n<!DOCTYPE folder-listing SYSTEM \"obex-folder-listing.dtd\">\n<folder-listing version=\"1.0\">\n")
            if (!isRoot) out.append("<parent-folder/>\n")
            val children = dir.listFiles().orEmpty().filter { safeName(it.name) }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (f in children) {
                val modified = time.format(Date(f.lastModified()))
                if (f.isDirectory) out.append("<folder name=\"${xml(f.name)}\" modified=\"$modified\"/>\n")
                else out.append("<file name=\"${xml(f.name)}\" size=\"${f.length()}\" modified=\"$modified\"/>\n")
            }
            out.append("</folder-listing>\n")
            return out.toString()
        }

        private fun xml(value: String) =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        private fun unicode(value: ByteArray) = String(value, Charsets.UTF_16BE).trimEnd('\u0000')

        fun safeName(name: String) =
            name.isNotEmpty() && name != "." && name != ".." && name.length <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }
    }

    private class Request(val op: Int, val headers: List<Pair<Int, ByteArray>>, val raw: ByteArray) {
        val final get() = op and 0x80 != 0
        fun header(id: Int) = headers.firstOrNull { it.first == id }?.second

        fun name(): String? = header(0x01)?.let(::unicode)

        fun destName(): String? = header(0x15)?.let(::unicode)

        fun type(): String? = header(0x42)?.toString(Charsets.US_ASCII)?.trimEnd('\u0000')
    }

    private fun readPacket(input: InputStream): ByteArray? {
        val head = ByteArray(3)
        var n = 0
        while (n < 3) {
            val r = input.read(head, n, 3 - n)
            if (r < 0) {
                if (n == 0) return null
                throw EOFException()
            }
            n += r
        }
        val length = ((head[1].toInt() and 255) shl 8) or (head[2].toInt() and 255)
        if (length < 3) throw IOException(tr("Paquete OBEX no válido"))
        val packet = head.copyOf(length)
        var at = 3
        while (at < length) {
            val r = input.read(packet, at, length - at)
            if (r < 0) throw EOFException()
            at += r
        }
        return packet
    }

    /** Atiende una sesión hasta que el otro equipo se desconecta. */
    fun serve(input: InputStream, output: OutputStream) {
        var cwd = root
        var connected = false
        var peerMtu = 255
        // Respuesta de un GET que se manda en varios paquetes.
        var pending: InputStream? = null
        // Subida en curso.
        var upload: Pair<File, OutputStream>? = null
        var uploadTarget: File? = null
        var putHeaders = ArrayList<Pair<Int, ByteArray>>()
        var getHeaders = ArrayList<Pair<Int, ByteArray>>()

        fun send(code: Int, headers: ByteArray = ByteArray(0)) {
            output.write(ObexCodec.packet(code, headers))
            output.flush()
        }

        fun cancelUpload() {
            upload?.second?.runCatching { close() }
            upload?.first?.delete()
            upload = null
            uploadTarget = null
            putHeaders = ArrayList()
        }

        fun child(name: String?): File? {
            if (name == null || !safeName(name)) return null
            val f = File(cwd, name)
            // Un enlace simbólico no puede llevar fuera de la carpeta compartida.
            return f.takeIf { it.canonicalFile.toPath().startsWith(root.toPath()) }
        }

        fun sendChunk() {
            val source = pending ?: return send(SUCCESS)
            // Cabecera de 3 bytes del paquete y 3 de la cabecera Body.
            val room = minOf(peerMtu, mtu) - 6
            val buf = ByteArray(room)
            var filled = 0
            while (filled < room) {
                val r = source.read(buf, filled, room - filled)
                if (r < 0) break
                filled += r
            }
            val last = filled < room
            if (last) {
                source.close()
                pending = null
                send(SUCCESS, ObexCodec.bytes(0x49, buf.copyOf(filled)))
            } else send(CONTINUE, ObexCodec.bytes(0x48, buf))
        }

        try {
            while (true) {
                val packet = readPacket(input) ?: return
                val op = packet[0].toInt() and 255
                when (op) {
                    0x80 -> { // CONNECT: versión, opciones, tamaño máximo de paquete y cabeceras
                        if (packet.size < 7) {
                            send(BAD_REQUEST)
                            continue
                        }
                        peerMtu = ObexCodec.ushort(packet, 5).coerceIn(255, 65535)
                        val target = ObexCodec.headers(packet, 7).firstOrNull { it.first == 0x46 }?.second
                        if (target == null || !target.contentEquals(FTP_TARGET)) {
                            send(UNAVAILABLE, byteArrayOf(0x10, 0, (mtu shr 8).toByte(), mtu.toByte()))
                            continue
                        }
                        connected = true
                        cwd = root
                        send(
                            SUCCESS,
                            byteArrayOf(0x10, 0, (mtu shr 8).toByte(), mtu.toByte()) +
                                byteArrayOf(0xcb.toByte(), 0, 0, 0, CONNECTION_ID.toByte()) +
                                ObexCodec.bytes(0x4a, FTP_TARGET))
                    }
                    0x81 -> { // DISCONNECT
                        cancelUpload()
                        send(SUCCESS)
                        return
                    }
                    0xFF -> { // ABORT
                        cancelUpload()
                        pending?.close()
                        pending = null
                        send(SUCCESS)
                    }
                    else -> {
                        if (!connected) {
                            send(FORBIDDEN)
                            continue
                        }
                        val headersStart = if (op == 0x85) 5 else 3
                        if (packet.size < headersStart) {
                            send(BAD_REQUEST)
                            continue
                        }
                        val request = Request(op, ObexCodec.headers(packet, headersStart), packet)
                        when (op and 0x7F) {
                            0x05 -> { // SETPATH: opciones (atrás, no crear) y nombre
                                val flags = packet[3].toInt()
                                val name = request.name()
                                when {
                                    flags and 1 != 0 -> {
                                        if (cwd == root) send(NOT_FOUND)
                                        else {
                                            cwd = cwd.parentFile!!.canonicalFile
                                            send(SUCCESS)
                                        }
                                    }
                                    name.isNullOrEmpty() -> {
                                        cwd = root
                                        send(SUCCESS)
                                    }
                                    else -> {
                                        val dir = child(name)
                                        when {
                                            dir == null -> send(BAD_REQUEST)
                                            dir.isDirectory -> {
                                                cwd = dir.canonicalFile
                                                send(SUCCESS)
                                            }
                                            dir.exists() -> send(BAD_REQUEST)
                                            flags and 2 != 0 -> send(NOT_FOUND)
                                            !writable -> send(FORBIDDEN)
                                            dir.mkdir() -> {
                                                cwd = dir.canonicalFile
                                                send(SUCCESS)
                                            }
                                            else -> send(INTERNAL)
                                        }
                                    }
                                }
                            }
                            0x03 -> { // GET
                                if (pending != null && request.headers.none { it.first == 0x01 || it.first == 0x42 }) {
                                    sendChunk()
                                    continue
                                }
                                getHeaders.addAll(request.headers)
                                if (!request.final) {
                                    send(CONTINUE)
                                    continue
                                }
                                val all = Request(op, getHeaders, packet)
                                getHeaders = ArrayList()
                                pending?.close()
                                pending = null
                                if (all.type() == "x-obex/folder-listing") {
                                    pending = folderListing(cwd, cwd == root).toByteArray(Charsets.UTF_8).inputStream()
                                    sendChunk()
                                } else {
                                    val file = child(all.name())
                                    when {
                                        file == null -> send(BAD_REQUEST)
                                        !file.isFile -> send(NOT_FOUND)
                                        else -> {
                                            pending = file.inputStream().buffered()
                                            sendChunk()
                                        }
                                    }
                                }
                            }
                            0x02 -> { // PUT: subir o, sin cuerpo, borrar
                                putHeaders.addAll(request.headers)
                                val all = Request(op, putHeaders, packet)
                                val hasBody = all.headers.any { it.first == 0x48 || it.first == 0x49 }
                                if (!writable) {
                                    if (request.final) putHeaders = ArrayList()
                                    send(FORBIDDEN)
                                    continue
                                }
                                if (!hasBody) {
                                    if (!request.final) {
                                        send(CONTINUE)
                                        continue
                                    }
                                    putHeaders = ArrayList()
                                    val target = child(all.name())
                                    when {
                                        target == null -> send(BAD_REQUEST)
                                        !target.exists() -> send(NOT_FOUND)
                                        target.isDirectory && target.list()?.isNotEmpty() == true -> send(CONFLICT)
                                        target.delete() -> send(SUCCESS)
                                        else -> send(INTERNAL)
                                    }
                                    continue
                                }
                                if (upload == null) {
                                    val target = child(all.name())
                                    if (target == null || target.isDirectory) {
                                        putHeaders = ArrayList()
                                        send(BAD_REQUEST)
                                        continue
                                    }
                                    val part = File(cwd, ".${target.name}.oi-parte")
                                    upload = part to part.outputStream().buffered()
                                    uploadTarget = target
                                }
                                for ((id, value) in request.headers) if (id == 0x48 || id == 0x49) upload!!.second.write(value)
                                if (!request.final) {
                                    send(CONTINUE)
                                    continue
                                }
                                val (part, stream) = upload!!
                                stream.close()
                                val target = uploadTarget!!
                                val expected = all.header(0xC3)?.let { java.nio.ByteBuffer.wrap(it).int.toLong() and 0xFFFFFFFFL }
                                upload = null
                                uploadTarget = null
                                putHeaders = ArrayList()
                                if (expected != null && part.length() != expected) {
                                    part.delete()
                                    send(BAD_REQUEST)
                                } else if ((target.exists() && !target.delete()) || !part.renameTo(target)) {
                                    part.delete()
                                    send(INTERNAL)
                                } else send(SUCCESS)
                            }
                            0x06 -> { // ACTION: mover o renombrar dentro de la carpeta
                                val action = request.header(0x94)?.firstOrNull()?.toInt() ?: -1
                                val from = child(request.name())
                                val to = child(request.destName())
                                when {
                                    !writable -> send(FORBIDDEN)
                                    action != 0 && action != 1 -> send(NOT_IMPLEMENTED)
                                    from == null || to == null -> send(BAD_REQUEST)
                                    !from.exists() -> send(NOT_FOUND)
                                    to.exists() -> send(CONFLICT)
                                    action == 0 && from.isDirectory -> send(BAD_REQUEST)
                                    action == 0 -> send(if (runCatching { from.copyTo(to) }.isSuccess) SUCCESS else INTERNAL)
                                    from.renameTo(to) -> send(SUCCESS)
                                    else -> send(INTERNAL)
                                }
                            }
                            else -> send(NOT_IMPLEMENTED)
                        }
                    }
                }
            }
        } finally {
            cancelUpload()
            pending?.close()
        }
    }
}
