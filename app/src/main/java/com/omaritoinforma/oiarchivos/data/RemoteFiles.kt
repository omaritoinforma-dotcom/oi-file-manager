package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element

enum class Protocol(val label: String) {
    FTP("FTP"),
    FTPS("FTPS"),
    SFTP("SFTP"),
    SMB("Windows / SMB"),
    WEBDAV("WebDAV"),
    DRIVE("Google Drive"),
    DROPBOX("Dropbox"),
    ONEDRIVE("OneDrive"),
    BOX("Box"),
    YANDEX("Yandex Disk"),
    S3("Amazon S3"),
    BAIDU("Baidu Netdisk"),
    SUGARSYNC("SugarSync"),
    BLUETOOTH("Bluetooth"),
    ROOT("Root / Magisk")
}

data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val user: String,
    val secret: String,
    val root: String = "/",
    val fingerprint: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val authState: String = "",
    val expiresAt: Long = 0,
    val googleAccount: String = ""
)

data class RemoteEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

/** Credentials are encrypted with a device-bound Android Keystore key; backups are disabled. */
class ConnectionStore(private val ctx: Context) {
    private val prefs
        get() = ctx.getSharedPreferences("connections", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("oi-connections", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "oi-connections",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build())
            }
            .generateKey()
    }

    fun load(): List<Connection> {
        val value = prefs.getString("data", null) ?: return emptyList()
        val parts = value.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val array =
            JSONArray(
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                Connection(
                    it.getString("id"),
                    it.getString("label"),
                    Protocol.valueOf(it.getString("protocol")),
                    it.getString("host"),
                    it.getInt("port"),
                    it.getString("user"),
                    it.getString("secret"),
                    it.getString("root"),
                    it.optString("fingerprint"),
                    it.optString("clientId"),
                    it.optString("clientSecret"),
                    it.optString("authState"),
                    it.optLong("expiresAt"),
                    it.optString("googleAccount"))
            }
        }
    }

    fun save(items: List<Connection>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("protocol", it.protocol.name)
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("user", it.user)
                    .put("secret", it.secret)
                    .put("root", it.root)
                    .put("fingerprint", it.fingerprint)
                    .put("clientId", it.clientId)
                    .put("clientSecret", it.clientSecret)
                    .put("authState", it.authState)
                    .put("expiresAt", it.expiresAt)
                    .put("googleAccount", it.googleAccount))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(
                    cipher.doFinal(array.toString().toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString("data", value).commit()
    }
}

interface RemoteFs : Closeable {
    fun list(path: String): List<RemoteEntry>

    fun read(path: String): InputStream

    /** Reads from [offset], or returns null when the server cannot resume (restart from zero). */
    fun readFrom(path: String, offset: Long): InputStream? = null

    fun write(parent: String, name: String, input: InputStream, size: Long): String

    fun mkdir(parent: String, name: String): String

    fun rename(entry: RemoteEntry, name: String)

    fun delete(entry: RemoteEntry)

    override fun close() {}
}

object RemoteFiles {
    lateinit var appContext: Context

    fun connect(connection: Connection): RemoteFs {
        val c =
            if (connection.authState.isBlank()) connection
            else CloudAuth.fresh(appContext, connection)
        return when (c.protocol) {
            Protocol.FTP,
            Protocol.FTPS -> FtpFs(c)
            Protocol.SFTP -> SftpFs(c)
            Protocol.SMB -> SmbFs(c)
            Protocol.ROOT -> RootFs()
            Protocol.BLUETOOTH -> BluetoothFs(c, appContext)
            Protocol.BOX -> BoxFs(c)
            Protocol.YANDEX -> YandexFs(c)
            Protocol.S3 -> S3Fs(c)
            Protocol.BAIDU -> BaiduFs(c)
            Protocol.SUGARSYNC -> SugarSyncFs(c)
            Protocol.WEBDAV -> DavFs(c)
            Protocol.DRIVE,
            Protocol.DROPBOX,
            Protocol.ONEDRIVE -> CloudFs(c)
        }
    }

    /** Resolves a saved connection for a resumed transfer; journals never store credentials. */
    fun connectById(id: String): RemoteFs =
        connect(ConnectionStore(appContext).load().first { it.id == id })

