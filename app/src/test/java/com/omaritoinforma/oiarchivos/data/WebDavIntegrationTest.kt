package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val directories = ConcurrentHashMap.newKeySet<String>()
    private val expectedAuth =
        "Basic " + Base64.getEncoder().encodeToString("oi:test".toByteArray(Charsets.UTF_8))

    @Before
    fun startServer() {
        directories += "/"
        files["/alpha.txt"] = "remote payload".toByteArray()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != expectedAuth)
                        return MockResponse().setResponseCode(401)

                    val path = normalize(request.requestUrl?.encodedPath ?: "/")
                    return when (request.method) {
                        "PROPFIND" -> propfind(path)
                        "GET" ->
                            files[path]?.let {
                                MockResponse()
                                    .setResponseCode(200)
                                    .setBody(okio.Buffer().write(it))
                            } ?: MockResponse().setResponseCode(404)
                        "PUT" -> {
                            if (path in files || path in directories)
                                MockResponse().setResponseCode(412)
                            else {
                                files[path] = request.body.readByteArray()
                                MockResponse().setResponseCode(201)
                            }
                        }
                        "MKCOL" -> {
                            if (path in files || path in directories)
                                MockResponse().setResponseCode(405)
                            else {
                                directories += path
                                MockResponse().setResponseCode(201)
                            }
                        }
                        "MOVE" -> {
                            val destination =
                                request.getHeader("Destination")
                                    ?.let { normalize(URI(it).rawPath ?: "/") }
                                    ?: return MockResponse().setResponseCode(400)
                            if (destination in files || destination in directories)
                                return MockResponse().setResponseCode(412)
                            val body = files.remove(path)
                            if (body != null) {
                                files[destination] = body
                                MockResponse().setResponseCode(201)
                            } else if (directories.remove(path)) {
                                directories += destination
                                val fileMoves =
                                    files.keys
                                        .filter { it.startsWith(path.trimEnd('/') + "/") }
                                        .associateWith { old ->
                                            destination + old.removePrefix(path)
                                        }
                                fileMoves.forEach { (old, new) ->
                                    files.remove(old)?.let { files[new] = it }
                                }
                                MockResponse().setResponseCode(201)
                            } else MockResponse().setResponseCode(404)
                        }
                        "DELETE" -> {
                            val removedFile = files.remove(path) != null
                            val removedDir = directories.remove(path)
                            if (removedDir) {
                                files.keys
                                    .filter { it.startsWith(path.trimEnd('/') + "/") }
                                    .forEach(files::remove)
                                directories
                                    .filter { it.startsWith(path.trimEnd('/') + "/") }
                                    .forEach(directories::remove)
                            }
                            MockResponse().setResponseCode(if (removedFile || removedDir) 204 else 404)
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun webDavClientPerformsAuthenticatedFileLifecycle() = runBlocking {
        val connection =
            Connection(
                label = "CI WebDAV",
                protocol = Protocol.WEBDAV,
                host = server.url("/").toString().trimEnd('/'),
                port = server.port,
                user = "oi",
                secret = "test",
                root = "/")

        RemoteFiles.connect(connection).use { fs ->
            val initial = fs.list("/")
            val alpha = initial.single { it.name == "alpha.txt" }
            assertFalse(alpha.directory)
            assertEquals("/alpha.txt", alpha.path)

            val localDir = temp.newFolder("download")
            val downloaded = RemoteFiles.download(fs, alpha, localDir, {})
            assertEquals("remote payload", downloaded.readText())

            val upload = File(temp.root, "upload.txt").apply { writeText("uploaded from OI") }
            RemoteFiles.upload(fs, listOf(upload), "/", {})
            assertArrayEquals(
                "uploaded from OI".toByteArray(),
                files["/upload.txt"])

            val uploaded = fs.list("/").single { it.name == "upload.txt" }
            fs.rename(uploaded, "renamed.txt")
            assertFalse(files.containsKey("/upload.txt"))
            assertEquals("uploaded from OI", files["/renamed.txt"]?.toString(Charsets.UTF_8))

            val folderPath = fs.mkdir("/", "folder")
            assertEquals("/folder", folderPath)
            assertTrue("/folder" in directories)

            val renamed = fs.list("/").single { it.name == "renamed.txt" }
            fs.delete(renamed)
            assertFalse(files.containsKey("/renamed.txt"))

            val folder = fs.list("/").single { it.name == "folder" }
            fs.delete(folder)
            assertFalse("/folder" in directories)
        }
    }

    private fun propfind(path: String): MockResponse {
        if (path !in directories) return MockResponse().setResponseCode(404)
        val current = path.trimEnd('/').ifEmpty { "/" }
        val prefix = if (current == "/") "/" else "$current/"
        val children =
            buildList {
                directories
                    .filter { it != current && it.startsWith(prefix) }
                    .filter { it.removePrefix(prefix).trim('/').let { tail -> '/' !in tail } }
                    .forEach { add(it to true) }
                files.keys
                    .filter { it.startsWith(prefix) }
                    .filter { '/' !in it.removePrefix(prefix) }
                    .forEach { add(it to false) }
            }.sortedBy { it.first }

        fun response(itemPath: String, directory: Boolean): String {
            val name = itemPath.substringAfterLast('/')
            val size = if (directory) 0 else files[itemPath]?.size ?: 0
            val href = if (directory && itemPath != "/") "$itemPath/" else itemPath
            val resource = if (directory) "<d:collection/>" else ""
            return """
                <d:response>
                  <d:href>$href</d:href>
                  <d:propstat><d:prop>
                    <d:displayname>$name</d:displayname>
                    <d:resourcetype>$resource</d:resourcetype>
                    <d:getcontentlength>$size</d:getcontentlength>
                  </d:prop></d:propstat>
                </d:response>
            """.trimIndent()
        }

        val xml =
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
            ${response(current, true)}
            ${children.joinToString("\n") { response(it.first, it.second) }}
            </d:multistatus>
            """.trimIndent()
        return MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(xml)
    }

    private fun normalize(raw: String): String {
        val decoded = URI(raw).path ?: raw
        val clean = "/" + decoded.trim('/').replace(Regex("/+"), "/")
        return clean.trimEnd('/').ifEmpty { "/" }
    }
}
