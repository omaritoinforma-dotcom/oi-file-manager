package com.omaritoinforma.oiarchivos.data

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-to-phone transfer over the same Wi-Fi, like ES File Explorer's Sender: the receiver
 * listens on a fixed port, the sender finds it by scanning the subnet, offers the files and sends
 * them only after the receiver accepts. Unlike ES, every file is checked with SHA-256 and names are
 * validated so a sender cannot write outside the destination folder.
 */
object Nearby {
    const val PORT = 42137
    const val PATH = "/oi-enviar/v1"
    const val MAX_FILES = 1000

    data class OfferedFile(val name: String, val size: Long, val sha256: String)

    data class Offer(val from: String, val files: List<OfferedFile>) {
        val bytes
            get() = files.sumOf { it.size }
    }

    data class Peer(val address: String, val port: Int, val name: String)

    fun offerJson(offer: Offer): String =
        JSONObject()
            .put("from", offer.from)
            .put(
                "files",
                JSONArray().apply {
                    offer.files.forEach {
                        put(
                            JSONObject()
                                .put("name", it.name)
                                .put("size", it.size)
                                .put("sha256", it.sha256))
                    }
                })
            .toString()

    /** Parses and validates an offer; a bad one is rejected before anyone is asked. */
    fun parseOffer(json: String): Offer {
        val o = JSONObject(json)
        val list = o.getJSONArray("files")
        if (list.length() !in 1..MAX_FILES) throw IOException("Oferta con demasiados archivos")
        val files =
            (0 until list.length()).map { i ->
                val f = list.getJSONObject(i)
                val name = f.getString("name")
                SafeFiles.requireName(name)
                val size = f.getLong("size")
                val sha = f.getString("sha256").lowercase()
                if (size < 0 || !sha.matches(Regex("[0-9a-f]{64}")))
                    throw IOException("Oferta no válida")
                OfferedFile(name, size, sha)
            }
        if (files.map { it.name }.toSet().size != files.size)
            throw IOException("Nombres repetidos en la oferta")
        return Offer(o.optString("from").take(64).ifBlank { "Otro teléfono" }, files)
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Asks [address]:[port] for its name; null when it is not an OI Archivos receiver. */
    fun hello(address: String, port: Int = PORT, timeoutMs: Int = 800): Peer? =
        runCatching {
                val c = URL("http://$address:$port$PATH/hola").openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs * 3
                try {
                    if (c.responseCode != 200) return null
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    if (o.optString("app") != "OI Archivos") return null
                    Peer(address, port, o.optString("name").take(64))
                } finally {
                    c.disconnect()
                }
            }
            .getOrNull()

    /** Finds receivers on [hosts] in parallel. */
    suspend fun discover(
        hosts: List<Inet4Address>,
        port: Int = PORT,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onFound: (Peer) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(48)
        val done = java.util.concurrent.atomic.AtomicInteger()
        for (host in hosts) launch(Dispatchers.IO) {
            gate.withPermit {
                host.hostAddress?.let { hello(it, port) }?.let(onFound)
                onProgress(done.incrementAndGet(), hosts.size)
            }
        }
    }

    /**
     * Offers [files] to [peer], waits for the person there to accept, then sends them.
     * Returns false when the offer is declined.
     */
    suspend fun send(
        peer: Peer,
        myName: String,
        files: List<File>,
        report: (OpProgress) -> Unit
    ): Boolean {
        val tracker = Tracker("Enviando a ${peer.name}", report)
        tracker.totalFiles = files.size
        tracker.totalBytes = files.sumOf { it.length() }
        tracker.current = "Preparando"
        tracker.emit()
        val offered =
            files.map {
                currentCoroutineContext().ensureActive()
                SafeFiles.requireRegular(it)
                if (!it.isFile) throw IOException("Solo se envían archivos: ${it.name}")
                OfferedFile(it.name, it.length(), sha256(it))
            }
        val base = "http://${peer.address}:${peer.port}$PATH"
        tracker.current = "Esperando que ${peer.name} acepte"
        tracker.emit()
        val c = URL("$base/oferta").openConnection() as HttpURLConnection
        val token =
            try {
                c.connectTimeout = 5000
                c.readTimeout = 130_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(offerJson(Offer(myName, offered)).toByteArray()) }
                when (c.responseCode) {
                    200 ->
                        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                            .getString("token")
                    403 -> return false
                    else -> throw IOException("El otro teléfono respondió ${c.responseCode}")
                }
            } finally {
                c.disconnect()
            }
        for ((index, file) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            tracker.current = file.name
            val put =
                URL("$base/archivo/$index?token=${URLEncoder.encode(token, "UTF-8")}")
                    .openConnection() as HttpURLConnection
            try {
                put.connectTimeout = 5000
                put.readTimeout = 60_000
                put.requestMethod = "PUT"
                put.doOutput = true
                put.setFixedLengthStreamingMode(file.length())
                put.outputStream.use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            tracker.addBytes(n.toLong())
                        }
                    }
                }
                if (put.responseCode != 200)
                    throw IOException(
                        "${file.name}: " +
                            (put.errorStream?.bufferedReader()?.use { it.readText() }
                                ?: "respuesta ${put.responseCode}"))
            } finally {
                put.disconnect()
            }
            tracker.fileDone()
        }
        return true
    }
}

