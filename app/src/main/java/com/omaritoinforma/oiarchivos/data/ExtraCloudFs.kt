package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.w3c.dom.Element

internal class YandexFs(c: Connection, private val jsonRequest: CloudJsonRequest? = null) : RemoteFs {
    private val api = "https://cloud-api.yandex.net/v1/disk/resources"
    private val http = Http("OAuth ${c.secret}")

    private fun json(url: String, method: String = "GET") =
        jsonRequest?.invoke(url, method, null) ?: JSONObject(http.request(url, method))

    override fun list(path: String): List<RemoteEntry> {
        val out = CloudListing<RemoteEntry> { it.path }
        var offset = 0
        while (true) {
            out.beginPage(offset.toString())
            val embedded =
                json("$api?path=${encode(path)}&limit=1000&offset=$offset")
                    .getJSONObject("_embedded")
            val items = embedded.getJSONArray("items")
            for (i in 0 until items.length()) {
                val f = items.getJSONObject(i)
                out.add(
                    RemoteEntry(
                        f.getString("path"),
                        f.getString("name"),
                        f.getString("type") == "dir",
                        f.optLong("size", -1)))
            }
            val total = embedded.getLong("total")
            if (total < out.size) throw IOException("Yandex devolvió un listado inconsistente")
            if (out.size.toLong() == total) break
            if (items.length() == 0) throw IOException("Yandex no completó la paginación")
            offset += items.length()
        }
        return out.result()
    }

    private fun signed(url: String): String {
        val href = json(url).getString("href")
        if (URL(href).protocol != "https") throw IOException("Respuesta sin HTTPS")
        return href
    }

    override fun read(path: String): InputStream {
        val link = signed("$api/download?path=${encode(path)}")
        return Http("").response(Http("").open(link, "GET"))
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val path = RemoteFiles.join(parent, name)
        val link = signed("$api/upload?path=${encode(path)}&overwrite=false")
        val c = Http("").open(link, "PUT", size = size)
        try {
            c.outputStream.use { input.copyTo(it) }
            Http("").response(c).close()
        } finally {
            c.disconnect()
        }
        return path
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { http.request("$api?path=${encode(it)}", "PUT") }

    override fun rename(entry: RemoteEntry, name: String) {
        http.request(
            "$api/move?from=${encode(entry.path)}&path=${encode(RemoteFiles.join(entry.path.substringBeforeLast('/'),name))}&overwrite=false",
            "POST")
    }

    override fun delete(entry: RemoteEntry) {
        http.request("$api?path=${encode(entry.path)}&permanently=false", "DELETE")
    }
}

internal class BoxFs(c: Connection, private val jsonRequest: CloudJsonRequest? = null) : RemoteFs {
    override val supportsDurableUploads = true
    private val api = "https://api.box.com/2.0"
    private val http = Http("Bearer ${c.secret}")
    internal var deleteRequest: CloudDeleteRequest = { url, headers ->
        http.request(url, "DELETE", headers = headers)
        Unit
    }

    private fun json(url: String, method: String = "GET", body: JSONObject? = null) =
        jsonRequest?.invoke(url, method, body) ?: JSONObject(
            http.request(
                url, method, body?.toString(), mapOf("Content-Type" to "application/json")))

    override fun list(path: String): List<RemoteEntry> {
        val out = CloudListing<RemoteEntry> { it.path }
        var marker = ""
        do {
            out.beginPage(marker)
            val markerQuery = if (marker.isEmpty()) "" else "&marker=${encode(marker)}"
            val result =
                json(
                    "$api/folders/${encode(id(path.ifBlank{"0"}))}/items?usemarker=true&limit=1000$markerQuery&fields=id,type,name,size,etag")
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val f = entries.getJSONObject(i)
                out.add(
                    RemoteEntry(
                        f.getString("type") + ":" + f.getString("id"),
                        f.getString("name"),
                        f.getString("type") == "folder",
                        f.optLong("size", -1),
                        f.optString("etag")))
            }
            if (!result.has("next_marker"))
                throw IOException("Box no confirmó un listado completo o un marcador siguiente")
            marker = result.pageToken("next_marker")
        } while (marker.isNotEmpty())
        return out.result()
    }

