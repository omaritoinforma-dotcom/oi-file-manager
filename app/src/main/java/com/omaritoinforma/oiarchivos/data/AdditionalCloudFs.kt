package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element

/** XML from cloud services is bounded and never allowed to resolve external entities. */
internal object CloudXml {
    fun parse(input: InputStream): Document {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bounded(input)))
    }

    fun text(element: Element, name: String): String =
        (0 until element.childNodes.length)
            .mapNotNull { element.childNodes.item(it) as? Element }
            .firstOrNull { it.tagName == name }
            ?.textContent
            .orEmpty()

    fun body(root: String, values: Map<String, String>): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val element = doc.createElement(root)
        doc.appendChild(element)
        values.forEach { (name, value) ->
            element.appendChild(doc.createElement(name).apply { textContent = value })
        }
        return serialize(doc)
    }

    fun serialize(doc: Document): String {
        val transformer =
            TransformerFactory.newInstance()
                .apply { runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) } }
                .newTransformer()
        return StringWriter()
            .also { transformer.transform(DOMSource(doc), StreamResult(it)) }
            .toString()
    }

    fun bounded(input: InputStream, limit: Int = 16 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit)
                throw IOException("Respuesta del proveedor demasiado grande")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

internal class BaiduFs(private val c: Connection) : RemoteFs {
    private val http = Http("")
    private val api = "https://pan.baidu.com/rest/2.0/xpan"
    private val ids = HashMap<String, Long>()

    private fun query(values: Map<String, String>) =
        values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    private fun url(endpoint: String, values: Map<String, String>) =
        "$endpoint?" + query(values + ("access_token" to c.secret))

