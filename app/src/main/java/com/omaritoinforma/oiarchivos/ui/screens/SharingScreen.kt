package com.omaritoinforma.oiarchivos.ui.screens

import android.content.pm.PackageManager
import android.os.Build

import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.FtpEncoding
import com.omaritoinforma.oiarchivos.data.ShareService
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.FolderActions
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.data.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var root by remember { mutableStateOf(vm.ftpShareRoot.value) }
    val state by ShareService.state.collectAsState()
    val error by ShareService.error.collectAsState()
    var port by remember { mutableStateOf(vm.ftpPort.value.takeIf { it != 0 }?.toString().orEmpty()) }
    val portNumber = port.toIntOrNull()
    val portInvalid = port.isNotBlank() && (portNumber == null || portNumber !in 1024..65535)
    val bluetoothPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) runCatching { ShareService.start(ctx, root, "BLUETOOTH") }.onFailure { vm.toast(it.message.orEmpty()) }
                else vm.toast(tr("Concede el permiso de dispositivos cercanos para usar Bluetooth"))
            }
    ToolPage(tr("Compartir por red"), vm) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    tr("Conecta el teléfono y la computadora a la misma red. Solo se comparte la carpeta que elijas."))
                OutlinedTextField(
                    root,
                    { root = it },
                    label = { Text(tr("Carpeta a compartir")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state == null)
                if (state == null) {
                    // Opciones del servidor FTP, como en ES: puerto fijo y codificación de los nombres.
                    OutlinedTextField(
                        port,
                        {
                            port = it.filter(Char::isDigit).take(5)
                            val n = port.toIntOrNull()
                            vm.ftpPort.value = if (n != null && n in 1024..65535) n else 0
                        },
                        label = { Text(tr("Puerto FTP (vacío: automático)")) },
                        supportingText = {
                            Text(
                                if (portInvalid) tr("Un número de 1024 a 65535")
                                else tr("Si lo fijas, el PC puede guardar la conexión"))
                        },
                        isError = portInvalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    var password by remember { mutableStateOf(vm.ftpPassword.value) }
                    val passwordInvalid = !ShareService.ftpPasswordValid(password)
                    OutlinedTextField(
                        password,
                        {
                            password = it
                            if (ShareService.ftpPasswordValid(it)) vm.ftpPassword.value = it
                        },
                        label = { Text(tr("Contraseña FTP fija (opcional)")) },
                        supportingText = {
                            Text(
                                if (passwordInvalid) tr("De 8 a 64 caracteres, sin espacios")
                                else tr("Vacía: se genera una nueva en cada inicio. El usuario es «oi»"))
                        },
                        isError = passwordInvalid,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                vm.ftpStopOnExit.value,
                                role = Role.Switch,
                                onValueChange = { vm.ftpStopOnExit.value = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr("Detener el servidor al salir de la app"), Modifier.weight(1f))
                            Switch(vm.ftpStopOnExit.value, null)
                        }
                    Text(tr("Codificación de los nombres en FTP"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FtpEncoding.entries.forEach { option ->
                            FilterChip(
                                selected = vm.ftpEncoding.value == option,
                                onClick = { vm.ftpEncoding.value = option },
                                label = { Text(option.label) })
                        }
                    }
                    Row {
                        Button(
                            onClick = {
                                vm.ftpShareRoot.value = root
                                runCatching { ShareService.start(ctx, root, "HTTP") }
                                    .onFailure { vm.toast(it.message.orEmpty()) }
                            }) {
                                Text(tr("Navegador / Wi-Fi"))
                            }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !portInvalid && ShareService.ftpPasswordValid(vm.ftpPassword.value),
                            onClick = {
                                vm.ftpShareRoot.value = root
                                runCatching { ShareService.start(ctx, root, "FTP") }
                                    .onFailure { vm.toast(it.message.orEmpty()) }
                            }) {
                                Text(tr("Servidor FTP"))
                            }
                    }
                    // Servidor OBEX FTP: los equipos emparejados exploran la carpeta por Bluetooth.
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                vm.obexWritable.value,
                                role = Role.Switch,
                                onValueChange = { vm.obexWritable.value = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr("Por Bluetooth, permitir que el otro equipo suba, renombre y borre"), Modifier.weight(1f))
                            Switch(vm.obexWritable.value, null)
                        }
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 31 &&
                                ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                                bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            else {
                                vm.ftpShareRoot.value = root
                                runCatching { ShareService.start(ctx, root, "BLUETOOTH") }.onFailure { vm.toast(it.message.orEmpty()) }
                            }
                        }) {
                            Text(tr("Bluetooth (OBEX FTP)"))
                        }
                    OutlinedButton(
                        onClick = {
                            vm.ftpShareRoot.value = root
                            runCatching { FolderActions.pinFtpServer(ctx) }
                                .onFailure { vm.toast(it.message ?: tr("No se pudo solicitar el acceso directo")) }
                        }) {
                            Text(tr("Acceso directo del servidor FTP"))
                        }
                }
                state?.takeIf { it.mode == "BLUETOOTH" }?.let { s ->
                    Text(
                        tr("Compartiendo «{0}» por Bluetooth como «{1}». Los equipos emparejados la ven en su explorador Bluetooth (OBEX FTP).", s.root, s.url.removePrefix("bluetooth://")))
                    Text(if (vm.obexWritable.value) tr("Pueden subir, renombrar y borrar.") else tr("Solo pueden ver y descargar."))
                    Button(onClick = { ShareService.stop(ctx) }) { Text(tr("Detener servidor")) }
                }
                state?.takeIf { it.mode != "BLUETOOTH" }?.let { s ->
                    SelectionContainer {
                        Text(
                            tr("Dirección: {0}\nUsuario: {1}\nContraseña: {2}\nCarpeta: {3}", s.url, s.user, s.password, s.root))
                    }
                    TextButton(
                        onClick = {
                            Opener.copyText(
                                ctx, tr("{0}\nUsuario: {1}\nContraseña: {2}", s.url, s.user, s.password))
                        }) {
                            Text(tr("Copiar datos"))
                        }
                    Button(onClick = { ShareService.stop(ctx) }) { Text(tr("Detener servidor")) }
                }
                Text(
                    tr("HTTP y FTP de esta pantalla no cifran el tráfico. Úsalos en una red de confianza. Se genera una contraseña nueva en cada inicio."))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
}