    private fun id(path: String) = path.substringAfter(':', path)

    override fun read(path: String): InputStream {
        val c = http.open("$api/files/${encode(id(path))}/content", "GET")
        val code = c.responseCode
        if (code in 300..399) {
            val location =
                c.getHeaderField("Location") ?: throw IOException("No se recibió una descarga")
            c.disconnect()
            if (URL(location).protocol != "https") throw IOException("Descarga sin HTTPS")
            return Http("").response(Http("").open(location, "GET"))
        }
        return http.response(c)
    }

    override fun mkdir(parent: String, name: String): String {
        SafeFiles.requireName(name)
        return "folder:" +
            json(
                    "$api/folders",
                    "POST",
                    JSONObject()
                        .put("name", name)
                        .put("parent", JSONObject().put("id", id(parent))))
                .getString("id")
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        SafeFiles.requireName(name)
        val boundary = "OI" + UUID.randomUUID().toString().replace("-", "")
        val attributes =
            JSONObject()
                .put("name", name)
                .put("parent", JSONObject().put("id", id(parent)))
                .toString()
        val head =
            "--$boundary\r\nContent-Disposition: form-data; name=\"attributes\"\r\n\r\n$attributes\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c =
            http.open(
                "https://upload.box.com/api/2.0/files/content",
                "POST",
                mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                head.size + size + tail.size)
        try {
            c.outputStream.use {
                it.write(head)
                input.copyTo(it)
                it.write(tail)
            }
            return "file:" +
                JSONObject(http.response(c).bufferedReader().use { it.readText() })
                    .getJSONArray("entries")
                    .getJSONObject(0)
                    .getString("id")
        } finally {
            c.disconnect()
        }
    }

    override fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        http.request(
            "$api/${if(entry.directory)"folders"else"files"}/${encode(id(entry.path))}",
            "PUT",
            JSONObject().put("name", name).toString(),
            mapOf("Content-Type" to "application/json"))
    }

    override fun publishUpload(stage: RemoteEntry, parent: String, name: String): String {
        SafeFiles.requireName(name)
        // Box rejects a conflicting name with HTTP 409; it does not replace another item.
        val result =
            json("$api/${if (stage.directory) "folders" else "files"}/${encode(id(stage.path))}",
                "PUT", JSONObject().put("name", name))
        if (result.getString("id") != id(stage.path))
            throw IOException("Box no confirmó el archivo publicado")
        return (if (stage.directory) "folder:" else "file:") + result.getString("id")
    }

    override fun delete(entry: RemoteEntry) {
        http.request(
            "$api/${if(entry.directory)"folders"else"files"}/${encode(id(entry.path))}?recursive=true",
            "DELETE")
    }

    override fun deleteIfUnchanged(entry: RemoteEntry): Boolean {
        if (entry.directory || entry.revision.isBlank()) return false
        deleteRequest("$api/files/${encode(id(entry.path))}", mapOf("If-Match" to entry.revision))
        return true
    }

    override fun deleteEmptyDirectory(entry: RemoteEntry): Boolean {
        if (!entry.directory) return false
        val headers = if (entry.revision.isBlank()) emptyMap()
            else mapOf("If-Match" to entry.revision)
        // An entry arriving after the client's empty check causes a conflict on the server.
        deleteRequest("$api/folders/${encode(id(entry.path))}?recursive=false", headers)
        return true
    }
}