    private fun json(
        endpoint: String,
        params: Map<String, String>,
        body: Map<String, String>? = null
    ): JSONObject {
        val data = body?.let { query(it).toByteArray() }
        val connection =
            http.open(
                url(endpoint, params),
                if (body == null) "GET" else "POST",
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "pan.baidu.com OIArchivos"),
                data?.size?.toLong())
        try {
            if (data != null) connection.outputStream.use { it.write(data) }
            return checked(
                http.response(connection).use {
                    JSONObject(String(CloudXml.bounded(it), Charsets.UTF_8))
                })
        } finally {
            connection.disconnect()
        }
    }

    private fun checked(result: JSONObject): JSONObject {
        val errno = result.optInt("errno", 0)
        if (errno != 0 || result.has("error_code"))
            throw IOException("Baidu rechazó la operación (${result.optInt("error_code", errno)})")
        val info = result.optJSONArray("info")
        if (info != null &&
            (0 until info.length()).any { info.getJSONObject(it).optInt("errno", 0) != 0 })
            throw IOException("Baidu no completó la operación")
        return result
    }

    override fun list(path: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        while (true) {
            val list =
                json(
                        "$api/file",
                        mapOf(
                            "method" to "list",
                            "dir" to path,
                            "start" to out.size.toString(),
                            "limit" to "1000",
                            "order" to "name"))
                    .getJSONArray("list")
            for (i in 0 until list.length()) {
                val f = list.getJSONObject(i)
                val p = f.getString("path")
                ids[p] = f.getLong("fs_id")
                out +=
                    RemoteEntry(
                        p,
                        f.getString("server_filename"),
                        f.getInt("isdir") == 1,
                        f.optLong("size", -1))
            }
            if (list.length() < 1000) return out
            if (out.size >= 50000) throw IOException("La carpeta supera 50.000 elementos")
        }
    }

    override fun read(path: String): InputStream {
        if (path !in ids) list(path.substringBeforeLast('/').ifBlank { "/" })
        val id = ids[path] ?: throw IOException("El archivo ya no existe")
        val link =
            json(
                    "$api/multimedia",
                    mapOf("method" to "filemetas", "dlink" to "1", "fsids" to "[$id]"))
                .getJSONArray("list")
                .getJSONObject(0)
                .getString("dlink")
        val parsed = URL(link)
        if (parsed.protocol != "https" ||
            !(parsed.host.endsWith(".baidu.com") || parsed.host.endsWith(".baidupcs.com")))
            throw IOException("Descarga de Baidu no válida")
        return http.response(
            http.open(link, "GET", mapOf("User-Agent" to "pan.baidu.com OIArchivos")))
    }

    override fun mkdir(parent: String, name: String): String {
        val path = RemoteFiles.join(parent, name)
        return json(
                "$api/file",
                mapOf("method" to "create"),
                mapOf(
                    "path" to path,
                    "size" to "0",
                    "isdir" to "1",
                    "rtype" to "0",
                    "block_list" to "[]"))
            .getString("path")
    }

    override fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        manage("rename", JSONArray().put(JSONObject().put("path", entry.path).put("newname", name)))
    }

    override fun delete(entry: RemoteEntry) {
        manage("delete", JSONArray().put(entry.path))
    }

    private fun manage(operation: String, files: JSONArray) {
        json(
            "$api/file",
            mapOf("method" to "filemanager", "opera" to operation),
            mapOf("async" to "0", "ondup" to "fail", "filelist" to files.toString()))
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val path = RemoteFiles.join(parent, name)
        val temp = File.createTempFile("baidu-upload-", ".part", RemoteFiles.appContext.cacheDir)
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(262144)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > 64L * 1024 * 1024 * 1024 || (size >= 0 && copied > size))
                        throw IOException("Tamaño de subida no válido")
                    output.write(buffer, 0, n)
                }
                if (size >= 0 && copied != size)
                    throw IOException("El archivo cambió durante la subida")
            }
            val chunk = ByteArray(4 * 1024 * 1024)
            val hashes = ArrayList<String>()
            temp.inputStream().use { source ->
                var remaining = temp.length()
                do {
                    val bytes = minOf(remaining, chunk.size.toLong()).toInt()
                    DataInputStream(source).readFully(chunk, 0, bytes)
                    hashes +=
                        MessageDigest.getInstance("MD5").digest(chunk.copyOf(bytes)).joinToString(
                            "") {
                                "%02x".format(it)
                            }
                    remaining -= bytes
                } while (remaining > 0)
            }
            val values =
                mapOf(
                    "path" to path,
                    "size" to temp.length().toString(),
                    "isdir" to "0",
                    "rtype" to "0",
                    "block_list" to JSONArray(hashes).toString())
            val pre =
                json("$api/file", mapOf("method" to "precreate"), values + ("autoinit" to "1"))
            if (pre.optInt("return_type") == 2) return path
            val upload = pre.getString("uploadid")
            val needed = pre.getJSONArray("block_list")
            RandomAccessFile(temp, "r").use { source ->
                for (i in 0 until needed.length()) {
                    val index = needed.getInt(i)
                    if (index !in hashes.indices) throw IOException("Respuesta de subida no válida")
                    source.seek(index.toLong() * chunk.size)
                    val bytes =
                        minOf(chunk.size.toLong(), temp.length() - source.filePointer).toInt()
                    source.readFully(chunk, 0, bytes)
                    val boundary = "OI${UUID.randomUUID().toString().replace("-", "")}"
                    val head =
                        "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                            .toByteArray()
                    val tail = "\r\n--$boundary--\r\n".toByteArray()
                    val connection =
                        http.open(
                            url(
                                "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2",
                                mapOf(
                                    "method" to "upload",
                                    "type" to "tmpfile",
                                    "path" to path,
                                    "uploadid" to upload,
                                    "partseq" to index.toString())),
                            "POST",
                            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                            (head.size + bytes + tail.size).toLong())
                    try {
                        connection.outputStream.use {
                            it.write(head)
                            it.write(chunk, 0, bytes)
                            it.write(tail)
                        }
                        val response =
                            http.response(connection).use {
                                checked(JSONObject(String(CloudXml.bounded(it), Charsets.UTF_8)))
                            }
                        if (!response.getString("md5").equals(hashes[index], true))
                            throw IOException("Baidu devolvió una suma de comprobación distinta")
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            return json("$api/file", mapOf("method" to "create"), values + ("uploadid" to upload))
                .getString("path")
        } finally {
            temp.delete()
        }
    }
}

internal class SugarSyncFs(private val c: Connection) : RemoteFs {
    private val http = Http(c.secret)
    private val headers =
        mapOf("User-Agent" to "OIArchivos", "Content-Type" to "application/xml; charset=UTF-8")

    private fun endpoint(path: String) = sugarEndpoint(path)

    private fun doc(url: String): Document {
        val connection = http.open(endpoint(url), "GET", headers)
        return http.response(connection).use(CloudXml::parse)
    }

