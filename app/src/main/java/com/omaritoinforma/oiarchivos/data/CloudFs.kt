package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official provider APIs. Tokens are supplied by the account owner, encrypted by ConnectionStore.
 */
internal class CloudFs(private val account: Connection) : RemoteFs {
    private val http = Http("Bearer ${account.secret}")
    private val drive = "https://www.googleapis.com/drive/v3"
    private val graph = "https://graph.microsoft.com/v1.0/me/drive"

    private fun json(url: String, method: String = "GET", body: JSONObject? = null) =
        JSONObject(
            http.request(
                url,
                method,
                body?.toString(),
                if (body == null) emptyMap() else mapOf("Content-Type" to "application/json")))

    private fun drop(method: String, body: JSONObject) =
        json("https://api.dropboxapi.com/2/$method", "POST", body)

    private fun root(path: String) =
        path.ifBlank { if (account.protocol == Protocol.DRIVE) "root" else "" }

    override fun list(path: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        when (account.protocol) {
            Protocol.DRIVE -> {
                var page = ""
                do {
                    val parent = root(path).replace("\\", "\\\\").replace("'", "\\'")
                    val result =
                        json(
                            "$drive/files?q=${encode("'$parent' in parents and trashed=false")}&pageSize=1000&fields=nextPageToken,files(id,name,mimeType,size)&pageToken=${encode(page)}")
                    val arr = result.optJSONArray("files") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        out +=
                            RemoteEntry(
                                f.getString("id"),
                                f.getString("name"),
                                f.getString("mimeType") == "application/vnd.google-apps.folder",
                                f.optString("size").toLongOrNull() ?: -1)
                    }
                    page = result.optString("nextPageToken")
                } while (page.isNotEmpty() && out.size < 50000)
            }
            Protocol.DROPBOX -> {
                var result =
                    drop(
                        "files/list_folder",
                        JSONObject().put("path", if (path == "/") "" else path).put("limit", 1000))
                while (true) {
                    val arr = result.getJSONArray("entries")
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        out +=
                            RemoteEntry(
                                f.getString("path_display"),
                                f.getString("name"),
                                f.getString(".tag") == "folder",
                                f.optLong("size", -1))
                    }
                    if (!result.optBoolean("has_more") || out.size >= 50000) break
                    result =
                        drop(
                            "files/list_folder/continue",
                            JSONObject().put("cursor", result.getString("cursor")))
                }
            }
            Protocol.ONEDRIVE -> {
                var url =
                    if (path.isBlank() || path == "/") "$graph/root/children"
                    else "$graph/items/${encode(path)}/children"
                while (url.isNotEmpty()) {
                    if (!url.startsWith("https://graph.microsoft.com/"))
                        throw IOException("Respuesta inesperada de OneDrive")
                    val result = json(url)
                    val arr = result.getJSONArray("value")
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        out +=
                            RemoteEntry(
                                f.getString("id"),
                                f.getString("name"),
                                f.has("folder"),
                                f.optLong("size", -1))
                    }
                    url = result.optString("@odata.nextLink")
                    if (out.size >= 50000) break
                }
            }
            else -> throw IOException("Proveedor no compatible")
        }
        return out
    }

    override fun read(path: String): InputStream =
        when (account.protocol) {
            Protocol.DRIVE -> {
                val metadata = json("$drive/files/${encode(path)}?fields=mimeType")
                val mime = metadata.getString("mimeType")
                if (mime.startsWith("application/vnd.google-apps."))
                    throw IOException(
                        "Exporta el documento de Google a PDF o DOCX antes de copiarlo")
                http.response(http.open("$drive/files/${encode(path)}?alt=media", "GET"))
            }
            Protocol.DROPBOX ->
                http.response(
                    http.open(
                        "https://content.dropboxapi.com/2/files/download",
                        "POST",
                        mapOf("Dropbox-API-Arg" to JSONObject().put("path", path).toString())))
            Protocol.ONEDRIVE -> {
                val c = http.open("$graph/items/${encode(path)}/content", "GET")
                if (c.responseCode in 300..399) {
                    val url =
                        c.getHeaderField("Location")
                            ?: throw IOException("OneDrive no devolvió una descarga")
                    c.disconnect()
                    if (URL(url).protocol != "https")
                        throw IOException("Descarga de OneDrive sin HTTPS")
                    // This preauthenticated URL must never receive the Microsoft account token.
                    Http("").response(Http("").open(url, "GET"))
                } else http.response(c)
            }
            else -> throw IOException("Proveedor no compatible")
        }

    override fun mkdir(parent: String, name: String): String {
        SafeFiles.requireName(name)
        return when (account.protocol) {
            Protocol.DRIVE ->
                json(
                        "$drive/files",
                        "POST",
                        JSONObject()
                            .put("name", name)
                            .put("mimeType", "application/vnd.google-apps.folder")
                            .put("parents", JSONArray().put(root(parent))))
                    .getString("id")
            Protocol.DROPBOX ->
                drop(
                        "files/create_folder_v2",
                        JSONObject()
                            .put("path", RemoteFiles.join(parent, name))
                            .put("autorename", false))
                    .getJSONObject("metadata")
                    .getString("path_display")
            Protocol.ONEDRIVE ->
                json(
                        if (parent.isBlank() || parent == "/") "$graph/root/children"
                        else "$graph/items/${encode(parent)}/children",
                        "POST",
                        JSONObject()
                            .put("name", name)
                            .put("folder", JSONObject())
                            .put("@microsoft.graph.conflictBehavior", "fail"))
                    .getString("id")
            else -> throw IOException("Proveedor no compatible")
        }
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        SafeFiles.requireName(name)
        return when (account.protocol) {
            Protocol.DRIVE -> {
                // Resumable protocol streams large files without loading them into memory.
                val start =
                    http.open(
                        "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable",
                        "POST",
                        mapOf(
                            "Content-Type" to "application/json",
                            "X-Upload-Content-Type" to "application/octet-stream",
                            "X-Upload-Content-Length" to "$size"),
                        null)
                start.doOutput = true
                try {
                    start.outputStream.use {
                        it.write(
                            JSONObject()
                                .put("name", name)
                                .put("parents", JSONArray().put(root(parent)))
                                .toString()
                                .toByteArray())
                    }
                    http.response(start).close()
                    val url =
                        start.getHeaderField("Location")
                            ?: throw IOException("No se pudo iniciar la subida")
                    if (!url.startsWith("https://www.googleapis.com/"))
                        throw IOException("Destino de subida inesperado")
                    val c =
                        http.open(
                            url, "PUT", mapOf("Content-Type" to "application/octet-stream"), size)
                    try {
                        c.outputStream.use { input.copyTo(it) }
                        JSONObject(http.response(c).bufferedReader().use { it.readText() })
                            .getString("id")
                    } finally {
                        c.disconnect()
                    }
                } finally {
                    start.disconnect()
                }
            }
            Protocol.DROPBOX -> {
                val path = RemoteFiles.join(parent, name)
                val chunk = ByteArray(8 * 1024 * 1024)
                var session = ""
                var offset = 0L
                while (true) {
                    val n = readChunk(input, chunk)
                    val final = offset + n >= size
                    val method: String
                    val args: JSONObject
                    if (session.isEmpty()) {
                        method = "upload_session/start"
                        args = JSONObject().put("close", false)
                    } else if (final) {
                        method = "upload_session/finish"
                        args =
                            JSONObject()
                                .put(
                                    "cursor",
                                    JSONObject().put("session_id", session).put("offset", offset))
                                .put(
                                    "commit",
                                    JSONObject()
                                        .put("path", path)
                                        .put("mode", "add")
                                        .put("autorename", false)
                                        .put("strict_conflict", true))
                    } else {
                        method = "upload_session/append_v2"
                        args =
                            JSONObject()
                                .put(
                                    "cursor",
                                    JSONObject().put("session_id", session).put("offset", offset))
                                .put("close", false)
                    }
                    val c =
                        http.open(
                            "https://content.dropboxapi.com/2/files/$method",
                            "POST",
                            mapOf(
                                "Content-Type" to "application/octet-stream",
                                "Dropbox-API-Arg" to args.toString()),
                            n.toLong())
                    val response =
                        try {
                            c.outputStream.use { it.write(chunk, 0, n) }
                            http.response(c).bufferedReader().use { it.readText() }
                        } finally {
                            c.disconnect()
                        }
                    if (session.isEmpty()) session = JSONObject(response).getString("session_id")
                    else if (final) break
                    offset += n
                    if (n == 0 && offset < size)
                        throw IOException("El archivo cambió durante la subida")
                }
                path
            }
            Protocol.ONEDRIVE -> {
                val prefix =
                    if (parent.isBlank() || parent == "/") "$graph/root:/${encode(name)}:"
                    else "$graph/items/${encode(parent)}:/${encode(name)}:"
                if (size <= 4L * 1024 * 1024) {
                    val c =
                        http.open(
                            "$prefix/content?@microsoft.graph.conflictBehavior=fail",
                            "PUT",
                            mapOf("Content-Type" to "application/octet-stream"),
                            size)
                    try {
                        c.outputStream.use { input.copyTo(it) }
                        JSONObject(http.response(c).bufferedReader().use { it.readText() })
                            .getString("id")
                    } finally {
                        c.disconnect()
                    }
                } else {
                    val upload =
                        json(
                                "$prefix/createUploadSession",
                                "POST",
                                JSONObject()
                                    .put(
                                        "item",
                                        JSONObject()
                                            .put("@microsoft.graph.conflictBehavior", "fail")
                                            .put("name", name)))
                            .getString("uploadUrl")
                    if (URL(upload).protocol != "https") throw IOException("Subida sin HTTPS")
                    val chunk = ByteArray(10 * 320 * 1024)
                    var offset = 0L
                    var id = ""
                    try {
                        while (offset < size) {
                            val n = readChunk(input, chunk)
                            if (n == 0) throw IOException("El archivo cambió durante la subida")
                            val c =
                                Http("")
                                    .open(
                                        upload,
                                        "PUT",
                                        mapOf(
                                            "Content-Range" to "bytes $offset-${offset+n-1}/$size"),
                                        n.toLong())
                            val response =
                                try {
                                    c.outputStream.use { it.write(chunk, 0, n) }
                                    JSONObject(
                                        Http("").response(c).bufferedReader().use { it.readText() })
                                } finally {
                                    c.disconnect()
                                }
                            id = response.optString("id")
                            offset += n
                        }
                        if (id.isBlank()) throw IOException("OneDrive no confirmó la subida")
                        id
                    } catch (e: Exception) {
                        runCatching { Http("").request(upload, "DELETE") }
                        throw e
                    }
                }
            }
            else -> throw IOException("Proveedor no compatible")
        }
    }

    override fun rename(entry: RemoteEntry, name: String) {
        SafeFiles.requireName(name)
        when (account.protocol) {
            Protocol.DRIVE ->
                patch("$drive/files/${encode(entry.path)}", JSONObject().put("name", name))
            Protocol.DROPBOX ->
                drop(
                    "files/move_v2",
                    JSONObject()
                        .put("from_path", entry.path)
                        .put("to_path", RemoteFiles.join(entry.path.substringBeforeLast('/'), name))
                        .put("autorename", false))
            Protocol.ONEDRIVE ->
                patch("$graph/items/${encode(entry.path)}", JSONObject().put("name", name))
            else -> throw IOException("Proveedor no compatible")
        }
    }

    private fun patch(url: String, body: JSONObject) {
        // Android HttpURLConnection does not support PATCH; official APIs accept method override.
        http.request(url, "PATCH", body.toString(), mapOf("Content-Type" to "application/json"))
    }

    override fun delete(entry: RemoteEntry) {
        when (account.protocol) {
            Protocol.DRIVE ->
                patch("$drive/files/${encode(entry.path)}", JSONObject().put("trashed", true))
            Protocol.DROPBOX -> drop("files/delete_v2", JSONObject().put("path", entry.path))
            Protocol.ONEDRIVE -> http.request("$graph/items/${encode(entry.path)}", "DELETE")
            else -> throw IOException("Proveedor no compatible")
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
