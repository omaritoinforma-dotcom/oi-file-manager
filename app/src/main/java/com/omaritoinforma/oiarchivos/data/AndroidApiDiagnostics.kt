package com.omaritoinforma.oiarchivos.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Public Android APIs and readable public files, always under this app's UID. */
object AndroidApiDiagnostics {
    val readActions = setOf("memory", "battery", "activity", "package", "network", "display", "storage",
        "processes", "list_packages", "system_list", "system_stat", "system_read")
    fun capabilities(): JSONObject = JSONObject()
        .put("readActions", JSONArray(readActions.sorted()))
        .put("confirmationActions", JSONArray(listOf("install", "uninstall")))
        .put("requiresAndroidConfirmation", true).put("silentInstall", false)
        .put("otherAppsPrivateData", false).put("otherAppsCrashLogs", false)
        .put("inputInjection", false).put("permissionGrant", false)
        .put("scope", "Public API metadata; memory/process/activity details limited to this app. Public file access remains subject to Android permissions.")

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    fun execute(context: Context, action: String, args: JSONObject): JSONObject {
        val pkg = args.optString("packageName")
        if (action in setOf("memory", "activity", "processes") && pkg.isNotBlank() && pkg != context.packageName)
            throw IOException("Sin ADB solo se puede inspeccionar el proceso de OI Archivos; no el de otra aplicación")
        val data: JSONObject
        val scope: String
        when (action) {
            "package" -> {
                val name = DebugCommands.packageName(pkg)
                val flags = PackageManager.GET_PERMISSIONS or
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                val info = context.packageManager.getPackageInfo(name, flags)
                val current = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                val history = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo?.hasMultipleSigners() == false)
                    info.signingInfo?.signingCertificateHistory else null
                val permissions = JSONArray()
                info.requestedPermissions.orEmpty().forEachIndexed { index, permission ->
                    permissions.put(JSONObject().put("name", permission).put("granted",
                        (info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and PackageInfoFlags.GRANTED != 0))
                }
                data = JSONObject().put("packageName", name).put("versionName", info.versionName)
                    .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                    .put("currentSignerSha256", JSONArray(current.orEmpty().map { digest(it.toByteArray()) }))
                    .put("signingHistorySha256", JSONArray(history.orEmpty().map { digest(it.toByteArray()) }))
                    .put("permissions", permissions)
                scope = "Installed package public metadata; no app-private files or runtime logs"
            }
            "list_packages" -> {
                val all = AppsRepo.list(context, true)
                data = JSONObject().put("packages", JSONArray(all.take(100).map { it.packageName }))
                    .put("total", all.size).put("limited", all.size > 100)
                    .put("pagination", "Use debug_list_apps with offset for the complete list")
                scope = "Visible installed package names"
            }
            "battery" -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: throw IOException("No hay información de batería disponible")
                data = JSONObject().put("level", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                    .put("scale", battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
                    .put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                    .put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                    .put("temperatureTenthsC", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                scope = "Public battery status"
            }
            "memory" -> {
                val manager = context.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
                val own = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(own)
                data = JSONObject().put("availableBytes", info.availMem).put("totalBytes", info.totalMem)
                    .put("lowMemory", info.lowMemory).put("ownProcessPssKb", own.totalPss)
                scope = "Public memory totals and OI Archivos own process only"
            }
            "activity", "processes" -> {
                val own = ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(own)
                data = JSONObject().put("packageName", context.packageName).put("pid", Process.myPid())
                    .put("uid", Process.myUid()).put("importance", own.importance)
                scope = "OI Archivos own process only; not the foreground app or other apps' processes"
            }
            "network" -> {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                data = JSONObject().put("activeNetwork", caps != null)
                    .put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    .put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                    .put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                    .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                scope = "Public network capabilities; no network traffic, Wi-Fi identity or credentials"
            }
            "display" -> {
                val metrics = context.resources.displayMetrics
                data = JSONObject().put("widthPixels", metrics.widthPixels).put("heightPixels", metrics.heightPixels)
                    .put("densityDpi", metrics.densityDpi)
                scope = "Display metrics visible to OI Archivos"
            }
            "storage" -> {
                data = JSONObject().put("volumes", JSONArray(StorageInfo.volumes(context).map {
                    JSONObject().put("path", it.path).put("freeBytes", it.free).put("totalBytes", it.total)
                }))
                scope = "Shared storage volumes visible to OI Archivos"
            }
            "system_list", "system_stat", "system_read" -> {
                val raw = args.optString("value").ifBlank { "/" }
                DebugCommands.argv(action, value = raw)
                val file = File(raw).canonicalFile
                DebugCommands.argv(action, value = file.path)
                if (action == "system_read") {
                    if (!file.isFile || !file.canRead()) throw IOException("Android no permite leer esta ruta con el UID de la aplicación")
                    val bytes = file.inputStream().use { input -> input.readNBytesCompat(32768) }
                    data = JSONObject().put("path", file.path).put("text", String(bytes, Charsets.UTF_8))
                        .put("limitedToBytes", 32768)
                } else {
                    fun metadata(f: File) = JSONObject().put("name", f.name).put("path", f.path)
                        .put("directory", f.isDirectory).put("readable", f.canRead()).put("bytes", f.length())
                    data = if (action == "system_stat") {
                        if (!file.exists()) throw IOException("Ruta no disponible para la aplicación")
                        metadata(file)
                    } else {
                        val all = file.listFiles() ?: throw IOException("Android no permite listar esta ruta con el UID de la aplicación")
                        JSONObject().put("path", file.path).put("entries", JSONArray(all.take(100).map { metadata(it) }))
                            .put("total", all.size).put("limited", all.size > 100)
                    }
                }
                scope = "Public read-only path under app UID; no shell or private app data"
            }
            else -> throw IOException("Operación no disponible mediante las API públicas de Android")
        }
        val output = data.toString(2)
        if (output.toByteArray(Charsets.UTF_8).size > 65536) throw IOException("El resultado supera 64 KiB")
        val reply = JSONObject().put("executor", "android_api").put("scope", scope).put("uid", Process.myUid())
            .put("exitCode", 0).put("stdout", output).put("completed", true)
            .put("truncated", false).put("timedOut", false)
        if (reply.toString().toByteArray(Charsets.UTF_8).size > 65536) throw IOException("El resultado supera 64 KiB")
        return reply
    }
}

private object PackageInfoFlags { const val GRANTED = android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED }
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val block = ByteArray(4096)
    while (out.size() < limit) {
        val n = read(block, 0, minOf(block.size, limit - out.size())); if (n < 0) break
        if (n > 0) out.write(block, 0, n)
    }
    return out.toByteArray()
}
