package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Categorías (Imágenes, Música, ...) usando el índice del sistema: es mucho más rápido que recorrer todo. */
object Categories {
    private val docExt = listOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "txt", "rtf", "csv", "epub", "md",
    )
    private val archiveExt = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    @Suppress("DEPRECATION")
    fun query(ctx: Context, cat: FileCategory, showHidden: Boolean): List<FileItem> {
        val dataCol = MediaStore.Files.FileColumns.DATA
        val mimeCol = MediaStore.Files.FileColumns.MIME_TYPE
        val (selection, args) = when (cat) {
            FileCategory.IMAGES -> "$mimeCol LIKE ?" to arrayOf("image/%")
            FileCategory.MUSIC -> "$mimeCol LIKE ?" to arrayOf("audio/%")
            FileCategory.VIDEOS -> "$mimeCol LIKE ?" to arrayOf("video/%")
            FileCategory.DOCUMENTS -> likeAny(dataCol, docExt)
            FileCategory.APKS -> likeAny(dataCol, listOf("apk"))
            FileCategory.ARCHIVES -> likeAny(dataCol, archiveExt)
            FileCategory.RECENT -> "$mimeCol IS NOT NULL" to emptyArray<String>()
        }
        val limit = if (cat == FileCategory.RECENT) 300 else 10_000
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(dataCol),
            selection,
            args.takeIf { it.isNotEmpty() },
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(dataCol)
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(idx) ?: continue
                if (!showHidden && path.contains("/.")) continue
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