    fun join(parent: String, name: String): String {
        SafeFiles.requireName(name)
        return parent.trimEnd('/') + "/" + name
    }

    /**
     * Temporary upload name derived from the target, so a retry after an interruption that left
     * the old part behind overwrites it instead of leaving hidden files on the server.
     */
    fun partName(target: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(target.toByteArray())
        return join(
            target.substringBeforeLast('/'),
            ".oi-" + hash.take(12).joinToString("") { "%02x".format(it) } + ".part")
    }

    fun unique(fs: RemoteFs, parent: String, name: String): String {
        val names = fs.list(parent).map { it.name }.toSet()
        if (name !in names) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 1
        while ("$base ($index)$ext" in names) index++
        return "$base ($index)$ext"
    }

    suspend fun upload(
        fs: RemoteFs,
        sources: List<File>,
        parent: String,
        report: (OpProgress) -> Unit
    ) {
        suspend fun one(file: File, dir: String) {
            currentCoroutineContext().ensureActive()
            SafeFiles.requireRegular(file)
            val name = unique(fs, dir, file.name)
            if (file.isDirectory) {
                val child = fs.mkdir(dir, name)
                for (f in
                    file.listFiles() ?: throw IOException("No se puede leer ${file.name}")) one(
                    f, child)
            } else {
                report(OpProgress("Subiendo", file.name, totalBytes = file.length()))
                val context = currentCoroutineContext()
                var done = 0L
                file.inputStream().use { raw ->
                    val input =
                        object : FilterInputStream(raw) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                context.ensureActive()
                                val n = super.read(b, off, len)
                                if (n > 0) {
                                    done += n
                                    report(OpProgress("Subiendo", file.name, done, file.length()))
                                }
                                return n
                            }

                            override fun read(): Int {
                                context.ensureActive()
                                return super.read()
                            }
                        }
                    try {
                        fs.write(dir, name, input, file.length())
                    } catch (e: Exception) {
                        throw e.cancellation() ?: e
                    }
                }
            }
        }
        sources.forEach { one(it, parent) }
    }

    suspend fun download(
        fs: RemoteFs,
        entry: RemoteEntry,
        dir: File,
        report: (OpProgress) -> Unit,
        depth: Int = 0
    ): File {
        currentCoroutineContext().ensureActive()
        SafeFiles.requireName(entry.name)
        if (depth > 128) throw IOException("La carpeta remota supera el límite de profundidad")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo crear el destino")
        val target = FileOps.uniqueName(dir, entry.name)
        if (entry.directory) {
            if (!target.mkdir()) throw IOException("No se pudo crear el destino")
            for (child in fs.list(entry.path)) download(fs, child, target, report, depth + 1)
        } else {
            val temp = File.createTempFile(".oi-remote-", ".tmp", dir)
            try {
                var done = 0L
                val buffer = ByteArray(128 * 1024)
                fs.read(entry.path).use { input ->
                    temp.outputStream().use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            report(OpProgress("Descargando", entry.name, done, entry.size))
                        }
                    }
                }
                if (entry.size >= 0 && done != entry.size) throw IOException("Descarga incompleta")
                SafeFiles.commit(temp, target, replace = false)
            } finally {
                temp.delete()
            }
        }
        return target
    }
}

private class FtpFs(c: Connection) : RemoteFs {
    private val client: FTPClient =
        if (c.protocol == Protocol.FTPS)
            FTPSClient(false).apply {
                isEndpointCheckingEnabled = true
                trustManager =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                        .apply { init(null as java.security.KeyStore?) }
                        .trustManagers
                        .filterIsInstance<javax.net.ssl.X509TrustManager>()
                        .first()
            }
        else FTPClient()

