package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * Envío a un Chromecast con el protocolo abierto CASTV2, sin el SDK de Google: TLS al puerto 8009 y
 * mensajes protobuf «CastMessage» con texto JSON dentro. Se abre el receptor multimedia por omisión
 * (CC1AD845) y se le pasa el enlace del archivo, que sirve [StreamServer] solo para la IP de la TV.
 */
object CastV2 {
    const val PORT = 8009
    const val DEFAULT_RECEIVER = "CC1AD845"
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val MAX_MESSAGE = 64 * 1024

    /** Un CastMessage: solo los campos de texto que usa el protocolo. */
    data class Message(val source: String, val destination: String, val namespace: String, val payload: String)

    /** Codifica [message] en protobuf (versión CASTV2_1_0, carga de texto). */
    fun encode(message: Message): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun text(field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            varint((field shl 3 or 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
        }
        varint((1 shl 3).toLong()) // protocol_version
        varint(0)
        text(2, message.source)
        text(3, message.destination)
        text(4, message.namespace)
        varint((5 shl 3).toLong()) // payload_type = STRING
        varint(0)
        text(6, message.payload)
        return out.toByteArray()
    }

    /** Lee un CastMessage; ignora los campos que no son de texto (por ejemplo, cargas binarias). */
    fun decode(data: ByteArray): Message {
        var i = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (i >= data.size || shift > 63) throw IOException(tr("Mensaje de Chromecast no válido"))
                val b = data[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val texts = HashMap<Int, String>()
        while (i < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint()
                2 -> {
                    val length = varint()
                    if (length < 0 || length > data.size - i) throw IOException(tr("Mensaje de Chromecast no válido"))
                    if (field in 2..6) texts[field] = String(data, i, length.toInt(), Charsets.UTF_8)
                    i += length.toInt()
                }
                5 -> i += 4
                1 -> i += 8
                else -> throw IOException(tr("Mensaje de Chromecast no válido"))
            }
        }
        return Message(texts[2].orEmpty(), texts[3].orEmpty(), texts[4].orEmpty(), texts[6].orEmpty())
    }

    /** Escribe [message] con su longitud delante (4 bytes, big endian), como lo espera el Chromecast. */
    fun write(out: OutputStream, message: Message) {
        val body = encode(message)
        out.write(
            byteArrayOf(
                (body.size ushr 24).toByte(), (body.size ushr 16).toByte(),
                (body.size ushr 8).toByte(), body.size.toByte()))
        out.write(body)
        out.flush()
    }

    fun read(input: DataInputStream): Message {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE) throw IOException(tr("Mensaje de Chromecast no válido"))
        val body = ByteArray(length)
        input.readFully(body)
        return decode(body)
    }

    /**
     * Abre la conexión TLS. Los Chromecast usan certificados propios firmados por Google que solo se
     * validan con su desafío de autenticación; como la app no lo hace, acepta el certificado, pero
     * solo hacia direcciones de la red local: el archivo nunca sale de ella.
     */
    fun tlsSocket(host: String, port: Int, timeoutMs: Int): Socket {
        val address = InetAddress.getByName(host)
        if (!(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress))
            throw IOException(tr("Solo se envía a un Chromecast de la red local"))
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(LocalCastTrust), SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(address, port), timeoutMs)
        plain.soTimeout = timeoutMs
        return (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also { it.startHandshake() }
    }

    /** Confianza para la conexión con un Chromecast de la red local (ver [tlsSocket]). */
    private object LocalCastTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("El Chromecast no pide certificado al teléfono")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Sin certificado")
            chain[0].checkValidity()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Un Chromecast de la red local. */
    data class Device(override val name: String, override val host: String, val port: Int = PORT) : Tv {
        override val key: String
            get() = "cast:$host:$port"
    }

    /**
     * Sesión con el receptor multimedia de un Chromecast. No es segura para varios hilos: la usa
     * [CastSession] desde uno solo. Cada orden lee mensajes hasta su respuesta y contesta a los PING.
     */
    class Client(
        private val device: Device,
        private val timeoutMs: Int = 8000,
        open: (String, Int, Int) -> Socket = ::tlsSocket
    ) : AutoCloseable {
        private val socket = open(device.host, device.port, timeoutMs)
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = socket.getOutputStream()
        private val sender = "sender-oi"
        private var requestId = 0
        private var transport: String? = null
        private var mediaSession: Int? = null

        init {
            send("receiver-0", NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        }

        private fun send(destination: String, namespace: String, payload: JSONObject) =
            write(output, Message(sender, destination, namespace, payload.toString()))

        /** Envía una orden con requestId y espera su respuesta (o un error del receptor). */
        private fun request(destination: String, namespace: String, payload: JSONObject): JSONObject {
            val id = ++requestId
            send(destination, namespace, payload.put("requestId", id))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val message = read(input)
                val json = runCatching { JSONObject(message.payload) }.getOrNull() ?: continue
                when {
                    message.namespace == NS_HEARTBEAT && json.optString("type") == "PING" ->
                        send(message.source, NS_HEARTBEAT, JSONObject().put("type", "PONG"))
                    message.namespace == NS_CONNECTION && json.optString("type") == "CLOSE" ->
                        throw EOFException(tr("El Chromecast cerró la conexión"))
                    json.optInt("requestId", -1) == id -> {
                        val type = json.optString("type")
                        if (type in setOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "LAUNCH_ERROR", "INVALID_PLAYER_STATE"))
                            throw IOException(tr("El Chromecast rechazó la orden ({0})", type))
                        return json
                    }
                }
            }
            throw IOException(tr("El Chromecast no respondió a tiempo"))
        }

