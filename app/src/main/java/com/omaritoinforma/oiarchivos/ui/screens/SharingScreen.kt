package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.ShareService
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.PathUtil

@Composable
fun SharingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var root by remember { mutableStateOf(PathUtil.internalRoot + "/Download") }
    val state by ShareService.state.collectAsState()
    val error by ShareService.error.collectAsState()
    ToolPage("Compartir por red", vm) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
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
