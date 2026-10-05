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
 * Envío entre teléfonos por la misma Wi-Fi, como el Sender de ES: el receptor escucha en un puerto
 * fijo, el emisor lo encuentra recorriendo la subred, ofrece los archivos y solo los envía si el
 * receptor acepta. A diferencia de ES, cada archivo se comprueba con SHA-256 y los nombres se validan
 * para que nadie pueda escribir fuera de la carpeta de destino.
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

    /**
     * Un teléfono que recibe. Si recibe con su propio punto de acceso, [wifi] y [wifiKey] son la red
     * a la que hay que unirse antes de enviar (clave vacía: red abierta).
     */
    data class Peer(val address: String, val port: Int, val name: String, val wifi: String? = null, val wifiKey: String = "")

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

    /** Lee y valida una oferta; una no válida se rechaza antes de preguntar a nadie. */
    fun parseOffer(json: String): Offer {
        val o = JSONObject(json)
        val list = o.getJSONArray("files")
        if (list.length() !in 1..MAX_FILES) throw IOException(tr("Oferta con demasiados archivos"))
        val files =
            (0 until list.length()).map { i ->
                val f = list.getJSONObject(i)
                val name = f.getString("name")
                SafeFiles.requireName(name)
                val size = f.getLong("size")
                val sha = f.getString("sha256").lowercase()
                if (size < 0 || !sha.matches(Regex("[0-9a-f]{64}")))
                    throw IOException(tr("Oferta no válida"))
                OfferedFile(name, size, sha)
            }
        if (files.map { it.name }.toSet().size != files.size)
            throw IOException(tr("Nombres repetidos en la oferta"))
        return Offer(o.optString("from").take(64).ifBlank { tr("Otro teléfono") }, files)
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

    /** Pide su nombre a [address]:[port]; null si no es un receptor de OI Archivos. */
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

    /** Busca receptores en [hosts] en paralelo. */
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
     * Ofrece [files] a [peer], espera a que la otra persona acepte y los envía.
     * Devuelve false si rechaza la oferta.
     */
    suspend fun send(
        peer: Peer,
        myName: String,
        files: List<File>,
        report: (OpProgress) -> Unit
    ): Boolean {
        val tracker = Tracker(tr("Enviando a {0}", peer.name), report)
        tracker.totalFiles = files.size
        tracker.totalBytes = files.sumOf { it.length() }
        tracker.current = tr("Preparando")
        tracker.emit()
        val offered =
            files.map {
                currentCoroutineContext().ensureActive()
                SafeFiles.requireRegular(it)
                if (!it.isFile) throw IOException(tr("Solo se envían archivos: {0}", it.name))
                OfferedFile(it.name, it.length(), sha256(it))
            }
        val base = "http://${peer.address}:${peer.port}$PATH"
        tracker.current = tr("Esperando que {0} acepte", peer.name)
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
                    else -> throw IOException(tr("El otro teléfono respondió {0}", c.responseCode))
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
 * Lado receptor. [decide] se ejecuta en un hilo del servidor y espera a que la persona acepte o
 * rechace (o se agote el tiempo); [onReceived] se llama con cada archivo comprobado.
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
            // Una solicitud rechazada puede dejar su contenido sin leer; si se reutilizara la
            // conexión, esos bytes serían el principio de la siguiente solicitud.
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
        val length = session.headers["content-length"]?.toLongOrNull() ?: throw IOException(tr("Falta el tamaño"))
        if (length !in 0..limit) throw IOException(tr("Tamaño no permitido"))
        val bytes = ByteArray(length.toInt())
        java.io.DataInputStream(session.inputStream).readFully(bytes)
        return bytes
    }

    private fun offer(session: IHTTPSession): Response {
        val offer = Nearby.parseOffer(String(body(session, 1L shl 20), Charsets.UTF_8))
        if (!destination.isDirectory && !destination.mkdirs())
            throw IOException(tr("No se pudo crear la carpeta de destino"))
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
            pending.offer.files.getOrNull(index) ?: throw IOException(tr("Archivo no ofrecido"))
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length != expected.size) throw IOException(tr("El tamaño no coincide con la oferta"))
        if (!pending.done.add(index)) throw IOException(tr("Archivo ya recibido"))
        val temp = File(destination, ".oi-recibiendo-${UUID.randomUUID()}.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var left = length
            val input: InputStream = session.inputStream
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException(tr("Envío incompleto"))
                    out.write(buffer, 0, n)
                    md.update(buffer, 0, n)
                    left -= n
                    onProgress(expected.name, length - left, length)
                }
                out.fd.sync()
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != expected.sha256) throw IOException(tr("El archivo llegó dañado"))
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
