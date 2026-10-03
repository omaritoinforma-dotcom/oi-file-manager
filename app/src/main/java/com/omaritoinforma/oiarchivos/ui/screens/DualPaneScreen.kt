package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.components.FileDragHandle
import com.omaritoinforma.oiarchivos.ui.components.fileDropTarget
import com.omaritoinforma.oiarchivos.util.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DualPaneScreen(vm: MainViewModel, initial: String) {
    var left by rememberSaveable { mutableStateOf(initial) }
    var right by rememberSaveable { mutableStateOf(PathUtil.internalRoot) }
    ToolPage("Doble panel", vm) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            Pane(vm, left, { left = it }, Modifier.weight(1f))
            VerticalDivider()
            Pane(vm, right, { right = it }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Pane(vm: MainViewModel, path: String, navigate: (String) -> Unit, modifier: Modifier) {
    var entries by remember(path) { mutableStateOf<List<FileItem>>(emptyList()) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    val selection = remember(path) { mutableStateMapOf<String, FileItem>() }
    val completed by TransferService.completion.collectAsState()
    var dropped by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path, completed) {
        withContext(Dispatchers.IO) {
                runCatching {
                    FileRepo.list(File(path), vm.showHidden)
                        ?: throw java.io.IOException("Carpeta no disponible")
                }
            }
            .onSuccess {
                entries = Sorter.sort(it, vm.sortBy, vm.ascending)
                selection.keys.filter { key -> entries.none { it.path == key } }.forEach(selection::remove)
            }
            .onFailure { error = it.message }
    }
    Column(modifier.then(fileDropTarget { dropped = it })) {
        Text(path, Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { File(path).parent?.let(navigate) }) { Text("Subir") }
        if (vm.clipboard != null)
            TextButton(onClick = { vm.pasteInto(path) }) { Text("Pegar aquí") }
        if (selection.isNotEmpty()) {
            TextButton(
                onClick = {
                    vm.clipboard = Clipboard(selection.keys.toList(), false)
                    selection.clear()
                }) {
                    Text("Copiar")
                }
            TextButton(
                onClick = {
                    vm.clipboard = Clipboard(selection.keys.toList(), true)
                    selection.clear()
                }) {
                    Text("Cortar")
                }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { entry ->
                ListItem(
                    headlineContent = {
                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Checkbox(
                            entry.path in selection,
                            {
                                if (it) selection[entry.path] = entry
                                else selection.remove(entry.path)
                            })
                    },
                    trailingContent = { FileDragHandle(if (entry.path in selection) selection.keys.toList() else listOf(entry.path)) },
                    modifier =
                        Modifier.clickable {
                            if (entry.isDirectory) navigate(entry.path) else vm.openFile(entry.path)
                        })
            }
        }
    }
    dropped?.let { paths ->
        AlertDialog(onDismissRequest = { dropped = null }, title = { Text("${paths.size} elementos") },
            text = { Text("Destino: $path") },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.clipboard = Clipboard(paths, false); vm.pasteInto(path); dropped = null }) { Text("Copiar aquí") }
                    TextButton(onClick = { vm.clipboard = Clipboard(paths, true); vm.pasteInto(path); dropped = null }) { Text("Mover aquí") }
                }
            }, dismissButton = { TextButton(onClick = { dropped = null }) { Text("Cancelar") } })
    }
}
