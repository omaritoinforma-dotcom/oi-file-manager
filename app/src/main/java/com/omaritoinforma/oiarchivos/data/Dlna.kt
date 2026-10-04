package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Enviar a la TV por DLNA/UPnP, como el «DLNA» de ES. ES usa la biblioteca Cling; aquí basta con lo
 * que hace falta para mandar un archivo: buscar televisores (SSDP), leer su descripción y darles
 * órdenes de AVTransport (SOAP). La TV descarga el archivo del [StreamServer].
 */
object Dlna {
    data class Renderer(val name: String, val location: String, val controlUrl: String, val host: String)

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val MAX_XML = 256 * 1024

    internal fun searchMessage() =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDRESS:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $RENDERER\r\n\r\n"

    /** Dirección LOCATION de una respuesta SSDP, o null si no es válida. */
    internal fun location(response: String): String? =
        response
            .lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * Busca televisores durante [timeoutMs]. Por defecto pregunta a toda la red (multidifusión); con
     * [target] se pregunta a un equipo concreto, para añadir una TV por su dirección.
     */
    fun search(
        timeoutMs: Int = 3000,
        target: InetSocketAddress = InetSocketAddress(SSDP_ADDRESS, SSDP_PORT),
        onFound: (Renderer) -> Unit
    ) {
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val message = searchMessage().toByteArray()
            repeat(2) { socket.send(DatagramPacket(message, message.size, target)) }
            val seen = HashSet<String>()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val location = location(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                if (seen.add(location)) runCatching { describe(location) }.getOrNull()?.let(onFound)
            }
        }
    }

    /** Añadir una TV a mano: una URL de descripción, o una IP (se le pregunta por SSDP). */
    fun find(address: String, timeoutMs: Int = 3000): Renderer? {
        val text = address.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) return describe(text)
        val host = text.substringBefore(':')
        val port = text.substringAfter(':', "").toIntOrNull() ?: SSDP_PORT
        var found: Renderer? = null
        search(timeoutMs, InetSocketAddress(InetAddress.getByName(host), port)) {
            if (found == null) found = it
        }
        return found
    }

    /** Lee la descripción del equipo: su nombre y dónde recibe las órdenes de reproducción. */
    fun describe(location: String): Renderer {
        val document = parse(http(location, "GET", null, null))
        val root = document.documentElement
        val name = text(root, "friendlyName") ?: "TV"
        val service =
            elements(root, "service").firstOrNull {
                text(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:AVTransport:") == true
            } ?: throw IOException(tr("«{0}» no admite reproducir archivos (sin AVTransport)", name))
        val control = text(service, "controlURL") ?: throw IOException(tr("Descripción incompleta"))
        val base = text(root, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        return Renderer(name, location, URL(URL(base), control).toString(), URL(location).host)
    }

    fun load(renderer: Renderer, url: String, title: String, mime: String) {
        soap(
            renderer,
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl(url, title, mime)))
    }

    fun play(renderer: Renderer) = soap(renderer, "Play", listOf("InstanceID" to "0", "Speed" to "1"))

    fun pause(renderer: Renderer) = soap(renderer, "Pause", listOf("InstanceID" to "0"))

    fun stop(renderer: Renderer) = soap(renderer, "Stop", listOf("InstanceID" to "0"))

    fun seek(renderer: Renderer, seconds: Long) =
        soap(renderer, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to clock(seconds)))

    /** Posición y duración en segundos, si la TV las informa. */
    fun position(renderer: Renderer): Pair<Long, Long>? {
        val document = parse(soap(renderer, "GetPositionInfo", listOf("InstanceID" to "0")))
        val root = document.documentElement
        val at = text(root, "RelTime")?.let(::seconds) ?: return null
        return at to (text(root, "TrackDuration")?.let(::seconds) ?: 0L)
    }

    internal fun clock(seconds: Long) =
        "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)

    internal fun seconds(clock: String): Long? {
        val parts = clock.trim().substringBefore('.').split(':')
        if (parts.size != 3) return null
        val (h, m, s) = parts.map { it.toLongOrNull() ?: return null }
        return h * 3600 + m * 60 + s
    }

    /** Metadatos DIDL-Lite: muchas TV no reproducen sin el tipo de contenido. */
    internal fun didl(url: String, title: String, mime: String): String {
        val type =
            when {
                mime.startsWith("video/") -> "object.item.videoItem"
                mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
                mime.startsWith("image/") -> "object.item.imageItem.photo"
                else -> "object.item"
            }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title><upnp:class>$type</upnp:class><res protocolInfo=\"http-get:*:${escape(mime)}:*\">${escape(url)}</res></item></DIDL-Lite>"
    }

    internal fun envelope(action: String, args: List<Pair<String, String>>) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">" +
            args.joinToString("") { (key, value) -> "<$key>${escape(value)}</$key>" } +
            "</u:$action></s:Body></s:Envelope>"

    private fun soap(renderer: Renderer, action: String, args: List<Pair<String, String>>): String =
        http(renderer.controlUrl, "POST", envelope(action, args), "\"$AV_TRANSPORT#$action\"")

    private fun http(url: String, method: String, body: String?, soapAction: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPACTION", soapAction)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { input -> input.readNBytesCompat(MAX_XML) } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) {
                val detail = runCatching { text(parse(text).documentElement, "errorDescription") }.getOrNull()
                throw IOException(tr("La TV respondió {0}", code) + (detail?.let { ": $it" } ?: ""))
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException(tr("Respuesta demasiado grande"))
        }
        return out.toByteArray()
    }

    /** XML de la TV sin DTD ni entidades externas: un equipo de la red no debe poder leer archivos (XXE). */
    internal fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        if ("<!DOCTYPE" in xml || "<!ENTITY" in xml) throw IOException(tr("Descripción no válida"))
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun elements(root: Element, name: String): List<Element> {
        val list = root.getElementsByTagNameNS("*", name)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun text(root: Element, name: String): String? =
        elements(root, name).firstOrNull()?.textContent?.trim()

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
