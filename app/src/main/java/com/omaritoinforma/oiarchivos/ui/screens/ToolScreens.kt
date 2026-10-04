@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ToolPage(
    title: String,
    vm: MainViewModel,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = actions)
        },
        content = content)
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    var history by remember { mutableStateOf(vm.history()) }
    ToolPage(
        "Historial de carpetas",
        vm,
        actions = {
            TextButton(
                onClick = {
                    vm.clearHistory()
                    history = emptyList()
                }) {
                    Text("Borrar")
                }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(history) { path ->
                    ListItem(
                        headlineContent = { Text(PathUtil.displayName(path)) },
                        supportingContent = { Text(path) },
                        modifier = Modifier.clickable { vm.openFolder(path) })
                }
            }
        }
}

@Composable
fun TransfersScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val completion by TransferService.completion.collectAsState()
    val progress by TransferService.progress.collectAsState()
    val records = remember(completion, progress == null) { TransferService.history(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    val queued =
        remember(completion, progress == null, refresh) {
            TransferService.pendingJobs(ctx)
        }
    val paused by TransferService.paused.collectAsState()
    val pausable by TransferService.supportsPause.collectAsState()
    ToolPage("Transferencias", vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (pausable && progress != null)
                item {
                    TextButton(onClick = { TransferService.pause(ctx, !paused) }) {
                        Text(if (paused) "Reanudar transferencia" else "Pausar transferencia")
                    }
                }
            if (progress == null)
                items(queued, key = { it.id }) { job ->
                    ListItem(
                        headlineContent = {
                            Text("${job.title} · ${job.completed} / ${job.count}")
                        },
                        supportingContent = { Text(job.destination) },
                        trailingContent = {
                            Column {
                                TextButton(
                                    onClick = {
                                        runCatching { TransferService.submitDurable(ctx, job) }
                                            .onFailure {
                                                vm.toast(it.message ?: "No se pudo reanudar")
                                            }
                                    }) {
                                        Text("Reanudar")
                                    }
                                TextButton(
                                    onClick = {
                                        runCatching { job.discard() }
                                        refresh++
                                    }) {
                                        Text("Descartar")
                                    }
                            }
                        })
                }
            if (records.isEmpty())
                item {
                    Text(
                        "Las operaciones y sus resultados aparecerán aquí.",
                        Modifier.padding(20.dp))
                }
            items(records, key = { it.id }) { record ->
                ListItem(
                    headlineContent = { Text(record.title + " · " + record.status) },
                    supportingContent = { Text(formatDate(record.time) + "\n" + record.detail) })
            }
        }
    }
}

@Composable
fun AdvancedSearchScreen(vm: MainViewModel, root: String) {
    var name by remember { mutableStateOf("") }
    var extensions by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ToolPage("Búsqueda avanzada", vm) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Buscar en $root y sus subcarpetas") }
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Nombre (opcional)") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        extensions,
                        { extensions = it },
                        label = { Text("Extensiones: jpg, mp4, pdf…") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        min,
                        { min = it },
                        label = { Text("Tamaño mínimo en MB") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        max,
                        { max = it },
                        label = { Text("Tamaño máximo en MB") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        days,
                        { days = it },
                        label = { Text("Modificados en los últimos días") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        contents,
                        { contents = it },
                        label = { Text("Texto dentro del archivo") },
                        supportingText = { Text("Archivos de texto de hasta 8 MB") },
                        modifier = Modifier.fillMaxWidth())
                }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                item {
                    Button(
                        onClick = {
                            val lo = if (min.isBlank()) 0.0 else min.toDoubleOrNull()
                            val hi = if (max.isBlank()) Double.MAX_VALUE else max.toDoubleOrNull()
                            val d = if (days.isBlank()) 0L else days.toLongOrNull()
                            if (lo == null ||
                                hi == null ||
                                d == null ||
                                lo < 0 ||
                                hi < lo ||
                                d < 0 ||
                                d > 36500) {
                                error = "Revisa el tamaño y el número de días"
                                return@Button
                            }
                            val filter =
                                SearchFilter(
                                    name,
                                    extensions
                                        .split(',')
                                        .map { it.trim().trimStart('.').lowercase() }
                                        .filter { it.isNotBlank() }
                                        .toSet(),
                                    (lo * 1048576).toLong(),
                                    if (max.isBlank()) Long.MAX_VALUE else (hi * 1048576).toLong(),
                                    if (d == 0L) 0 else System.currentTimeMillis() - d * 86400000,
                                    contents)
                            vm.runTask("Búsqueda avanzada") { report ->
                                val results = AnalysisTools.search(File(root), filter, report)
                                withContext(Dispatchers.Main) {
                                    vm.showResults(
                                        results, root, name.ifBlank { "Filtros avanzados" })
                                }
                                OperationResult(
                                    "${results.size} resultados" +
                                        if (results.size == 5000) " (límite alcanzado)" else "")
                            }
                        }) {
                            Text("Buscar")
                        }
                }
            }
    }
}

