package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Cliente del protocolo ADB por red, para instalar y manejar una Android TV (o cualquier Android con
 * la depuración por red activada, puerto 5555) como el «asistente de TV» de ES.
 *
 * Cada mensaje lleva una cabecera de 24 bytes (orden, dos argumentos, longitud, suma de los bytes y
 * la orden invertida) y luego los datos. Al conectar, la TV pide firmar un número al azar con la
 * clave RSA de la app; la primera vez no la conoce, así que se le manda la clave pública y la TV
 * pregunta en pantalla si se permite la depuración desde este teléfono.
 */
object Adb {
    const val PORT = 5555
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val VERSION = 0x01000001
    const val MAX_DATA = 256 * 1024
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** Prefijo DigestInfo de SHA-1: adbd comprueba la firma como si el número fuera un resumen SHA-1. */
    private val SHA1_PREFIX =
        byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0))

    fun encode(m: Message): ByteArray {
        val b = ByteBuffer.allocate(24 + m.data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(m.command).putInt(m.arg0).putInt(m.arg1).putInt(m.data.size)
        b.putInt(m.data.sumOf { it.toInt() and 0xFF }) // las versiones nuevas no lo miran, las viejas sí
        b.putInt(m.command.inv())
        b.put(m.data)
        return b.array()
    }

    fun read(input: DataInputStream): Message {
        val head = ByteArray(24)
        input.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int
        if (b.int != command.inv()) throw IOException(tr("Respuesta no válida del dispositivo"))
        if (length < 0 || length > 1024 * 1024) throw IOException(tr("Respuesta no válida del dispositivo"))
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    /** Firma el número que manda el dispositivo, como hace el adb del ordenador. */
    fun sign(keys: KeyPair, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(keys.private)
            update(SHA1_PREFIX + token)
            sign()
        }

    /**
     * La clave pública en el formato que guarda Android en adb_keys: la estructura RSAPublicKey de
     * mincrypt (longitud en palabras, -1/n[0] mod 2^32, el módulo, R² mod n y el exponente, todo en
     * palabras de 32 bits con el byte menor primero) en Base64, un espacio y un nombre.
     */
    fun publicKey(keys: KeyPair, name: String): ByteArray {
        val pub = keys.public as java.security.interfaces.RSAPublicKey
        val n = pub.modulus
        val words = (n.bitLength() + 31) / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv.toInt())
        putWords(b, n, words)
        putWords(b, rr, words)
        b.putInt(pub.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(b.array()) + " " + name + "\u0000").toByteArray()
    }

    private fun putWords(b: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        for (i in 0 until words) b.putInt(value.shiftRight(32 * i).and(mask).toInt())
    }

    /** Lee una clave pública en el formato de adb_keys (lo usan las pruebas y sirve para mostrar su huella). */
    fun parsePublicKey(text: String): java.security.interfaces.RSAPublicKey {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(text.trim().substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        val words = b.int
        b.int
        var n = BigInteger.ZERO
        for (i in 0 until words) n = n.or(BigInteger.valueOf(b.int.toLong() and 0xFFFFFFFFL).shiftLeft(32 * i))
        repeat(words) { b.int }
        val e = BigInteger.valueOf(b.int.toLong() and 0xFFFFFFFFL)
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as java.security.interfaces.RSAPublicKey
    }

    /** Clave RSA de la app, creada la primera vez y guardada en [file]; la TV la recuerda si se marca «Permitir siempre». */
    fun loadOrCreateKeys(file: File): KeyPair {
        if (file.isFile) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                val private = factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
                val public = factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent))
                return KeyPair(public, private)
            }
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(keys.private.encoded)
        if (!tmp.renameTo(file)) throw IOException(tr("No se pudo guardar la clave de ADB"))
        return keys
    }

    /** «192.168.1.40», «192.168.1.40:5556» o «[fe80::1]:5555» → dirección y puerto. */
    fun parseAddress(text: String): Pair<String, Int> {
        val t = text.trim()
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) throw IOException(tr("Dirección no válida"))
            val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: PORT
            return t.substring(1, end) to port
        }
        if (t.count { it == ':' } == 1) {
            val port = t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IOException(tr("Puerto no válido"))
            return t.substringBefore(':') to port
        }
        if (t.isEmpty()) throw IOException(tr("Dirección no válida"))
        return t to PORT
    }

    /** Lo que cuenta el dispositivo de sí mismo al conectar («device::ro.product.model=…;…»). */
    data class Device(val serial: String, val model: String, val product: String, val features: Set<String>) {
        val name get() = model.ifBlank { product }.ifBlank { serial }
    }

    fun parseBanner(banner: String, serial: String): Device {
        val props =
            banner.substringAfter("::", "").split(';').mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k to it.substringAfter('=')
            }.toMap()
        return Device(
            serial,
            props["ro.product.model"].orEmpty(),
            props["ro.product.name"].orEmpty(),
            props["features"].orEmpty().split(',').filter { it.isNotBlank() }.toSet())
    }

    /** Teclas del mando a distancia (códigos KEYCODE_ de Android). */
    enum class Key(val code: Int, private val labelEs: String) {
        UP(19, trKey("Arriba")),
        DOWN(20, trKey("Abajo")),
        LEFT(21, trKey("Izquierda")),
        RIGHT(22, trKey("Derecha")),
        OK(23, trKey("Aceptar")),
        BACK(4, trKey("Atrás")),
        HOME(3, trKey("Inicio")),
        MENU(82, trKey("Menú")),
        PLAY_PAUSE(85, trKey("Reproducir o pausar")),
        VOLUME_UP(24, trKey("Subir volumen")),
        VOLUME_DOWN(25, trKey("Bajar volumen")),
        POWER(26, trKey("Encender o apagar"));

        val label get() = tr(labelEs)
    }

    /**
     * Una conexión con el dispositivo. Las órdenes van de una en una (cada una abre su canal y lo
     * cierra al terminar), así que el cliente no es para usar desde varios hilos a la vez.
     */
    class Client private constructor(
        private val socket: Socket,
        private val reader: DataInputStream,
        val device: Device,
        private val maxData: Int
    ) : AutoCloseable {
        private val output: OutputStream = socket.getOutputStream()
        private var nextId = 1

        companion object {
            /**
             * Conecta y se autentica. Si la TV aún no conoce la clave de la app, muestra «¿Permitir la
             * depuración?» y se espera hasta [approvalMs] a que alguien acepte con el mando.
             */
            fun connect(
                host: String,
                port: Int,
                keys: KeyPair,
                keyName: String,
                timeoutMs: Int = 8000,
                approvalMs: Int = 60_000,
                onApprovalNeeded: () -> Unit = {}
            ): Client {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    socket.tcpNoDelay = true
                    val out = socket.getOutputStream()
                    val input = DataInputStream(socket.getInputStream().buffered())
                    fun send(m: Message) {
                        out.write(encode(m))
                        out.flush()
                    }
                    send(Message(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray()))
                    var signed = false
                    while (true) {
                        val m = read(input)
                        when (m.command) {
                            CNXN -> {
                                val banner = String(m.data, Charsets.UTF_8).trimEnd('\u0000')
                                val device = parseBanner(banner, "$host:$port")
                                socket.soTimeout = timeoutMs
                                return Client(socket, input, device, minOf(m.arg1, MAX_DATA).coerceAtLeast(4096))
                            }
                            AUTH -> {
                                if (m.arg0 != AUTH_TOKEN) throw IOException(tr("Respuesta no válida del dispositivo"))
                                if (!signed) {
                                    signed = true
                                    send(Message(AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data)))
                                } else {
                                    // No conoce la clave: se la manda y la TV pregunta en pantalla.
                                    send(Message(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys, keyName)))
                                    onApprovalNeeded()
                                    socket.soTimeout = approvalMs
                                }
                            }
                            else -> throw IOException(tr("Respuesta no válida del dispositivo"))
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    socket.close()
                    throw IOException(tr("El dispositivo no respondió. ¿Está activada la depuración por red y aceptada en la TV?"), e)
                } catch (e: java.io.EOFException) {
                    socket.close()
                    throw IOException(tr("El dispositivo cerró la conexión. Si en la TV salió «¿Permitir la depuración?», hay que aceptarla."), e)
                } catch (e: Throwable) {
                    socket.close()
                    throw e
                }
            }
        }

        private fun send(m: Message) {
            output.write(encode(m))
            output.flush()
        }

        /** Siguiente mensaje del canal [local]; los de otros canales (ya cerrados) se descartan. */
        private fun next(local: Int): Message {
            while (true) {
                val m = read(reader)
                if (m.arg1 == local) return m
            }
        }

        /** Un canal abierto con un servicio del dispositivo («shell:…», «exec:…», «sync:»). */
        inner class Stream internal constructor(private val local: Int, private val remote: Int) : AutoCloseable {
            private var pending = ByteArray(0)
            private var pos = 0
            var closed = false
                private set

            /** Siguiente trozo que manda el dispositivo, o null si cerró el canal. */
            fun receive(): ByteArray? {
                if (pos < pending.size) {
                    val rest = pending.copyOfRange(pos, pending.size)
                    pos = pending.size
                    return rest
                }
                if (closed) return null
                while (true) {
                    val m = next(local)
                    when (m.command) {
                        WRTE -> {
                            send(Message(OKAY, local, remote))
                            if (m.data.isNotEmpty()) return m.data
                        }
                        CLSE -> {
                            closed = true
                            return null
                        }
                        OKAY -> {}
                        else -> throw IOException(tr("Respuesta no válida del dispositivo"))
                    }
                }
            }

            /** Exactamente [n] bytes (para el protocolo sync). */
            fun readExactly(n: Int): ByteArray {
                val out = ByteArray(n)
                var filled = 0
                while (filled < n) {
                    if (pos >= pending.size) {
                        pending = receive() ?: throw IOException(tr("El dispositivo cerró la conexión"))
                        pos = 0
                    }
                    val take = minOf(n - filled, pending.size - pos)
                    System.arraycopy(pending, pos, out, filled, take)
                    pos += take
                    filled += take
                }
                return out
            }

            /** Manda [data] en trozos y espera la confirmación de cada uno. */
            fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
                var at = offset
                while (at < offset + length) {
                    val n = minOf(maxData, offset + length - at)
                    send(Message(WRTE, local, remote, data.copyOfRange(at, at + n)))
                    at += n
                    while (true) {
                        val m = next(local)
                        when (m.command) {
                            OKAY -> break
                            // Puede contestar antes de leerlo todo (por ejemplo, un error al instalar).
                            WRTE -> {
                                send(Message(OKAY, local, remote))
                                pending += m.data
                            }
                            CLSE -> {
                                closed = true
                                throw IOException(String(pending, Charsets.UTF_8).trim().ifEmpty { tr("El dispositivo cerró la conexión") })
                            }
                        }
                    }
                }
            }

            /** Todo lo que falta hasta que el dispositivo cierra el canal. */
            fun readAll(): String {
                val out = ByteArrayOutputStream()
                while (true) out.write(receive() ?: break)
                return out.toString("UTF-8")
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    runCatching { send(Message(CLSE, local, remote)) }
                }
            }
        }

        fun open(service: String): Stream {
            val local = nextId++
            send(Message(OPEN, local, 0, (service + "\u0000").toByteArray()))
            while (true) {
                val m = next(local)
                when (m.command) {
                    OKAY -> return Stream(local, m.arg0)
                    CLSE -> throw IOException(tr("El dispositivo rechazó «{0}»", service.substringBefore(':')))
                }
            }
        }

        fun shell(command: String): String = open("shell:$command").use { it.readAll() }

        /** Apps instaladas por el usuario (sin las del sistema), ordenadas. */
        fun packages(): List<String> =
            shell("pm list packages -3").lines().mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.isNotEmpty() && p != it.trim() } }.sorted()

        /** Abre una app; en una TV se busca primero la entrada para televisores (LEANBACK). */
        fun launch(pkg: String) {
            requirePackageName(pkg)
            val tv = shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1")
            if (!tv.contains("No activities found")) return
            val phone = shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            if (phone.contains("No activities found")) throw IOException(tr("«{0}» no se puede abrir", pkg))
        }

        fun uninstall(pkg: String) {
            requirePackageName(pkg)
            val out = shell("pm uninstall $pkg").trim()
            if (!out.contains("Success")) throw IOException(out.ifEmpty { tr("No se pudo desinstalar") })
        }

        fun key(key: Key) {
            shell("input keyevent ${key.code}")
        }

        /**
         * Instala un APK. En Android 7 o superior se manda directamente a «cmd package install»;
         * en los más viejos se copia antes a /data/local/tmp con el protocolo sync y se usa pm install.
         */
        fun install(apk: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
            val size = apk.length()
            val streamed = "cmd" in device.features || "abb_exec" in device.features || "abb" in device.features
            if (streamed) {
                val out =
                    try {
                        open("exec:cmd package install -r -S $size").use { s ->
                            copy(apk, s, progress)
                            s.readAll()
                        }
                    } catch (e: IOException) {
                        e.message.orEmpty()
                    }
                when {
                    out.contains("Success") -> return
                    out.contains("Failure") || out.contains("Error") -> throw IOException(failure(out))
                }
                // Sin respuesta clara: se prueba la vía antigua.
            }
            val remote = "/data/local/tmp/oi-archivos-${System.currentTimeMillis()}.apk"
            push(apk, remote, progress)
            try {
                val out = shell("pm install -r '$remote'")
                if (!out.contains("Success")) throw IOException(failure(out))
            } finally {
                runCatching { shell("rm -f '$remote'") }
            }
        }

        private fun failure(out: String): String =
            Regex("Failure \\[([^\\]]+)]").find(out)?.groupValues?.get(1)?.let { tr("La TV no lo instaló: {0}", it) }
                ?: out.trim().ifEmpty { tr("No se pudo instalar") }

        private fun copy(file: File, s: Stream, progress: (Long, Long) -> Unit) {
            val total = file.length()
            var sent = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(maxData)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    s.write(buf, 0, n)
                    sent += n
                    progress(sent, total)
                }
            }
        }

        /** Copia [file] a [remote] en el dispositivo con el protocolo sync (SEND, DATA…, DONE). */
        fun push(file: File, remote: String, progress: (Long, Long) -> Unit = { _, _ -> }) {
            open("sync:").use { s ->
                fun request(id: String, arg: Int, data: ByteArray = ByteArray(0)) {
                    val b = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                    b.put(id.toByteArray()).putInt(arg).put(data)
                    s.write(b.array())
                }
                val spec = "$remote,${0x81a4}".toByteArray() // archivo normal, permisos 0644
                request("SEND", spec.size, spec)
                val total = file.length()
                var sent = 0L
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024) // el protocolo sync no acepta trozos mayores
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        request("DATA", n, buf.copyOf(n))
                        sent += n
                        progress(sent, total)
                    }
                }
                request("DONE", (System.currentTimeMillis() / 1000).toInt())
                val head = ByteBuffer.wrap(s.readExactly(8)).order(ByteOrder.LITTLE_ENDIAN)
                val id = ByteArray(4).also { head.get(it) }.toString(Charsets.US_ASCII)
                val length = head.int
                if (id != "OKAY") {
                    val reason = if (id == "FAIL") String(s.readExactly(length), Charsets.UTF_8) else id
                    throw IOException(tr("No se pudo copiar a la TV: {0}", reason))
                }
                request("QUIT", 0)
            }
        }

        override fun close() {
            socket.close()
        }
    }

    /** Solo nombres de paquete válidos llegan al shell de la TV. */
    fun requirePackageName(pkg: String) {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)) throw IOException(tr("Nombre de app no válido"))
    }
}
