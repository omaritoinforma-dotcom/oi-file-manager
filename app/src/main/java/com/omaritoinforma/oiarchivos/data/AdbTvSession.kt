package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Conexión ADB con una Android TV; sigue abierta aunque se cambie de pantalla. */
object AdbTvSession {
    data class State(
        val address: String,
        val device: Adb.Device? = null,
        val apps: List<String> = emptyList(),
        /** Qué se está haciendo ahora («Conectando…», «Instalando…»), o null. */
        val busy: String? = null,
        val waitingApproval: Boolean = false,
        val progress: Float? = null,
        val message: String? = null,
        val error: String? = null
    )

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var client: Adb.Client? = null

    private fun update(change: (State) -> State) {
        state.value = state.value?.let(change)
    }

    fun connect(context: Context, address: String) {
        val app = context.applicationContext
        disconnect()
        state.value = State(address.trim(), busy = tr("Conectando…"))
        scope.launch {
            lock.withLock {
                try {
                    val (host, port) = Adb.parseAddress(address)
                    val keys = Adb.loadOrCreateKeys(File(app.filesDir, "adb/adbkey"))
                    val c =
                        Adb.Client.connect(host, port, keys, "oiarchivos@" + Build.MODEL.replace(' ', '_')) {
                            update { it.copy(waitingApproval = true) }
                        }
                    client = c
                    val prefs = Prefs(app)
                    prefs.adbTvAddresses = listOf(address.trim()) + prefs.adbTvAddresses.filter { it != address.trim() }
                    val apps = runCatching { c.packages() }.getOrDefault(emptyList())
                    update { it.copy(device = c.device, apps = apps, busy = null, waitingApproval = false) }
                } catch (e: Exception) {
                    client = null
                    update { it.copy(busy = null, waitingApproval = false, error = e.message ?: tr("No se pudo conectar")) }
                }
            }
        }
    }

    /** Ejecuta [action] con la conexión abierta mostrando [what] mientras dura. */
    private fun run(what: String, done: String? = null, action: (Adb.Client) -> Unit) {
        val current = state.value ?: return
        if (current.device == null) return
        update { it.copy(busy = what, progress = null, message = null, error = null) }
        scope.launch {
            lock.withLock {
                try {
                    val c = client ?: throw java.io.IOException(tr("Sin conexión con la TV"))
                    action(c)
                    val apps = runCatching { c.packages() }.getOrNull()
                    update { it.copy(busy = null, progress = null, message = done, apps = apps ?: it.apps) }
                } catch (e: Exception) {
                    update { it.copy(busy = null, progress = null, error = e.message ?: tr("No se pudo completar")) }
                }
            }
        }
    }

    fun install(apks: List<File>) =
        run(tr("Instalando en la TV…"), if (apks.size == 1) tr("«{0}» instalada en la TV", apks[0].name) else tr("{0} APK instaladas en la TV", apks.size)) { c ->
            apks.forEachIndexed { i, apk ->
                update { it.copy(busy = tr("Instalando «{0}» ({1} de {2})…", apk.name, i + 1, apks.size)) }
                c.install(apk) { done, total -> update { it.copy(progress = if (total > 0) done.toFloat() / total else null) } }
            }
        }

    fun launch(pkg: String) = run(tr("Abriendo «{0}»…", pkg), tr("«{0}» abierta en la TV", pkg)) { it.launch(pkg) }

    fun uninstall(pkg: String) = run(tr("Desinstalando «{0}»…", pkg), tr("«{0}» desinstalada de la TV", pkg)) { it.uninstall(pkg) }

    fun refresh() = run(tr("Leyendo las apps…")) {}

    /** Las teclas del mando no muestran «ocupado»: se mandan en orden y solo se avisa si fallan. */
    fun key(key: Adb.Key) {
        if (state.value?.device == null) return
        scope.launch {
            lock.withLock {
                runCatching { (client ?: throw java.io.IOException(tr("Sin conexión con la TV"))).key(key) }
                    .onFailure { e -> update { it.copy(error = e.message ?: tr("No se pudo completar")) } }
            }
        }
    }

    fun disconnect() {
        val c = client
        client = null
        state.value = null
        if (c != null) scope.launch { runCatching { c.close() } }
    }
}
