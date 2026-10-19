package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Uses device-held connections, never returns authentication material. */
class RemoteFileAgent(private val context: Context, private val config: BridgeConfig) {
    private fun writeAllowed() {
        if (!config.allowChanges) throw IOException("Activa los cambios de archivos en el móvil")
    }

    fun connections(): JSONObject {
        if (!config.allowRemote) throw IOException("Activa las conexiones guardadas en Ajustes > Conector")
        val items = JSONArray()
        ConnectionStore(context).load().filter { it.protocol != Protocol.ROOT }.forEach {
            items.put(JSONObject().put("id", it.id).put("label", it.label)
                .put("protocol", it.protocol.name).put("root", it.root))
        }
        return JSONObject().put("connections", items)
    }

    fun execute(action: String, args: JSONObject): JSONObject {
        if (!config.allowRemote) throw IOException("Las conexiones guardadas no están autorizadas")
        val connection = ConnectionStore(context).load().singleOrNull { it.id == args.getString("connectionId") }
            ?: throw IOException("Conexión guardada no encontrada")
        if (connection.protocol == Protocol.ROOT) throw IOException("Root no se expone mediante este conector")
        val root = BridgePaths.within(connection.root, "")
        val path = BridgePaths.within(root, args.optString("path"))
        RemoteFiles.connect(connection).use { fs ->
            // Walk via list: SFTP filters links, so they cannot be used as intermediate directories.
            fun entry(target: String): RemoteEntry {
                if (target == root) return RemoteEntry(root, root.substringAfterLast('/'), true, 0)
                var parent = root
                var found: RemoteEntry? = null
                val relative = target.removePrefix(if (root == "/") "/" else "$root/")
                val segments = relative.split('/')
                segments.forEachIndexed { index, name ->
                    found = fs.list(parent).singleOrNull { it.name == name }
                        ?: throw IOException("No existe o no es accesible: $name")
                    if (index < segments.lastIndex && found?.directory != true)
                        throw IOException("El padre no es una carpeta")
                    parent = RemoteFiles.join(parent, name)
                }
                return found!!
            }
            fun destination(target: String): Pair<String, String> {
                if (target == root) throw IOException("No se modifica la raíz de una conexión")
                val parent = target.substringBeforeLast('/').ifEmpty { "/" }
                if (!entry(parent).directory) throw IOException("Destino no válido")
                val name = target.substringAfterLast('/'); SafeFiles.requireName(name)
                if (fs.list(parent).any { it.name == name }) throw IOException("El destino ya existe")
                return parent to name
            }
            fun metadata(e: RemoteEntry) = JSONObject().put("path",e.path).put("name",e.name)
                .put("directory",e.directory).put("bytes",e.size)
            fun textBytes(): ByteArray {
                val e = entry(path)
                if (e.directory || e.size > 65536) throw IOException("Solo archivos de hasta 64 KiB")
                return fs.read(path).use { it.readBytesLimited(65536) }
            }
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return when (action) {
                "list_files" -> {
                    if (!entry(path).directory) throw IOException("No es una carpeta")
                    val all = fs.list(path).sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
                    val offset = args.optInt("offset",0)
                    if (offset < 0) throw IOException("Desplazamiento no válido")
                    val items = JSONArray(); all.drop(offset).take(250).forEach { items.put(metadata(it)) }
                    JSONObject().put("path",path).put("items",items).put("total",all.size)
                        .put("nextOffset",if (offset + 250 < all.size) offset + 250 else JSONObject.NULL)
                }
                "file_info" -> metadata(entry(path))
                "read_text" -> textBytes().let { JSONObject().put("path",path)
                    .put("text",String(it,Charsets.UTF_8)).put("sha256",digest(it)) }
                "read_file_chunk" -> {
                    val e = entry(path); val offset = args.optLong("offset",0)
                    val length = args.optInt("length",65536)
                    if (e.directory || offset < 0 || offset > e.size || length !in 1..65536)
                        throw IOException("Lectura no válida")
                    val bytes = fs.read(path).use { stream ->
                        var remaining = offset
                        while (remaining > 0) {
                            val skipped = stream.skip(remaining)
                            if (skipped > 0) remaining -= skipped
                            else if (stream.read() >= 0) remaining-- else throw IOException("Archivo cambiado")
                        }
                        stream.readBytesLimited(length, false)
                    }
                    JSONObject().put("path",path).put("offset",offset).put("totalBytes",e.size)
                        .put("readBytes",bytes.size).put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                }
                "write_new_text", "write_new_file" -> {
                    writeAllowed(); val (parent,name) = destination(path)
                    val bytes = if (action == "write_new_text") args.getString("content").toByteArray(Charsets.UTF_8)
                        else Base64.decode(args.getString("base64"),Base64.NO_WRAP)
                    if (bytes.size > 65536) throw IOException("Máximo 64 KiB por archivo")
                    fs.write(parent,name,bytes.inputStream(),bytes.size.toLong())
                    JSONObject().put("created",path).put("bytes",bytes.size).put("sha256",digest(bytes))
                }
                "_commit_upload" -> {
                    writeAllowed(); val (parent,name) = destination(path)
                    val part=java.io.File(args.getString("uploadTemp"))
                    part.inputStream().use { fs.write(parent,name,it,part.length()) }
                    JSONObject().put("created",path).put("bytes",part.length())
                }
                "make_directory" -> {
                    writeAllowed(); val (parent,name) = destination(path)
                    JSONObject().put("created",fs.mkdir(parent,name))
                }
                "rename_file", "move_file" -> {
                    writeAllowed(); if (path == root) throw IOException("No se mueve la raíz")
                    val source = entry(path)
                    val target = BridgePaths.within(root,args.getString("destination"))
                    destination(target)
                    if (source.directory && target.startsWith("$path/")) throw IOException("Destino dentro del origen")
                    fs.move(source,target)
                    JSONObject().put("source",path).put("destination",target)
                }
                "copy_file" -> {
                    writeAllowed(); val source = entry(path)
                    if (source.directory || source.size > 8L * 1024 * 1024) throw IOException("Copia remota: archivo individual de hasta 8 MiB")
                    val target = BridgePaths.within(root,args.getString("destination"))
                    val (parent,name) = destination(target)
                    fs.read(path).use { fs.write(parent,name,it,source.size) }
                    JSONObject().put("source",path).put("destination",target)
                }
                "delete_file" -> {
                    writeAllowed()
                    if (!config.allowDelete || path == root) throw IOException("Borrado definitivo no autorizado")
                    val source = entry(path)
                    if (source.directory && fs.list(path).isNotEmpty()) throw IOException("La carpeta debe estar vacía")
                    fs.delete(source)
                    JSONObject().put("deleted",path)
                }
                // Remote providers have no common recoverable-trash or atomic CAS contract.
                "trash_file", "replace_text" -> throw IOException("El proveedor no garantiza papelera o reemplazo condicional; conserva el original y escribe otro archivo")
                else -> throw IOException("Operación remota no compatible")
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int, rejectExtra: Boolean = true): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buffer,0,minOf(buffer.size,max-out.size()))
        if (n < 0) break
        if (n > 0) out.write(buffer,0,n)
    }
    if (rejectExtra && read() != -1) throw IOException("El archivo supera el límite")
    return out.toByteArray()
}
