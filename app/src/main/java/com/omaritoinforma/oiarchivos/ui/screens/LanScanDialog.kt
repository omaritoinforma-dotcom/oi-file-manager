package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.LanHost
import com.omaritoinforma.oiarchivos.data.LanScanner
import com.omaritoinforma.oiarchivos.data.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

/** Servicios que se anuncian por mDNS / DNS-SD, como hace ES con Zeroconf. */
private val announced =
    mapOf(
        "_smb._tcp." to Protocol.SMB,
        "_ftp._tcp." to Protocol.FTP,
        "_sftp-ssh._tcp." to Protocol.SFTP,
        "_webdav._tcp." to Protocol.WEBDAV)

/**
 * Escucha los servidores anunciados hasta [stop]. Se resuelve un servicio cada vez porque las
 * versiones antiguas de Android rechazan resoluciones simultáneas.
 */
private class Announcements(ctx: Context, val onFound: (LanHost) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        for (type in announced.keys) {
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)

                    override fun onServiceLost(info: NsdServiceInfo) {}

                    override fun onDiscoveryStarted(type: String) {}

                    override fun onDiscoveryStopped(type: String) {}

                    override fun onStartDiscoveryFailed(type: String, code: Int) {}

                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { listeners += listener }
        }
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        queue.addLast(info)
        next()
    }

    @Synchronized
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done()

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val protocol =
                                announced.entries
                                    .firstOrNull { info.serviceType.trimEnd('.') + "." == it.key }
                                    ?.value
                            val address = info.host?.hostAddress
                            if (protocol != null && address != null && ':' !in address)
                                onFound(LanHost(protocol, address, info.port, info.serviceName))
                            done()
                        }
                    })
            }
            .onFailure { done() }
    }

    @Synchronized
    private fun done() {
        resolving = false
        next()
    }

    fun stop() = listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
}

@Composable
fun LanScanDialog(onDismiss: () -> Unit, onPick: (LanHost) -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<LanHost>() }
    var progress by remember { mutableFloatStateOf(0f) }
    var scanning by remember { mutableStateOf(true) }
    var networks by remember { mutableStateOf("") }
    fun add(host: LanHost) {
        // Un servidor anunciado (con su nombre) sustituye a la misma dirección encontrada por puerto.
        val same = found.indexOfFirst { it.address == host.address && it.port == host.port }
        if (same < 0) found += host else if (host.name.isNotBlank()) found[same] = host
    }
    DisposableEffect(Unit) {
        val announcements = Announcements(ctx) { host -> android.os.Handler(ctx.mainLooper).post { add(host) } }
        announcements.start()
        onDispose { announcements.stop() }
    }
    LaunchedEffect(Unit) {
        val nets = withContext(Dispatchers.IO) { LanScanner.localNetworks() }
        networks = nets.joinToString { "${it.first.hostAddress}/${it.second}" }
        val hosts = nets.flatMap { (address, prefix) -> LanScanner.subnetHosts(address, prefix) }.distinct()
        LanScanner.scan(
            hosts,
            onProgress = { done, total -> progress = done.toFloat() / total }) { host ->
                android.os.Handler(ctx.mainLooper).post { add(host) }
            }
        scanning = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Red local")) },
        text = {
            Column {
                Text(
                    if (networks.isBlank()) tr("Conéctate a una red Wi-Fi para buscar equipos.")
                    else if (scanning) tr("Buscando servidores SMB, FTP, FTPS y SFTP en {0}…", networks)
                    else tr("Búsqueda terminada en {0}.", networks))
                if (scanning)
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (!scanning && found.isEmpty())
                    Text(tr("No se encontraron servidores."), Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(found.sortedWith(compareBy({ it.address }, { it.port }))) { host ->
                        ListItem(
                            headlineContent = {
                                Text("${host.protocol.label} · ${host.address}:${host.port}")
                            },
                            supportingContent = {
                                if (host.name.isNotBlank()) Text(host.name)
                            },
                            modifier = Modifier.clickable { onPick(host) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } })
}
