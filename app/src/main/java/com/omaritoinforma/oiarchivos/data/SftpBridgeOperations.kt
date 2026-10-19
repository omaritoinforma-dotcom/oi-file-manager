package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs MCP SFTP operations through profiles already stored in OI Archivos.
 * SSH credentials never leave Android Keystore / the device. Only an opaque
 * connection ID crosses the bridge. Remote writes require the owner's switch.
 */
internal object SftpBridgeOperations {
    private const val MAX_TEXT = 65536

    fun execute(context: Context, action: String, args: JSONObject, allowChanges: Boolean): JSONObject? {
        if (action == "list_sftp_connections") {
            val connections = ConnectionStore(context).load()
                .filter { it.protocol == Protocol.SFTP }
            val items = JSONArray()
            connections.forEach { c ->
                items.put(JSONObject().put("id", c.id).put("label", c.label).put("root", root(c)))
            }
            return JSONObject().put("connections", items)
        }
        if (!action.startsWith("sftp_")) return null
        val id = args.optString("connectionId", "")
        val connection = ConnectionStore(context).load()
            .firstOrNull { it.id == id && it.protocol == Protocol.SFTP }
            ?: throw IOException("No existe esa conexión SFTP guardada")
        val path = pathWithin(connection, args.optString("path", root(connection)))
        RemoteFiles.connect(connection).use { fs ->
            return when (action) {
                "sftp_list" -> {
                    val items = JSONArray()
                    val entries = fs.list(path)
                    entries.take(250).forEach { e ->
                        items.put(JSONObject().put("name", e.name).put("path", e.path)
                            .put("directory", e.directory).put("bytes", e.size))
                    }
                    JSONObject().put("connectionId", id).put("path", path).put("items", items)
                        .put("truncated", entries.size > 250)
                }
                "sftp_read_text" -> {
                    val entry = existingFile(fs, path)
                    if (entry.size > MAX_TEXT) throw IOException("El archivo supera 64 KiB")
                    val data = fs.read(path).use { bounded(it, MAX_TEXT) }
                    val digest = MessageDigest.getInstance("SHA-256").digest(data)
                        .joinToString("") { "%02x".format(it) }
                    JSONObject().put("path", path).put("text", String(data, Charsets.UTF_8))
                        .put("sha256", digest)
                }
                "sftp_read_file_chunk" -> {
                    val entry = existingFile(fs, path)
                    val offset = args.optLong("offset", 0)
                    if (offset < 0 || offset > entry.size) throw IOException("Offset no válido")
                    val length = args.optInt("length", MAX_TEXT).coerceIn(1, MAX_TEXT)
                    val data = fs.read(path).use { stream ->
                        var remaining = offset
                        while (remaining > 0) {
                            val skipped = stream.skip(remaining)
                            if (skipped > 0) remaining -= skipped
                            else if (stream.read() == -1) throw IOException("Archivo cambió durante la lectura")
                            else remaining--
                        }
                        bounded(stream, minOf(length.toLong(), entry.size - offset).toInt())
                    }
                    JSONObject().put("path", path).put("offset", offset)
                        .put("totalBytes", entry.size).put("readBytes", data.size)
                        .put("base64", Base64.encodeToString(data, Base64.NO_WRAP))
                }
                "sftp_make_directory" -> {
                    requireChanges(allowChanges)
                    val (parent, name) = parentAndName(connection, path)
                    ensureAbsent(fs, parent, name)
                    fs.mkdir(parent, name)
                    JSONObject().put("created", path)
                }
                "sftp_write_new_text" -> {
                    requireChanges(allowChanges)
                    val data = args.optString("content", "").toByteArray(Charsets.UTF_8)
                    if (data.size > MAX_TEXT) throw IOException("Máximo 64 KiB por operación")
                    val (parent, name) = parentAndName(connection, path)
                    ensureAbsent(fs, parent, name)
                    fs.write(parent, name, ByteArrayInputStream(data), data.size.toLong())
                    JSONObject().put("created", path).put("bytes", data.size)
                }
                "sftp_rename" -> {
                    requireChanges(allowChanges)
                    val (parent, _) = parentAndName(connection, path)
                    val source = fs.list(parent).firstOrNull { it.path == path }
                        ?: throw IOException("Origen no encontrado o enlace simbólico no permitido")
                    val newName = args.optString("newName", "")
                    SafeFiles.requireName(newName)
                    ensureAbsent(fs, parent, newName)
                    fs.rename(source, newName)
                    JSONObject().put("source", path)
                        .put("destination", RemoteFiles.join(parent, newName))
                }
                else -> throw IOException("Operación SFTP desconocida")
            }
        }
    }

    private fun requireChanges(allowed: Boolean) {
        if (!allowed) throw IOException("Activa escritura en Ajustes > Conector remoto")
    }

    private fun root(connection: Connection): String {
        val raw = connection.root.ifBlank { "/" }
        return cleanAbsolute(raw).trimEnd('/').ifEmpty { "/" }
    }

    private fun pathWithin(connection: Connection, requested: String): String {
        val target = cleanAbsolute(requested).trimEnd('/').ifEmpty { "/" }
        val base = root(connection)
        if (base != "/" && target != base && !target.startsWith("$base/")) {
            throw IOException("Ruta fuera de la raíz SFTP guardada")
        }
        return target
    }

    private fun cleanAbsolute(value: String): String {
        if (!value.startsWith('/') || value.contains('\u0000') || value.contains('\\')) {
            throw IOException("Ruta SFTP absoluta no válida")
        }
        val segments = value.split('/')
        if (segments.any { it == "." || it == ".." } || value.contains("//")) {
            throw IOException("Ruta SFTP con componentes no permitidos")
        }
        return value
    }

    private fun parentAndName(connection: Connection, path: String): Pair<String, String> {
        if (path == root(connection)) throw IOException("No se modifica la raíz SFTP")
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        SafeFiles.requireName(name)
        pathWithin(connection, parent)
        return parent to name
    }

    private fun ensureAbsent(fs: RemoteFs, parent: String, name: String) {
        if (fs.list(parent).any { it.name == name }) throw IOException("El destino ya existe")
    }

    private fun existingFile(fs: RemoteFs, path: String): RemoteEntry {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val entry = fs.list(parent).firstOrNull { it.path == path }
            ?: throw IOException("Archivo no encontrado o enlace simbólico no permitido")
        if (entry.directory) throw IOException("La ruta corresponde a una carpeta")
        return entry
    }

    private fun bounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val read = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read < 0) break
            if (read > 0) out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
