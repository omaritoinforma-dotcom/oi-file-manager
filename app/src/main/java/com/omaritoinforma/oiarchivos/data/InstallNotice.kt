package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * «Notificarme los permisos de aplicaciones» de ES: al terminar de instalar una app desde OI Archivos,
 * un aviso dice qué permisos delicados pide (ubicación, cámara, micrófono…).
 */
object InstallNotice {
    const val NOTIFICATION_BASE = 4000

    /** Texto del aviso, o null si la app no pide nada delicado. */
    fun text(label: String, groups: Collection<SensitiveGroup>): String? =
        if (groups.isEmpty()) null
        else "«$label» pide acceso a: ${groups.joinToString(", ") { it.label }}. Toca para revisar los permisos."

    /** Nombre de la app y permisos delicados que declara el APK, leídos sin instalarlo otra vez. */
    @Suppress("DEPRECATION")
    fun inspect(ctx: Context, apk: File): Pair<String, List<SensitiveGroup>>? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_PERMISSIONS) ?: return null
        val label =
            info.applicationInfo
                ?.let {
                    it.sourceDir = apk.path
                    it.publicSourceDir = apk.path
                    pm.getApplicationLabel(it).toString()
                }
                ?.takeIf { it.isNotBlank() } ?: info.packageName
        val (granted, asked) = AppAnalysis.classify(info.requestedPermissions?.toList().orEmpty(), emptySet())
        return label to (granted + asked).toList()
    }
}
