package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val size: Long,
    val isSystem: Boolean,
    val splits: List<String> = emptyList(),
)

object AppsRepo {
    fun list(ctx: Context, includeSystem: Boolean): List<AppInfo> {
        val pm = ctx.packageManager
        val packages =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(0)
            }
        return packages
            .mapNotNull { p ->
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
                    splits = ai.splitSourceDirs?.toList().orEmpty(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Guarda el APK (o el conjunto de APK divididos en un .apks) en [folder]. */
    suspend fun backup(app: AppInfo, folder: File): File {
        val dir = folder.apply { mkdirs() }
        if (!dir.isDirectory) throw java.io.IOException("No se pudo crear la carpeta de copias")
        val extension = if (app.splits.isEmpty()) "apk" else "apks"
        val safe =
            "${app.label}_${app.versionName}.$extension".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = FileOps.uniqueName(dir, safe)
        if (app.splits.isEmpty())
            SafeFiles.writeAtomic(out) { File(app.apkPath).copyTo(it, overwrite = true) }
        else ArchiveTools.compress((listOf(app.apkPath) + app.splits).map(::File), out, "") {}
        return out
    }
}
