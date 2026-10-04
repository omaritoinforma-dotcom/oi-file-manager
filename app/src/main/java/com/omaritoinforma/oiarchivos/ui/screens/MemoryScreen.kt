package com.omaritoinforma.oiarchivos.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MemoryScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        withContext(Dispatchers.IO) { runCatching { readMemorySnapshot(context) } }
            .onSuccess { snapshot = it }
            .onFailure { error = "No se pudo consultar la memoria del dispositivo." }
        loading = false
    }

    ToolPage(
        title = "Memoria / procesos",
        vm = vm,
        actions = { TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Actualizar") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            snapshot?.let { memory ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "RAM del dispositivo",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Total: ${formatSize(memory.totalBytes)}")
                            Text("Disponible: ${formatSize(memory.availableBytes)}")
                            Text(
                                "Umbral de memoria baja: ${formatSize(memory.lowMemoryThresholdBytes)}"
                            )
                            Text(
                                if (memory.lowMemory) "Android indica memoria baja."
                                else "Android no indica memoria baja.",
                                color =
                                    if (memory.lowMemory) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Procesos visibles (${memory.processes.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Android limita la lista a los procesos que permite ver, normalmente los de esta app. " +
                                "La memoria PSS se consulta solo para los procesos de esta app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "PSS estima la RAM usada repartiendo la memoria compartida. Android puede reutilizar una medición reciente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (memory.processQueryFailed) {
                    item {
                        Text(
                            "Android no permitió consultar los procesos.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (memory.processes.isEmpty()) {
                    item { Text("Android no devolvió procesos visibles en esta consulta.") }
                }
                items(memory.processes, key = { it.pid }) { process ->
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            ListItem(
                                headlineContent = { Text(process.name) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "PID ${process.pid} · ${processImportanceLabel(process.importance)}"
                                        )
                                        Text(
                                            when {
                                                !process.ownUid ->
                                                    "Memoria de este proceso no disponible para esta app."
                                                process.pssBytes != null ->
                                                    "Memoria PSS: ${formatSize(process.pssBytes)}"
                                                else ->
                                                    "Memoria PSS no disponible en esta consulta."
                                            }
                                        )
                                    }
                                },
                            )
                            process.packageName?.let { packageName ->
                                TextButton(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    onClick = {
                                        runCatching {
                                                context.startActivity(
                                                    Intent(
                                                            Settings
                                                                .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.fromParts(
                                                                "package",
                                                                packageName,
                                                                null,
                                                            ),
                                                        )
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            }
                                            .onFailure {
                                                vm.toast(
                                                    "No se pudo abrir la información de la app."
                                                )
                                            }
                                    },
                                ) {
                                    Text("Información de la app")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val processes: List<VisibleProcess>,
    val processQueryFailed: Boolean,
)

private data class VisibleProcess(
    val pid: Int,
    val name: String,
    val importance: Int,
    val ownUid: Boolean,
    val pssBytes: Long?,
    val packageName: String?,
)

private fun readMemorySnapshot(context: Context): MemorySnapshot {
    val manager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: error("El servicio de memoria no está disponible")
    val memory = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(memory)
    val processResult = runCatching { manager.runningAppProcesses.orEmpty() }
    val processes =
        processResult.getOrDefault(emptyList()).filter { it.pid > 0 }.distinctBy { it.pid }
    val ownUid = Process.myUid()
    // Android 10+ only exposes per-process memory for the caller's UID. Apply that
    // boundary on every supported Android version, even if more processes are visible.
    val ownProcesses = processes.filter { it.uid == ownUid }
    val ownMemory =
        if (ownProcesses.isEmpty()) emptyMap()
        else {
            runCatching {
                    val memoryInfo =
                        manager.getProcessMemoryInfo(ownProcesses.map { it.pid }.toIntArray())
                    ownProcesses
                        .mapIndexedNotNull { index, process ->
                            memoryInfo
                                .getOrNull(index)
                                ?.totalPss
                                ?.takeIf { it > 0 }
                                ?.let { pssKb -> process.pid to pssKb.toLong() * 1024L }
                        }
                        .toMap()
                }
                .getOrDefault(emptyMap())
        }
    return MemorySnapshot(
        totalBytes = memory.totalMem,
        availableBytes = memory.availMem,
        lowMemoryThresholdBytes = memory.threshold,
        lowMemory = memory.lowMemory,
        processes =
            processes
                .sortedWith(compareBy({ it.importance }, { it.processName }, { it.pid }))
                .map { process ->
                    VisibleProcess(
                        pid = process.pid,
                        name =
                            process.processName?.takeIf { it.isNotBlank() }
                                ?: "Proceso ${process.pid}",
                        importance = process.importance,
                        ownUid = process.uid == ownUid,
                        pssBytes = ownMemory[process.pid],
                        packageName = process.pkgList?.firstOrNull()?.takeIf { it.isNotBlank() },
                    )
                },
        processQueryFailed = processResult.isFailure,
    )
}

private fun processImportanceLabel(importance: Int): String =
    when {
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Finalizado"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Primer plano"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
            "Servicio en primer plano"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Servicio"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING ->
            "App en reposo"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE ->
            "En segundo plano"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "En caché"
        else -> "Importancia $importance"
    }
