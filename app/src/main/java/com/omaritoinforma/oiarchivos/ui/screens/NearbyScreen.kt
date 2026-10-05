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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.LanScanner
import com.omaritoinforma.oiarchivos.data.Nearby
import com.omaritoinforma.oiarchivos.data.NearbyLink
import com.omaritoinforma.oiarchivos.data.NearbyReceiver
import com.omaritoinforma.oiarchivos.data.OperationResult
import com.omaritoinforma.oiarchivos.data.WifiDirectLink
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

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
    val destination = remember { File(vm.downloadFolder.value, "Recibidos") }
    var receiving by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf<IncomingOffer?>(null) }
    val received = remember { mutableStateListOf<String>() }
    var receiveError by remember { mutableStateOf<String?>(null) }
    // Punto de acceso propio para recibir sin router (null: se recibe por la Wi-Fi a la que ya está conectado).
    var hotspot by remember { mutableStateOf<WifiDirectLink.Hotspot?>(null) }
    var startingHotspot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun startHotspot() {
        startingHotspot = true
        receiveError = null
        scope.launch {
            runCatching { WifiDirectLink.startHotspot(ctx) }
                .onSuccess {
                    hotspot = it
                    receiving = true
                }
                .onFailure { receiveError = it.message ?: tr("No se pudo crear el punto de acceso") }
            startingHotspot = false
        }
    }
    val hotspotPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { _ ->
                if (WifiDirectLink.hasHotspotPermission(ctx)) startHotspot() else receiveError = tr("Sin ese permiso Android no deja crear el punto de acceso")
            }
    DisposableEffect(hotspot) {
        val current = hotspot
        onDispose { current?.close() }
    }

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
                    receiveError = tr("No se pudo empezar a recibir: {0}", it.message)
                    receiving = false
                }
        }
        onDispose {
            incoming?.answer?.complete(false)
            server?.stop()
            if (!receiving) hotspot = null
        }
    }

    val toSend = vm.nearbyFiles
    fun sendTo(peer: Nearby.Peer) {
        val files = toSend.map(::File)
        vm.runTask(tr("Enviando a {0}", peer.name)) { report ->
            val sent =
                peer.wifi?.let { wifi -> WifiDirectLink.withNetwork(ctx, wifi, peer.wifiKey) { Nearby.send(peer, myName, files, report) } }
                    ?: Nearby.send(peer, myName, files, report)
            if (sent) {
                withContext(Dispatchers.Main) { vm.nearbyFiles = emptyList() }
                OperationResult(tr("Enviado a {0}", peer.name))
            } else OperationResult(tr("{0} rechazó el envío", peer.name))
        }
    }
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

    ToolPage(tr("Enviar a otro teléfono"), vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("Recibir"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (receiving) tr("Visible como «{0}» en {1}. Deja esta pantalla abierta.", myName, address)
                    else tr("El otro teléfono debe estar en la misma red Wi-Fi y tener OI Archivos."),
                    Modifier.padding(vertical = 8.dp))
                receiveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { receiving = !receiving }, enabled = !startingHotspot) {
                    Text(if (receiving) tr("Dejar de recibir") else tr("Empezar a recibir"))
                }
                if (!receiving)
                    OutlinedButton(
                        onClick = {
                            if (WifiDirectLink.hasHotspotPermission(ctx)) startHotspot()
                            else hotspotPermission.launch(WifiDirectLink.hotspotPermissions)
                        },
                        enabled = !startingHotspot,
                        modifier = Modifier.padding(top = 8.dp)) {
                            Text(tr("Recibir con punto de acceso (sin router)"))
                        }
                if (startingHotspot) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                val ap = hotspot
                if (receiving && ap != null)
                    Text(
                        tr("Punto de acceso «{0}» creado. El otro teléfono se une solo al leer el código QR.", ap.ssid),
                        Modifier.padding(top = 8.dp))
                if (receiving && (address.isNotEmpty() || ap != null)) {
                    // Código QR: el otro teléfono lo lee con su cámara y el enlace abre OI Archivos para enviar.
                    val link =
                        if (ap != null) NearbyLink.build(ap.address, Nearby.PORT, myName, ap.ssid, ap.key)
                        else NearbyLink.build(address.substringBefore(","), Nearby.PORT, myName)
                    Text(
                        tr("O que el otro teléfono lea este código QR con su cámara:"),
                        Modifier.padding(top = 12.dp))
                    QrImage(link, Modifier.padding(vertical = 8.dp).size(220.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(link, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (received.isNotEmpty())
                    Text(
                        tr("Recibidos en {0}: {1}", destination.absolutePath, received.joinToString()),
                        Modifier.padding(top = 8.dp))
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text(tr("Enviar"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (toSend.isEmpty())
                        tr("Selecciona archivos en el explorador y usa Más → Enviar a otro teléfono.")
                    else
                        tr("{0} archivo(s), {1}", toSend.size, formatSize(toSend.sumOf { File(it).length() })),
                    Modifier.padding(vertical = 8.dp))
                if (toSend.isNotEmpty())
                    Button(onClick = { searching = true }, enabled = !searching) {
                        Text(tr("Buscar teléfonos"))
                    }
                if (searching)
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (searched && !searching && peers.isEmpty())
                    Text(
                        tr("No se encontró ningún teléfono recibiendo. En el otro, abre esta pantalla y toca Empezar a recibir."),
                        Modifier.padding(top = 8.dp))
            }
            items(peers, key = { it.address }) { peer ->
                ListItem(
                    headlineContent = { Text(peer.name) },
                    supportingContent = { Text(peer.address) },
                    modifier =
                        Modifier.clickable { sendTo(peer) })
            }
        }
    }

    vm.qrPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { vm.qrPeer = null },
            title = { Text(tr("Enviar a «{0}»", peer.name)) },
            text = {
                Text(
                    if (toSend.isEmpty())
                        tr("Primero selecciona archivos en el explorador y usa Más → Enviar a otro teléfono; luego vuelve a leer el código.")
                    else
                        tr("{0} archivo(s), {1}, a {2}. El otro teléfono tendrá que aceptarlos.", toSend.size, formatSize(toSend.sumOf { File(it).length() }), peer.address) +
                            (peer.wifi?.let { "\n" + tr("Antes, este teléfono se unirá a su red «{0}» (Android lo preguntará).", it) } ?: ""))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.qrPeer = null
                        sendTo(peer)
                    },
                    enabled = toSend.isNotEmpty()) {
                        Text(tr("Enviar"))
                    }
            },
            dismissButton = { TextButton(onClick = { vm.qrPeer = null }) { Text(tr("Cancelar")) } })
    }

    incoming?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Archivos entrantes")) },
            text = {
                Text(
                    tr("«{0}» quiere enviarte {1} archivo(s), {2}:\n", pending.offer.from, pending.offer.files.size, formatSize(pending.offer.bytes)) +
                        pending.offer.files.take(5).joinToString("\n") { it.name } +
                        if (pending.offer.files.size > 5) "\n…" else "")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.answer.complete(true)
                        incoming = null
                    }) {
                        Text(tr("Aceptar"))
                    }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pending.answer.complete(false)
                        incoming = null
                    }) {
                        Text(tr("Rechazar"))
                    }
            })
    }
}

/** Dibuja un código QR (módulos negros sobre fondo blanco, con su margen). */
@Composable
private fun QrImage(text: String, modifier: Modifier) {
    val grid = remember(text) { NearbyLink.qr(text) }
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = tr("Código QR") }) {
        drawRect(androidx.compose.ui.graphics.Color.White)
        val cell = size.minDimension / grid.size
        grid.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark)
                    drawRect(
                        androidx.compose.ui.graphics.Color.Black,
                        androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                        androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
