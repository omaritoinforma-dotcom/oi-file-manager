package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.PathUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Qué copia la copia automática («Copia de seguridad automática» de ES). */
enum class BackupKind(private val labelEs: String, val kinds: Set<FileKind>, val folders: List<String>) {
    PHOTOS(trKey("Fotos"), setOf(FileKind.IMAGE), listOf("DCIM", "Pictures")),
    VIDEOS(trKey("Vídeos"), setOf(FileKind.VIDEO), listOf("DCIM", "Movies", "Pictures")),
    MUSIC(trKey("Música"), setOf(FileKind.AUDIO), listOf("Music"));

    val label: String
        get() = tr(labelEs)
}

/**
 * Copia automática a una conexión guardada (SFTP, FTP, WebDAV, SMB o nube), como la de ES: sube
 * lo nuevo o cambiado de las carpetas elegidas, conservando su ruta («DCIM/Camera/foto.jpg»).
 * Un índice local recuerda lo ya copiado; si la copia se corta, la siguiente sigue donde iba.
 * La lanza Android cuando cambia MediaStore (una foto nueva) y, además, cada 6 horas.
 */
object AutoBackup {
    data class LocalFile(val path: String, val size: Long, val modified: Long)

    /** [remote]: ruta relativa a la carpeta de destino, separada por «/». */
    data class Upload(val file: LocalFile, val remote: String)

    data class Outcome(val uploaded: Int, val error: String?)

    private const val PERIODIC = "copia-automatica"
    private const val CHANGES = "copia-cambios"
    private const val CHANNEL = "copia"
    private const val NOTIFICATION_ID = 32
    private const val INDEX_FILE = "copia-automatica.json"
    private val running = AtomicBoolean(false)

    /** Ruta en el destino: la del archivo dentro del almacenamiento ("DCIM/Camera/x.jpg"). */
    fun remoteRelative(path: String, internalRoot: String): String {
        val root = internalRoot.trimEnd('/')
        return if (path.startsWith("$root/")) path.removePrefix("$root/")
        // Otra unidad (tarjeta SD, USB): /storage/1234-ABCD/Música/x → 1234-ABCD/Música/x
        else path.removePrefix("/storage/").trimStart('/')
    }

    fun signature(file: LocalFile) = "${file.size}:${file.modified}"

    /** Lo que falta por copiar: archivos nuevos o cambiados desde la última copia, sin ocultos. */
    fun plan(files: List<LocalFile>, index: Map<String, String>, internalRoot: String): List<Upload> =
        files
            .asSequence()
            .filter { "/." !in it.path }
            .distinctBy { it.path }
            .map { Upload(it, remoteRelative(it.path, internalRoot)) }
            .filter { index[it.remote] != signature(it.file) }
            .sortedBy { it.file.modified }
            .toList()

