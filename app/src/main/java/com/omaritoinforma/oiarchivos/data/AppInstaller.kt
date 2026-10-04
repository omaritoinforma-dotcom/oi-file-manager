package com.omaritoinforma.oiarchivos.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cola de una tanda de instalaciones o desinstalaciones: orden, recuento y resumen. No usa Android
 * para poder probarla. Cada tarea tiene una clave propia; un aviso con otra clave (repetido, tardío
 * o de una tanda anterior) se ignora.
 */
class BatchQueue<T>(private val id: Long, jobs: List<T>, private val label: (T) -> String) {
    private val pending = ArrayDeque(jobs)
    val total = jobs.size
    var current: T? = null
        private set
    var key = ""
        private set
    private var index = 0
    private var ok = 0
    private val failures = mutableListOf<String>()

    val done: Int
        get() = ok + failures.size

    /** Pasa a la siguiente tarea; null cuando no quedan. */
    fun next(): T? {
        current = pending.removeFirstOrNull()
        key = if (current != null) "$id-${index++}" else ""
        return current
    }

    /** Anota el resultado de la tarea actual. Devuelve false si [forKey] no es el de la actual. */
    fun result(forKey: String, success: Boolean, reason: String? = null): Boolean {
        val job = current
        if (job == null || forKey != key) return false
        if (success) ok++ else failures += "${label(job)}: ${reason ?: "error"}"
        current = null
        return true
    }

    fun summary(verb: String): String = buildString {
        append("$verb $ok de $total")
        if (failures.isNotEmpty()) append(". ").append(failures.joinToString("; "))
    }
}

/**
 * Instalar y desinstalar varias apps seguidas, como el gestor de apps de ES. Sin root, Android pide
 * confirmar cada una, así que se hacen de una en una con PackageInstaller. Cuando el sistema pide
 * confirmación, la pantalla de la app la abre (ver MainActivity): así nunca se abre una ventana
 * desde segundo plano.
 */
object AppInstaller {
    sealed interface Job {
        val label: String
    }

    data class Install(val apk: File) : Job {
        override val label: String
            get() = apk.name
    }

    data class Uninstall(val packageName: String, override val label: String) : Job

    data class Progress(val installing: Boolean, val done: Int, val total: Int, val current: String)

    private const val ACTION = "com.omaritoinforma.oiarchivos.RESULTADO_INSTALACION"
    private const val EXTRA_KEY = "clave"

    /** Qué se está haciendo, para mostrarlo en pantalla; null si no hay ninguna tanda. */
    val progress = MutableStateFlow<Progress?>(null)

    /** Ventana de confirmación del sistema que la app tiene que abrir. */
    val confirm = MutableStateFlow<Intent?>(null)

    /** Resumen de cada tanda terminada. */
    val finished = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Carpetas que dejó una app desinstalada, para proponer borrarlas. */
    data class Leftovers(val label: String, val folders: List<File>)

    val leftovers = MutableStateFlow<List<Leftovers>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var queue: BatchQueue<Job>? = null
    private var installing = false

    val busy: Boolean
        get() = queue != null

    /** Empieza una tanda; false si ya hay otra en marcha o no hay nada que hacer. */
    fun start(ctx: Context, jobs: List<Job>): Boolean {
        if (busy || jobs.isEmpty()) return false
        installing = jobs.first() is Install
        queue = BatchQueue(System.nanoTime(), jobs) { it.label }
        next(ctx.applicationContext)
        return true
    }

    private fun next(ctx: Context) {
        val q = queue ?: return
        val job = q.next()
        if (job == null) {
            queue = null
            progress.value = null
            confirm.value = null
            finished.tryEmit(q.summary(if (installing) tr("Instaladas") else tr("Desinstaladas")))
            return
        }
        progress.value = Progress(installing, q.done, q.total, job.label)
        val key = q.key
        scope.launch {
            runCatching {
                    when (job) {
                        is Install -> install(ctx, job.apk, key)
                        is Uninstall ->
                            ctx.packageManager.packageInstaller.uninstall(
                                job.packageName, statusSender(ctx, key))
                    }
                }
                .onFailure { finish(ctx, key, false, it.message ?: "error") }
        }
    }

    private suspend fun install(ctx: Context, apk: File, key: String) =
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val info =
                ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
                    ?: throw IOException(tr("no es un APK válido"))
            val installer = ctx.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(info.packageName)
            params.setSize(apk.length())
            val id = installer.createSession(params)
            val session = installer.openSession(id)
            try {
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(statusSender(ctx, key))
                session.close()
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }

    private fun statusSender(ctx: Context, key: String): IntentSender {
        val intent =
            Intent(ctx, AppInstallReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KEY, key)
        // Mutable: el sistema añade el resultado al aviso.
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), intent, flags).intentSender
    }

    /** Aviso del sistema sobre la tarea actual: pide confirmación o trae el resultado. */
    internal fun onStatus(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (queue?.key != key) return
                val ask = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (ask == null) finish(ctx, key, false, tr("Android no pidió confirmación"))
                else confirm.value = ask
            }
            PackageInstaller.STATUS_SUCCESS -> finish(ctx, key, true, null)
            else -> finish(ctx, key, false, describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    /** La ventana de confirmación no se pudo abrir. */
    fun confirmFailed(ctx: Context, reason: String) {
        val q = queue ?: return
        finish(ctx.applicationContext, q.key, false, reason)
    }

    private fun finish(ctx: Context, key: String, success: Boolean, reason: String?) {
        val q = queue ?: return
        val job = q.current
        if (!q.result(key, success, reason)) return
        if (success && job is Uninstall && Prefs(ctx).cleanAssociatedFolders)
            scope.launch {
                val found =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                AssociatedFolders.find(
                                    android.os.Environment.getExternalStorageDirectory(), job.label, job.packageName)
                            }
                            .getOrDefault(emptyList())
                    }
                if (found.isNotEmpty()) leftovers.value = leftovers.value + Leftovers(job.label, found)
            }
        if (success && job is Install && Prefs(ctx).appPermissionNotify)
            runCatching {
                InstallNotice.inspect(ctx, job.apk)?.let { (label, groups) ->
                    InstallNotice.text(label, groups)?.let {
                        StorageWatch.notifyInstalledPermissions(ctx, job.apk.name, it)
                    }
                }
            }
        confirm.value = null
        next(ctx)
    }

    fun describe(status: Int, message: String?): String =
        when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelado"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> tr("bloqueado por el sistema")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> tr("choca con una app ya instalada")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> tr("no es compatible con este teléfono")
            PackageInstaller.STATUS_FAILURE_INVALID -> tr("APK no válido")
            PackageInstaller.STATUS_FAILURE_STORAGE -> tr("no hay espacio suficiente")
            else -> message?.takeIf { it.isNotBlank() } ?: "error"
        }
}

/** Recibe del sistema el resultado de cada instalación o desinstalación. */
class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppInstaller.onStatus(context.applicationContext, intent)
    }
}
