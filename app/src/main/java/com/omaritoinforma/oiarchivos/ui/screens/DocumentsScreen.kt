package com.omaritoinforma.oiarchivos.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun DocumentsScreen(vm: MainViewModel, uri: String) {
    val ctx = LocalContext.current
    val tree = remember(uri) { DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) }
    val stack = remember(uri) { mutableStateListOf<DocumentFile>().apply { tree?.let { add(it) } } }
    val dir = stack.lastOrNull()
    var entries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateMapOf<String, DocumentFile>() }
    var deleting by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<DocumentFile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val completion by TransferService.completion.collectAsState()
    LaunchedEffect(completion) { refresh++ }
    LaunchedEffect(dir, refresh) {
        if (dir != null)
            withContext(Dispatchers.IO) { runCatching { dir.listFiles().toList() } }
                .onSuccess {
                    entries = it
                    selected.clear()
                    error = null
                }
                .onFailure { error = "Acceso no disponible: ${it.message}" }
    }
    ToolPage(
        dir?.name ?: "USB / documentos",
        vm,
        actions = { TextButton(onClick = { refresh++ }) { Text("Actualizar") } }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row {
                    TextButton(
                        onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        enabled = stack.size > 1) {
                            Text("Subir")
                        }
                    TextButton(onClick = { creating = true }, enabled = dir?.canWrite() == true) {
                        Text("Nueva carpeta")
                    }
                }
                error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                val clip = vm.clipboard
                if (clip != null && dir != null)
                    TextButton(
                        onClick = {
                            vm.runTask("Copiando a USB / nube") { report ->
                                suspend fun copy(source: File, parent: DocumentFile) {
                                    currentCoroutineContext().ensureActive()
                                    SafeFiles.requireRegular(source)
                                    SafeFiles.requireName(source.name)
                                    val name = uniqueDocumentName(parent, source.name)
                                    if (source.isDirectory) {
                                        val child =
                                            parent.createDirectory(name)
                                                ?: throw IOException("No se pudo crear la carpeta")
                                        for (f in
                                            source.listFiles()
                                                ?: throw IOException(
                                                    "No se pudo leer el origen")) copy(f, child)
                                    } else {
                                        val child =
                                            parent.createFile("application/octet-stream", name)
                                                ?: throw IOException("No se pudo crear el archivo")
                                        try {
                                            source.inputStream().use { input ->
                                                (ctx.contentResolver.openOutputStream(
                                                        child.uri, "wt")
                                                        ?: throw IOException(
                                                            "No se puede escribir"))
                                                    .use { out ->
                                                        val buffer = ByteArray(131072)
                                                        var done = 0L
                                                        while (true) {
                                                            currentCoroutineContext().ensureActive()
                                                            val n = input.read(buffer)
                                                            if (n < 0) break
                                                            out.write(buffer, 0, n)
                                                            done += n
                                                            report(
                                                                OpProgress(
                                                                    "Copiando",
                                                                    source.name,
                                                                    done,
                                                                    source.length()))
                                                        }
                                                    }
                                            }
                                        } catch (e: Exception) {
                                            child.delete()
                                            throw e
                                        }
                                    }
                                }
                                val sources = clip.paths.map(::File)
                                sources.forEach { copy(it, dir) }
                                if (clip.move)
                                    sources.forEach {
                                        if (!it.deleteRecursively())
                                            throw IOException(
                                                "Copiado; no se pudo eliminar el original")
                                    }
                                withContext(Dispatchers.Main) { vm.clipboard = null }
                                OperationResult("Archivos copiados")
                            }
                        }) {
                            Text("Pegar aquí desde el teléfono")
                        }
                if (selected.isNotEmpty()) {
                    Row {
                        TextButton(
                            onClick = {
                                val chosen = selected.values.toList()
                                vm.runTask("Importando documentos") { report ->
                                    val dest =
                                        File(PathUtil.internalRoot, "Download/OI Archivos").apply {
                                            mkdirs()
                                        }
                                    val outputs =
                                        chosen.map { importDocument(ctx, it, dest, report) }
                                    OperationResult("Guardados en Descargas/OI Archivos", outputs)
                                }
                            }) {
                                Text("Copiar al teléfono")
                            }
                        TextButton(
                            onClick = { rename = selected.values.singleOrNull() },
                            enabled = selected.size == 1) {
                                Text("Renombrar")
                            }
                        TextButton(onClick = { deleting = true }) { Text("Eliminar") }
                    }
                }
                LazyColumn {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        val key = entry.uri.toString()
                        ListItem(
                            headlineContent = { Text(entry.name ?: "Archivo") },
                            supportingContent = {
                                Text(
                                    if (entry.isDirectory) "Carpeta"
                                    else formatSize(entry.length()))
                            },
                            leadingContent = {
                                Checkbox(
                                    key in selected,
                                    { if (it) selected[key] = entry else selected.remove(key) })
                            },
                            modifier =
                                Modifier.clickable {
                                    if (entry.isDirectory) stack.add(entry)
                                    else {
                                        vm.runTask("Abriendo documento") { report ->
                                            val dest =
                                                File(ctx.cacheDir, "document-preview").apply {
                                                    mkdirs()
                                                }
                                            val local = importDocument(ctx, entry, dest, report)
                                            withContext(Dispatchers.Main) {
                                                vm.openFile(local.path)
                                            }
                                            OperationResult(null)
                                        }
                                    }
                                })
                    }
                }
            }
        }
    if (creating)
        RemoteNameDialog("Nueva carpeta", "", { creating = false }) { name ->
            creating = false
            vm.runTask("Creando carpeta") {
                if (dir?.createDirectory(name) == null) throw IOException("No se pudo crear")
                OperationResult("Carpeta creada")
            }
        }
    if (rename != null)
        RemoteNameDialog("Renombrar", rename!!.name.orEmpty(), { rename = null }) { name ->
            val entry = rename!!
            rename = null
            vm.runTask("Renombrando") {
                if (!entry.renameTo(name)) throw IOException("No se pudo renombrar")
                OperationResult("Renombrado")
            }
        }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Eliminar documentos") },
            text = { Text("El proveedor puede eliminar estos archivos definitivamente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        val chosen = selected.values.toList()
                        vm.runTask("Eliminando documentos") {
                            for (entry in chosen) if (!entry.delete())
                                throw IOException("No se pudo eliminar ${entry.name}")
                            OperationResult("Eliminados")
                        }
                    }) {
                        Text("Eliminar")
                    }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}

private fun uniqueDocumentName(parent: DocumentFile, name: String): String {
    SafeFiles.requireName(name)
    val existing = parent.listFiles().map { it.name }.toSet()
    if (name !in existing) return name
    val base = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var n = 1
    while ("$base ($n)$ext" in existing) n++
    return "$base ($n)$ext"
}

suspend fun importDocument(
    ctx: android.content.Context,
    source: DocumentFile,
    dir: File,
    report: (OpProgress) -> Unit
): File {
    currentCoroutineContext().ensureActive()
    val name = source.name ?: "archivo"
    SafeFiles.requireName(name)
    val target = FileOps.uniqueName(dir, name)
    if (source.isDirectory) {
        if (!target.mkdirs()) throw IOException("No se pudo crear el destino")
        for (child in source.listFiles()) importDocument(ctx, child, target, report)
    } else {
        val temp = File.createTempFile(".oi-doc-", ".tmp", dir)
        try {
            (ctx.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("No se pudo leer el documento"))
                .use { input ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(131072)
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            report(OpProgress("Importando", name, done, source.length()))
                        }
                    }
                }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, target, false)
        } finally {
            temp.delete()
        }
    }
    return target
}