/** S3-compatible HTTPS endpoints, path-style buckets, AWS Signature V4 and streamed payloads. */
internal class S3Fs(
    private val account: Connection,
    private val listingResponse: ((String, Map<String, String>) -> InputStream)? = null
) : RemoteFs {
    private val base = account.host.trimEnd('/')
    private val region = account.fingerprint.ifBlank { "us-east-1" }

    init {
        if (URL(base).protocol != "https") throw IOException("S3 requiere un endpoint HTTPS")
        if (account.user.isBlank() || account.secret.isBlank())
            throw IOException("Faltan las claves de S3")
    }

    private fun uri(path: String) =
        base + "/" + path.trimStart('/').split('/').joinToString("/") { encode(it) }

    private fun hmac(key: ByteArray, text: String) =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(text.toByteArray())

    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }

    private fun request(
        path: String,
        method: String,
        query: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        input: InputStream? = null,
        size: Long? = null
    ): InputStream {
        val now = Date()
        val day =
            SimpleDateFormat("yyyyMMdd", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(now)
        val time =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(now)
        val canonicalQuery =
            query.entries
                .map { encode(it.key) to encode(it.value) }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .joinToString("&") { it.first + "=" + it.second }
        val url = uri(path) + if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
        val parsed = URL(url)
        val headers =
            sortedMapOf(
                    "host" to parsed.authority,
                    "x-amz-content-sha256" to "UNSIGNED-PAYLOAD",
                    "x-amz-date" to time)
                .apply { putAll(extra.mapKeys { it.key.lowercase() }) }
        val signed = headers.keys.joinToString(";")
        val canonical =
            method +
                "\n" +
                parsed.path +
                "\n" +
                canonicalQuery +
                "\n" +
                headers.entries.joinToString("") { it.key + ":" + it.value.trim() + "\n" } +
                "\n" +
                signed +
                "\nUNSIGNED-PAYLOAD"
        val scope = "$day/$region/s3/aws4_request"
        val toSign =
            "AWS4-HMAC-SHA256\n$time\n$scope\n" +
                hex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        val signing =
            hmac(
                hmac(hmac(hmac(("AWS4" + account.secret).toByteArray(), day), region), "s3"),
                "aws4_request")
        val auth =
            "AWS4-HMAC-SHA256 Credential=${account.user}/$scope, SignedHeaders=$signed, Signature=" +
                hex(hmac(signing, toSign))
        signing.fill(0)
        val c = Http(auth).open(url, method, headers, size)
        try {
            if (input != null) c.outputStream.use { input.copyTo(it) }
            else if (size != null) c.outputStream.close()
            return Http(auth).response(c)
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
    }

    private fun xml(input: InputStream): org.w3c.dom.Document {
        val bytes = input.use { CloudXml.bounded(it, 8 * 1024 * 1024) }
        val text = String(bytes, Charsets.UTF_8)
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
            throw IOException("Declaración XML no permitida")
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
        return factory.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun listing(bucket: String, query: Map<String, String>) =
        xml(listingResponse?.invoke("/$bucket", query) ?: request("/$bucket", "GET", query))

    private fun continuation(doc: org.w3c.dom.Document): String {
        val truncated = doc.getElementsByTagNameNS("*", "IsTruncated").item(0)?.textContent?.trim()
        val next =
            doc.getElementsByTagNameNS("*", "NextContinuationToken")
                .item(0)?.textContent.orEmpty()
        if (truncated !in listOf("true", "false") ||
            (truncated == "true" && next.isBlank()) ||
            (truncated == "false" && next.isNotEmpty()))
            throw IOException("S3 no confirmó un listado completo o una página siguiente válida")
        return next
    }

    override fun list(path: String): List<RemoteEntry> {
        val clean = path.trim('/')
        val bucket = clean.substringBefore('/')
        if (bucket.isBlank()) throw IOException("Escribe /nombre-del-bucket en la carpeta inicial")
        val prefix = if (clean.contains('/')) clean.substringAfter('/').trimEnd('/') + "/" else ""
        val out = CloudListing<RemoteEntry> { it.path }
        var next = ""
        do {
            out.beginPage(next)
            val query = mutableMapOf("list-type" to "2", "delimiter" to "/", "prefix" to prefix)
            if (next.isNotEmpty()) query["continuation-token"] = next
            val doc = listing(bucket, query)
            val common = doc.getElementsByTagNameNS("*", "CommonPrefixes")
            for (i in 0 until common.length) {
                val key =
                    (common.item(i) as Element)
                        .getElementsByTagNameNS("*", "Prefix")
                        .item(0)
                        .textContent
                        .trimEnd('/')
                if (!key.startsWith(prefix) || key.isEmpty())
                    throw IOException("S3 devolvió una carpeta fuera de la carpeta solicitada")
                out.add(RemoteEntry("/$bucket/$key", key.substringAfterLast('/'), true, 0))
            }
            val objects = doc.getElementsByTagNameNS("*", "Contents")
            for (i in 0 until objects.length) {
                val item = objects.item(i) as Element
                val key = item.getElementsByTagNameNS("*", "Key").item(0).textContent
                if (!key.startsWith(prefix))
                    throw IOException("S3 devolvió un archivo fuera de la carpeta solicitada")
                if (key == prefix || key.endsWith('/')) continue
                out.add(
                    RemoteEntry(
                        "/$bucket/$key",
                        key.substringAfterLast('/'),
                        false,
                        item.getElementsByTagNameNS("*", "Size").item(0).textContent.toLong()))
            }
            next = continuation(doc)
        } while (next.isNotEmpty())
        return out.result()
    }

    override fun read(path: String) = request(path, "GET")

    override fun write(parent: String, name: String, input: InputStream, size: Long): String =
        RemoteFiles.join(parent, name).also {
            request(it, "PUT", input = input, size = size).close()
        }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { request("$it/", "PUT", size = 0).close() }

    override fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        val target = RemoteFiles.join(entry.path.substringBeforeLast('/'), name)
        if (target == entry.path) return
        if (list(entry.path.substringBeforeLast('/')).any { it.name == name })
            throw IOException("Ya existe un archivo o carpeta con ese nombre")
        if (entry.directory) {
            // Snapshot the complete prefix first. Originals are deleted only once every copy
            // succeeded.
            val bucket = entry.path.trimStart('/').substringBefore('/')
            val prefix = entry.path.trimStart('/').substringAfter('/').trimEnd('/') + "/"
            val objects = CloudListing<String>(100_000) { it }
            var next = ""
            do {
                objects.beginPage(next)
                val query = mutableMapOf("list-type" to "2", "prefix" to prefix)
                if (next.isNotEmpty()) query["continuation-token"] = next
                val doc = listing(bucket, query)
                val contents = doc.getElementsByTagNameNS("*", "Contents")
                for (i in 0 until contents.length) {
                    val key =
                        (contents.item(i) as Element)
                            .getElementsByTagNameNS("*", "Key")
                            .item(0)
                            .textContent
                    if (!key.startsWith(prefix))
                        throw IOException("S3 devolvió un archivo fuera de la carpeta")
                    objects.add("/$bucket/$key")
                }
                next = continuation(doc)
            } while (next.isNotEmpty())
            for (source in objects.result()) copyObject(
                source, target.trimEnd('/') + "/" + source.substringAfter("/$bucket/$prefix"))
            if (objects.size == 0) request(target.trimEnd('/') + "/", "PUT", size = 0).close()
            for (source in objects.result()) request(source, "DELETE").close()
            return
        }
        copyObject(entry.path, target)
        delete(entry)
    }

    private fun copyObject(source: String, target: String) {
        val result =
            xml(
                request(
                    target,
                    "PUT",
                    extra =
                        mapOf(
                            "x-amz-copy-source" to
                                "/" +
                                    source.trimStart('/').split('/').joinToString("/") {
                                        encode(it)
                                    },
                            "if-none-match" to "*"),
                    size = 0))
        if (result.documentElement.localName != "CopyObjectResult")
            throw IOException("S3 no confirmó la copia")
    }

    override fun delete(entry: RemoteEntry) {
        if (entry.directory) {
            list(entry.path).forEach { delete(it) }
            request(entry.path.trimEnd('/') + "/", "DELETE").close()
        } else request(entry.path, "DELETE").close()
    }
}
