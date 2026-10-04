package com.omaritoinforma.oiarchivos.ui.screens

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.LanScanner
import com.omaritoinforma.oiarchivos.data.Nearby
import com.omaritoinforma.oiarchivos.data.NearbyReceiver
import com.omaritoinforma.oiarchivos.data.OperationResult
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class IncomingOffer(val offer: Nearby.Offer, val answer: CompletableFuture<Boolean>)

/** Enviar a otro teléfono y recibir de él por la misma Wi-Fi (el «Sender» de ES). */
@Composable
fun NearbyScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val myName = remember {
        runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
    val destination = remember { File(PathUtil.internalRoot, "Download/OI Archivos/Recibidos") }
    var receiving by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf<IncomingOffer?>(null) }
    val received = remember { mutableStateListOf<String>() }
    var receiveError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(receiving) {
        var server: NearbyReceiver? = null
        if (receiving) {
            val main = android.os.Handler(ctx.mainLooper)
            server =
                NearbyReceiver(
                    destination,
                    myName,
                    decide = { offer ->
                        val pending = IncomingOffer(offer, CompletableFuture())
                        main.post { incoming = pending }
                        // Las ofertas sin respuesta se rechazan a los dos minutos.
                        runCatching { pending.answer.get(120, TimeUnit.SECONDS) }
                            .getOrDefault(false)
                            .also { main.post { if (incoming === pending) incoming = null } }
                    },
                    onReceived = { file ->
                        main.post { received += file.name }
                        com.omaritoinforma.oiarchivos.util.Media.scan(ctx, listOf(file))
                    })
            runCatching { server.start(5000, true) }
                .onSuccess {
                    receiveError = null
                    address =
                        LanScanner.localNetworks().joinToString { it.first.hostAddress.orEmpty() }
                }
                .onFailure {
                    receiveError = "No se pudo empezar a recibir: ${it.message}"
                    receiving = false
                }
        }
        onDispose {
            incoming?.answer?.complete(false)
            server?.stop()
        }
    }

    val toSend = vm.nearbyFiles
    val peers = remember { mutableStateListOf<Nearby.Peer>() }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        peers.clear()
        val hosts =
            withContext(Dispatchers.IO) {
                LanScanner.localNetworks().flatMap { (a, p) -> LanScanner.subnetHosts(a, p) }.distinct()
            }
        val main = android.os.Handler(ctx.mainLooper)
        Nearby.discover(hosts, onProgress = { done, total -> progress = done.toFloat() / total }) {
            peer ->
            main.post { if (peers.none { p -> p.address == peer.address }) peers += peer }
        }
        searching = false
        searched = true
    }

    ToolPage("Enviar a otro teléfono", vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("Recibir", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (receiving) "Visible como «$myName» en $address. Deja esta pantalla abierta."
                    else "El otro teléfono debe estar en la misma red Wi-Fi y tener OI Archivos.",
                    Modifier.padding(vertical = 8.dp))
                receiveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { receiving = !receiving }) {
                    Text(if (receiving) "Dejar de recibir" else "Empezar a recibir")
                }
                if (received.isNotEmpty())
                    Text(
                        "Recibidos en Descargas/OI Archivos/Recibidos: ${received.joinToString()}",
                        Modifier.padding(top = 8.dp))
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Enviar", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (toSend.isEmpty())
                        "Selecciona archivos en el explorador y usa Más → Enviar a otro teléfono."
                    else
                        "${toSend.size} archivo(s), ${formatSize(toSend.sumOf { File(it).length() })}",
                    Modifier.padding(vertical = 8.dp))
                if (toSend.isNotEmpty())
                    Button(onClick = { searching = true }, enabled = !searching) {
                        Text("Buscar teléfonos")
                    }
                if (searching)
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (searched && !searching && peers.isEmpty())
                    Text(
                        "No se encontró ningún teléfono recibiendo. En el otro, abre esta pantalla y toca Empezar a recibir.",
                        Modifier.padding(top = 8.dp))
            }
            items(peers, key = { it.address }) { peer ->
                ListItem(
                    headlineContent = { Text(peer.name) },
                    supportingContent = { Text(peer.address) },
                    modifier =
                        Modifier.clickable {
                            val files = toSend.map(::File)
                            vm.runTask("Enviando a ${peer.name}") { report ->
                                if (Nearby.send(peer, myName, files, report)) {
                                    withContext(Dispatchers.Main) { vm.nearbyFiles = emptyList() }
                                    OperationResult("Enviado a ${peer.name}")
                                } else OperationResult("${peer.name} rechazó el envío")
                            }
                        })
            }
        }
    }

    incoming?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Archivos entrantes") },
            text = {
                Text(
                    "«${pending.offer.from}» quiere enviarte ${pending.offer.files.size} archivo(s), " +
                        "${formatSize(pending.offer.bytes)}:\n" +
                        pending.offer.files.take(5).joinToString("\n") { it.name } +
                        if (pending.offer.files.size > 5) "\n…" else "")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.answer.complete(true)
                        incoming = null
                    }) {
                        Text("Aceptar")
                    }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pending.answer.complete(false)
                        incoming = null
                    }) {
                        Text("Rechazar")
                    }
            })
    }
}
