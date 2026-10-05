package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.Adb
import com.omaritoinforma.oiarchivos.data.AdbTvSession
import com.omaritoinforma.oiarchivos.data.Prefs
import com.omaritoinforma.oiarchivos.data.tr
import com.omaritoinforma.oiarchivos.ui.MainViewModel

/** «Android TV por ADB»: instalar APK, abrir y desinstalar apps y usar el teléfono como mando. */
@Composable
fun AdbTvScreen(vm: MainViewModel) {
    val session by AdbTvSession.state.collectAsState()
    ToolPage(tr("Android TV por ADB"), vm) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val current = session
            if (current?.device != null) Connected(vm, current) else ConnectForm(current)
        }
    }
}

@Composable
private fun ConnectForm(state: AdbTvSession.State?) {
    val ctx = LocalContext.current
    val recent = remember { Prefs(ctx).adbTvAddresses }
    var address by remember { mutableStateOf(state?.address ?: recent.firstOrNull().orEmpty()) }
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            tr("En la TV: Ajustes → Preferencias del dispositivo → Información, pulsa 7 veces «Compilación» y luego activa en Opciones para desarrolladores la depuración USB o por red. La TV debe estar en la misma Wi-Fi."),
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            address,
            { address = it },
            label = { Text(tr("IP de la TV (puerto 5555 si no se indica)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (recent.isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.take(3).forEach { r -> AssistChip(onClick = { address = r }, label = { Text(r) }) }
            }
        val busy = state?.busy
        Button(
            onClick = { AdbTvSession.connect(ctx, address) },
            enabled = address.isNotBlank() && busy == null) {
                Text(tr("Conectar"))
            }
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (state.waitingApproval) tr("Acepta «¿Permitir la depuración?» en la pantalla de la TV (marca «Permitir siempre» para no volver a preguntar).")
                else busy)
        }
        state?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Connected(vm: MainViewModel, state: AdbTvSession.State) {
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    val pending = vm.adbApks
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Conectado a «{0}»", state.device!!.name), style = MaterialTheme.typography.titleMedium)
                Text(state.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.busy?.let {
                    Text(it)
                    val p = state.progress
                    if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (pending.isNotEmpty())
                    Button(
                        onClick = {
                            AdbTvSession.install(pending.map { java.io.File(it) })
                            vm.adbApks = emptyList()
                        },
                        enabled = state.busy == null) {
                            Text(
                                if (pending.size == 1) tr("Instalar «{0}» en la TV", java.io.File(pending[0]).name)
                                else tr("Instalar {0} APK en la TV", pending.size))
                        }
                else
                    Text(
                        tr("Para instalar, elige uno o varios APK en el explorador y usa Más → Instalar en Android TV."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = AdbTvSession::disconnect) { Text(tr("Desconectar")) }
            }
        }
        item { Remote() }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Apps instaladas ({0})", state.apps.size), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = AdbTvSession::refresh, enabled = state.busy == null) { Text(tr("Actualizar")) }
            }
        }
        items(state.apps, key = { it }) { pkg ->
            ListItem(
                headlineContent = { Text(pkg) },
                trailingContent = {
                    Row {
                        TextButton(onClick = { AdbTvSession.launch(pkg) }, enabled = state.busy == null) { Text(tr("Abrir")) }
                        TextButton(onClick = { confirmUninstall = pkg }, enabled = state.busy == null) { Text(tr("Desinstalar")) }
                    }
                })
        }
    }
    confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(tr("¿Desinstalar de la TV?")) },
            text = { Text(pkg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = null
                        AdbTvSession.uninstall(pkg)
                    }) {
                        Text(tr("Desinstalar"))
                    }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text(tr("Cancelar")) } })
    }
}

/** Mando a distancia: cruceta, Aceptar, Atrás, Inicio y volumen. */
@Composable
private fun Remote() {
    @Composable
    fun key(k: Adb.Key, modifier: Modifier = Modifier) =
        FilledTonalButton(onClick = { AdbTvSession.key(k) }, modifier = modifier) { Text(k.label, maxLines = 1) }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Mando"), fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            key(Adb.Key.UP)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(Adb.Key.LEFT)
                key(Adb.Key.OK)
                key(Adb.Key.RIGHT)
            }
            key(Adb.Key.DOWN)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(Adb.Key.BACK)
                key(Adb.Key.HOME)
                key(Adb.Key.MENU)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(Adb.Key.VOLUME_DOWN)
                key(Adb.Key.PLAY_PAUSE)
                key(Adb.Key.VOLUME_UP)
            }
            key(Adb.Key.POWER)
        }
}
