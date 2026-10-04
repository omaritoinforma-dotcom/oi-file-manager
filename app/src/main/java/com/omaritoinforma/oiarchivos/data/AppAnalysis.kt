package com.omaritoinforma.oiarchivos.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Permisos que tocan datos o funciones delicadas, agrupados por lo que significan para el usuario. */
enum class SensitiveGroup(private val labelEs: String, val permissions: Set<String>) {
    LOCATION(
        trKey("Ubicación"),
        setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION")),
    CAMERA(trKey("Cámara"), setOf(Manifest.permission.CAMERA)),
    MICROPHONE(trKey("Micrófono"), setOf(Manifest.permission.RECORD_AUDIO)),
    CONTACTS(
        trKey("Contactos"),
        setOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS)),
    SMS(
        trKey("SMS"),
        setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH)),
    PHONE(
        trKey("Teléfono y llamadas"),
        setOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_NUMBERS)),
    CALENDAR(
        trKey("Calendario"),
        setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    BODY(
        trKey("Salud y actividad"),
        setOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            "android.permission.BODY_SENSORS_BACKGROUND")),
    STORAGE(
        trKey("Archivos y multimedia"),
        setOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO")),
    NEARBY(
        trKey("Dispositivos cercanos"),
        setOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.NEARBY_WIFI_DEVICES")),
    SYSTEM(
        trKey("Sobre otras apps, instalar y ver apps"),
        setOf(
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            "android.permission.QUERY_ALL_PACKAGES"));

    val label: String
        get() = tr(labelEs)
}

/** Una app con sus permisos delicados: [granted] ya concedidos, [requested] solo pedidos. */
data class AppRisk(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val apkSize: Long,
    val targetSdk: Int,
    val granted: Set<SensitiveGroup>,
    val requested: Set<SensitiveGroup>
) {
    /** Todo lo delicado que la app pide, esté o no concedido. */
    val groups: Set<SensitiveGroup>
        get() = granted + requested
}

/** Analizador de apps de ES: qué apps piden qué permisos delicados. Todo se lee del sistema, sin red. */
object AppAnalysis {
    /** Grupos delicados de una lista de permisos pedidos, con los ya concedidos aparte. */
    fun classify(requested: List<String>, grantedPermissions: Set<String>): Pair<Set<SensitiveGroup>, Set<SensitiveGroup>> {
        val granted = LinkedHashSet<SensitiveGroup>()
        val onlyRequested = LinkedHashSet<SensitiveGroup>()
        for (group in SensitiveGroup.entries) {
            val asked = requested.filter { it in group.permissions }
            if (asked.isEmpty()) continue
            if (asked.any { it in grantedPermissions }) granted += group else onlyRequested += group
        }
        return granted to onlyRequested
    }

    /** Más delicadas primero: más grupos concedidos, luego más pedidos, luego por nombre. */
    fun rank(apps: List<AppRisk>): List<AppRisk> =
        apps.sortedWith(
            compareByDescending<AppRisk> { it.granted.size }
                .thenByDescending { it.groups.size }
                .thenBy { it.label.lowercase() })

    /** Cuántas apps usan cada grupo (concedido o pedido). */
    fun counts(apps: List<AppRisk>): Map<SensitiveGroup, Int> =
        SensitiveGroup.entries.associateWith { g -> apps.count { g in it.groups } }.filterValues { it > 0 }

    fun scan(ctx: Context, includeSystem: Boolean): List<AppRisk> {
        val pm = ctx.packageManager
        val packages =
            if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return rank(
            packages.mapNotNull { p ->
                val ai = p.applicationInfo ?: return@mapNotNull null
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (system && !includeSystem) return@mapNotNull null
                risk(pm, p, ai, system)
            })
    }

    private fun risk(pm: PackageManager, p: PackageInfo, ai: ApplicationInfo, system: Boolean): AppRisk {
        val requested = p.requestedPermissions?.toList().orEmpty()
        val flags = p.requestedPermissionsFlags ?: IntArray(0)
        val grantedPermissions =
            requested
                .filterIndexed { i, _ ->
                    ((flags.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                }
                .toSet()
        val (granted, onlyRequested) = classify(requested, grantedPermissions)
        return AppRisk(
            ai.loadLabel(pm).toString(),
            p.packageName,
            system,
            ai.sourceDir?.let { java.io.File(it).length() } ?: 0L,
            ai.targetSdkVersion,
            granted,
            onlyRequested)
    }
}
