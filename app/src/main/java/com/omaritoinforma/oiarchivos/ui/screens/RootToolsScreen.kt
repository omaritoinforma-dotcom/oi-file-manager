package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RootToolsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("/") }
    var mode by remember { mutableStateOf("644") }
    ToolPage("Explorador root", vm) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Esta función necesita un teléfono con root y su/Magisk. Al abrirla, el gestor de root te pedirá autorización. Los cambios en archivos del sistema pueden afectar Android.")
                OutlinedTextField(
                    path,
                    { path = it },
                    label = { Text("Ruta") },
                    modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        vm.runTask("Solicitando root") {
                            RootFs().use { it.list(path) }
                            val store = ConnectionStore(ctx)
                            val c =
                                Connection(
                                    "system-root",
                                    "Raíz con root",
                                    Protocol.ROOT,
                                    "",
                                    1,
                                    "",
                                    "",
                                    path)
                            store.save(store.load().filter { it.id != c.id } + c)
                            withContext(Dispatchers.Main) { vm.goTo(Screen.Remote(c.id)) }
                            OperationResult(null)
                        }
                    }) {
                        Text("Autorizar y explorar")
                    }
                OutlinedTextField(
                    mode, { mode = it }, label = { Text("Permisos octales, por ejemplo 644") })
                TextButton(
                    onClick = {
                        vm.runTask("Cambiando permisos") {
                            RootFs().use { it.chmod(path, mode) }
                            OperationResult("Permisos actualizados")
                        }
                    }) {
                        Text("Aplicar permisos a esta ruta")
                    }
                Text(
                    "En Android 11 o superior, el permiso de todos los archivos no abre los datos privados de otras apps. Esta pantalla usa únicamente el acceso root concedido por el teléfono.")
            }
    }
}
