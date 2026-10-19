package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omaritoinforma.oiarchivos.MainActivity
import com.omaritoinforma.oiarchivos.util.Perms
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** An optional, outbound-only Android file agent with an owner-visible stop control. */
class RemoteBridgeService : Service() {
    private val active = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val config = BridgeConfig(this)
        if (!config.valid() || !Perms.hasStorage(this)) {
            BridgeStatus.message = "Configura HTTPS, token y permiso de archivos"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("oi_bridge", "Conector remoto", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 87,
            Intent(this, RemoteBridgeService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 88, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(87, NotificationCompat.Builder(this, "oi_bridge")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OI Archivos: acceso remoto activo")
            .setContentText("Acceso al almacenamiento permitido por Android")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Desconectar", stop).build())
        BridgeStatus.running = true
        worker = Thread({ loop(config) }, "oi-bridge").apply { start() }
        return START_NOT_STICKY
    }

    private fun loop(config: BridgeConfig) {
        var errors = 0
        while (active.get() && !Thread.currentThread().isInterrupted) {
            try {
                val request = Request.Builder().url(config.endpoint.trimEnd('/') + "/device/next")
                    .bridgeAuthorization(config)
                    .header("X-OI-Device", Build.MODEL.take(70)).get().build()
                val command = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Error HTTP " + response.code)
                    JSONObject(response.body?.string().orEmpty())
                }
                BridgeStatus.message = "Conectado"
                errors = 0
                val id = command.optString("id")
                if (id.isBlank() || id == "null") {
                    Thread.sleep(2500)
                    continue
                }
                val output = runCatching {
                    FileAgent(this, config.allowChanges, config.allowTrash)
                        .execute(command.optString("action"), command.optJSONObject("args") ?: JSONObject())
                }
                val reply = JSONObject().put("id", id).put("ok", output.isSuccess)
                if (output.isSuccess) reply.put("data", output.getOrNull())
                else reply.put("error", output.exceptionOrNull()?.message ?: "Error")
                val post = Request.Builder().url(config.endpoint.trimEnd('/') + "/device/result")
                    .bridgeAuthorization(config)
                    .post(reply.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(post).execute().use {
                    if (!it.isSuccessful) throw IOException("No se confirmó la respuesta")
                }
            } catch (e: Exception) {
                if (!active.get()) break
                errors++
                BridgeStatus.message = "Sin conexión: " + e.message.orEmpty().take(90)
                try { Thread.sleep((errors * 2000L).coerceAtMost(15000L)) }
                catch (_: InterruptedException) { break }
            }
        }
        BridgeStatus.running = false
    }
    override fun onDestroy() {
        active.set(false)
        worker?.interrupt()
        client.dispatcher.cancelAll()
        BridgeStatus.running = false
        BridgeStatus.message = "Desconectado"
        super.onDestroy()
    }
    companion object {
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteBridgeService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RemoteBridgeService::class.java))
    }
}

class FileAgent(private val context: Context, private val changes: Boolean, private val trashAllowed: Boolean) {
    private fun volumes() = StorageInfo.volumes(context).map { File(it.path).canonicalFile }
    private fun file(path: String): File {
        val raw = if (path.isBlank()) Environment.getExternalStorageDirectory().absolutePath else path
        if (!raw.startsWith("/") || raw.contains('\u0000')) throw IOException("Ruta absoluta no válida")
        var original = File(raw).toPath()
        while (original != null) {
            if (Files.isSymbolicLink(original)) throw IOException("Enlaces simbólicos no permitidos")
            original = original.parent
        }
        val f = File(raw).canonicalFile
        val allowed = volumes().any { root ->
            f.path == root.path || f.path.startsWith(root.path + File.separator)
        }
        if (!allowed) throw IOException("Ruta fuera del almacenamiento accesible")
        var cursor = f.toPath()
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) throw IOException("Enlaces simbólicos no permitidos")
            cursor = cursor.parent
        }
        // OS storage policy remains authoritative; this does not grant root or Android/data access.
        return f
    }
    private fun requireChanges() {
        if (!changes) throw IOException("Activa operaciones de escritura en Ajustes > Conector")
    }
    fun execute(action: String, args: JSONObject): JSONObject {
        val config = BridgeConfig(context)
        if (action == "device_capabilities") return JSONObject()
            .put("version", com.omaritoinforma.oiarchivos.BuildConfig.VERSION_NAME)
            .put("storage", Perms.hasStorage(context)).put("changes",config.allowChanges)
            .put("trash",config.allowTrash).put("remoteConnections",config.allowRemote)
            .put("permanentDelete",config.allowDelete)
        if (action == "list_connections") return RemoteFileAgent(context,config).connections()
        if (args.has("connectionId")) return RemoteFileAgent(context,config).execute(action,args)
        if (!Perms.hasStorage(context)) throw IOException("Permiso de almacenamiento revocado")
        if (action == "list_roots") {
            val a = JSONArray()
            StorageInfo.volumes(context).forEach {
                a.put(JSONObject().put("name", it.name).put("path", it.path)
                    .put("freeBytes", it.free).put("totalBytes", it.total))
            }
            return JSONObject().put("roots", a)
        }
        val path = args.optString("path", "")
        val f = file(path)
        return when (action) {
            "list_files" -> {
                if (!f.isDirectory || !f.canRead()) throw IOException("Carpeta no accesible")
                val all = f.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: throw IOException("No se pudo listar")
                val array = JSONArray()
                val offset = args.optInt("offset",0)
                if (offset < 0) throw IOException("Desplazamiento no válido")
                all.drop(offset).take(250).forEach { child ->
                    if (!Files.isSymbolicLink(child.toPath())) {
                        array.put(JSONObject().put("name",child.name).put("path",child.absolutePath)
                            .put("directory",child.isDirectory).put("bytes",if(child.isFile)child.length() else 0)
                            .put("modified",child.lastModified()))
                    }
                }
                JSONObject().put("path", f.path).put("items",array).put("total",all.size)
                    .put("nextOffset",if (offset + 250 < all.size) offset + 250 else JSONObject.NULL)
            }
            "file_info" -> {
                if (!f.exists()) throw IOException("No existe")
                JSONObject().put("path",f.path).put("directory",f.isDirectory)
                    .put("bytes",f.length()).put("modified",f.lastModified())
                    .put("readable",f.canRead()).put("writable",f.canWrite())
            }
            "read_text" -> {
                if (!f.isFile || !f.canRead()) throw IOException("Archivo no accesible")
                if (f.length() > 65536) throw IOException("La lectura de texto supera 64 KiB")
                JSONObject().put("path", f.path).put("text", f.readText(Charsets.UTF_8))
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                        .joinToString("") { "%02x".format(it) })
            }
            "read_file_chunk" -> {
                if (!f.isFile || !f.canRead()) throw IOException("No se puede leer el archivo")
                val offset = args.optLong("offset", 0L)
                if (offset < 0 || offset > f.length()) throw IOException("Desplazamiento no válido")
                val size = args.optInt("length", 65536).coerceIn(1, 65536)
                val bytes = ByteArray(minOf(size.toLong(), f.length() - offset).toInt())
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(offset)
                    raf.readFully(bytes)
                }
                JSONObject().put("path", f.path).put("offset", offset)
                    .put("totalBytes", f.length()).put("readBytes", bytes.size)
                    .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            "replace_text" -> {
                requireChanges()
                if (!f.isFile || !f.canWrite() || f.length() > 65536)
                    throw IOException("Archivo de texto no válido o superior a 64 KiB")
                val content = args.optString("content", "").toByteArray(Charsets.UTF_8)
                if (content.size > 65536) throw IOException("Nuevo contenido superior a 64 KiB")
                val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val expected = args.optString("expectedSha256", "")
                if (!digest.equals(expected, ignoreCase = true))
                    throw IOException("El archivo cambió; revisa su SHA-256 antes de reemplazar")
                SafeFiles.writeAtomic(f) { tmp -> tmp.writeBytes(content) }
                JSONObject().put("updated",f.path).put("bytes",content.size)
            }
            "make_directory" -> {
                requireChanges()
                if (f.exists() || !f.mkdir()) throw IOException("La carpeta existe o no se pudo crear")
                JSONObject().put("created",f.path)
            }
            "write_new_text", "write_new_file" -> {
                requireChanges()
                val content = if (action == "write_new_file") Base64.decode(args.getString("base64"),Base64.NO_WRAP)
                    else args.optString("content", "").toByteArray(Charsets.UTF_8)
                if (content.size > 65536) throw IOException("Máximo 64 KiB por operación")
                if (f.parentFile?.isDirectory != true) throw IOException("Directorio de destino no válido")
                Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    .use { it.write(content) }
                JSONObject().put("created",f.path).put("bytes",content.size)
            }
            "rename_file", "move_file", "copy_file" -> {
                requireChanges()
                val dest = file(args.optString("destination"))
                if (volumes().any { it.path == f.path } || !f.exists() || dest.parentFile?.isDirectory != true || dest.exists())
                    throw IOException("Origen o destino no válido, o el destino ya existe")
                if (f.isDirectory && dest.path.startsWith(f.path + File.separator))
                    throw IOException("No puedes mover/copiar una carpeta dentro de sí misma")
                if (action == "copy_file" && f.walkTopDown().maxDepth(128).any { Files.isSymbolicLink(it.toPath()) })
                    throw IOException("No se copian árboles con enlaces simbólicos")
                val success = if (action == "copy_file") f.copyRecursively(dest, overwrite=false)
                    else f.renameTo(dest)
                if (!success) throw IOException("Operación no completada")
                JSONObject().put("source",f.path).put("destination",dest.path).put("operation",action)
            }
            "trash_file" -> {
                requireChanges()
                if (!trashAllowed) throw IOException("Activa mover a papelera en Ajustes > Conector")
                if (!f.exists() || f.path == Environment.getExternalStorageDirectory().canonicalPath ||
                    volumes().any { it.path == f.path } ||
                    f.path.startsWith(RecycleBin.dir.canonicalPath + File.separator))
                    throw IOException("No se permite enviar este destino a la papelera")
                if (!RecycleBin.trash(f)) throw IOException("No se pudo mover a la papelera")
                JSONObject().put("trashed", f.path)
            }
            "delete_file" -> {
                requireChanges()
                if (!config.allowDelete) throw IOException("Activa el borrado definitivo en el móvil")
                if (!f.exists() || volumes().any { it.path == f.path }) throw IOException("No se elimina una raíz")
                // File.delete refuses nonempty folders, so a typo cannot recursively erase a tree.
                if (!f.delete()) throw IOException("No se pudo borrar; las carpetas deben estar vacías")
                JSONObject().put("deleted",f.path)
            }
            else -> throw IOException("Herramienta no reconocida")
        }
    }
}