        /** Abre el receptor multimedia por omisión y se conecta a él. */
        fun launch() {
            val status =
                request("receiver-0", NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_RECEIVER))
            val app =
                status.optJSONObject("status")?.optJSONArray("applications")?.let { apps ->
                    (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull {
                        it.optString("appId") == DEFAULT_RECEIVER
                    }
                } ?: throw IOException(tr("El Chromecast no abrió el reproductor"))
            transport = app.optString("transportId").ifBlank { throw IOException(tr("El Chromecast no abrió el reproductor")) }
            send(transport!!, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        }

        private fun media(payload: JSONObject): JSONObject {
            val to = transport ?: throw IOException(tr("Sin conexión con la TV"))
            val reply = request(to, NS_MEDIA, payload)
            reply.optJSONArray("status")?.optJSONObject(0)?.optInt("mediaSessionId", -1)?.takeIf { it >= 0 }?.let {
                mediaSession = it
            }
            return reply
        }

        private fun session(type: String) =
            JSONObject().put("type", type).put(
                "mediaSessionId", mediaSession ?: throw IOException(tr("No hay nada reproduciéndose")))

        fun load(url: String, title: String, contentType: String) {
            media(
                JSONObject()
                    .put("type", "LOAD")
                    .put("autoplay", true)
                    .put("currentTime", 0)
                    .put(
                        "media",
                        JSONObject()
                            .put("contentId", url)
                            .put("contentType", contentType)
                            .put("streamType", "BUFFERED")
                            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))))
        }

        fun play() {
            media(session("PLAY"))
        }

        fun pause() {
            media(session("PAUSE"))
        }

        fun stop() {
            media(session("STOP"))
        }

        fun seek(seconds: Long) {
            media(session("SEEK").put("currentTime", seconds))
        }

        /** Posición y duración en segundos, si el Chromecast las da. */
        fun position(): Pair<Long, Long>? {
            val status = media(JSONObject().put("type", "GET_STATUS")).optJSONArray("status")?.optJSONObject(0) ?: return null
            val at = status.optDouble("currentTime", Double.NaN)
            val total = status.optJSONObject("media")?.optDouble("duration", Double.NaN) ?: Double.NaN
            if (at.isNaN()) return null
            return at.toLong() to (if (total.isNaN()) 0L else total.toLong())
        }

        override fun close() {
            runCatching {
                transport?.let { send(it, NS_CONNECTION, JSONObject().put("type", "CLOSE")) }
                send("receiver-0", NS_CONNECTION, JSONObject().put("type", "CLOSE"))
            }
            runCatching { socket.close() }
        }
    }

    /**
     * Comprueba si en [address] hay un Chromecast (acepta TLS en el 8009) y busca su nombre en la
     * información que publica en el puerto 8008. Devuelve null si no responde.
     */
    fun find(address: String, timeoutMs: Int = 3000): Device? {
        val host = runCatching { InetAddress.getByName(address.trim()) }.getOrNull() as? Inet4Address ?: return null
        val ip = host.hostAddress ?: return null
        runCatching { tlsSocket(ip, PORT, timeoutMs).close() }.getOrElse { return null }
        return Device(name(ip, timeoutMs) ?: tr("Chromecast ({0})", ip), ip)
    }

    /** Nombre del Chromecast según /setup/eureka_info (sin datos personales: solo «name»). */
    internal fun name(ip: String, timeoutMs: Int): String? =
        runCatching {
                val c = java.net.URL("http://$ip:8008/setup/eureka_info?params=name").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                try {
                    if (c.responseCode != 200) return null
                    val text = c.inputStream.use { stream -> stream.readNBytesLimited(16 * 1024) }
                    JSONObject(text).optString("name").trim().take(64).ifBlank { null }
                } finally {
                    c.disconnect()
                }
            }
            .getOrNull()

    private fun java.io.InputStream.readNBytesLimited(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }
}
