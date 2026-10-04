package com.omaritoinforma.oiarchivos.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Informe diario de archivos nuevos («Informe diario» de ES): una notificación al día con cuántos
 * archivos aparecieron y de qué tipo, sin nombrar ninguno. A diferencia del aviso en el momento
 * (el «Registrador»), este resume el día.
 */
object NewFilesReport {
    data class Entry(val path: String, val size: Long)

    data class Report(val total: Int, val bytes: Long, val perKind: Map<NewFileKind, Int>, val others: Int)

    private const val PERIODIC = "informe-diario"
    private const val NOW = "informe-ahora"
    const val NOTIFICATION_ID = 33

    /** Cuenta los archivos por tipo; ignora ocultos y datos de otras apps. */
    fun build(
        files: List<Entry>,
        kindOf: (String) -> FileKind = { Kinds.ofExt(it.substringAfterLast('.', "").lowercase()) }
    ): Report {
        val visible =
            files.filter {
                it.path.startsWith("/") &&
                    "/." !in it.path &&
                    "/Android/data/" !in it.path &&
                    "/Android/obb/" !in it.path
            }.distinctBy { it.path }
        val perKind = LinkedHashMap<NewFileKind, Int>()
        var others = 0
        for (f in visible) {
            val kind = kindOf(File(f.path).name)
            val group = NewFileKind.entries.firstOrNull { kind in it.kinds }
            if (group == null) others++ else perKind[group] = (perKind[group] ?: 0) + 1
        }
        return Report(visible.size, visible.sumOf { it.size }, perKind, others)
    }

    /** Texto de la notificación; null si no hay nada nuevo. */
    fun text(report: Report): String? {
        if (report.total == 0) return null
        val parts =
            report.perKind.map { (kind, n) -> "$n ${kind.label.lowercase()}" } +
                (if (report.others > 0) listOf("${report.others} otros") else emptyList())
        val head = if (report.total == 1) tr("1 archivo nuevo") else tr("{0} archivos nuevos", report.total)
        return "$head (${formatSize(report.bytes)}): ${parts.joinToString(", ")}"
    }

    /** Programa o cancela el informe de cada día según el ajuste. */
    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val work = WorkManager.getInstance(ctx)
        if (prefs.dailyReport) {
            if (prefs.reportSince == 0L) prefs.reportSince = System.currentTimeMillis() / 1000
            work.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NewFilesReportWorker>(24, TimeUnit.HOURS).build())
        } else work.cancelUniqueWork(PERIODIC)
    }

    /** «Ver el informe ahora»: el informe de lo que apareció desde el último. */
    fun now(ctx: Context) {
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NewFilesReportWorker>().build())
    }

    internal fun run(ctx: Context) {
        val prefs = Prefs(ctx)
        val since = prefs.reportSince.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 - 24 * 3600)
        val rows = runCatching { StorageWatch.queryAddedSince(ctx, since, limit = 20_000) }.getOrDefault(emptyList())
        val report = build(rows.map { Entry(it.first, File(it.first).length()) })
        // La próxima vez cuenta desde ahora, haya o no archivos nuevos.
        prefs.reportSince = System.currentTimeMillis() / 1000
        val text = text(report) ?: return
        StorageWatch.notifyReport(ctx, text)
    }
}

class NewFilesReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        NewFilesReport.run(applicationContext)
        return Result.success()
    }
}
