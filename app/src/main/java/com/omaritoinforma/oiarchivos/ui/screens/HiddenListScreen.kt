@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lista de ocultos: lo que se ocultó con «Ocultar»; con contraseña si «Proteger los archivos ocultos» está activado. */
@Composable
fun HiddenListScreen(vm: MainViewModel) {
    var files by remember { mutableStateOf<List<File>?>(null) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version) {
        files = withContext(Dispatchers.IO) { vm.hiddenList() }
    }
    ToolPage("Lista de ocultos", vm) { pad ->
        val list = files
        when {
            list == null -> Text("Leyendo…", Modifier.padding(pad).padding(16.dp))
            list.isEmpty() ->
                Text(
                    "No hay nada oculto. Selecciona archivos o carpetas y usa «Más» → «Ocultar».",
                    Modifier.padding(pad).padding(16.dp))
            else ->
                LazyColumn(Modifier.padding(pad)) {
                    items(list, key = { it.path }) { file ->
                        ListItem(
                            headlineContent = { Text(file.name.removePrefix(".")) },
                            supportingContent = { Text(file.parent.orEmpty()) },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        vm.unhideFiles(listOf(file))
                                        version++
                                    }) {
                                        Text("Mostrar")
                                    }
                            },
                            modifier =
                                Modifier.clickable {
                                    vm.openFile(file.path)
                                })
                    }
                }
        }
    }
}
