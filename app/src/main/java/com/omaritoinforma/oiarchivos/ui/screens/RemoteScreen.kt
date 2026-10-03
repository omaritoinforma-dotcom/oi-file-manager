package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(vm: MainViewModel, id: String) {
    val ctx = LocalContext.current
    var connection by remember(id) { mutableStateOf<Connection?>(null) }
    val stack = remember(id) { mutableStateListOf<String>() }
    var list by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val selected = remember(id) { mutableStateMapOf<String, RemoteEntry>() }
    var rename by remember { mutableStateOf<RemoteEntry?>(null) }
    var create by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
                runCatching { ConnectionStore(ctx).load().first { it.id == id } }
            }
            .onSuccess {
                connection = it
                stack.add(it.root)
            }
            .onFailure { error = it.message }
    }
    val c = connection
    val path = stack.lastOrNull()
    LaunchedEffect(c, path, revision) {
        if (c != null && path != null) {
            loading = true
            error = null
            withContext(Dispatchers.IO) {
                    runCatching { RemoteFiles.connect(c).use { it.list(path) } }
                }
                .onSuccess {
                    list =
                        it.sortedWith(
                            compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name })
                }
                .onFailure { error = it.message }
            loading = false
            selected.clear()
        }
    }
    val completion by TransferService.completion.collectAsState()
    LaunchedEffect(completion) { revision++ }
    ToolPage(
        c?.label ?: "Archivos remotos",
        vm,
        actions = { TextButton(onClick = { revision++ }) { Text("Actualizar") } }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row {
                    TextButton(
                        onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        enabled = stack.size > 1) {
                            Text("Subir")
                        }
                    TextButton(onClick = { create = true }, enabled = c != null) {
                        Text("Nueva carpeta")
                    }
                    Text(path.orEmpty(), Modifier.weight(1f).padding(12.dp), maxLines = 2)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                val clip = vm.clipboard
                val remoteClip = NetworkClipboard.value
                if (c != null && path != null && (clip != null || remoteClip != null))
                    Button(
                        onClick = {
                            vm.runTask("Pegando archivos remotos") { report ->
                                RemoteFiles.connect(c).use { target ->
                                    if (remoteClip != null) {
                                        if (remoteClip.connection.id == c.id &&
                                            remoteClip.entries.any {
                                                it.path == path ||
                                                    (it.directory &&
                                                        path.startsWith(it.path.trimEnd('/') + "/"))
                                            })
                                            throw java.io.IOException(
                                                "No puedes copiar una carpeta dentro de sí misma")
                                        val stage =
                                            File(
                                                    ctx.cacheDir,
                                                    "remote-${java.util.UUID.randomUUID()}")
                                                .apply { mkdirs() }
                                        try {
                                            RemoteFiles.connect(remoteClip.connection).use { source
                                                ->
                                                for (entry in remoteClip.entries) {
                                                    val local =
                                                        RemoteFiles.download(
                                                            source, entry, stage, report)
                                                    RemoteFiles.upload(
                                                        target, listOf(local), path, report)
                                                    if (remoteClip.move) source.delete(entry)
                                                    local.deleteRecursively()
                                                }
                                            }
                                        } finally {
                                            stage.deleteRecursively()
                                        }
                                        withContext(Dispatchers.Main) {
                                            NetworkClipboard.value = null
                                        }
                                    } else if (clip != null) {
                                        val sources = clip.paths.map(::File)
                                        RemoteFiles.upload(target, sources, path, report)
                                        if (clip.move)
                                            for (source in sources) {
                                                kotlinx.coroutines
                                                    .currentCoroutineContext()
                                                    .ensureActive()
                                                if (!source.deleteRecursively())
                                                    throw java.io.IOException(
                                                        "Subido, pero no se pudo borrar el original")
                                            }
                                        withContext(Dispatchers.Main) { vm.clipboard = null }
                                    }
                                }
                                OperationResult("Archivos pegados")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Pegar aquí")
                        }
                if (selected.isNotEmpty() && c != null) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                NetworkClipboard.value =
                                    NetworkClip(c, selected.values.toList(), false)
                                vm.clipboard = null
                                selected.clear()
                                vm.toast("Ve a la carpeta de destino y pega los archivos")
                            }) {
                                Text("Copiar")
                            }
                        TextButton(
                            onClick = {
                                NetworkClipboard.value =
                                    NetworkClip(c, selected.values.toList(), true)
                                vm.clipboard = null
                                selected.clear()
                            }) {
                                Text("Cortar")
                            }
                        TextButton(
                            onClick = {
                                val entries = selected.values.toList()
                                vm.runTask("Descargando archivos") { report ->
                                    val out = ArrayList<File>()
                                    RemoteFiles.connect(c).use { fs ->
                                        for (entry in entries) out +=
                                            RemoteFiles.download(
                                                fs,
                                                entry,
                                                File(PathUtil.internalRoot, "Download/OI Archivos"),
                                                report)
                                    }
                                    OperationResult("Guardados en Descargas/OI Archivos", out)
                                }
                            }) {
                                Text("Descargar")
                            }
                    }
                    Row {
                        TextButton(
                            onClick = { rename = selected.values.singleOrNull() },
                            enabled = selected.size == 1) {
                                Text("Renombrar")
                            }
                        TextButton(onClick = { delete = true }) { Text("Eliminar") }
                        TextButton(onClick = { selected.clear() }) { Text("Cancelar") }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            supportingContent = {
                                Text(
                                    if (entry.directory) "Carpeta"
                                    else if (entry.size < 0) "Tamaño desconocido"
                                    else formatSize(entry.size))
                            },
                            leadingContent = {
                                Checkbox(
                                    entry.path in selected,
                                    {
                                        if (it) selected[entry.path] = entry
                                        else selected.remove(entry.path)
                                    })
                            },
                            modifier =
                                Modifier.combinedClickable(
                                    onLongClick = { selected[entry.path] = entry },
                                    onClick = {
                                        if (entry.directory && selected.isEmpty())
                                            stack.add(entry.path)
                                        else if (selected.isNotEmpty()) {
                                            if (entry.path in selected) selected.remove(entry.path)
                                            else selected[entry.path] = entry
                                        } else if (c != null) {
                                            vm.runTask("Abriendo archivo remoto") { report ->
                                                val preview =
                                                    File(ctx.cacheDir, "remote-preview").apply {
                                                        mkdirs()
                                                    }
                                                val local =
                                                    RemoteFiles.connect(c).use { fs ->
                                                        RemoteFiles.download(
                                                            fs, entry, preview, report)
                                                    }
                                                withContext(Dispatchers.Main) {
                                                    vm.openFile(local.path)
                                                }
                                                OperationResult(null)
                                            }
                                        }
                                    }))
                    }
                }
            }
        }
    if (rename != null)
        RemoteNameDialog("Renombrar", rename!!.name, { rename = null }) { name ->
            val entry = rename!!
            rename = null
            if (c != null)
                vm.runTask("Renombrando") {
                    RemoteFiles.connect(c).use { it.rename(entry, name) }
                    OperationResult("Nombre actualizado")
                }
        }
    if (create)
        RemoteNameDialog("Nueva carpeta", "", { create = false }) { name ->
            create = false
            if (c != null && path != null)
                vm.runTask("Creando carpeta") {
                    RemoteFiles.connect(c).use { it.mkdir(path, name) }
                    OperationResult("Carpeta creada")
                }
        }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Eliminar ${selected.size} elementos") },
            text = { Text("La eliminación en servidores remotos puede ser permanente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        delete = false
                        val entries = selected.values.toList()
                        if (c != null)
                            vm.runTask("Eliminando archivos remotos") {
                                RemoteFiles.connect(c).use { fs ->
                                    entries.forEach { fs.delete(it) }
                                }
                                OperationResult("Elementos eliminados")
                            }
                    }) {
                        Text("Eliminar")
                    }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancelar") } })
}

@Composable
fun RemoteNameDialog(
    title: String,
    initial: String,
    dismiss: () -> Unit,
    submit: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }) },
        confirmButton = {
            TextButton(
                onClick = { submit(name.trim()) }, enabled = SafeFiles.validName(name.trim())) {
                    Text("Guardar")
                }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
}
