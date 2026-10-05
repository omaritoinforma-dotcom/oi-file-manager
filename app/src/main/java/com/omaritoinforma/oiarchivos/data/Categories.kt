package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Qué tipos de archivo entran en la categoría «Documentos» («Document type setting» de ES). Cada uno
 * agrupa las extensiones que se reconocen juntas.
 */
enum class DocumentType(private val labelEs: String, val extensions: List<String>) {
    PDF(trKey("PDF"), listOf("pdf")),
    WORD(trKey("Word y texto enriquecido"), listOf("doc", "docx", "odt", "rtf")),
    EXCEL(trKey("Hojas de cálculo"), listOf("xls", "xlsx", "ods", "csv")),
    POWERPOINT(trKey("Presentaciones"), listOf("ppt", "pptx", "odp")),
    TEXT(trKey("Texto (.txt y .md)"), listOf("txt", "md")),
    EBOOK(trKey("Libros electrónicos (.epub)"), listOf("epub"));

    val label: String
        get() = tr(labelEs)

    companion object {
        val all: Set<DocumentType> = entries.toSet()

        /** Extensiones de los tipos elegidos. */
        fun extensions(types: Set<DocumentType>): List<String> = types.flatMap { it.extensions }
    }
}

/** Categorías (Imágenes, Música, ...) usando el índice del sistema: es mucho más rápido que recorrer todo. */
object Categories {
    private val archiveExt = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    // Una sola definición de cada regla: sirve para pedir al sistema solo lo que toca y para
    // comprobar después cada ruta (y para las pruebas).
    private val ebookExt = listOf("epub", "mobi", "azw", "azw3", "fb2", "djvu", "lit")
    private val wordExt = listOf("doc", "docx", "odt", "rtf")
    private val excelExt = listOf("xls", "xlsx", "ods", "csv")
    private val powerPointExt = listOf("ppt", "pptx", "odp")

    /** Carpetas donde Android y las apps guardan las capturas de pantalla (en varios idiomas). */
    private val screenshotDirs = listOf("Screenshots", "Screenshot", "Capturas de pantalla", "Capturas", "Captures d'écran", "Bildschirmfotos")

    /** Carpetas de grabadoras de voz. */
    private val recordingDirs = listOf("Recordings", "Recording", "Voice Recorder", "Sound Recorder", "Sound recordings", "Grabaciones", "Grabadora", "VoiceRecorder")

    private fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun inDir(path: String, dirs: List<String>) =
        dirs.any { path.contains("/$it/", ignoreCase = true) }

    /** Si [path] (con su tipo MIME, si se conoce) pertenece a la categoría [cat]. */
    fun matches(
        cat: FileCategory,
        path: String,
        mime: String? = null,
        documentTypes: Set<DocumentType> = DocumentType.all
    ): Boolean {
        val ext = extension(path)
        val name = path.substringAfterLast('/')
        return when (cat) {
            FileCategory.IMAGES -> mime?.startsWith("image/") == true
            FileCategory.MUSIC -> mime?.startsWith("audio/") == true
            FileCategory.VIDEOS -> mime?.startsWith("video/") == true
            FileCategory.DOCUMENTS -> ext in DocumentType.extensions(documentTypes)
            FileCategory.APKS -> ext == "apk"
            FileCategory.ARCHIVES -> ext in archiveExt
            FileCategory.EBOOKS -> ext in ebookExt
            FileCategory.SCREENSHOTS ->
                (mime?.startsWith("image/") == true || ext in setOf("png", "jpg", "jpeg", "webp")) &&
                    (inDir(path, screenshotDirs) || name.startsWith("Screenshot_", ignoreCase = true))
            FileCategory.RECORDINGS ->
                (mime?.startsWith("audio/") == true ||
                    ext in setOf("m4a", "amr", "3gp", "wav", "mp3", "ogg", "opus", "aac")) &&
                    inDir(path, recordingDirs)
            FileCategory.WORD -> ext in wordExt
            FileCategory.EXCEL -> ext in excelExt
            FileCategory.POWERPOINT -> ext in powerPointExt
            FileCategory.RECENT -> mime != null
        }
    }

    /** Lo de una categoría cuyo nombre contiene [query] (sin distinguir mayúsculas). */
    fun byName(items: List<FileItem>, query: String): List<FileItem> =
        items.filter { it.name.contains(query, ignoreCase = true) }

    @Suppress("DEPRECATION")
    fun query(
        ctx: Context,
        cat: FileCategory,
        showHidden: Boolean,
        documentTypes: Set<DocumentType> = DocumentType.all
    ): List<FileItem> {
        val dataCol = MediaStore.Files.FileColumns.DATA
        val mimeCol = MediaStore.Files.FileColumns.MIME_TYPE
        val (selection, args) = when (cat) {
            FileCategory.IMAGES -> "$mimeCol LIKE ?" to arrayOf("image/%")
            FileCategory.MUSIC -> "$mimeCol LIKE ?" to arrayOf("audio/%")
            FileCategory.VIDEOS -> "$mimeCol LIKE ?" to arrayOf("video/%")
            FileCategory.DOCUMENTS -> likeAny(dataCol, DocumentType.extensions(documentTypes))
            FileCategory.APKS -> likeAny(dataCol, listOf("apk"))
            FileCategory.ARCHIVES -> likeAny(dataCol, archiveExt)
            FileCategory.EBOOKS -> likeAny(dataCol, ebookExt)
            FileCategory.WORD -> likeAny(dataCol, wordExt)
            FileCategory.EXCEL -> likeAny(dataCol, excelExt)
            FileCategory.POWERPOINT -> likeAny(dataCol, powerPointExt)
            FileCategory.SCREENSHOTS ->
                "$mimeCol LIKE ? AND (" + (screenshotDirs.map { "$dataCol LIKE ?" } + "$dataCol LIKE ?").joinToString(" OR ") + ")" to
                    arrayOf("image/%") + screenshotDirs.map { "%/$it/%" } + "%/Screenshot_%"
            FileCategory.RECORDINGS ->
                "$mimeCol LIKE ? AND (" + recordingDirs.joinToString(" OR ") { "$dataCol LIKE ?" } + ")" to
                    arrayOf("audio/%") + recordingDirs.map { "%/$it/%" }
            FileCategory.RECENT -> "$mimeCol IS NOT NULL" to emptyArray<String>()
        }
        if (cat == FileCategory.DOCUMENTS && documentTypes.isEmpty()) return emptyList()
        val limit = if (cat == FileCategory.RECENT) 300 else 10_000
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(dataCol, mimeCol),
            selection,
            args.takeIf { it.isNotEmpty() },
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(dataCol)
            val mimeIdx = c.getColumnIndexOrThrow(mimeCol)
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(idx) ?: continue
                if (!showHidden && path.contains("/.")) continue
                // «_» en LIKE vale por cualquier carácter: se confirma con la misma regla de [matches].
                if (!matches(cat, path, c.getString(mimeIdx), documentTypes)) continue
                if (!seen.add(path)) continue
                val f = File(path)
                if (f.isFile) out += f.toItem()
            }
        }
        return out
    }

    private fun likeAny(col: String, exts: List<String>): Pair<String, Array<String>> =
        exts.joinToString(" OR ") { "$col LIKE ?" } to exts.map { "%.$it" }.toTypedArray()
}
