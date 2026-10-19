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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.omaritoinforma.oiarchivos.data.BridgeConfig
import com.omaritoinforma.oiarchivos.data.RemoteBridgeService
import com.omaritoinforma.oiarchivos.data.BridgeStatus
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
    val context = LocalContext.current
    val bridge = remember { BridgeConfig(context) }
    var endpoint by remember { mutableStateOf(bridge.endpoint) }
    var newToken by remember { mutableStateOf("") }
    var writeAccess by remember { mutableStateOf(bridge.allowChanges) }
    var trashAccess by remember { mutableStateOf(bridge.allowTrash) }
    var remoteAccess by remember { mutableStateOf(bridge.allowRemote) }
    var deleteAccess by remember { mutableStateOf(bridge.allowDelete) }
    var debugAccess by remember { mutableStateOf(bridge.allowDebug) }
    var bridgeActive by remember { mutableStateOf(BridgeStatus.running) }
    var feedback by remember { mutableStateOf("") }
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
            item { SectionTitle("Conector remoto de archivos", Modifier.padding(start = 16.dp, top = 16.dp)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    Button(onClick={context.startActivity(android.content.Intent(context,com.omaritoinforma.oiarchivos.RootlessDebugActivity::class.java))}){Text("Abrir depurador sin root")}
                    SwitchRow("Permitir depuración remota", "Diagnósticos y herramientas ADB; requiere autorización adicional de Shizuku en modo sin root",debugAccess) {
                        debugAccess=it;bridge.allowDebug=it
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Permite conectar asistentes y herramientas compatibles mediante un servidor seguro. Únicamente archivos a los que Android da acceso; nunca root.")
                    Button(onClick = {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://test.omaritoinforma.com/oi-android-bridge/")))
                    }) { Text("Abrir panel de emparejamiento") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Servidor HTTPS del puente") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newToken,
                        onValueChange = { newToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (bridge.token.isNotEmpty()) "Cambiar token de emparejamiento" else "Token de emparejamiento") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        bridge.endpoint = endpoint
                        if (newToken.isNotBlank()) { bridge.token = newToken.trim(); newToken = "" }
                        feedback = if (bridge.valid()) "Configuración guardada" else "Se requiere HTTPS y token de 32 caracteres o más"
                    }) { Text("Guardar conexión") }
                    if (feedback.isNotBlank()) Text(feedback)
                    Button(onClick = {
                        writeAccess = true; trashAccess = true; remoteAccess = true; deleteAccess = true
                        bridge.allowChanges = true; bridge.allowTrash = true
                        bridge.allowRemote = true; bridge.allowDelete = true
                        feedback = "Gestión de archivos habilitada. Pulsa Conectar para abrir el enlace."
                    }) { Text("Permitir gestión completa de archivos") }
                }
            }
            item {
                SwitchRow("Permitir cambios de archivos",
                    "Crear, copiar, mover y renombrar; desactivado por defecto",
                    writeAccess) {
                    writeAccess = it
                    bridge.allowChanges = it
                    if (!it) {
                        trashAccess = false; bridge.allowTrash = false
                        deleteAccess = false; bridge.allowDelete = false
                    }
                }
            }
            item {
                SwitchRow("Permitir enviar a papelera",
                    "Separado de escritura. No autoriza borrar definitivamente.",
                    trashAccess) {
                    trashAccess = it && writeAccess
                    bridge.allowTrash = trashAccess
                }
            }
            item {
                SwitchRow("Usar conexiones guardadas", "SFTP, FTP, SMB y nubes ya configuradas; las contraseñas permanecen en el móvil",remoteAccess) {
                    remoteAccess = it; bridge.allowRemote = it
                }
            }
            item {
                SwitchRow("Permitir borrado definitivo", "Archivos individuales y carpetas vacías; requiere permitir cambios",deleteAccess) {
                    deleteAccess = it && writeAccess; bridge.allowDelete = deleteAccess
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(if (bridgeActive) "Conector activo: " + BridgeStatus.message else "Conector detenido")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (bridgeActive) {
                            RemoteBridgeService.stop(context)
                            bridgeActive = false
                        } else {
                            bridge.endpoint = endpoint
                            if (newToken.isNotBlank()) { bridge.token = newToken.trim(); newToken = "" }
                            if (bridge.valid()) {
                                RemoteBridgeService.start(context)
                                bridgeActive = true
                                feedback = "Conexión iniciada; comprueba el aviso permanente"
                            } else feedback = "Configura la URL HTTPS y el token"
                        }
                    }) { Text(if (bridgeActive) "Desconectar" else "Conectar") }
                }
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