/**
 * The receiving side. [decide] runs on a server thread and blocks until the person accepts or
 * declines (or a time limit passes); [onReceived] is called for every verified file.
 */
class NearbyReceiver(
    private val destination: File,
    private val myName: String,
    port: Int = Nearby.PORT,
    private val decide: (Nearby.Offer) -> Boolean,
    private val onReceived: (File) -> Unit = {},
    private val onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
) : NanoHTTPD(port) {
    private class Pending(val offer: Nearby.Offer, val done: MutableSet<Int>)

    private val offers = ConcurrentHashMap<String, Pending>()

    private fun text(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body).apply {
            // A rejected request may leave its body unread; reusing the connection would make
            // those bytes the start of the next request.
            if (status != Response.Status.OK) closeConnection(true)
        }

    override fun serve(session: IHTTPSession): Response =
        try {
            val uri = session.uri
            when {
                session.method == Method.GET && uri == "${Nearby.PATH}/hola" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        JSONObject().put("app", "OI Archivos").put("name", myName).toString())
                session.method == Method.POST && uri == "${Nearby.PATH}/oferta" -> offer(session)
                session.method == Method.PUT && uri.startsWith("${Nearby.PATH}/archivo/") ->
                    file(session, uri.substringAfterLast('/').toInt())
                else -> text(Response.Status.NOT_FOUND, "No existe")
            }
        } catch (e: Exception) {
            text(Response.Status.BAD_REQUEST, e.message ?: "Solicitud no válida")
        }

    private fun body(session: IHTTPSession, limit: Long): ByteArray {
        val length = session.headers["content-length"]?.toLongOrNull() ?: throw IOException("Falta el tamaño")
        if (length !in 0..limit) throw IOException("Tamaño no permitido")
        val bytes = ByteArray(length.toInt())
        java.io.DataInputStream(session.inputStream).readFully(bytes)
        return bytes
    }

    private fun offer(session: IHTTPSession): Response {
        val offer = Nearby.parseOffer(String(body(session, 1L shl 20), Charsets.UTF_8))
        if (!destination.isDirectory && !destination.mkdirs())
            throw IOException("No se pudo crear la carpeta de destino")
        if (offer.bytes > destination.usableSpace)
            return text(Response.Status.BAD_REQUEST, "No hay espacio suficiente")
        if (!decide(offer)) return text(Response.Status.FORBIDDEN, "Rechazado")
        val token = UUID.randomUUID().toString()
        offers[token] = Pending(offer, java.util.Collections.synchronizedSet(HashSet()))
        return newFixedLengthResponse(
            Response.Status.OK, "application/json", JSONObject().put("token", token).toString())
    }

    private fun file(session: IHTTPSession, index: Int): Response {
        val pending =
            offers[session.parms["token"].orEmpty()]
                ?: return text(Response.Status.FORBIDDEN, "Envío no aceptado")
        val expected =
            pending.offer.files.getOrNull(index) ?: throw IOException("Archivo no ofrecido")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length != expected.size) throw IOException("El tamaño no coincide con la oferta")
        if (!pending.done.add(index)) throw IOException("Archivo ya recibido")
        val temp = File(destination, ".oi-recibiendo-${UUID.randomUUID()}.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var left = length
            val input: InputStream = session.inputStream
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Envío incompleto")
                    out.write(buffer, 0, n)
                    md.update(buffer, 0, n)
                    left -= n
                    onProgress(expected.name, length - left, length)
                }
                out.fd.sync()
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != expected.sha256) throw IOException("El archivo llegó dañado")
            val target = FileOps.uniqueName(destination, expected.name)
            SafeFiles.commit(temp, target, replace = false)
            onReceived(target)
            if (pending.done.size == pending.offer.files.size)
                offers.values.remove(pending)
            return text(Response.Status.OK, "Recibido")
        } catch (e: Exception) {
            pending.done.remove(index)
            throw e
        } finally {
            temp.delete()
        }
    }
}
