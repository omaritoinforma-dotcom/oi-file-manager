package com.omaritoinforma.oiarchivos.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Envío entre teléfonos sin router (el «punto de acceso» del Sender de ES): el que recibe crea un
 * punto de acceso local (LocalOnlyHotspot, sin compartir datos móviles) y muestra en el QR su red y
 * clave; el que envía se une a esa red solo para la app y le manda los archivos por ella.
 */
object WifiDirectLink {
    /** Permiso que pide Android para crear el punto de acceso. */
    val hotspotPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasHotspotPermission(ctx: Context) = ctx.checkSelfPermission(hotspotPermission) == PackageManager.PERMISSION_GRANTED

    class Hotspot(val ssid: String, val key: String, val address: String, private val reservation: WifiManager.LocalOnlyHotspotReservation) : AutoCloseable {
        override fun close() = reservation.close()
    }

    /** Crea el punto de acceso local y espera a que tenga dirección. */
    @SuppressLint("MissingPermission")
    suspend fun startHotspot(ctx: Context): Hotspot {
        if (!hasHotspotPermission(ctx)) throw IOException(tr("Falta el permiso para crear el punto de acceso"))
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val before = ipv4ByInterface().keys
        val reservation =
            withTimeout(20_000) {
                suspendCancellableCoroutine { cont ->
                    wifi.startLocalOnlyHotspot(
                        object : WifiManager.LocalOnlyHotspotCallback() {
                            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                                if (cont.isActive) cont.resume(reservation) else reservation.close()
                            }

                            override fun onFailed(reason: Int) {
                                if (cont.isActive)
                                    cont.resumeWithException(
                                        IOException(
                                            if (reason == ERROR_INCOMPATIBLE_MODE) tr("Apaga el punto de acceso del teléfono e inténtalo otra vez")
                                            else tr("Este teléfono no pudo crear el punto de acceso (código {0})", reason)))
                            }
                        },
                        Handler(Looper.getMainLooper()))
                }
            }
        try {
            val (ssid, key) =
                if (Build.VERSION.SDK_INT >= 30) {
                    val c = reservation.softApConfiguration
                    (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.removeSurrounding("\"") else @Suppress("DEPRECATION") c.ssid).orEmpty() to c.passphrase.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    val c = reservation.wifiConfiguration
                    c?.SSID.orEmpty().removeSurrounding("\"") to c?.preSharedKey.orEmpty().removeSurrounding("\"")
                }
            if (ssid.isEmpty()) throw IOException(tr("Este teléfono no pudo crear el punto de acceso (código {0})", -1))
            // La interfaz del punto de acceso aparece un momento después.
            var address: String? = null
            repeat(50) {
                address = hotspotAddress(before, ipv4ByInterface())
                if (address != null) return Hotspot(ssid, key, address!!, reservation)
                kotlinx.coroutines.delay(100)
            }
            throw IOException(tr("El punto de acceso no tiene dirección"))
        } catch (e: Throwable) {
            reservation.close()
            throw e
        }
    }

    private fun ipv4ByInterface(): Map<String, String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .mapNotNull { nif -> nif.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.let { nif.name to it.hostAddress!! } }
                .toMap()
        }.getOrDefault(emptyMap())

    /**
     * Dirección del punto de acceso: la de una interfaz nueva (ap0, swlan0, wlan1…), o la de una
     * interfaz de punto de acceso conocida si ya existía.
     */
    fun hotspotAddress(before: Set<String>, now: Map<String, String>): String? {
        val fresh = now.filterKeys { it !in before }
        val apLike = Regex("(ap|swlan|softap|wlan[1-9])\\d*")
        return (fresh.entries.firstOrNull { apLike.matches(it.key) } ?: fresh.entries.firstOrNull()
            ?: now.entries.firstOrNull { it.key.startsWith("ap") || it.key.startsWith("swlan") })?.value
    }

    /**
     * Se une a la red [ssid] solo para esta app (Android pregunta antes al usuario), ejecuta [block]
     * con el tráfico de la app por esa red y la suelta al terminar.
     */
    suspend fun <T> withNetwork(ctx: Context, ssid: String, key: String, block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < 29)
            throw IOException(tr("Para unirse sola a la red hace falta Android 10 o superior: conéctate a mano a «{0}» y vuelve a leer el código", ssid))
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (key.isNotEmpty()) setWpa2Passphrase(key) }.build()
        val request =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network =
                withTimeout(120_000) {
                    suspendCancellableCoroutine<Network> { cont ->
                        val cb =
                            object : ConnectivityManager.NetworkCallback() {
                                override fun onAvailable(network: Network) {
                                    if (cont.isActive) cont.resume(network)
                                }

                                override fun onUnavailable() {
                                    if (cont.isActive) cont.resumeWithException(IOException(tr("No se pudo unir a la red «{0}»", ssid)))
                                }
                            }
                        callback = cb
                        cm.requestNetwork(request, cb)
                    }
                }
            cm.bindProcessToNetwork(network)
            try {
                return block()
            } finally {
                cm.bindProcessToNetwork(null)
            }
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }
}
