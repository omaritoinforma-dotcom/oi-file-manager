package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val size: Long,
    val isSystem: Boolean,
)

object AppsRepo {
    val backupDir: File get() = File(Environment.getExternalStorageDirectory(), "OI Archivos/Apps")

    fun list(ctx: Context, includeSystem: Boolean): List<AppInfo> {
        val pm = ctx.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return packages.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && !includeSystem) return@mapNotNull null
            val apk = ai.sourceDir ?: return@mapNotNull null
            AppInfo(
                label = ai.loadLabel(pm).toString(),
                packageName = p.packageName,
                versionName = p.versionName ?: "",
                apkPath = apk,
                size = File(apk).length(),
                isSystem = system,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun backup(app: AppInfo): File {
        val dir = backupDir.apply { mkdirs() }
        val safe = "${app.label}_${app.versionName}.apk".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = File(dir, safe)
        File(app.apkPath).copyTo(out, overwrite = true)
        return out
    }
}
