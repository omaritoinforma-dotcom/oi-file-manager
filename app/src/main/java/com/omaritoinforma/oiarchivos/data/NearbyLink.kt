package com.omaritoinforma.oiarchivos.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Enlace del código QR para enviar entre teléfonos («código QR» del Sender de ES). El teléfono que
 * recibe muestra el QR; el que envía lo lee con su cámara y el enlace abre OI Archivos listo para
 * enviar. Un QR puede venir de cualquiera, así que solo se aceptan direcciones de redes locales y,
 * aun así, el envío no empieza sin confirmarlo, y el receptor tiene que aceptar los archivos.
 */
object NearbyLink {
    const val SCHEME = "oiarchivos"
    const val HOST = "enviar"

    fun build(address: String, port: Int, name: String, wifi: String? = null, wifiKey: String = ""): String =
        "$SCHEME://$HOST?host=${enc(address)}&port=$port&nombre=${enc(name.take(60))}" +
            (if (wifi != null) "&wifi=${enc(wifi)}&clave=${enc(wifiKey)}" else "")

    /** El destino que describe [link], o null si no es un enlace válido o la dirección no es local. */
    fun parse(link: String): Nearby.Peer? {
        val prefix = "$SCHEME://$HOST?"
        if (!link.startsWith(prefix) || link.length > 400) return null
        val query =
            link.removePrefix(prefix).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to runCatching { URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrNull()
            }.toMap()
        val host = query["host"]?.trim() ?: return null
        val port = query["port"]?.toIntOrNull() ?: return null
        if (!isLocalAddress(host) || port !in 1024..65535) return null
        val name = query["nombre"].orEmpty().filter { !it.isISOControl() }.take(60).trim().ifEmpty { host }
        // Red del punto de acceso del que recibe: nombre de hasta 32 bytes y clave WPA2 de 8 a 63 caracteres (o vacía).
        val wifi = query["wifi"]
        if (wifi != null) {
            val key = query["clave"].orEmpty()
            if (wifi.isEmpty() || wifi.toByteArray().size > 32 || wifi.any { it.isISOControl() }) return null
            if (key.isNotEmpty() && (key.length !in 8..63 || key.any { it.code !in 0x20..0x7E })) return null
            return Nearby.Peer(host, port, name, wifi, key)
        }
        return Nearby.Peer(host, port, name)
    }

    /** Direcciones IPv4 de la red local: 10.x, 172.16-31.x, 192.168.x y enlace local 169.254.x. */
    fun isLocalAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { if (it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit)) it.toInt() else return false }
        if (n.any { it !in 0..255 }) return false
        return when {
            n[0] == 10 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            n[0] == 192 && n[1] == 168 -> true
            n[0] == 169 && n[1] == 254 -> true
            else -> false
        }
    }

    /** Cuadrícula del código QR de [text]: true = módulo oscuro. Lleva un margen de 2 módulos. */
    fun qr(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