    /** Archivos de los tipos y carpetas elegidos; nunca entra en carpetas ocultas. */
    fun collect(
        kinds: Set<BackupKind>,
        folders: List<String>,
        internalRoot: String,
        kindOf: (String) -> FileKind = { Kinds.ofExt(it.substringAfterLast('.', "").lowercase()) }
    ): List<LocalFile> {
        val out = LinkedHashMap<String, LocalFile>()
        fun walk(dir: File, accept: (File) -> Boolean) {
            if (!dir.isDirectory) return
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && accept(it) }
                .forEach { out.putIfAbsent(it.path, LocalFile(it.path, it.length(), it.lastModified())) }
        }
        kinds.forEach { kind ->
            kind.folders.forEach { name -> walk(File(internalRoot, name)) { kindOf(it.name) in kind.kinds } }
        }
        folders.forEach { walk(File(it)) { true } }
        return out.values.toList()
    }

    /** Carpeta de destino limpia: segmentos sin «.», «..» ni barras invertidas. */
    fun checkFolder(folder: String): List<String> {
        val parts = folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IOException(tr("Escribe la carpeta de destino"))
        if (parts.any { it == "." || it == ".." || '\\' in it })
            throw IOException(tr("La carpeta de destino no puede tener «.», «..» ni «\\»"))
        return parts
    }

    // ---- Índice de lo ya copiado ----

    private fun loadIndex(ctx: Context, destination: String): MutableMap<String, String> {
        val file = File(ctx.filesDir, INDEX_FILE)
        val out = HashMap<String, String>()
        runCatching {
            val json = JSONObject(file.readText())
            // Si cambia el destino, se empieza de cero: allí no hay nada.
            if (json.optString("destino") == destination) {
                val files = json.getJSONObject("archivos")
                files.keys().forEach { out[it] = files.getString(it) }
            }
        }
        return out
    }

    private fun saveIndex(ctx: Context, destination: String, index: Map<String, String>) {
        val json = JSONObject().put("destino", destination).put("archivos", JSONObject(index))
        val target = File(ctx.filesDir, INDEX_FILE)
        val tmp = File(ctx.filesDir, "$INDEX_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) throw IOException(tr("No se pudo guardar el índice de la copia"))
    }

    // ---- Copia ----

    /** Hace la copia ahora. Nunca hay dos a la vez. */
    suspend fun run(ctx: Context, report: (OpProgress) -> Unit): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome(0, tr("ya hay una copia en marcha"))
        var uploaded = 0
        try {
            val prefs = Prefs(ctx)
            val connection =
                ConnectionStore(ctx).load().firstOrNull { it.id == prefs.autoBackupConnection }
                    ?: throw IOException(tr("Elige el destino de la copia"))
            val parts = checkFolder(prefs.autoBackupFolder)
            val destination = connection.id + "|" + parts.joinToString("/")
            val index = loadIndex(ctx, destination)
            val uploads =
                withContext(Dispatchers.IO) {
                    plan(
                        collect(prefs.autoBackupKinds, prefs.autoBackupFolders, PathUtil.internalRoot),
                        index,
                        PathUtil.internalRoot)
                }
            if (uploads.isNotEmpty())
                withContext(Dispatchers.IO) {
                    RemoteFiles.connect(connection).use { fs ->
                        val names = HashMap<String, MutableSet<String>>()
                        fun children(dir: String) =
                            names.getOrPut(dir) { fs.list(dir).map { it.name }.toMutableSet() }
                        fun ensure(base: String, segments: List<String>): String {
                            var dir = base
                            for (segment in segments) {
                                val path = RemoteFiles.join(dir, segment)
                                dir = if (segment in children(dir)) path
                                else fs.mkdir(dir, segment).also { children(dir).add(segment) }
                            }
                            return dir
                        }
                        val base = ensure(connection.root.ifBlank { "/" }, parts)
                        // El índice se guarda cada 20 archivos y al terminar o cortarse la copia.
                        var unsaved = 0
                        try {
                            for ((i, up) in uploads.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                val local = File(up.file.path)
                                if (!local.isFile) continue
                                report(OpProgress(tr("Copia automática"), local.name, doneFiles = i, totalFiles = uploads.size))
                                val dir = ensure(base, up.remote.split('/').dropLast(1))
                                val name = up.remote.substringAfterLast('/')
                                // Una versión anterior del mismo archivo se sustituye.
                                if (name in children(dir))
                                    fs.list(dir).firstOrNull { it.name == name && !it.directory }?.let(fs::delete)
                                local.inputStream().use { fs.write(dir, name, it, local.length()) }
                                children(dir).add(name)
                                index[up.remote] = signature(up.file)
                                uploaded++
                                if (++unsaved >= 20) {
                                    saveIndex(ctx, destination, index)
                                    unsaved = 0
                                }
                            }
                        } finally {
                            if (unsaved > 0) saveIndex(ctx, destination, index)
                        }
                    }
                }
            record(ctx, Outcome(uploaded, null), connection.label)
            return Outcome(uploaded, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Android detuvo el trabajo: lo copiado queda en el índice y la próxima vez sigue.
            throw e
        } catch (e: Exception) {
            val outcome = Outcome(uploaded, e.message ?: "error")
            record(ctx, outcome, null)
            return outcome
        } finally {
            running.set(false)
        }
    }

    private fun record(ctx: Context, outcome: Outcome, destination: String?) {
        val time = java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        Prefs(ctx).autoBackupLast =
            when {
                outcome.error != null ->
                    tr("{0}: error ({1})", time, outcome.error) +
                        (if (outcome.uploaded > 0) tr("; {0} copiado(s) antes", outcome.uploaded) else "")
                outcome.uploaded == 0 -> tr("{0}: todo estaba copiado", time)
                else -> tr("{0}: {1} archivo(s) copiado(s) en «{2}»", time, outcome.uploaded, destination)
            }
    }

    // ---- Programación ----

    /** Programa o cancela la copia según los ajustes. [replace]: aplica ajustes recién cambiados. */
    fun schedule(ctx: Context, replace: Boolean = false) {
        val prefs = Prefs(ctx)
        val work = WorkManager.getInstance(ctx)
        if (!prefs.autoBackup || prefs.autoBackupConnection.isBlank()) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(CHANGES)
            return
        }
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(network(prefs))
                .build())
        watchChanges(ctx, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    private fun network(prefs: Prefs): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (prefs.autoBackupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    /** Trabajo que Android lanza cuando cambia MediaStore; tras cada ejecución se vuelve a poner. */
    internal fun watchChanges(ctx: Context, policy: ExistingWorkPolicy) {
        val prefs = Prefs(ctx)
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (prefs.autoBackupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(
                CHANGES,
                policy,
                OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraints).addTag(CHANGES).build())
    }

    fun notifyResult(ctx: Context, outcome: Outcome) {
        if (outcome.uploaded == 0 && outcome.error == null) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Copia automática"), NotificationManager.IMPORTANCE_LOW))
        val text = Prefs(ctx).autoBackupLast
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(
                        if (outcome.error != null) android.R.drawable.stat_notify_error
                        else android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(if (outcome.error != null) tr("Copia automática: error") else tr("Copia automática"))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build())
        }
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs(ctx).autoBackup) return Result.success()
        val before = Prefs(ctx).autoBackupLast.substringAfter(": ", "")
        val outcome = AutoBackup.run(ctx) {}
        val repeated = outcome.error != null && Prefs(ctx).autoBackupLast.substringAfter(": ", "") == before
        // Si la copia manual está en marcha, ella ya sube lo nuevo; un error repetido no se vuelve
        // a avisar (por ejemplo, el servidor sigue sin responder).
        if (outcome.error != "ya hay una copia en marcha" && !repeated) AutoBackup.notifyResult(ctx, outcome)
        if ("copia-cambios" in tags) AutoBackup.watchChanges(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
