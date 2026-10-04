package com.omaritoinforma.oiarchivos.ui.screens

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
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.PathUtil

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var root by remember { mutableStateOf(PathUtil.internalRoot + "/Download") }
    val state by ShareService.state.collectAsState()
    val error by ShareService.error.collectAsState()
    var port by remember { mutableStateOf(vm.ftpPort.value.takeIf { it != 0 }?.toString().orEmpty()) }
    val portNumber = port.toIntOrNull()
    val portInvalid = port.isNotBlank() && (portNumber == null || portNumber !in 1024..65535)
    ToolPage("Compartir por red", vm) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Conecta el teléfono y la computadora a la misma red. Solo se comparte la carpeta que elijas.")
                OutlinedTextField(
                    root,
                    { root = it },
                    label = { Text("Carpeta a compartir") },
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
                        label = { Text("Puerto FTP (vacío: automático)") },
                        supportingText = {
                            Text(
                                if (portInvalid) "Un número de 1024 a 65535"
                                else "Si lo fijas, el PC puede guardar la conexión")
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
                        label = { Text("Contraseña FTP fija (opcional)") },
                        supportingText = {
                            Text(
                                if (passwordInvalid) "De 8 a 64 caracteres, sin espacios"
                                else "Vacía: se genera una nueva en cada inicio. El usuario es «oi»")
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
                            Text("Detener el servidor al salir de la app", Modifier.weight(1f))
                            Switch(vm.ftpStopOnExit.value, null)
                        }
                    Text("Codificación de los nombres en FTP", style = MaterialTheme.typography.labelLarge)
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
                                runCatching { ShareService.start(ctx, root, "HTTP") }
                                    .onFailure { vm.toast(it.message.orEmpty()) }
                            }) {
                                Text("Navegador / Wi-Fi")
                            }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !portInvalid && ShareService.ftpPasswordValid(vm.ftpPassword.value),
                            onClick = {
                                runCatching { ShareService.start(ctx, root, "FTP") }
                                    .onFailure { vm.toast(it.message.orEmpty()) }
                            }) {
                                Text("Servidor FTP")
                            }
                    }
                }
                state?.let { s ->
                    SelectionContainer {
                        Text(
                            "Dirección: ${s.url}\nUsuario: ${s.user}\nContraseña: ${s.password}\nCarpeta: ${s.root}")
                    }
                    TextButton(
                        onClick = {
                            Opener.copyText(
                                ctx, "${s.url}\nUsuario: ${s.user}\nContraseña: ${s.password}")
                        }) {
                            Text("Copiar datos")
                        }
                    Button(onClick = { ShareService.stop(ctx) }) { Text("Detener servidor") }
                }
                Text(
                    "HTTP y FTP de esta pantalla no cifran el tráfico. Úsalos en una red de confianza. Se genera una contraseña nueva en cada inicio.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
}
