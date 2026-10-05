package com.omaritoinforma.oiarchivos.data

import android.app.*
import android.content.*
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.net.*
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Servers are opt-in, authenticated, restricted to one user-selected folder, and foreground-owned.
 */
data class ShareInfo(
    val url: String,
    val user: String,
    val password: String,
    val root: String,
    val mode: String
)

class ShareService : Service() {
    private var http: LocalHttp? = null
    private var ftp: LocalFtp? = null
    private var bluetooth: BluetoothObexShare? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        TransferService.createChannel(this)
        val stop =
            PendingIntent.getService(
                this,
                22,
                Intent(this, ShareService::class.java).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(
            22,
            NotificationCompat.Builder(this, "transfers")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(tr("Compartir archivos por red"))
                .setContentText(tr("Servidor activo · toca Detener al terminar"))
                .setOngoing(true)
                .addAction(0, tr("Detener"), stop)
                .build())
        try {
            http?.stop()
            ftp?.close()
            bluetooth?.close()
            bluetooth = null
            val root =
                File(intent?.getStringExtra("root") ?: throw IOException(tr("Elige una carpeta")))
                    .canonicalFile
            if (!root.isDirectory || !root.canRead())
                throw IOException(tr("La carpeta no se puede leer"))
            val mode = intent.getStringExtra("mode") ?: "HTTP"
            if (mode == "BLUETOOTH") {
                val share = BluetoothObexShare(this, root, Prefs(this).obexWritable)
                bluetooth = share
                state.value = ShareInfo("bluetooth://${share.deviceName}", "", "", root.path, mode)
                error.value = null
                return START_NOT_STICKY
            }
            val address = localAddress() ?: throw IOException(tr("Conéctate a una red Wi-Fi local"))
            // Con una contraseña fija elegida en la pantalla de compartir, el PC puede guardar la conexión (solo FTP).
            val fixed = Prefs(this).ftpPassword.takeIf { mode == "FTP" && ftpPasswordValid(it) && it.isNotEmpty() }
            val password =
                fixed
                    ?: Base64.encodeToString(
                        ByteArray(18).apply { SecureRandom().nextBytes(this) },
                        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val port =
                if (mode == "FTP") {
                    val prefs = Prefs(this)
                    ftp =
                        try {
                            LocalFtp(root, address, password, prefs.ftpPort, prefs.ftpEncoding.charset)
                        } catch (e: BindException) {
                            throw IOException(tr("El puerto {0} está ocupado: elige otro", prefs.ftpPort))
                        }
                    ftp!!.apply { start() }.port
                } else {
                    http =
                        LocalHttp(root, address, password).apply {
                            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        }
                    http!!.listeningPort
                }
            state.value =
                ShareInfo(
                    "${if(mode=="FTP")"ftp"else"http"}://$address:$port/",
                    "oi",
                    password,
                    root.path,
                    mode)
            error.value = null
        } catch (e: Exception) {
            error.value = e.message
            stopSelf()
        }
        FtpTileService.refresh(this)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        http?.stop()
        ftp?.close()
        bluetooth?.close()
        state.value = null
        FtpTileService.refresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        /** Vacía (una nueva cada vez) o de 8 a 64 caracteres sin espacios ni de control. */
        fun ftpPasswordValid(password: String): Boolean =
            password.isEmpty() ||
                (password.length in 8..64 && password.none { it.isWhitespace() || it.isISOControl() })

        val state = MutableStateFlow<ShareInfo?>(null)
        val error = MutableStateFlow<String?>(null)

        fun start(ctx: Context, root: String, mode: String) {
            if (mode == "FTP") Prefs(ctx).ftpRoot = root
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ShareService::class.java).putExtra("root", root).putExtra("mode", mode))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShareService::class.java))
        }

        private fun localAddress(): String? =
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull {
                    it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress
                }
                ?.hostAddress
    }
}

private fun within(root: File, path: String): File {
    val target =
        if (path.isBlank() || path == "/") root
        else SafeFiles.archiveTarget(root, path.trimStart('/'))
    if (target != root && !target.canonicalPath.startsWith(root.canonicalPath + File.separator))
        throw IOException("Ruta fuera de la carpeta compartida")
    return target
}

