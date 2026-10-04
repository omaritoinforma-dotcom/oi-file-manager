package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omaritoinforma.oiarchivos.MainActivity
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import java.util.concurrent.TimeUnit

/** Tipos de archivo que avisa el «Registrador» de archivos nuevos (como «Formato del nuevo archivo» de ES). */
enum class NewFileKind(val label: String, val kinds: Set<FileKind>) {
    IMAGES("Imágenes", setOf(FileKind.IMAGE)),
    VIDEOS("Vídeos", setOf(FileKind.VIDEO)),
    AUDIO("Música", setOf(FileKind.AUDIO)),
    DOCUMENTS("Documentos", setOf(FileKind.DOC, FileKind.PDF, FileKind.TEXT)),
    APK("APK", setOf(FileKind.APK)),
    ARCHIVES("Comprimidos", setOf(FileKind.ARCHIVE))
}

/**
 * Avisos de almacenamiento de ES: «Mostrar advertencia de espacio bajo» y la notificación del
 * «Registrador» cuando aparecen archivos nuevos. Android no deja vigilar todo el almacenamiento
 * con la app cerrada, así que se usa WorkManager: un trabajo que Android lanza cuando cambia
 * MediaStore (archivos nuevos) y otro cada hora (espacio).
 */
object StorageWatch {
    private const val NEW_FILES = "registrador"
    private const val SPACE = "espacio"
    const val CHANNEL = "avisos"
    const val LOW_SPACE_ID = 30
    const val NEW_FILES_ID = 31
    val thresholdsMb = listOf(500, 1024, 2048, 5120, 10240)

    data class SpaceCheck(val notify: Boolean, val warned: Boolean)

    /**
     * Avisa una sola vez cuando el espacio libre baja del umbral, y vuelve a poder avisar cuando
     * se recupera un 10 % por encima: así no se repite el aviso cada hora.
     */
    fun checkSpace(freeBytes: Long, thresholdMb: Int, warned: Boolean): SpaceCheck {
        val threshold = thresholdMb * 1024L * 1024L
        return when {
            freeBytes < threshold -> SpaceCheck(notify = !warned, warned = true)
            freeBytes >= threshold + threshold / 10 -> SpaceCheck(notify = false, warned = false)
            else -> SpaceCheck(notify = false, warned = warned)
        }
    }

    /** Archivos nuevos que se avisan: del tipo elegido, no ocultos ni de datos de apps; el más reciente primero. */
    fun pickNew(
        rows: List<Pair<String, Long>>,
        kinds: Set<NewFileKind>,
        kindOf: (String) -> FileKind = { Kinds.ofExt(it.substringAfterLast('.', "").lowercase()) }
    ): List<String> {
        val wanted = kinds.flatMap { it.kinds }.toSet()
        return rows
            .filter { (path, _) ->
                path.startsWith("/") &&
                    "/." !in path &&
                    "/Android/data/" !in path &&
                    "/Android/obb/" !in path &&
                    kindOf(File(path).name) in wanted
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
    }

    /** Programa o cancela los trabajos según los ajustes. Se llama al abrir la app y al cambiarlos. */
    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val work = WorkManager.getInstance(ctx)
        if (prefs.newFilesNotify) {
            if (prefs.newFilesSince == 0L) prefs.newFilesSince = System.currentTimeMillis() / 1000
            watchNewFiles(ctx, ExistingWorkPolicy.KEEP)
        } else {
            work.cancelUniqueWork(NEW_FILES)
            prefs.newFilesSince = 0L
        }
        if (prefs.lowSpaceWarning)
            work.enqueueUniquePeriodicWork(
                SPACE,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StorageWatchWorker>(1, TimeUnit.HOURS).build())
        else work.cancelUniqueWork(SPACE)
    }

    /** Trabajo que Android lanza cuando cambia MediaStore; tras cada ejecución se vuelve a poner. */
    internal fun watchNewFiles(ctx: Context, policy: ExistingWorkPolicy) {
        val constraints =
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(
                NEW_FILES,
                policy,
                OneTimeWorkRequestBuilder<StorageWatchWorker>()
                    .setConstraints(constraints)
                    .addTag(NEW_FILES)
                    .build())
    }

    fun freeBytes(): Long = runCatching { StatFs(PathUtil.internalRoot).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun totalBytes(): Long = runCatching { StatFs(PathUtil.internalRoot).totalBytes }.getOrDefault(0L)

    /** Filas (ruta, fecha en segundos) añadidas a MediaStore después de [since]. */
    @Suppress("DEPRECATION")
    fun queryAddedSince(ctx: Context, since: Long): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED)
        val selection = buildString {
            append("${MediaStore.MediaColumns.DATE_ADDED} > ?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        ctx.contentResolver
            .query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                arrayOf(since.toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            ?.use { c ->
                while (c.moveToNext() && out.size < 500) {
                    val path = c.getString(0) ?: continue
                    out += path to c.getLong(1)
                }
            }
        return out
    }

    private fun channel(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Avisos de almacenamiento", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context, request: Int, extra: Pair<String, String>): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            request,
            Intent(ctx, MainActivity::class.java)
                .putExtra(extra.first, extra.second)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notify(ctx: Context, id: Int, notification: android.app.Notification) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
        }
    }

    fun notifyLowSpace(ctx: Context, free: Long, total: Long) {
        channel(ctx)
        val text = "Quedan ${formatSize(free)} libres de ${formatSize(total)}. Toca para limpiar basura."
        notify(
            ctx,
            LOW_SPACE_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Espacio insuficiente")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open(ctx, 30, "screen" to "cleaner"))
                .setAutoCancel(true)
                .build())
    }

    fun notifyNewFiles(ctx: Context, paths: List<String>) {
        channel(ctx)
        val title = if (paths.size == 1) "1 archivo nuevo" else "${paths.size} archivos nuevos"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        paths.take(5).forEach { style.addLine(File(it).name) }
        if (paths.size > 5) style.setSummaryText("y ${paths.size - 5} más")
        notify(
            ctx,
            NEW_FILES_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(paths.joinToString(", ") { File(it).name })
                .setStyle(style)
                // Al tocarlo se abre la carpeta del más reciente.
                .setContentIntent(open(ctx, 31, "folder" to File(paths.first()).parent.orEmpty()))
                .setAutoCancel(true)
                .build())
    }
}

class StorageWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.lowSpaceWarning) {
            val free = StorageWatch.freeBytes()
            val check = StorageWatch.checkSpace(free, prefs.lowSpaceMb, prefs.lowSpaceWarned)
            prefs.lowSpaceWarned = check.warned
            if (check.notify) StorageWatch.notifyLowSpace(ctx, free, StorageWatch.totalBytes())
        }
        if (prefs.newFilesNotify) {
            val since = prefs.newFilesSince
            val rows = runCatching { StorageWatch.queryAddedSince(ctx, since) }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) prefs.newFilesSince = maxOf(since, rows.maxOf { it.second })
            val fresh = StorageWatch.pickNew(rows, prefs.newFilesKinds)
            if (fresh.isNotEmpty()) StorageWatch.notifyNewFiles(ctx, fresh)
            // El disparador por cambios en MediaStore sirve una sola vez: se vuelve a poner detrás.
            if ("registrador" in tags)
                StorageWatch.watchNewFiles(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}
