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

enum class SortBy(val label: String) { NAME("Nombre"), DATE("Fecha"), SIZE("Tamaño"), TYPE("Tipo") }

enum class ThemeMode(val label: String) { SYSTEM("Según el sistema"), LIGHT("Claro"), DARK("Oscuro") }

/** Color de la app («Temas» de ES). DYNAMIC usa los colores del fondo de pantalla (Android 12 o posterior). */
enum class AccentColor(val label: String) {
    DYNAMIC("Colores del sistema"),
    BLUE("Azul"),
    RED("Rojo"),
    GREEN("Verde"),
    ORANGE("Naranja"),
    PURPLE("Morado"),
    TEAL("Turquesa"),
    PINK("Rosa")
}

enum class Conflict { RENAME, OVERWRITE, SKIP }

enum class FileCategory(val label: String) {
    IMAGES("Imágenes"),
    MUSIC("Música"),
    VIDEOS("Videos"),
    DOCUMENTS("Documentos"),
    APKS("APK"),
    ARCHIVES("Comprimidos"),

    // Subcategorías de ES: libros electrónicos, capturas, grabaciones y Office separado.
    EBOOKS("Libros"),
    SCREENSHOTS("Capturas"),
    RECORDINGS("Grabaciones"),
    WORD("Word"),
    EXCEL("Excel"),
    POWERPOINT("PowerPoint"),
    RECENT("Recientes"),
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