    init {
        try {
            client.connectTimeout = 15000
            client.defaultTimeout = 30000
            // Names with accents or ñ are sent as UTF-8 (RFC 2640), not the ISO-8859-1 default.
            client.controlEncoding = "UTF-8"
            client.connect(c.host, c.port)
            if (!client.login(c.user.ifBlank { "anonymous" }, c.secret))
                throw IOException("El servidor rechazó las credenciales")
            runCatching { client.sendCommand("OPTS UTF8 ON") }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val entries = client.listFiles(path)
        if (client.replyCode >= 400) throw IOException(client.replyString)
        return entries
            .filter { it.name !in setOf(".", "..") && !it.isSymbolicLink }
            .map { RemoteEntry(RemoteFiles.join(path, it.name), it.name, it.isDirectory, it.size) }
    }

    override fun read(path: String): InputStream = stream(path, 0)

    override fun readFrom(path: String, offset: Long): InputStream? =
        runCatching { stream(path, offset) }.getOrNull()

    private fun stream(path: String, offset: Long): InputStream {
        client.restartOffset = offset
        val raw = client.retrieveFileStream(path) ?: throw IOException("No se pudo descargar")
        return object : FilterInputStream(raw) {
            override fun close() {
                super.close()
                if (!client.completePendingCommand()) throw IOException("Descarga incompleta")
            }
        }
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val target = RemoteFiles.join(parent, name)
        val temp = RemoteFiles.partName(target)
        // commons-net only closes the data connection for IOExceptions; a cancellation left it
        // open and the next command waited for the server until the timeout.
        val guarded =
            object : FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    try {
                        super.read(b, off, len)
                    } catch (e: RuntimeException) {
                        throw IOException("Transferencia interrumpida", e)
                    }

                override fun read(): Int =
                    try {
                        super.read()
                    } catch (e: RuntimeException) {
                        throw IOException("Transferencia interrumpida", e)
                    }
            }
        var stored = false
        try {
            if (!client.storeFile(temp, guarded) || !client.rename(temp, target))
                throw IOException("No se pudo subir el archivo")
            stored = true
        } finally {
            if (!stored) runCatching { client.deleteFile(temp) }
        }
        return target
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also {
            if (!client.makeDirectory(it)) throw IOException("No se pudo crear la carpeta")
        }

    override fun rename(entry: RemoteEntry, name: String) {
        val target = RemoteFiles.join(entry.path.substringBeforeLast('/'), name)
        if (!client.rename(entry.path, target)) throw IOException("No se pudo renombrar")
    }

    override fun delete(entry: RemoteEntry) {
        if (entry.directory) {
            list(entry.path).forEach { delete(it) }
            if (!client.removeDirectory(entry.path))
                throw IOException("No se pudo borrar la carpeta")
        } else if (!client.deleteFile(entry.path)) throw IOException("No se pudo borrar el archivo")
    }

    override fun close() {
        runCatching { client.logout() }
        if (client.isConnected) client.disconnect()
    }
}

private class SftpFs(c: Connection) : RemoteFs {
    private val session: Session
    private val sftp: ChannelSftp

    init {
        if (c.fingerprint.isBlank())
            throw IOException("SFTP requiere la huella SHA256 del servidor")
        val jsch = JSch()
        jsch.hostKeyRepository =
            object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray?): Int {
                    val actual =
                        "SHA256:" +
                            java.util.Base64.getEncoder()
                                .withoutPadding()
                                .encodeToString(
                                    MessageDigest.getInstance("SHA-256").digest(key ?: byteArrayOf()))
                    if (actual != c.fingerprint.trim())
                        throw IOException("La huella SFTP no coincide. Huella recibida: $actual")
                    return HostKeyRepository.OK
                }

                override fun add(hostkey: HostKey?, ui: UserInfo?) {}

                override fun remove(host: String?, type: String?) {}

                override fun remove(host: String?, type: String?, key: ByteArray?) {}

                override fun getKnownHostsRepositoryID() = "OI Archivos"

