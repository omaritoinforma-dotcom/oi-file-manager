package com.omaritoinforma.oiarchivos.data

import java.io.File

data class FileItem(
    val file: File,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val childCount: Int,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

fun File.toItem(): FileItem {
    val dir = isDirectory
    return FileItem(
        file = this,
        name = name.ifEmpty { absolutePath },
        path = absolutePath,
        isDirectory = dir,
        size = if (dir) 0L else length(),
        lastModified = lastModified(),
        isHidden = name.startsWith("."),
        childCount = if (dir) (list()?.size ?: 0) else -1,
    )
}

enum class ViewMode { LIST, DETAILS, GRID }

/** Codificación de los nombres de archivo del servidor FTP del teléfono («Codificación» de ES). */
enum class FtpEncoding(private val labelEs: String, private val charsetName: String) {
    UTF8(trKey("UTF-8"), "UTF-8"),
    LATIN1(trKey("ISO-8859-1 (Europa occidental)"), "ISO-8859-1"),
    WINDOWS_1252(trKey("Windows-1252"), "windows-1252"),
    GBK(trKey("GBK (chino simplificado)"), "GBK"),
    SHIFT_JIS(trKey("Shift_JIS (japonés)"), "Shift_JIS");

    val label: String
        get() = tr(labelEs)

    val charset: java.nio.charset.Charset
        get() = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
}

/** Cómo se dibujan los iconos de carpeta («Estilo de carpetas» de los temas de ES). */
enum class FolderStyle(private val labelEs: String) {
    CLASSIC(trKey("Clásica (amarilla)")),
    ACCENT(trKey("Color de la app")),
    GREY(trKey("Gris"));

    val label: String
        get() = tr(labelEs)
}

/** Orientación de la pantalla («Orientación de la pantalla» de ES). */
enum class ScreenOrientation(private val labelEs: String) {
    AUTO(trKey("Automática")),
    PORTRAIT(trKey("Vertical")),
    LANDSCAPE(trKey("Horizontal"));

    val label: String
        get() = tr(labelEs)
}

enum class SortBy(private val labelEs: String) { NAME(trKey("Nombre")), DATE(trKey("Fecha")), SIZE(trKey("Tamaño")), TYPE(trKey("Tipo"));

    val label: String
        get() = tr(labelEs)
}

enum class ThemeMode(private val labelEs: String) { SYSTEM(trKey("Según el sistema")), LIGHT(trKey("Claro")), DARK(trKey("Oscuro"));

    val label: String
        get() = tr(labelEs)
}

/** Color de la app («Temas» de ES). DYNAMIC usa los colores del fondo de pantalla (Android 12 o posterior). */
enum class AccentColor(private val labelEs: String) {
    DYNAMIC(trKey("Colores del sistema")),
    BLUE(trKey("Azul")),
    RED(trKey("Rojo")),
    GREEN(trKey("Verde")),
    ORANGE(trKey("Naranja")),
    PURPLE(trKey("Morado")),
    TEAL(trKey("Turquesa")),
    PINK(trKey("Rosa"));

    val label: String
        get() = tr(labelEs)
}

enum class Conflict { RENAME, OVERWRITE, SKIP }

enum class FileCategory(private val labelEs: String) {
    IMAGES(trKey("Imágenes")),
    MUSIC(trKey("Música")),
    VIDEOS(trKey("Videos")),
    DOCUMENTS(trKey("Documentos")),
    APKS(trKey("APK")),
    ARCHIVES(trKey("Comprimidos")),

    // Subcategorías de ES: libros electrónicos, capturas, grabaciones y Office separado.
    EBOOKS(trKey("Libros")),
    SCREENSHOTS(trKey("Capturas")),
    RECORDINGS(trKey("Grabaciones")),
    WORD(trKey("Word")),
    EXCEL(trKey("Excel")),
    POWERPOINT(trKey("PowerPoint")),
    RECENT(trKey("Recientes")),;

    val label: String
        get() = tr(labelEs)
}

sealed interface Location {
    data class Folder(val path: String) : Location
    data class Category(val category: FileCategory) : Location
    /**
     * [filter] marca resultados de búsqueda avanzada, que se repiten con el mismo filtro al actualizar.
     * Con [category], se busca solo entre los archivos de esa categoría (como en ES) y [root] no cuenta.
     */
    data class Search(
        val root: String,
        val query: String,
        val filter: SearchFilter? = null,
        val category: FileCategory? = null
    ) : Location
}

data class Clipboard(val paths: List<String>, val move: Boolean)

data class OpProgress(
    val title: String,
    val current: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val bytesPerSec: Long = 0,
)

data class StorageVolumeInfo(
    val name: String,
    val path: String,
    val total: Long,
    val free: Long,
    val removable: Boolean,
)

data class RenameRules(
    val find: String = "",
    val replace: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val numberFrom: Int? = null,
    val newExtension: String? = null,
) {
    fun apply(name: String, index: Int, isDirectory: Boolean): String {
        val dot = if (isDirectory) -1 else name.lastIndexOf('.')
        var base = if (dot > 0) name.substring(0, dot) else name
        var ext = if (dot > 0) name.substring(dot + 1) else ""
        if (find.isNotEmpty()) base = base.replace(find, replace)
        base = prefix + base + suffix
        if (numberFrom != null) base += " ${numberFrom + index}"
        if (!isDirectory && !newExtension.isNullOrBlank()) ext = newExtension.trim().trimStart('.')
        return if (ext.isEmpty()) base else "$base.$ext"
    }
}
