package com.omaritoinforma.oiarchivos.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.omaritoinforma.oiarchivos.data.FileItem

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOC, ARCHIVE, APK, CODE, TEXT, OTHER }

@Suppress("DEPRECATION")
object Kinds {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico", "tif", "tiff")
    private val video = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "m4v", "ts", "mpg", "mpeg")
    private val audio = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "amr", "mid", "midi")
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar")
    private val doc = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "rtf")
    private val code = setOf(
        "kt", "kts", "java", "py", "js", "mjs", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs",
        "php", "rb", "swift", "dart", "sh", "bat", "ps1", "html", "htm", "css", "scss", "json", "xml",
        "yml", "yaml", "toml", "gradle", "sql", "lua", "gd", "gdshader", "tscn", "tres",
    )
    private val text = setOf("txt", "md", "log", "csv", "ini", "conf", "cfg", "properties", "srt", "vtt", "nfo", "env")

    fun of(item: FileItem): FileKind = if (item.isDirectory) FileKind.FOLDER else ofExt(item.extension)

    fun ofExt(ext: String): FileKind = when (ext) {
        in image -> FileKind.IMAGE
        in video -> FileKind.VIDEO
        in audio -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        "apk" -> FileKind.APK
        in archive -> FileKind.ARCHIVE
        in doc -> FileKind.DOC
        in code -> FileKind.CODE
        in text -> FileKind.TEXT
        else -> FileKind.OTHER
    }

    fun isEditable(ext: String): Boolean = ext in text || ext in code

    fun icon(kind: FileKind): ImageVector = when (kind) {
        FileKind.FOLDER -> Icons.Filled.Folder
        FileKind.IMAGE -> Icons.Filled.Image
        FileKind.VIDEO -> Icons.Filled.Movie
        FileKind.AUDIO -> Icons.Filled.MusicNote
        FileKind.PDF -> Icons.Filled.PictureAsPdf
        FileKind.DOC -> Icons.Filled.Description
        FileKind.ARCHIVE -> Icons.Filled.Archive
        FileKind.APK -> Icons.Filled.Android
        FileKind.CODE -> Icons.Filled.Code
        FileKind.TEXT -> Icons.Filled.Description
        FileKind.OTHER -> Icons.Filled.InsertDriveFile
    }

    fun color(kind: FileKind): Color = when (kind) {
        FileKind.FOLDER -> Color(0xFFFFB300)
        FileKind.IMAGE -> Color(0xFF43A047)
        FileKind.VIDEO -> Color(0xFFE53935)
        FileKind.AUDIO -> Color(0xFF8E24AA)
        FileKind.PDF -> Color(0xFFD32F2F)
        FileKind.DOC -> Color(0xFF1E88E5)
        FileKind.ARCHIVE -> Color(0xFF8D6E63)
        FileKind.APK -> Color(0xFF7CB342)
        FileKind.CODE -> Color(0xFF00897B)
        FileKind.TEXT -> Color(0xFF546E7A)
        FileKind.OTHER -> Color(0xFF78909C)
    }
}
