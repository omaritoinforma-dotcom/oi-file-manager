@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Atrás"))
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
        tr("Historial"),
        vm,
        actions = {
            TextButton(
                onClick = {
                    vm.clearHistory()
                    history = emptyList()
                }) {
                    Text(tr("Borrar"))
                }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                if (history.isEmpty())
                    item { Text(tr("El historial está vacío."), Modifier.padding(16.dp)) }
                items(history) { path ->
                    val folder = remember(path) { File(path).isDirectory }
                    ListItem(
                        headlineContent = { Text(PathUtil.displayName(path)) },
                        supportingContent = { Text(path) },
                        leadingContent = {
                            Icon(
                                if (folder) Icons.Filled.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = if (folder) tr("Carpeta") else tr("Archivo"))
                        },
                        modifier =
                            Modifier.clickable {
                                if (folder) vm.openFolder(path) else vm.openFile(path)
                            })
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
    ToolPage(tr("Transferencias"), vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (pausable && progress != null)
                item {
                    TextButton(onClick = { TransferService.pause(ctx, !paused) }) {
                        Text(if (paused) tr("Reanudar transferencia") else tr("Pausar transferencia"))
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
                                                vm.toast(it.message ?: tr("No se pudo reanudar"))
                                            }
                                    }) {
                                        Text(tr("Reanudar"))
                                    }
                                TextButton(
                                    onClick = {
                                        runCatching { job.discard() }
                                        refresh++
                                    }) {
                                        Text(tr("Descartar"))
                                    }
                            }
                        })
                }
            if (records.isEmpty())
                item {
                    Text(
                        tr("Las operaciones y sus resultados aparecerán aquí."),
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
    var types by remember { mutableStateOf(emptySet<SearchKind>()) }
    var hidden by remember { mutableStateOf(vm.showHidden) }
    var subfolders by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    ToolPage(tr("Búsqueda avanzada"), vm) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(tr("Buscar en {0}", root)) }
                item {
                    Text(tr("Tipo (sin elegir, de cualquier tipo)"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchKind.entries.forEach { kind ->
                            FilterChip(
                                selected = kind in types,
                                onClick = { types = if (kind in types) types - kind else types + kind },
                                label = { Text(kind.label) })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text(tr("Nombre (opcional)")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        extensions,
                        { extensions = it },
                        label = { Text(tr("Extensiones: jpg, mp4, pdf…")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        min,
                        { min = it },
                        label = { Text(tr("Tamaño mínimo en MB")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        max,
                        { max = it },
                        label = { Text(tr("Tamaño máximo en MB")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        days,
                        { days = it },
                        label = { Text(tr("Modificados en los últimos días")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        contents,
                        { contents = it },
                        label = { Text(tr("Texto dentro del archivo")) },
                        supportingText = { Text(tr("Archivos de texto de hasta 8 MB")) },
                        modifier = Modifier.fillMaxWidth())
                }
                item { SwitchRow(tr("Buscar en las subcarpetas"), subfolders) { subfolders = it } }
                item {
                    SwitchRow(tr("Incluir archivos y carpetas ocultos"), hidden) {
                        if (it) vm.allowHiddenSearch { hidden = true } else hidden = false
                    }
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
                                error = tr("Revisa el tamaño y el número de días")
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
                                    contents,
                                    types,
                                    hidden,
                                    subfolders)
                            vm.runTask(tr("Búsqueda avanzada")) { report ->
                                val results = AnalysisTools.search(File(root), filter, report)
                                withContext(Dispatchers.Main) {
                                    vm.showResults(
                                        results,
                                        root,
                                        name.ifBlank { tr("Filtros avanzados") },
                                        filter)
                                }
                                OperationResult(
                                    tr("{0} resultados", results.size) +
                                        if (results.size == 5000) tr(" (límite alcanzado)") else "")
                            }
                        }) {
                            Text(tr("Buscar"))
                        }
                }
            }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f))
            Switch(checked, null)
        }
}

@Composable
fun AnalysisScreen(vm: MainViewModel, root: String) {
    var result by remember(root) { mutableStateOf<SpaceAnalysis?>(null) }
    var duplicates by remember { mutableStateOf(true) }
    val selected = remember { mutableStateMapOf<String, File>() }
    var confirm by remember { mutableStateOf(false) }
    ToolPage(tr("Analizar almacenamiento"), vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(root)
                Row {
                    Checkbox(duplicates, { duplicates = it })
                    Text(tr("Buscar duplicados por SHA-256"), Modifier.padding(top = 12.dp))
                }
            }
            item {
                Button(
                    onClick = {
                        vm.runTask(tr("Analizando almacenamiento")) { report ->
                            val analysis = AnalysisTools.analyze(File(root), duplicates, report)
                            withContext(Dispatchers.Main) {
                                result = analysis
                                selected.clear()
                            }
                            OperationResult(tr("Análisis terminado"))
                        }
                    }) {
                        Text(tr("Analizar"))
                    }
            }
            val r = result
            if (r != null) {
                item {
                    Text(
                        tr("{0} · {1} archivos", formatSize(r.bytes), r.files),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 14.dp))
                    if (r.limited)
                        Text(tr("Resultado parcial: se alcanzó el límite de 200.000 archivos"))
                }
                item {
                    Text(
                        tr("Carpetas y archivos que más ocupan"),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(r.folders) { (name, bytes) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = { Text(formatSize(bytes)) })
                }
                item { Text(tr("Archivos más grandes"), style = MaterialTheme.typography.titleMedium) }
                items(r.largest, key = { "large:" + it.path }) { file ->
                    AnalysisRow(
                        file,
                        file.path in selected,
                        { if (it) selected[file.path] = file else selected.remove(file.path) },
                        { vm.openFolder(file.parent ?: root) })
                }
                item { Text(tr("Duplicados exactos"), style = MaterialTheme.typography.titleMedium) }
                r.duplicates.forEachIndexed { i, group ->
                    item {
                        Text(
                            tr("Grupo {0} · {1} cada uno", i+1, formatSize(group.first().length())),
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
                    Text(tr("Candidatos a revisar"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Archivos temporales, vacíos y carpetas vacías. Revisa los seleccionados antes de eliminarlos."))
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
                            Text(tr("Enviar {0} a la papelera", selected.size))
                        }
                    }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Revisar eliminación")) },
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
                        Text(tr("Enviar a la papelera"))
                    }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancelar")) } })
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