@Composable
fun AnalysisScreen(vm: MainViewModel, root: String) {
    var result by remember(root) { mutableStateOf<SpaceAnalysis?>(null) }
    var duplicates by remember { mutableStateOf(true) }
    val selected = remember { mutableStateMapOf<String, File>() }
    var confirm by remember { mutableStateOf(false) }
    ToolPage("Analizar almacenamiento", vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(root)
                Row {
                    Checkbox(duplicates, { duplicates = it })
                    Text("Buscar duplicados por SHA-256", Modifier.padding(top = 12.dp))
                }
            }
            item {
                Button(
                    onClick = {
                        vm.runTask("Analizando almacenamiento") { report ->
                            val analysis = AnalysisTools.analyze(File(root), duplicates, report)
                            withContext(Dispatchers.Main) {
                                result = analysis
                                selected.clear()
                            }
                            OperationResult("Análisis terminado")
                        }
                    }) {
                        Text("Analizar")
                    }
            }
            val r = result
            if (r != null) {
                item {
                    Text(
                        "${formatSize(r.bytes)} · ${r.files} archivos",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 14.dp))
                    if (r.limited)
                        Text("Resultado parcial: se alcanzó el límite de 200.000 archivos")
                }
                item {
                    Text(
                        "Carpetas y archivos que más ocupan",
                        style = MaterialTheme.typography.titleMedium)
                }
                items(r.folders) { (name, bytes) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = { Text(formatSize(bytes)) })
                }
                item { Text("Archivos más grandes", style = MaterialTheme.typography.titleMedium) }
                items(r.largest, key = { "large:" + it.path }) { file ->
                    AnalysisRow(
                        file,
                        file.path in selected,
                        { if (it) selected[file.path] = file else selected.remove(file.path) },
                        { vm.openFolder(file.parent ?: root) })
                }
                item { Text("Duplicados exactos", style = MaterialTheme.typography.titleMedium) }
                r.duplicates.forEachIndexed { i, group ->
                    item {
                        Text(
                            "Grupo ${i+1} · ${formatSize(group.first().length())} cada uno",
                            Modifier.padding(top = 12.dp))
                    }
                    items(group, key = { "dupe:$i:" + it.path }) { file ->
                        AnalysisRow(
                            file,
                            file.path in selected,
                            { if (it) selected[file.path] = file else selected.remove(file.path) },
                            { vm.openFolder(file.parent ?: root) })
                    }
                }
                item {
                    Text("Candidatos a revisar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Archivos temporales, vacíos y carpetas vacías. Revisa los seleccionados antes de eliminarlos.")
                }
                items(r.candidates, key = { "temp:" + it.path }) { file ->
                    AnalysisRow(
                        file,
                        file.path in selected,
                        { if (it) selected[file.path] = file else selected.remove(file.path) },
                        { vm.openFolder(file.parent ?: root) })
                }
                if (selected.isNotEmpty())
                    item {
                        Button(onClick = { confirm = true }) {
                            Text("Enviar ${selected.size} a la papelera")
                        }
                    }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Revisar eliminación") },
            text = {
                Text(
                    selected.values.take(15).joinToString("\n") { it.path } +
                        if (selected.size > 15) "\n…" else "")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.delete(selected.values.map { it.toItem() }, true)
                        selected.clear()
                        result = null
                    }) {
                        Text("Enviar a la papelera")
                    }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}

@Composable
private fun AnalysisRow(file: File, checked: Boolean, check: (Boolean) -> Unit, open: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(file.parent.orEmpty()) },
        leadingContent = { Checkbox(checked, check) },
        trailingContent = { Text(formatSize(file.length())) },
        modifier = Modifier.clickable(onClick = open))
}
