package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Imagen de fondo de la app («Temas» de ES). Se guarda una copia reducida en el almacenamiento
 * privado de la app, así que sigue ahí aunque se borre o mueva el original, y no pesa de más.
 */
object BackgroundImage {
    /** Lado mayor de la copia: de sobra para una pantalla de teléfono. */
    const val MAX_PIXELS = 2_000_000L

    /** Visibilidad de la imagen, en %. Con más, el texto deja de leerse bien. */
    val strengths = 10..60
    const val DEFAULT_STRENGTH = 25

    /** Cuánto del color normal de la pantalla se deja por encima de la imagen (0 a 1). */
    fun overlayAlpha(strengthPercent: Int): Float = 1f - strengthPercent.coerceIn(strengths) / 100f

    fun file(ctx: Context): File = File(ctx.filesDir, "fondo.jpg")

    /** Guarda [source] como fondo (reducido y con la orientación aplicada) y devuelve la copia. */
    fun set(ctx: Context, source: File): File {
        if (!source.isFile) throw IOException("No existe la imagen")
        val bitmap = ImageTools.open(source, MAX_PIXELS)
        val target = file(ctx)
        val temp = File(target.parentFile, "fondo.jpg.tmp")
        try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
                    throw IOException("No se pudo guardar el fondo")
                out.fd.sync()
            }
            SafeFiles.commit(temp, target, replace = true)
        } finally {
            temp.delete()
            bitmap.recycle()
        }
        return target
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Abre la copia guardada, o null si no hay o no se puede leer. */
    fun load(ctx: Context): Bitmap? =
        file(ctx).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
}