private fun html(value: String) =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private class LocalHttp(private val root: File, address: String, private val password: String) :
    NanoHTTPD(address, 0) {
    private val authorization =
        "Basic " + Base64.encodeToString("oi:$password".toByteArray(), Base64.NO_WRAP)

    override fun serve(session: IHTTPSession): Response {
        if (!MessageDigest.isEqual(
            session.headers["authorization"].orEmpty().toByteArray(), authorization.toByteArray()))
            return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "Acceso protegido")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"OI Archivos\"") }
        return try {
            val file = within(root, session.uri)
            if (session.method == Method.POST) {
                val length =
                    session.headers["content-length"]?.toLongOrNull()
                        ?: throw IOException("Falta el tamaño de la subida")
                if (length > 1024L * 1024 * 1024 || length < 0)
                    throw IOException("Máximo 1 GB por subida desde el navegador")
                if (!file.isDirectory) throw IOException("El destino no es una carpeta")
                if (session.parms["csrf"] != password) throw IOException("Formulario no autorizado")
                val body = HashMap<String, String>()
                session.parseBody(body)
                val uploaded = body["file"] ?: throw IOException("Elige un archivo")
                val name =
                    session.parms["file"]?.replace('\\', '/')?.substringAfterLast('/') ?: "archivo"
                SafeFiles.requireName(name)
                val target = FileOps.uniqueName(file, name)
                SafeFiles.writeAtomic(target) { part ->
                    File(uploaded).inputStream().use { input ->
                        part.outputStream().use { input.copyTo(it) }
                    }
                }
                // 303 = "ver la carpeta de nuevo"; un 301 es permanente y el navegador podría guardarlo en caché.
                return newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "Subido")
                    .apply { addHeader("Location", session.uri) }
            }
            if (session.method != Method.GET && session.method != Method.HEAD)
                return newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Método no permitido")
            if (!file.exists())
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No existe")
            if (file.isFile) {
                val mime =
                    android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase())
                        ?: "application/octet-stream"
                return newFixedLengthResponse(
                        Response.Status.OK, mime, file.inputStream(), file.length())
                    .apply {
                        addHeader(
                            "Content-Disposition",
                            "attachment; filename*=UTF-8''${encode(file.name)}")
                        addHeader("X-Content-Type-Options", "nosniff")
                    }
            }
            val base =
                if (file == root) "/"
                else "/" + file.relativeTo(root).invariantSeparatorsPath.trimEnd('/') + "/"
            val listing =
                (file.listFiles() ?: emptyArray())
                    .filter { !Files.isSymbolicLink(it.toPath()) }
                    .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                    .joinToString("") { child ->
                        "<li><a href=\"${html(base+encode(child.name))}\">${html(child.name)}${if(child.isDirectory)"/"else""}</a></li>"
                    }
            val parent = if (file == root) "" else "<p><a href=\"../\">Carpeta superior</a></p>"
            val content =
                "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>OI Archivos</title><h1>OI Archivos</h1><p>${html(base)}</p>$parent<ul>$listing</ul><form method=post enctype=multipart/form-data action='${html(base)}?csrf=${encode(password)}'><input type=file name=file required><button>Subir archivo (máx. 1 GB)</button></form>"
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", content).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Frame-Options", "DENY")
                addHeader(
                    "Content-Security-Policy",
                    "default-src 'none'; form-action 'self'; frame-ancestors 'none'")
                addHeader("Referrer-Policy", "no-referrer")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", e.message ?: "Error")
        }
    }
}

/** Passive FTP subset sufficient for desktop clients. No anonymous access or active-mode bounce. */
/**
 * Servidor FTP del teléfono. [port] 0 elige uno libre; [charset] es la codificación de los nombres
 * (UTF-8 salvo que el cliente sea antiguo). Admite modo pasivo (PASV, EPSV) y activo (PORT, EPRT):
 * en el activo solo conecta de vuelta con la dirección del propio cliente y a puertos altos, para
 * que nadie pueda usar el servidor para atacar a un tercero (ataque «FTP bounce»).
 */
