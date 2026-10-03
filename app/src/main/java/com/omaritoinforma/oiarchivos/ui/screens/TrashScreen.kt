@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.formatDate
import com.omaritoinforma.oiarchivos.util.formatSize

@Composable
fun TrashScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadTrash() }
    var confirmEmpty by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papelera") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    if (vm.trash.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Filled.DeleteSweep, "Vaciar papelera") }
                    }
                },
            )
        },
    ) { padding ->
        if (vm.trash.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("La papelera está vacía", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(vm.trash.toList(), key = { it.id }) { e ->
                    val kind = if (e.isDirectory) FileKind.FOLDER else Kinds.ofExt(e.name.substringAfterLast('.', "").lowercase())
                    ListItem(
                        leadingContent = { Icon(Kinds.icon(kind), null, tint = Kinds.color(kind)) },
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                "${e.originalPath}\n${formatDate(e.deletedAt)} · ${formatSize(e.size)}",
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.restore(e) }) { Icon(Icons.Filled.Restore, "Restaurar") }
                                IconButton(onClick = { vm.deleteForever(e) }) {
                                    Icon(Icons.Filled.DeleteForever, "Eliminar definitivamente", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Vaciar papelera") },
            text = { Text("Se eliminarán definitivamente ${vm.trash.size} elemento(s). No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("Vaciar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancelar") } },
        )
    }
}
