@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.BuildConfig
import com.omaritoinforma.oiarchivos.data.GestureAction
import com.omaritoinforma.oiarchivos.data.ThemeMode
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.components.SectionTitle

@Composable
fun SettingsScreen(vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SwitchRow(
                    "Mostrar archivos ocultos",
                    "Archivos y carpetas que empiezan con punto",
                    vm.showHidden) {
                        vm.toggleHidden()
                    }
            }
            item {
                SwitchRow(
                    "Usar la papelera",
                    "Al eliminar, mover a la papelera para poder restaurar",
                    vm.useTrash) {
                        vm.updateUseTrash(it)
                    }
            }
            item {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    Text("Tamaño de las celdas: ${vm.gridSize}")
                    androidx.compose.material3.Slider(
                        value = vm.gridSize.toFloat(),
                        onValueChange = { vm.updateGridSize(it.toInt()) },
                        valueRange = 72f..160f,
                        steps = 10)
                }
            }
            item {
                SectionTitle("Gestos del explorador", Modifier.padding(start = 16.dp, top = 16.dp))
            }
            item {
                GestureRow("Deslizar a la izquierda", vm.swipeLeft) { vm.updateGesture(true, it) }
            }
            item {
                GestureRow("Deslizar a la derecha", vm.swipeRight) { vm.updateGesture(false, it) }
            }
            item {
                Text(
                    "Cuando actives un gesto, abre el menú lateral con su botón.",
                    Modifier.padding(16.dp))
            }
            item { SectionTitle("Tema", Modifier.padding(start = 16.dp, top = 16.dp)) }
            items(ThemeMode.entries.toList()) { m ->
                ListItem(
                    headlineContent = { Text(m.label) },
                    leadingContent = {
                        RadioButton(selected = vm.themeMode == m, onClick = { vm.updateTheme(m) })
                    },
                    modifier = Modifier.clickable { vm.updateTheme(m) },
                )
            }
            item { SectionTitle("Acerca de", Modifier.padding(start = 16.dp, top = 16.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("OI Archivos ${BuildConfig.VERSION_NAME}") },
                    supportingContent = {
                        Text(
                            "Uso personal. Sin anuncios ni rastreo. La red se usa para las conexiones y transferencias que activas.")
                    },
                )
            }
        }
    }
}

@Composable
private fun GestureRow(title: String, action: GestureAction, onChange: (GestureAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(action.label) },
            modifier = Modifier.clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GestureAction.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onChange(choice)
                        expanded = false
                    })
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