    override fun list(path: String): List<RemoteEntry> {
        if (path == "/" || path.isBlank()) {
            val userUrl =
                runCatching { JSONObject(c.authState).getString("user") }
                    .getOrDefault("https://api.sugarsync.com/user")
            val user = doc(userUrl).documentElement
            val out = ArrayList<RemoteEntry>()
            for ((tag, name) in
                listOf(
                    "magicBriefcase" to "Magic Briefcase",
                    "webArchive" to "Archivo web",
                    "mobilePhotos" to "Fotos móviles",
                    "syncfolders" to "Carpetas sincronizadas",
                    "workspaces" to "Equipos")) {
                val url = CloudXml.text(user, tag)
                if (url.isNotBlank()) out += RemoteEntry(endpoint(url), name, true, 0)
            }
            return out.distinctBy { it.path }
        }
        val contents = endpoint(path).let { if (it.endsWith("/contents")) it else "$it/contents" }
        val out = ArrayList<RemoteEntry>()
        var start = 0
        while (true) {
            val root = doc("$contents?start=$start&max=500").documentElement
            val children =
                (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            for (f in children.filter { it.tagName == "collection" || it.tagName == "file" }) {
                val url = CloudXml.text(f, "ref").ifBlank { CloudXml.text(f, "contents") }
                val name = CloudXml.text(f, "displayName")
                if (url.isNotBlank() && name.isNotBlank())
                    out +=
                        RemoteEntry(
                            endpoint(url),
                            name,
                            f.tagName == "collection",
                            CloudXml.text(f, "size").toLongOrNull() ?: 0)
            }
            if (root.getAttribute("hasMore") != "true") return out
            val next = root.getAttribute("end").toIntOrNull() ?: (start + children.size)
            if (next <= start || next >= 50000)
                throw IOException("La carpeta supera 50.000 elementos o no admite paginación")
            start = next
        }
    }

    override fun read(path: String): InputStream =
        http.response(http.open(endpoint(path).trimEnd('/') + "/data", "GET", headers))

    private fun create(parent: String, name: String, folder: Boolean): String {
        SafeFiles.requireName(name)
        if (parent == "/" || !URL(endpoint(parent)).path.startsWith("/folder/"))
            throw IOException("Abre una carpeta de SugarSync antes de crear archivos")
        if (list(parent).any { it.name == name }) throw IOException("El destino ya existe")
        val body =
            CloudXml.body(
                    if (folder) "folder" else "file",
                    mapOf("displayName" to name) +
                        if (folder) emptyMap()
                        else mapOf("mediaType" to "application/octet-stream"))
                .toByteArray()
        val connection = http.open(endpoint(parent), "POST", headers, body.size.toLong())
        try {
            connection.outputStream.use { it.write(body) }
            http.response(connection).close()
            return endpoint(
                connection.getHeaderField("Location")
                    ?: throw IOException("SugarSync no devolvió el archivo creado"))
        } finally {
            connection.disconnect()
        }
    }

    override fun mkdir(parent: String, name: String) = create(parent, name, true)

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val path = create(parent, name, false)
        try {
            val connection =
                http.open(
                    "$path/data",
                    "PUT",
                    mapOf(
                        "User-Agent" to "OIArchivos", "Content-Type" to "application/octet-stream"),
                    size)
            try {
                connection.outputStream.use { input.copyTo(it, 262144) }
                http.response(connection).close()
            } finally {
                connection.disconnect()
            }
            return path
        } catch (e: Exception) {
            runCatching { http.request(path, "DELETE", headers = headers) }
            throw e
        }
    }

    override fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        val document = doc(entry.path)
        val display =
            document.documentElement.getElementsByTagName("displayName").item(0)
                ?: throw IOException("Respuesta de SugarSync no válida")
        display.textContent = name
        http.request(endpoint(entry.path), "PUT", CloudXml.serialize(document), headers)
    }

    override fun delete(entry: RemoteEntry) {
        http.request(endpoint(entry.path), "DELETE", headers = headers)
    }
}

internal fun sugarEndpoint(url: String): String {
    val endpoint = URL(url)
    if (endpoint.protocol != "https" ||
        endpoint.host != "api.sugarsync.com" ||
        endpoint.port !in listOf(-1, 443) ||
        endpoint.userInfo != null ||
        endpoint.ref != null)
        throw IOException("Dirección de SugarSync no válida")
    return endpoint.toString()
}
