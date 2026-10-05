package com.omaritoinforma.oiarchivos.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Standard Bluetooth OBEX File Transfer Profile, over a paired RFCOMM connection. */
@SuppressLint(
    "MissingPermission") // Explicit runtime check below; Android also checks every socket call.
internal class BluetoothFs(connection: Connection, ctx: Context) : RemoteFs {
    private val socket: BluetoothSocket
    private val client: ObexFtpClient

    init {
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED)
            throw IOException(tr("Concede el permiso de dispositivos cercanos para usar Bluetooth"))
        val adapter =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw IOException(tr("Este dispositivo no tiene Bluetooth"))
        if (!adapter.isEnabled) throw IOException(tr("Activa Bluetooth en los ajustes de Android"))
        socket =
            adapter
                .getRemoteDevice(connection.host)
                .createRfcommSocketToServiceRecord(
                    UUID.fromString("00001106-0000-1000-8000-00805f9b34fb"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { socket.connect() }.get(30, TimeUnit.SECONDS)
            client = ObexFtpClient(socket.inputStream, socket.outputStream) { socket.close() }
        } catch (e: Exception) {
            socket.close()
            throw IOException(
                tr("No se pudo abrir el explorador Bluetooth. El equipo remoto debe ofrecer OBEX FTP y autorizar la conexión."),
                e)
        } finally {
            executor.shutdownNow()
        }
    }

    override fun list(path: String) = client.list(path)

    override fun read(path: String) = client.read(path)

    override fun write(parent: String, name: String, input: InputStream, size: Long) = client.write(parent, name, input, size)

    override fun mkdir(parent: String, name: String) = client.mkdir(parent, name)

    override fun delete(entry: RemoteEntry) = client.delete(entry)

    override fun rename(entry: RemoteEntry, name: String) = client.rename(entry, name)

    override fun close() {
        socket.close()
    }
}

/**
 * Cliente OBEX FTP sobre cualquier par de flujos (un socket RFCOMM en el teléfono, tuberías en las
 * pruebas). [abort] corta la conexión si el otro equipo no responde a tiempo.
 */
internal class ObexFtpClient(private val input: InputStream, private val output: OutputStream, private val abort: () -> Unit) {
    private var connectionId = ByteArray(0)
    private var mtu = 8192

    init {
        val reply =
            exchange(
                0x80,
                byteArrayOf(0x10, 0, 0x20, 0) + ObexCodec.bytes(0x46, ObexFtpServer.FTP_TARGET),
                connecting = true)
        if (reply.size < 7) throw IOException(tr("Respuesta Bluetooth incompleta"))
        val remoteMtu = ObexCodec.ushort(reply, 5)
        if (remoteMtu < 255) throw IOException(tr("Tamaño de paquete OBEX no válido"))
        mtu = remoteMtu.coerceAtMost(32768)
        ObexCodec.headers(reply, 7)
            .firstOrNull { it.first == 0xcb }
            ?.let { connectionId = byteArrayOf(0xcb.toByte()) + it.second }
    }