class BridgeConfig(context: Context) {
    private val sp = context.getSharedPreferences("oi_remote_bridge", Context.MODE_PRIVATE)
    var endpoint: String
        get() = sp.getString("endpoint","").orEmpty()
        set(v) { sp.edit().putString("endpoint",v.trim()).apply() }
    var allowChanges: Boolean
        get() = sp.getBoolean("changes",false)
        set(v) { sp.edit().putBoolean("changes",v).apply() }
    var allowTrash: Boolean
        get() = sp.getBoolean("trash",false)
        set(v) { sp.edit().putBoolean("trash",v).apply() }
    var allowRemote: Boolean
        get() = sp.getBoolean("remote_connections",false)
        set(v) { sp.edit().putBoolean("remote_connections",v).apply() }
    var allowDelete: Boolean
        get() = sp.getBoolean("permanent_delete",false)
        set(v) { sp.edit().putBoolean("permanent_delete",v).apply() }
    var token: String
        get() {
            val raw = sp.getString("encrypted_token","").orEmpty()
            if (raw.isEmpty()) return ""
            return runCatching {
                val data = Base64.decode(raw, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, data,0,12))
                String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8)
            }.getOrDefault("")
        }
        set(v) {
            if (v.isEmpty()) { sp.edit().remove("encrypted_token").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            val data = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
            sp.edit().putString("encrypted_token", Base64.encodeToString(data,Base64.NO_WRAP)).apply()
        }
    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("oi_remote_v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("oi_remote_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    /** Keep legacy Docker tokens; Sites pairing binds both headers to its exact origin. */
    fun credentials(): Pair<String, String?> {
        val raw = token
        if (!raw.startsWith("oi1.")) return raw to null
        val data = JSONObject(String(Base64.decode(raw.removePrefix("oi1."), Base64.NO_WRAP), Charsets.UTF_8))
        val expected = data.getString("origin").toHttpUrl()
        val actual = endpoint.toHttpUrl()
        require(expected.isHttps && actual.isHttps && expected.host == actual.host && expected.port == actual.port &&
            actual.encodedPath == "/" && actual.query == null && actual.fragment == null) { "El token pertenece a otro servidor" }
        val device = data.getString("token")
        val site = data.getString("siteToken")
        require(device.length >= 32 && site.isNotBlank()) { "Emparejamiento no válido" }
        return device to site
    }
    fun valid(): Boolean = runCatching {
        val uri = endpoint.toHttpUrl()
        uri.isHttps && uri.username.isBlank() && uri.password.isBlank() && credentials().first.length >= 32
    }.getOrDefault(false)
}

object BridgeStatus {
    @Volatile var running = false
    @Volatile var message = "Desconectado"
}

/** Redirects must not forward either credential to another origin. */
private fun Request.Builder.bridgeAuthorization(config: BridgeConfig): Request.Builder {
    val (device, site) = config.credentials()
    header("Authorization", "Bearer " + device)
    if (site != null) header("OAI-Sites-Authorization", "Bearer " + site)
    return this
}
