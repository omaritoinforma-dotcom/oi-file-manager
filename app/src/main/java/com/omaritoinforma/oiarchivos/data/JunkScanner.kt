package com.omaritoinforma.oiarchivos.data

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Limpiador de basura, como el de ES: temporales y vacíos, miniaturas guardadas, APK de apps que ya
 * están instaladas y restos de apps desinstaladas. Solo propone; lo elegido va a la papelera.
 */
object JunkScanner {
    enum class Kind(val label: String, val description: String) {
        TEMP("Temporales y vacíos", "Archivos .tmp, .temp, .bak, archivos vacíos y carpetas vacías"),
        THUMBNAILS("Miniaturas guardadas", "Copias pequeñas de fotos; Android las vuelve a crear"),
        INSTALLED_APK("APK ya instalados", "Instaladores de apps que ya tienes en esa versión o en una más nueva"),
        LEFTOVERS("Restos de apps desinstaladas", "Carpetas de apps que ya no están en el teléfono")
    }

    data class Item(val file: File, val kind: Kind, val size: Long, val detail: String = "")

    /** Paquete y versión de un APK, o null si no se puede leer. */
    data class ApkInfo(val packageName: String, val versionCode: Long, val label: String)

    private val tempExtensions = setOf("tmp", "temp", "bak")
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private fun size(file: File): Long =
        if (file.isFile) file.length() else SafeFiles.walk(file).filter { it.isFile }.sumOf { it.length() }

    /**
     * Recorre [root] (normalmente el almacenamiento interno). [installedVersion] da la versión
     * instalada de un paquete o null si no está; [apkInfo] lee un APK.
     */
    suspend fun scan(
        root: File,
        installedVersion: (String) -> Long?,
        apkInfo: (File) -> ApkInfo?,
        report: (OpProgress) -> Unit = {}
    ): List<Item> {
        val out = ArrayList<Item>()
        val leftoverBases = listOf("Android/media", "Android/obb").map { File(root, it).canonicalFile }
        var seen = 0
        for (f in SafeFiles.walk(root)) {
            currentCoroutineContext().ensureActive()
            if (++seen % 200 == 0) report(OpProgress("Buscando basura", f.name, doneFiles = seen))
            val parent = f.parentFile?.canonicalFile
            // Restos: carpetas con nombre de paquete dentro de Android/media u Android/obb.
            if (f.isDirectory && parent in leftoverBases && packageName.matches(f.name) &&
                installedVersion(f.name) == null) {
                out += Item(f, Kind.LEFTOVERS, size(f), f.name)
                continue
            }
            if (f.isDirectory) {
                if (f != root && f.list()?.isEmpty() == true) out += Item(f, Kind.TEMP, 0)
                continue
            }
            if (!f.isFile) continue
            val extension = f.extension.lowercase()
            when {
                f.path.split('/').any { it == ".thumbnails" } -> out += Item(f, Kind.THUMBNAILS, f.length())
                extension in tempExtensions || f.length() == 0L -> out += Item(f, Kind.TEMP, f.length())
                extension == "apk" -> {
                    val info = apkInfo(f) ?: continue
                    val installed = installedVersion(info.packageName) ?: continue
                    if (installed >= info.versionCode)
                        out += Item(f, Kind.INSTALLED_APK, f.length(), "${info.label} ya instalada")
                }
            }
        }
        // Lo que está dentro de un resto ya cuenta con su carpeta.
        val leftovers = out.filter { it.kind == Kind.LEFTOVERS }.map { it.file.path + "/" }
        return out.filter { item -> item.kind == Kind.LEFTOVERS || leftovers.none { item.file.path.startsWith(it) } }
    }
}
