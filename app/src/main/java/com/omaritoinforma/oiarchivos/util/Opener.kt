package com.omaritoinforma.oiarchivos.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** «Abrir como» de ES: abrir un archivo como si fuera de otro tipo, sea cual sea su extensión. */
enum class OpenAs(val label: String, val mime: String?) {
    /** Se abre en el editor de texto de OI Archivos. */
    TEXT("Texto (editor de OI Archivos)", null),
    IMAGE("Imagen", "image/*"),
    AUDIO("Audio", "audio/*"),
    VIDEO("Vídeo", "video/*"),
    PDF("PDF", "application/pdf"),
    ANY("Cualquier tipo", "*/*")
}

/** Abrir, compartir e instalar archivos con otras apps. */
object Opener {
    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun open(ctx: Context, f: File, chooser: Boolean = false, mimeType: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(ctx, f), mimeType ?: mime(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val launch = if (chooser) Intent.createChooser(intent, "Abrir con") else intent
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            if (!chooser) open(ctx, f, chooser = true, mimeType = "*/*")
            else Toast.makeText(ctx, "No hay ninguna app para abrir este archivo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun installApk(ctx: Context, f: File) = open(ctx, f, mimeType = "application/vnd.android.package-archive")

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        try {
            val uris = ArrayList(files.map { uri(ctx, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(files[0]))
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo compartir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ruta", text))
    }
}
