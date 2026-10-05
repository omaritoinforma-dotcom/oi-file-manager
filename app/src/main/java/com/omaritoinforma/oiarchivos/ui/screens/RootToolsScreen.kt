package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
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
    ToolPage(tr("Explorador root"), vm) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    tr("Esta función necesita un teléfono con root y su/Magisk. Al abrirla, el gestor de root te pedirá autorización. Los cambios en archivos del sistema pueden afectar Android."))
                OutlinedTextField(
                    path,
                    { path = it },
                    label = { Text(tr("Ruta")) },
                    modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        vm.runTask(tr("Solicitando root")) {
                            RootFs().use { it.list(path) }
                            val store = ConnectionStore(ctx)
                            val c =
                                Connection(
                                    "system-root",
                                    tr("Raíz con root"),
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
                        Text(tr("Autorizar y explorar"))
                    }
                OutlinedTextField(
                    mode, { mode = it }, label = { Text(tr("Permisos octales, por ejemplo 644")) })
                TextButton(
                    onClick = {
                        vm.runTask(tr("Cambiando permisos")) {
                            RootFs().use { it.chmod(path, mode) }
                            OperationResult(tr("Permisos actualizados"))
                        }
                    }) {
                        Text(tr("Aplicar permisos a esta ruta"))
                    }
                Text(
                    tr("En Android 11 o superior, el permiso de todos los archivos no abre los datos privados de otras apps. Esta pantalla usa únicamente el acceso root concedido por el teléfono."))
                RootSystemTools()
            }
    }
}

/** Montar el sistema, editar el hosts y devolver apps del sistema quitadas. */
@Composable
private fun RootSystemTools() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mount by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var removed by remember { mutableStateOf<List<String>>(emptyList()) }
    var hostsReplaced by remember { mutableStateOf(false) }
    // El montaje puede no verse desde el espacio de montajes de la app: se recuerda lo guardado aquí.
    var savedHere by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            mount = runCatching { RootSystem.mountOf("/system") }.getOrNull()
            removed = runCatching { RootSystem.removedSystemApps(ctx) }.getOrDefault(emptyList())
            hostsReplaced = RootSystem.hostsReplaced() || savedHere
        }
    }
    fun act(done: String, action: () -> Unit) {
        busy = true
        message = null
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            busy = false
            result.onSuccess { message = done }.onFailure { error = it.message ?: tr("No se pudo completar") }
            round++
        }
    }
    HorizontalDivider()
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Text(tr("Partición del sistema"), fontWeight = FontWeight.Bold)
    val m = mount
    if (m != null)
        Text(
            if (m.second) tr("{0} está montada en solo lectura", m.first)
            else tr("{0} está montada en lectura y escritura", m.first))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && m?.second != false, onClick = {
            act(tr("Sistema montado en lectura y escritura")) { RootSystem.remountSystem(writable = true) }
        }) { Text(tr("Lectura y escritura")) }
        OutlinedButton(enabled = !busy && m?.second == false, onClick = {
            act(tr("Sistema montado en solo lectura")) { RootSystem.remountSystem(writable = false) }
        }) { Text(tr("Solo lectura")) }
    }
    Text(
        tr("En muchos Android actuales la partición del sistema no se puede escribir aunque haya root (verificación del arranque); el hosts se cambia entonces montando una copia encima."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text(tr("Archivo hosts"), fontWeight = FontWeight.Bold)
    val text = hosts
    if (text == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                hosts = runCatching { RootSystem.readHosts() }.getOrElse { "127.0.0.1 localhost\n::1 ip6-localhost\n" }
            }) { Text(tr("Editar el archivo hosts")) }
            if (hostsReplaced)
                OutlinedButton(enabled = !busy, onClick = {
                    savedHere = false
                    act(tr("Se volvió al hosts original")) { RootSystem.restoreHosts() }
                }) {
                    Text(tr("Volver al original"))
                }
        }
    } else {
        OutlinedTextField(
            text,
            { hosts = it },
            label = { Text(RootSystem.HOSTS) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            minLines = 6,
            modifier = Modifier.fillMaxWidth())
        val problems = remember(text) { RootSystem.hostsErrors(text) }
        problems.take(3).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && problems.isEmpty(), onClick = {
                act(tr("Hosts guardado")) {
                    RootSystem.saveHosts(text)
                    savedHere = true
                }
                hosts = null
            }) { Text(tr("Guardar hosts")) }
            OutlinedButton(onClick = { hosts = null }) { Text(tr("Cancelar")) }
        }
        Text(
            tr("Si el sistema no se puede escribir, la copia se monta encima del original y dura hasta reiniciar."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Text(tr("Apps del sistema quitadas"), fontWeight = FontWeight.Bold)
    Text(
        tr("Se quitan en Apps → Mostrar apps del sistema → ⋮ → Quitar app del sistema (root). El APK sigue en el sistema y se puede devolver aquí."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (removed.isEmpty()) Text(tr("Ninguna"))
    removed.forEach { pkg ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(pkg, Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { act(tr("«{0}» devuelta", pkg)) { RootSystem.restoreSystemApp(pkg) } }) {
                Text(tr("Devolver"))
            }
        }
    }
}