    private fun exchange(op: Int, headers: ByteArray, connecting: Boolean = false): ByteArray {
        val body = if (connecting || op == 0x85) headers else connectionId + headers
        val packet = ObexCodec.packet(op, body)
        if (!connecting && packet.size > mtu)
            throw IOException(tr("Paquete Bluetooth demasiado grande"))
        output.write(packet)
        output.flush()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun readBytes(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var position = 0
            while (position < count) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) {
                    abort()
                    throw IOException(tr("El equipo Bluetooth no respondió a tiempo"))
                }
                if (input.available() == 0) {
                    Thread.sleep(10)
                    continue
                }
                val n = input.read(bytes, position, minOf(count - position, input.available()))
                if (n < 0) throw EOFException(tr("Se cerró la conexión Bluetooth"))
                position += n
            }
            return bytes
        }
        val prefix = readBytes(3)
        val length = ObexCodec.ushort(prefix, 1)
        if (length !in 3..65535) throw IOException(tr("Respuesta OBEX no válida"))
        val reply = prefix + readBytes(length - 3)
        val status = reply[0].toInt() and 255
        if (status != 0x90 && status != 0xa0)
            throw IOException(
                tr("El equipo Bluetooth rechazó la operación (OBEX {0})", status.toString(16)))
        return reply
    }

    private fun cd(path: String, create: Boolean = false) {
        exchange(0x85, byteArrayOf(2, 0) + connectionId + ObexCodec.name(""))
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size > 128) throw IOException(tr("Ruta Bluetooth demasiado profunda"))
        for ((i, part) in parts.withIndex()) {
            SafeFiles.requireName(part)
            val flags = if (create && i == parts.lastIndex) 0 else 2
            exchange(0x85, byteArrayOf(flags.toByte(), 0) + connectionId + ObexCodec.name(part))
        }
    }

    private fun get(name: String?, type: String? = null): InputStream {
        val headers =
            (name?.let { ObexCodec.name(it) } ?: ByteArray(0)) +
                (type?.let { ObexCodec.bytes(0x42, (it + "\u0000").toByteArray(Charsets.US_ASCII)) }
                    ?: ByteArray(0))
        return object : InputStream() {
            private var reply: ByteArray? = exchange(0x83, headers)
            private var body = ByteArrayInputStream(ByteArray(0))
            private var finished = false

            override fun read(): Int =
                ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                while (true) {
                    val n = body.read(buffer, offset, length)
                    if (n > 0) return n
                    if (finished && reply == null) return -1
                    val packet = reply ?: exchange(0x83, ByteArray(0))
                    reply = null
                    finished = (packet[0].toInt() and 255) == 0xa0
                    val chunks =
                        ObexCodec.headers(packet, 3).filter { it.first == 0x48 || it.first == 0x49 }
                    body =
                        ByteArrayInputStream(chunks.fold(ByteArray(0)) { out, h -> out + h.second })
                }
            }

            override fun close() {
                if (!finished || body.available() > 0) abort()
            }
        }
    }

    fun list(path: String): List<RemoteEntry> {
        cd(path)
        val data =
            get(null, "x-obex/folder-listing").use { source ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > 8 * 1024 * 1024)
                        throw IOException(tr("Listado Bluetooth demasiado grande"))
                }
                out.toByteArray()
            }
        val result =
            ObexFolderListing.parse(String(data, Charsets.UTF_8)).map { (name, folder, size) ->
                SafeFiles.requireName(name)
                RemoteEntry(RemoteFiles.join(path, name), name, folder, size)
            }
        if (result.size > 50000) throw IOException(tr("Demasiados archivos Bluetooth"))
        return result
    }

    fun read(path: String): InputStream {
        cd(path.substringBeforeLast('/', ""))
        return get(path.substringAfterLast('/').also(SafeFiles::requireName))
    }

    fun write(parent: String, name: String, input: InputStream, size: Long): String {
        SafeFiles.requireName(name)
        cd(parent)
        var first = true
        while (true) {
            val metadata =
                if (first)
                    ObexCodec.name(name) +
                        (if (size in 0..0xffffffffL)
                            byteArrayOf(0xc3.toByte()) +
                                ByteBuffer.allocate(4).putInt(size.toInt()).array()
                        else ByteArray(0))
                else ByteArray(0)
            val capacity = mtu - 3 - connectionId.size - metadata.size - 3
            if (capacity < 1)
                throw IOException(tr("El nombre supera el tamaño de paquete del equipo Bluetooth"))
            val buffer = ByteArray(capacity)
            val n = input.read(buffer)
            if (n == 0) continue
            first = false
            val final = n < 0
            val reply =
                exchange(
                    if (final) 0x82 else 0x02,
                    metadata +
                        ObexCodec.bytes(
                            if (final) 0x49 else 0x48,
                            if (final) ByteArray(0) else buffer.copyOf(n)))
            if (final) {
                if ((reply[0].toInt() and 255) != 0xa0)
                    throw IOException(tr("Subida Bluetooth incompleta"))
                break
            }
        }
        return RemoteFiles.join(parent, name)
    }

    fun mkdir(parent: String, name: String): String {
        SafeFiles.requireName(name)
        val path = RemoteFiles.join(parent, name)
        cd(path, create = true)
        return path
    }

    fun delete(entry: RemoteEntry) {
        cd(entry.path.substringBeforeLast('/', ""))
        exchange(0x82, ObexCodec.name(entry.name))
    }

    fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        cd(entry.path.substringBeforeLast('/', ""))
        exchange(
            0x86,
            byteArrayOf(0x94.toByte(), 1) + ObexCodec.name(entry.name) + ObexCodec.name(name, 0x15))
    }
}

