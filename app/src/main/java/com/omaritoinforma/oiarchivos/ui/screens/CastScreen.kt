package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.CastDiscovery
import com.omaritoinforma.oiarchivos.data.CastSession
import com.omaritoinforma.oiarchivos.data.CastV2
import com.omaritoinforma.oiarchivos.data.Dlna
import com.omaritoinforma.oiarchivos.data.Tv
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

/** «Enviar a la TV»: elegir un televisor DLNA o un Chromecast y controlar la reproducción. */
@Composable
fun CastScreen(vm: MainViewModel) {
    val session by CastSession.state.collectAsState()
    ToolPage(tr("Enviar a la TV"), vm) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val current = session
            if (current != null) CastControls(current) else CastPicker(vm)
        }
    }
}

@Composable
private fun CastControls(state: CastSession.State) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.title, style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                state.busy -> tr("Enviando a «{0}»…", state.renderer.name)
                state.playing -> tr("Reproduciendo en «{0}»", state.renderer.name)
                else -> tr("En pausa en «{0}»", state.renderer.name)
            })
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.duration > 0) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: state.position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { CastSession.seek(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..state.duration.toFloat())
        }
        Text("${clock(state.position)} / ${if (state.duration > 0) clock(state.duration) else "--:--"}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = CastSession::pauseOrResume, enabled = !state.busy && state.error == null) {
                Text(if (state.playing) tr("Pausa") else tr("Reanudar"))
            }
            OutlinedButton(onClick = CastSession::stop) { Text(tr("Detener")) }
        }
        Text(
            tr("La TV lee el archivo directamente de este teléfono; mantén la app abierta o en segundo plano mientras se reproduce."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CastPicker(vm: MainViewModel) {
    val source = vm.castSource
    val found = remember { mutableStateListOf<Tv>() }
    val ctx = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(round) {
        searching = true
        fun add(tv: Tv) {
            scope.launch { if (found.none { it.key == tv.key }) found += tv }
        }
        // Televisores DLNA (SSDP) y Chromecast (mDNS) a la vez.
        coroutineScope {
            launch(Dispatchers.IO) { runCatching { Dlna.search { add(it) } } }
            launch { runCatching { CastDiscovery.search(ctx) { add(it) } } }
        }
        searching = false
    }
    LazyColumn {
        item {
            Text(
                if (source == null)
                    tr("Elige una foto, música o vídeo en el explorador y usa Más → Enviar a la TV.")
                else tr("Enviar «{0}» a:", source.name),
                Modifier.padding(16.dp))
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            else if (found.isEmpty())
                Text(
                    tr("No se encontró ninguna TV. Debe estar encendida y en la misma Wi-Fi; también puedes añadirla por su dirección."),
                    Modifier.padding(16.dp))
        }
        items(found, key = { it.key }) { renderer ->
            ListItem(
                headlineContent = { Text(renderer.name) },
                supportingContent = {
                    Text(if (renderer is CastV2.Device) tr("Chromecast · {0}", renderer.host) else tr("DLNA · {0}", renderer.host))
                },
                modifier =
                    Modifier.clickable(enabled = source != null) {
                        source?.let { CastSession.start(renderer, it) }
                    })
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { round++ }, enabled = !searching) { Text(tr("Buscar otra vez")) }
                OutlinedButton(onClick = { adding = true }) { Text(tr("Añadir por dirección")) }
            }
        }
    }
    if (adding)
        AddRendererDialog(
            onDismiss = { adding = false },
            onFound = { renderer ->
                adding = false
                if (found.none { it.key == renderer.key }) found += renderer
            })
}

@Composable
private fun AddRendererDialog(onDismiss: () -> Unit, onFound: (Tv) -> Unit) {
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Añadir TV")) },
        text = {
            Column {
                Text(tr("IP de la TV o del Chromecast (por ejemplo 192.168.1.50) o la URL de la descripción DLNA."))
                OutlinedTextField(
                    address,
                    {
                        address = it
                        error = null
                    },
                    label = { Text(tr("Dirección de la TV")) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val renderer =
                            withContext(Dispatchers.IO) {
                                runCatching { Dlna.find(address) ?: CastV2.find(address) }
                            }
                        busy = false
                        renderer
                            .onSuccess { if (it != null) onFound(it) else error = tr("No respondió ninguna TV") }
                            .onFailure { error = it.message ?: tr("No se pudo conectar") }
                    }
                }) {
                    Text(tr("Buscar"))
                }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } })
}

private fun clock(seconds: Long) =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
