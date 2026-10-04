package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omaritoinforma.oiarchivos.MainActivity
import com.omaritoinforma.oiarchivos.util.Media
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TransferRecord(
    val id: String,
    val title: String,
    val time: Long,
    val status: String,
    val detail: String
)

data class OperationResult(val message: String?, val changed: List<File> = emptyList())

/** Las operaciones pertenecen al servicio, no a una pantalla de Compose ni a la Activity. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var interruptedDetail = ""
    private var userCanceled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            userCanceled = true
            paused.value = false
            job?.cancel()
            return START_NOT_STICKY
        }
        if (intent?.action == "pause" || intent?.action == "resume") {
            if (supportsPause.value) paused.value = intent.action == "pause"
            progress.value?.let {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                    11, notification(this, it))
            }
            return START_NOT_STICKY
        }
        createChannel(this)
        startForeground(11, notification(this, OpProgress(pendingTitle)))
        if (job?.isActive == true) return START_NOT_STICKY
        val work = pendingWork
        pendingWork = null
        userCanceled = false
        if (work == null) {
            finish()
            return START_NOT_STICKY
        }
        val id = UUID.randomUUID().toString()
        val title = pendingTitle
        interruptedDetail = id
        record(this, TransferRecord(id, title, System.currentTimeMillis(), "En curso", ""))
        job =
            scope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val context = currentCoroutineContext()
                            var lastNotification = 0L
                            work { value ->
                                context.ensureActive()
                                while (supportsPause.value && paused.value) {
                                    Thread.sleep(100)
                                    context.ensureActive()
                                }
                                progress.value = value
                                val now = System.currentTimeMillis()
                                if (now - lastNotification >= 500) {
                                    lastNotification = now
                                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                                        .notify(11, notification(this@TransferService, value))
                                }
                            }
                        }
                    if (result.changed.isNotEmpty())
                        withContext(Dispatchers.IO) {
                            Media.scan(this@TransferService, result.changed)
                        }
                    val detail = result.message ?: "Completado"
                    record(
                        this@TransferService,
                        TransferRecord(id, title, System.currentTimeMillis(), "Completado", detail))
                    notifyDone(title, detail, failed = false)
                    completion.value = System.nanoTime() to detail
                } catch (e: CancellationException) {
                    if (userCanceled)
                        withContext(NonCancellable + Dispatchers.IO) { activeDurable?.discard() }
                    record(
                        this@TransferService,
                        TransferRecord(
                            id,
                            title,
                            System.currentTimeMillis(),
                            if (userCanceled) "Cancelado" else "Interrumpido",
                            "Los originales pendientes se conservan"))
                    completion.value = System.nanoTime() to "Operación cancelada"
                } catch (e: Exception) {
                    val detail = e.message ?: e.javaClass.simpleName
                    record(
                        this@TransferService,
                        TransferRecord(id, title, System.currentTimeMillis(), "Error", detail))
                    notifyDone(title, detail, failed = true)
                    completion.value = System.nanoTime() to "Error: $detail"
                } finally {
                    finish()
                }
            }
        return START_NOT_STICKY
    }

    /**
     * Aviso al terminar, como en ES: queda en la barra de notificaciones hasta tocarlo, salvo que
     * esté activado «Cerrar la notificación al terminar».
     */
    private fun notifyDone(title: String, detail: String, failed: Boolean) {
        if (Prefs(this).closeNotificationWhenDone) return
        val open =
            PendingIntent.getActivity(
                this,
                4,
                Intent(this, MainActivity::class.java)
                    .putExtra("screen", "transfers")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat.Builder(this, "transfers")
                .setSmallIcon(
                    if (failed) android.R.drawable.stat_notify_error
                    else android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (failed) "$title: error" else "$title: terminado")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_ID, notification)
        }
    }

    private fun finish() {
        interruptedDetail = ""
        activeDurable = null
        progress.value = null
        paused.value = false
        supportsPause.value = false
        busy = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Identificador del aviso de tarea terminada (el de progreso es el 11). */
        const val DONE_ID = 12
        val progress = MutableStateFlow<OpProgress?>(null)
        val completion = MutableStateFlow<Pair<Long, String>?>(null)
        val paused = MutableStateFlow(false)
        val supportsPause = MutableStateFlow(false)
        @Volatile private var activeDurable: DurableJob? = null
        private var pendingWork: (suspend ((OpProgress) -> Unit) -> OperationResult)? = null
        private var pendingTitle = "Operación"
        @Volatile private var busy = false

        /** Verdadero mientras hay una operación en curso. */
        val isBusy: Boolean
            get() = busy

        fun submit(
            ctx: Context,
            title: String,
            work: suspend ((OpProgress) -> Unit) -> OperationResult
        ): Boolean = start(ctx, title, false, work)

        @Synchronized
        private fun start(
            ctx: Context,
            title: String,
            pausable: Boolean,
            work: suspend ((OpProgress) -> Unit) -> OperationResult
        ): Boolean {
            if (busy) return false
            busy = true
            supportsPause.value = pausable
            pendingWork = work
            pendingTitle = title
            progress.value = OpProgress(title)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
            } catch (e: Exception) {
                busy = false
                pendingWork = null
                progress.value = null
                throw e
            }
            return true
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, TransferService::class.java).setAction("cancel"))
        }

        fun pause(ctx: Context, pause: Boolean) {
            ctx.startService(
                Intent(ctx, TransferService::class.java)
                    .setAction(if (pause) "pause" else "resume"))
        }

        fun jobsDirectory(ctx: Context) = File(ctx.filesDir, "transfer-jobs")

        fun submitDurable(ctx: Context, job: DurableJob): Boolean =
            submitDurable(ctx, job.title) { job }

        /**
         * [plan] se ejecuta en el servicio en un hilo de E/S, para poder listar carpetas remotas. En
         * cuanto existe el registro se puede pausar, y cancelar descarta sus archivos parciales.
         */
        fun submitDurable(ctx: Context, title: String, plan: suspend () -> DurableJob): Boolean =
            start(ctx, title, true) { report ->
                val job = plan()
                activeDurable = job
                job.run(report)
            }

        /** Copias locales y transferencias de red/nube pendientes por una pausa, un error o un cierre. */
        fun pendingJobs(ctx: Context): List<DurableJob> =
            DurableCopy.pending(jobsDirectory(ctx)) +
                DurableRemote.pending(jobsDirectory(ctx), RemoteFiles::connectById)

        fun createChannel(ctx: Context) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        "transfers",
                        "Transferencias de archivos",
                        NotificationManager.IMPORTANCE_LOW))
        }

        private fun notification(ctx: Context, p: OpProgress): android.app.Notification {
            val open =
                PendingIntent.getActivity(
                    ctx,
                    1,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel =
                PendingIntent.getService(
                    ctx,
                    2,
                    Intent(ctx, TransferService::class.java).setAction("cancel"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pause =
                PendingIntent.getService(
                    ctx,
                    3,
                    Intent(ctx, TransferService::class.java)
                        .setAction(if (paused.value) "resume" else "pause"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, "transfers")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(p.title)
                .setContentText(p.current)
                .setContentIntent(open)
                .setOngoing(true)
                .setProgress(
                    100,
                    if (p.totalBytes > 0)
                        (p.doneBytes * 100 / p.totalBytes).toInt().coerceIn(0, 100)
                    else 0,
                    p.totalBytes <= 0)
                .addAction(0, "Cancelar", cancel)
                .apply {
                    if (supportsPause.value)
                        addAction(0, if (paused.value) "Reanudar" else "Pausar", pause)
                }
                .build()
        }

        @Synchronized
        private fun record(ctx: Context, item: TransferRecord) {
            val list = history(ctx).filter { it.id != item.id }.toMutableList()
            list.add(0, item)
            val arr = JSONArray()
            list.take(100).forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("time", it.time)
                        .put("status", it.status)
                        .put("detail", it.detail))
            }
            ctx.getSharedPreferences("transfer_history", Context.MODE_PRIVATE)
                .edit()
                .putString("records", arr.toString())
                .commit()
        }

        fun history(ctx: Context): List<TransferRecord> =
            runCatching {
                    val arr =
                        JSONArray(
                            ctx.getSharedPreferences("transfer_history", Context.MODE_PRIVATE)
                                .getString("records", "[]"))
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        val state = o.getString("status")
                        TransferRecord(
                            o.getString("id"),
                            o.getString("title"),
                            o.getLong("time"),
                            if (state == "En curso" && !busy) "Interrumpido" else state,
                            o.optString("detail"))
                    }
                }
                .getOrDefault(emptyList())
    }
}