                override fun getHostKey(): Array<HostKey> = emptyArray()

                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
        session = jsch.getSession(c.user, c.host, c.port)
        try {
            session.setPassword(c.secret)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.timeout = 30000
            session.connect(15000)
            sftp = (session.openChannel("sftp") as ChannelSftp).apply { connect(15000) }
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    override fun list(path: String): List<RemoteEntry> =
        sftp
            .ls(path)
            .map { it as ChannelSftp.LsEntry }
            .filter { it.filename !in setOf(".", "..") && !it.attrs.isLink }
            .map {
                RemoteEntry(
                    RemoteFiles.join(path, it.filename), it.filename, it.attrs.isDir, it.attrs.size)
            }

    override fun read(path: String) = sftp.get(path)

    override fun readFrom(path: String, offset: Long): InputStream? =
        sftp.get(path, null as SftpProgressMonitor?, offset)

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val target = RemoteFiles.join(parent, name)
        val temp = RemoteFiles.partName(target)
        try {
            sftp.put(input, temp)
            sftp.rename(temp, target)
        } finally {
            runCatching { sftp.rm(temp) }
        }
        return target
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { sftp.mkdir(it) }

    override fun rename(entry: RemoteEntry, name: String) {
        sftp.rename(entry.path, RemoteFiles.join(entry.path.substringBeforeLast('/'), name))
    }

    override fun delete(entry: RemoteEntry) {
        if (entry.directory) {
            list(entry.path).forEach { delete(it) }
            sftp.rmdir(entry.path)
        } else sftp.rm(entry.path)
    }

    override fun close() {
        sftp.disconnect()
        session.disconnect()
    }
}

private class SmbFs(c: Connection) : RemoteFs {
    private val context: CIFSContext =
        BaseContext(
                PropertyConfiguration(
                    Properties().apply {
                        setProperty("jcifs.smb.client.minVersion", "SMB202")
                        setProperty("jcifs.smb.client.maxVersion", "SMB311")
                        setProperty("jcifs.smb.client.responseTimeout", "30000")
                        setProperty("jcifs.smb.client.signingEnforced", "true")
                    }))
            .withCredentials(NtlmPasswordAuthenticator("", c.user, c.secret))
    private val host = c.host

    private fun file(path: String, directory: Boolean = false) =
        SmbFile(
            "smb://$host/" +
                path.trimStart('/').let { if (directory) it.trimEnd('/') + "/" else it },
            context)

    override fun list(path: String): List<RemoteEntry> =
        file(path, true).use { dir ->
            dir.listFiles().map { child ->
                child.use {
                    RemoteEntry(
                        path.trimEnd('/') + "/" + child.name.trimEnd('/'),
                        child.name.trimEnd('/'),
                        child.isDirectory,
                        if (child.isDirectory) 0 else child.length())
                }
            }
        }

    override fun read(path: String): InputStream {
        val f = file(path)
        val stream = f.inputStream
        return object : FilterInputStream(stream) {
            override fun close() {
                super.close()
                f.close()
            }
        }
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val target = RemoteFiles.join(parent, name)
        val temp = RemoteFiles.partName(target)
        file(temp).use { part ->
            try {
                part.outputStream.use { input.copyTo(it) }
                file(target).use { part.renameTo(it) }
            } finally {
                if (part.exists()) part.delete()
            }
        }
        return target
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { file(it, true).use { f -> f.mkdir() } }

    override fun rename(entry: RemoteEntry, name: String) {
        file(entry.path, entry.directory).use { src ->
            file(RemoteFiles.join(entry.path.substringBeforeLast('/'), name), entry.directory).use {
                src.renameTo(it)
            }
        }
    }

    override fun delete(entry: RemoteEntry) {
        file(entry.path, entry.directory).use { it.delete() }
    }

    override fun close() {
        context.close()
    }
}

/** HTTP helper never disables certificate validation or forwards secrets across redirects. */
internal class Http(private val auth: String) {
    fun open(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        size: Long? = null
    ): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol !in setOf("http", "https")) throw IOException("Solo HTTP o HTTPS")
        return (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
            if (auth.isNotBlank()) setRequestProperty("Authorization", auth)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (size != null) {
                doOutput = true
                if (size >= 0) setFixedLengthStreamingMode(size)
                else setChunkedStreamingMode(128 * 1024)
            }
        }
    }

    fun response(connection: HttpURLConnection): InputStream {
        val code = connection.responseCode
        if (code !in 200..299 && code != 207) {
            connection.errorStream?.close()
            connection.disconnect()
            throw IOException(
                when (code) {
                    401,
                    403 -> "Acceso rechazado; revisa credenciales o token"
                    404 -> "No existe el archivo"
                    409,
                    412 -> "El destino ya existe"
                    else -> "Respuesta HTTP $code"
                })
        }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() {
                super.close()
                connection.disconnect()
            }
        }
    }

