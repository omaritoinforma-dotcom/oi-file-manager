package com.omaritoinforma.oiarchivos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.util.PathUtil
import java.io.File

/** Elegir una carpeta navegando o escribiendo su ruta (el «DirChoosePreference» de ES). */
@Composable
fun FolderPickerDialog(
    title: String,
    start: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var path by remember {
        mutableStateOf(File(start).takeIf { it.isDirectory }?.absolutePath ?: PathUtil.internalRoot)
    }
    var typed by remember(path) { mutableStateOf(path) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    val folders =
        remember(path) {
            File(path)
                .listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
    val go = {
        val target = runCatching { File(typed.trim()).canonicalFile }.getOrNull()
        if (target != null && target.isDirectory) path = target.absolutePath
        else error = "No existe esa carpeta"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        error = null
                    },
                    label = { Text("Ruta") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    trailingIcon = {
                        IconButton(onClick = go) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Ir")
                        }
                    },
                    modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(
                        onClick = { File(path).parentFile?.let { path = it.absolutePath } },
                        enabled = File(path).parentFile != null) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = null)
                            Text("Subir")
                        }
                }
                if (folders.isEmpty())
                    Text(
                        "Sin subcarpetas",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(folders, key = { it.absolutePath }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.clickable { path = folder.absolutePath })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("Elegir esta carpeta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