internal class LocalFtp(
    private val root: File,
    private val address: String,
    private val password: String,
    port: Int = 0,
    private val charset: Charset = Charsets.UTF_8
) : Closeable {
    private val server =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(address), port), 20)
        }
    val port: Int
        get() = server.localPort

    private val workers = Executors.newFixedThreadPool(4)
    private val clients = Collections.synchronizedList(ArrayList<Socket>())
    @Volatile private var running = true

    fun start() {
        Thread(
                {
                    while (running) {
                        try {
                            val socket = server.accept()
                            clients.add(socket)
                            workers.submit { serve(socket) }
                        } catch (_: Exception) {
                            if (running) close()
                        }
                    }
                },
                "oi-ftp-accept")
            .apply {
                isDaemon = true
                start()
            }
    }

    private fun serve(socket: Socket) {
        var passive: ServerSocket? = null
        var active: InetSocketAddress? = null
        try {
            socket.soTimeout = 120000
            val reader = socket.getInputStream().bufferedReader(charset)
            val writer = socket.getOutputStream().bufferedWriter(charset)
            var user = false
            var logged = false
            var cwd = "/"
            var rename: File? = null
            var offset = 0L
            fun reply(code: Int, text: String) {
                writer.write("$code $text\r\n")
                writer.flush()
            }
            fun target(arg: String): File =
                within(root, if (arg.startsWith('/')) arg else cwd.trimEnd('/') + "/" + arg)
            fun relative(file: File) = "/" + file.relativeTo(root).invariantSeparatorsPath
            fun data(block: (Socket) -> Unit) {
                val p = passive
                val a = active
                val peer =
                    when {
                        p != null -> {
                            reply(150, "Abriendo datos")
                            p.soTimeout = 30000
                            p.accept().also {
                                if (it.inetAddress != socket.inetAddress) {
                                    it.close()
                                    throw IOException("Cliente de datos inesperado")
                                }
                            }
                        }
                        a != null -> {
                            reply(150, "Abriendo datos")
                            Socket().apply { connect(a, 30000) }
                        }
                        else -> throw IOException("Usa PASV, EPSV, PORT o EPRT")
                    }
                peer.use {
                    it.soTimeout = 30000
                    block(it)
                }
                p?.close()
                passive = null
                active = null
                reply(226, "Transferencia completa")
            }
            /** Dirección de datos que pide el cliente en modo activo, si es la suya y un puerto alto. */
            fun activeTarget(ip: String, port: Int): InetSocketAddress? {
                if (ip != socket.inetAddress.hostAddress || port !in 1024..65535) {
                    reply(504, "Solo se conecta a tu propia dirección y a puertos desde 1024")
                    return null
                }
                return InetSocketAddress(socket.inetAddress, port)
            }
            reply(220, "OI Archivos FTP")
            while (running) {
                val line = reader.readLine() ?: break
                if (line.length > 8192) break
                val cmd = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "")
                if (cmd == "QUIT") {
                    reply(221, "Hasta luego")
                    break
                }
                if (cmd == "USER") {
                    user = arg == "oi"
                    reply(331, "Contraseña requerida")
                    continue
                }
                if (cmd == "PASS") {
                    logged =
                        user && MessageDigest.isEqual(arg.toByteArray(), password.toByteArray())
                    reply(
                        if (logged) 230 else 530,
                        if (logged) "Sesión iniciada" else "Credenciales rechazadas")
                    continue
                }
                if (!logged) {
                    reply(530, "Inicia sesión")
                    continue
                }
                try {
                    when (cmd) {
                        "SYST" -> reply(215, "UNIX Type: L8")
                        "FEAT" -> {
                            val utf8 = if (charset == Charsets.UTF_8) " UTF8\r\n" else ""
                            writer.write(
                                "211-Features\r\n$utf8 EPSV\r\n EPRT\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End\r\n")
                            writer.flush()
                        }
                        "OPTS" ->
                            // Los nombres van en la codificación elegida; no se puede pasar a UTF-8 a medias.
                            if (arg.uppercase().startsWith("UTF8") && charset != Charsets.UTF_8)
                                reply(504, "Los nombres van en ${charset.name()}")
                            else reply(200, "OK")
                        "TYPE",
                        "NOOP" -> reply(200, "OK")
                        "PWD",
                        "XPWD" -> reply(257, "\"${cwd.replace("\"","\"\"")}\"")
                        "CWD",
                        "CDUP" -> {
                            val raw =
                                if (cmd == "CDUP")
                                    cwd.trimEnd('/').substringBeforeLast('/', "/").ifBlank { "/" }
                                else arg
                            val f = target(raw)
                            if (!f.isDirectory) throw IOException("No existe la carpeta")
                            cwd = relative(f)
                            reply(250, "Carpeta cambiada")
                        }
                        "PORT" -> {
                            val n = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
                            if (n.size != 6 || n.any { it !in 0..255 }) reply(501, "PORT incorrecto")
                            else {
                                activeTarget(n.take(4).joinToString("."), n[4] * 256 + n[5])?.let {
                                    passive?.close()
                                    passive = null
                                    active = it
                                    reply(200, "PORT aceptado")
                                }
                            }
                        }
                        "EPRT" -> {
                            val delimiter = arg.firstOrNull()
                            val parts = if (delimiter == null) emptyList() else arg.split(delimiter)
                            val port = parts.getOrNull(3)?.toIntOrNull()
                            when {
                                parts.size < 4 || port == null -> reply(501, "EPRT incorrecto")
                                parts[1] != "1" -> reply(522, "Solo IPv4 (protocolo 1)")
                                else ->
                                    activeTarget(parts[2], port)?.let {
                                        passive?.close()
                                        passive = null
                                        active = it
                                        reply(200, "EPRT aceptado")
                                    }
                            }
                        }
                        "PASV",
                        "EPSV" -> {
                            passive?.close()
                            active = null
                            passive = ServerSocket(0, 1, InetAddress.getByName(address))
                            val p = passive!!.localPort
                            if (cmd == "EPSV") reply(229, "Entering Extended Passive Mode (|||$p|)")
                            else
                                reply(
                                    227,
                                    "Entering Passive Mode (${address.replace('.',',')},${p/256},${p%256})")
                        }
                        "LIST",
                        "NLST",
                        "MLSD" -> {
                            val f =
                                if (arg.isBlank() || arg.startsWith('-')) target("")
                                else target(arg)
                            val children =
                                (f.listFiles() ?: throw IOException("No se puede listar")).filter {
                                    !Files.isSymbolicLink(it.toPath())
                                }
                            data { peer ->
                                peer.getOutputStream().bufferedWriter(charset).use { out ->
                                    children.forEach { child ->
                                        val name = child.name.replace('\r', '_').replace('\n', '_')
                                        val text =
                                            when (cmd) {
                                                "NLST" -> name
                                                "MLSD" ->
                                                    "type=${if(child.isDirectory)"dir"else"file"};size=${child.length()}; $name"
                                                else ->
                                                    "${if(child.isDirectory)"d"else"-"}rw-r--r-- 1 oi oi ${child.length()} ${SimpleDateFormat("MMM dd HH:mm",Locale.US).format(Date(child.lastModified()))} $name"
                                            }
                                        out.write(text + "\r\n")
                                    }
                                }
                            }
                        }
                        "RETR" -> {
                            val f = target(arg)
                            if (!f.isFile) throw IOException("No existe el archivo")
                            data { peer ->
                                f.inputStream().use { input ->
                                    var skipped = 0L
                                    while (skipped < offset) {
                                        val n = input.skip(offset - skipped)
                                        if (n <= 0) throw IOException("Posición fuera del archivo")
                                        skipped += n
                                    }
                                    input.copyTo(peer.getOutputStream())
                                }
                            }
                            offset = 0
                        }
                        "STOR" -> {
                            val f = target(arg)
                            if (offset != 0L) throw IOException("Reanudar subidas no es compatible")
                            if (f.exists())
                                throw IOException("El archivo ya existe; usa otro nombre")
                            data { peer ->
                                SafeFiles.writeAtomic(f) { part ->
                                    peer.getInputStream().use { input ->
                                        part.outputStream().use { input.copyTo(it) }
                                    }
                                }
                            }
                        }
                        "SIZE" -> reply(213, "${target(arg).length()}")
                        "MDTM" ->
                            reply(
                                213,
                                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                                    .format(Date(target(arg).lastModified())))
                        "REST" -> {
                            offset =
                                arg.toLongOrNull()?.takeIf { it >= 0 }
                                    ?: throw IOException("Posición no válida")
                            reply(350, "Posición aceptada")
                        }
                        "MKD" -> {
                            val f = target(arg)
                            if (!f.mkdir()) throw IOException("No se pudo crear")
                            reply(257, "\"${relative(f)}\"")
                        }
                        "DELE" -> {
                            val f = target(arg)
                            if (!f.isFile || !f.delete()) throw IOException("No se pudo eliminar")
                            reply(250, "Eliminado")
                        }
                        "RMD" -> {
                            val f = target(arg)
                            if (f == root || !f.isDirectory || !f.delete())
                                throw IOException("Carpeta no vacía o protegida")
                            reply(250, "Eliminada")
                        }
                        "RNFR" -> {
                            rename =
                                target(arg).takeIf { it.exists() && it != root }
                                    ?: throw IOException("No existe")
                            reply(350, "Destino requerido")
                        }
                        "RNTO" -> {
                            val src = rename ?: throw IOException("Usa RNFR")
                            val dst = target(arg)
                            if (dst.exists() || !src.renameTo(dst))
                                throw IOException("No se pudo renombrar")
                            rename = null
                            reply(250, "Renombrado")
                        }
                        else -> reply(502, "Comando no compatible")
                    }
                } catch (_: Exception) {
                    passive?.close()
                    passive = null
                    active = null
                    reply(550, "Operación rechazada o incompleta")
                }
            }
        } catch (_: Exception) {} finally {
            runCatching { passive?.close() }
            clients.remove(socket)
            socket.close()
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        synchronized(clients) {
            clients.toList().forEach { runCatching { it.close() } }
            clients.clear()
        }
        workers.shutdownNow()
    }
}
