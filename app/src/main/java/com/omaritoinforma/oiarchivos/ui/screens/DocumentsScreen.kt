package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.omaritoinforma.oiarchivos.ui.Screen
import com.omaritoinforma.oiarchivos.util.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DocumentsScreen(vm: MainViewModel, uri: String) {
    val ctx = LocalContext.current
    val store = remember(ctx) { AndroidDocumentStore(ctx) }
    val root = remember(uri) { runCatching {
        val tree = Uri.parse(uri)
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
    }.getOrNull() }
    val stack = remember(uri) { mutableStateListOf<String>().apply { root?.let(::add) } }
    val dir = stack.lastOrNull()
    var directory by remember(uri) { mutableStateOf<DocumentInfo?>(null) }
    var entries by remember(uri) { mutableStateOf<List<DocumentInfo>>(emptyList()) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val selected = remember(uri) { mutableStateMapOf<String, DocumentInfo>() }
    var deleting by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<DocumentInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    val completion by TransferService.completion.collectAsState()
    val chooseTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { chosen ->
        if (chosen != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(chosen,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { ctx.contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { vm.toast("Permiso temporal; vuelve a elegir la carpeta si caduca") }
            vm.goTo(Screen.Documents(chosen.toString()))
        }
    }
    LaunchedEffect(completion) { refresh++ }
    LaunchedEffect(dir, refresh) {
        selected.clear()
        if (dir == null) { error = "La carpeta seleccionada no está disponible"; return@LaunchedEffect }
        withContext(Dispatchers.IO) { runCatching { store.stat(dir) to store.children(dir) } }
            .onSuccess { directory = it.first; entries = it.second; error = null }
            .onFailure { entries = emptyList(); directory = null; error = "Acceso no disponible: ${it.message}" }
    }
    fun open(entry: DocumentInfo, edit: Boolean = false) {
        vm.runTask(if (edit) "Preparando editor" else "Abriendo documento") { report ->
            val previewDir = File(ctx.cacheDir, "document-preview").apply {
                if (!isDirectory && !mkdirs()) throw IOException("No se pudo preparar la vista previa")
            }
            if (edit && entry.size > 8L * 1024 * 1024)
                throw IOException("El editor permite archivos de hasta 8 MB")
            val document = DocumentFile.fromSingleUri(ctx, Uri.parse(entry.id))
                ?: throw IOException("El documento ya no está disponible")
            val local = importDocument(ctx, document, previewDir, report,
                if (edit) 8L * 1024 * 1024 else Long.MAX_VALUE)
            if (edit) {
                if (local.length() > 8L * 1024 * 1024) throw IOException("El editor permite archivos de hasta 8 MB")
                val fingerprint = DocumentTransactions.fingerprint(store, entry.id, 8L * 1024 * 1024)
                val cached = DocumentTransactions.fingerprint(LocalDocumentStore(), local.absolutePath, 8L * 1024 * 1024)
                if (fingerprint.size != cached.size || fingerprint.sha256 != cached.sha256)
                    throw IOException("El original cambió al abrirlo; vuelve a intentarlo")
                val origin = DocumentOrigin(uri, dir ?: throw IOException("Carpeta no disponible"),
                    entry.id, entry.name, ctx.contentResolver.getType(Uri.parse(entry.id)) ?: "text/plain", fingerprint)
                withContext(Dispatchers.Main) { vm.goTo(Screen.Editor(local.path, origin)) }
            } else withContext(Dispatchers.Main) { vm.openFile(local.path) }
            OperationResult(null)
        }
    }
    ToolPage(directory?.name ?: "USB / SD / documentos", vm,
        actions = { TextButton(onClick = { refresh++ }) { Text("Actualizar") } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row {
                TextButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }, enabled = stack.size > 1) { Text("Subir") }
                TextButton(onClick = { creating = true }, enabled = directory?.canCreate == true) { Text("Nueva carpeta") }
            }
            Row {
                TextButton(onClick = { chooseTree.launch(null) }) { Text("Otra USB / SD") }
                TextButton(onClick = { vm.openFolder(PathUtil.internalRoot) }) { Text("Teléfono") }
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            val clip = DocumentClipboard.value
            val localClip = vm.clipboard
            if (clip != null || localClip != null) {
                Text("${clip?.ids?.size ?: localClip!!.paths.size} elemento(s) para ${if (clip?.move ?: localClip!!.move) "mover" else "copiar"}. Navega hasta el destino.", Modifier.padding(horizontal = 12.dp))
                Row {
                    Button(onClick = { dir?.let(vm::pasteDocuments) }, enabled = directory?.canCreate == true,
                        modifier = Modifier.padding(8.dp)) { Text("Pegar aquí") }
                    TextButton(onClick = { DocumentClipboard.value = null; vm.clipboard = null }) { Text("Cancelar") }
                }
            }
            if (selected.isNotEmpty()) {
                Row {
                    TextButton(onClick = { vm.copyDocuments(uri, selected.keys.toList(), false); selected.clear() }) { Text("Copiar") }
                    TextButton(onClick = { vm.copyDocuments(uri, selected.keys.toList(), true); selected.clear() },
                        enabled = selected.values.all { it.canDelete }) { Text("Cortar") }
                    TextButton(onClick = { deleting = true }, enabled = selected.values.all { it.canDelete }) { Text("Eliminar") }
                }
                Row {
                    TextButton(onClick = { rename = selected.values.singleOrNull() },
                        enabled = selected.size == 1 && selected.values.single().canRename) { Text("Renombrar") }
                    TextButton(onClick = { selected.values.singleOrNull()?.let { open(it, true) } },
                        enabled = selected.size == 1 && !selected.values.single().directory) { Text("Editar texto") }
                }
            }
            LazyColumn {
                items(entries, key = { it.id }) { entry ->
                    ListItem(headlineContent = { Text(entry.name) },
                        supportingContent = { Text(if (entry.directory) "Carpeta" else if (entry.size < 0) "Tamaño desconocido" else formatSize(entry.size)) },
                        leadingContent = { Checkbox(entry.id in selected, {
                            if (it) selected[entry.id] = entry else selected.remove(entry.id)
                        }) },
                        modifier = Modifier.clickable {
                            if (entry.directory) stack.add(entry.id)
                            else open(entry, Kinds.isEditable(entry.name.substringAfterLast('.', "").lowercase()))
                        })
                }
            }
        }
    }
    if (creating) RemoteNameDialog("Nueva carpeta", "", { creating = false }) { name ->
        creating = false
        vm.runTask("Creando carpeta") {
            SafeFiles.requireName(name)
            store.createDirectory(dir ?: throw IOException("Carpeta no disponible"), name)
            OperationResult("Carpeta creada")
        }
    }
    rename?.let { entry ->
        RemoteNameDialog("Renombrar", entry.name, { rename = null }) { name ->
            rename = null
            vm.runTask("Renombrando") {
                store.rename(entry.id, name)
                OperationResult("Renombrado")
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("Eliminar documentos") }, text = { Text("El proveedor puede eliminar estos archivos definitivamente.") },
        confirmButton = { TextButton(onClick = {
            deleting = false
            val chosen = selected.values.toList()
            vm.runTask("Eliminando documentos") {
                for (entry in chosen) if (!store.delete(entry.id)) throw IOException("No se pudo eliminar ${entry.name}")
                OperationResult("Eliminados")
            }
        }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}

/** Shared-content imports use the same verified copy engine and never delete the source. */
suspend fun importDocument(ctx: Context, source: DocumentFile, dir: File, report: (OpProgress) -> Unit,
    maxFileSize: Long = Long.MAX_VALUE): File {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo crear el destino")
    val result = DocumentTransfers.transfer(AndroidDocumentStore(ctx), listOf(source.uri.toString()),
        LocalDocumentStore(), dir.absolutePath, false, report = report, maxFileSize = maxFileSize)
    return File(result.destinationIds.single())
}