/**
 * Lee un listado x-obex/folder-listing sin procesar DTD ni entidades externas: solo las etiquetas
 * file y folder, sus atributos y las entidades XML básicas.
 */
internal object ObexFolderListing {
    data class Item(val name: String, val folder: Boolean, val size: Long)

    fun parse(xml: String): List<Item> {
        if (xml.contains("<!ENTITY", ignoreCase = true)) throw IOException(tr("Listado Bluetooth no válido"))
        val text = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val attr = Regex("([A-Za-z_][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        return Regex("<(file|folder)\\b([^>]*)>").findAll(text).mapNotNull { m ->
            val attrs = attr.findAll(m.groupValues[2]).associate { a ->
                a.groupValues[1].lowercase() to decode(a.groupValues[3].ifEmpty { a.groupValues[4] })
            }
            val name = attrs["name"] ?: return@mapNotNull null
            Item(name, m.groupValues[1] == "folder", attrs["size"]?.toLongOrNull() ?: -1)
        }.toList()
    }

    private fun decode(value: String): String =
        Regex("&(#x[0-9A-Fa-f]+|#\\d+|amp|lt|gt|quot|apos);").replace(value) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code == null || code !in 1..0x10FFFF) throw IOException(tr("Listado Bluetooth no válido"))
                    String(Character.toChars(code))
                }
            }
        }
}

internal object ObexCodec {
    fun ushort(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) throw IOException(tr("Paquete OBEX incompleto"))
        return ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
    }

    fun packet(op: Int, body: ByteArray): ByteArray {
        val size = body.size + 3
        if (size > 65535) throw IOException(tr("Paquete OBEX demasiado grande"))
        return byteArrayOf(op.toByte(), (size shr 8).toByte(), size.toByte()) + body
    }

    fun bytes(id: Int, value: ByteArray): ByteArray = packet(id, value)

    fun name(value: String, id: Int = 1): ByteArray =
        bytes(id, (value + "\u0000").toByteArray(Charsets.UTF_16BE))

    fun headers(packet: ByteArray, start: Int): List<Pair<Int, ByteArray>> {
        if (start < 3 || start > packet.size || ushort(packet, 1) != packet.size)
            throw IOException(tr("Paquete OBEX no válido"))
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var offset = start
        while (offset < packet.size) {
            val id = packet[offset].toInt() and 255
            val variable = (id and 0xc0) < 0x80
            val length =
                if (variable) {
                    if (offset + 3 > packet.size) throw IOException(tr("Cabecera OBEX incompleta"))
                    ushort(packet, offset + 1)
                } else if ((id and 0xc0) == 0x80) 2 else 5
            val headerSize = if (variable) 3 else 1
            if (length < headerSize || offset + length > packet.size)
                throw IOException(tr("Cabecera OBEX no válida"))
            result += id to packet.copyOfRange(offset + headerSize, offset + length)
            offset += length
        }
        return result
    }
}