    fun request(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        if (method in setOf("PROPFIND", "MKCOL", "MOVE", "PATCH")) {
            val request = Request.Builder().url(url)
            if (auth.isNotBlank()) request.header("Authorization", auth)
            headers.forEach { (key, value) -> request.header(key, value) }
            request.method(method, body?.toRequestBody())
            val client =
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Respuesta HTTP ${response.code}")
                return response.body?.string().orEmpty()
            }
        }
        val data = body?.toByteArray(Charsets.UTF_8)
        val c = open(url, method, headers, data?.size?.toLong())
        try {
            if (data != null) c.outputStream.use { it.write(data) }
            return response(c).bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

private class DavFs(c: Connection) : RemoteFs {
    private val base = c.host.trimEnd('/')
    private val http =
        Http(
            "Basic " +
                java.util.Base64.getEncoder().encodeToString("${c.user}:${c.secret}".toByteArray()))

    init {
        if (!base.startsWith("https://") && !base.startsWith("http://"))
            throw IOException("Escribe la dirección completa, http:// o https://")
    }

    private fun url(path: String) =
        base + "/" + path.trimStart('/').split('/').joinToString("/") { encode(it) }

    override fun list(path: String): List<RemoteEntry> {
        val xml =
            http.request(
                url(path),
                "PROPFIND",
                "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>",
                mapOf("Depth" to "1", "Content-Type" to "application/xml"))
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
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
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val current = URI(url(path)).path.trimEnd('/')
        return (0 until responses.length).mapNotNull { index ->
            val item = responses.item(index) as Element
            val href =
                item.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent
                    ?: return@mapNotNull null
            val decoded = URI(href).path.trimEnd('/')
            if (decoded == current) return@mapNotNull null
            // Only accept direct children within this WebDAV folder.
            if (decoded.substringBeforeLast('/') != current) return@mapNotNull null
            val name = decoded.substringAfterLast('/')
            SafeFiles.requireName(name)
            RemoteEntry(
                RemoteFiles.join(path, name),
                name,
                item.getElementsByTagNameNS("DAV:", "collection").length > 0,
                item
                    .getElementsByTagNameNS("DAV:", "getcontentlength")
                    .item(0)
                    ?.textContent
                    ?.toLongOrNull() ?: 0)
        }
    }

    override fun read(path: String) = http.response(http.open(url(path), "GET"))

    override fun readFrom(path: String, offset: Long): InputStream? {
        val c = http.open(url(path), "GET", mapOf("Range" to "bytes=$offset-"))
        // Only a partial response that starts exactly at the offset can be appended.
        if (c.responseCode != 206 ||
            c.getHeaderField("Content-Range")?.startsWith("bytes $offset-") != true) {
            runCatching { c.inputStream.close() }
            c.disconnect()
            return null
        }
        return http.response(c)
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val target = RemoteFiles.join(parent, name)
        val c = http.open(url(target), "PUT", mapOf("If-None-Match" to "*"), size)
        try {
            c.outputStream.use { input.copyTo(it) }
            http.response(c).close()
        } finally {
            c.disconnect()
        }
        return target
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { http.request(url(it), "MKCOL") }

    override fun rename(entry: RemoteEntry, name: String) {
        http.request(
            url(entry.path),
            "MOVE",
            headers =
                mapOf(
                    "Destination" to
                        url(RemoteFiles.join(entry.path.substringBeforeLast('/'), name)),
                    "Overwrite" to "F"))
    }

    override fun delete(entry: RemoteEntry) {
        http.request(url(entry.path), "DELETE")
    }
}

/** Libraries such as JSch wrap the cancellation thrown from our stream in their own exception. */
internal fun Throwable.cancellation(): kotlinx.coroutines.CancellationException? =
    generateSequence(this) { it.cause }
        .take(16)
        .filterIsInstance<kotlinx.coroutines.CancellationException>()
        .firstOrNull()

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
