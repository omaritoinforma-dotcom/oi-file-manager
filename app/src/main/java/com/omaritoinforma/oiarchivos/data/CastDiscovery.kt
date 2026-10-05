package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Busca Chromecast en la Wi-Fi con mDNS («_googlecast._tcp»), como hace Android con los suyos. */
object CastDiscovery {
    private const val SERVICE = "_googlecast._tcp."

    /** Nombre visible: el atributo «fn» del anuncio, o el nombre del servicio. */
    internal fun friendlyName(attributes: Map<String, ByteArray?>, serviceName: String): String =
        attributes["fn"]?.toString(Charsets.UTF_8)?.trim()?.take(64)?.ifBlank { null }
            ?: serviceName.substringBefore("-").ifBlank { "Chromecast" }

    /** Busca durante [timeoutMs] y llama a [onFound] con cada Chromecast resuelto. */
    @Suppress("DEPRECATION")
    suspend fun search(ctx: Context, timeoutMs: Long = 5000, onFound: (CastV2.Device) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        // resolveService solo admite una resolución a la vez: se resuelven en cola.
        val pending = LinkedBlockingQueue<NsdServiceInfo>()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    pending.offer(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {}
            }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.getOrElse { return }
        try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    while (true) {
                        val info = pending.poll() ?: run {
                            delay(200)
                            null
                        } ?: continue
                        val done = CompletableDeferred<NsdServiceInfo?>()
                        nsd.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    done.complete(null)
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    done.complete(serviceInfo)
                                }
                            })
                        val resolved = withTimeoutOrNull(3000) { done.await() } ?: continue
                        val address = resolved.host as? Inet4Address ?: continue
                        val host = address.hostAddress ?: continue
                        onFound(
                            CastV2.Device(friendlyName(resolved.attributes, resolved.serviceName), host, resolved.port))
                    }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}
