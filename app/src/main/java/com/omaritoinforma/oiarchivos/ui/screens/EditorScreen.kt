@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDITOR_BYTES = 8L * 1024 * 1024

/** Editor de texto UTF-8 integrado. */
@Composable
fun EditorScreen(vm: MainViewModel, path: String) {
    val file = remember(path) { File(path) }
    var text by remember(path) { mutableStateOf<String?>(null) }
    var original by remember(path) { mutableStateOf("") }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > MAX_EDITOR_BYTES) throw IllegalStateException("El archivo pesa más de 8 MB; ábrelo con otra app.")
                file.readText(Charsets.UTF_8)
            }
        }
        result.onSuccess {
            text = it
            original = it
        }.onFailure { loadError = it.message ?: "No se pudo leer el archivo" }
    }

    val current = text
    val modified = current != null && current != original

    fun save(then: () -> Unit = {}) {
        val content = text ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { file.writeText(content, Charsets.UTF_8) }.isSuccess }
            if (ok) {
                original = content
                vm.toast("Guardado")
                then()
            } else {
                vm.toast("No se pudo guardar (¿carpeta de solo lectura?)")
            }
        }
    }

    BackHandler(enabled = modified) { confirmExit = true }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (modified) confirmExit = true else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                title = {
                    Column {
                        Text(file.name + if (modified) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            file.parent ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = modified) { Icon(Icons.Filled.Save, "Guardar") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                loadError != null -> Text(
                    loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> BasicTextField(
                    value = current,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Quieres guardar los cambios antes de salir?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    save { vm.back() }
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmExit = false
                    original = text ?: ""
                    vm.back()
                }) { Text("Descartar") }
            },
        )
    }
}
