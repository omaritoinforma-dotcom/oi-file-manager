package com.omaritoinforma.oiarchivos.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Girar, voltear y recortar imágenes (el editor de imagen de ES). Las cuentas del recuadro están en
 * [ImageCrop]; aquí se abre, se transforma y se guarda con Android.
 */
object ImageTools {
    /** Por encima de esto la imagen se abre reducida: un mapa de bits de 24 Mpx ocupa ~96 MB. */
    const val MAX_PIXELS = 24_000_000L

    /** Para el editor en pantalla: no hace falta más resolución que esto. */
    const val PREVIEW_PIXELS = 2_000_000L

    /** Los formatos que se pueden sobrescribir sin cambiarles la extensión. */
    private val replaceable = setOf("jpg", "jpeg", "png", "webp")

    fun canReplace(file: File) = file.extension.lowercase() in replaceable

    private fun exifMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        return m
    }

    /** Ancho y alto reales tal como se ve la imagen (con la orientación EXIF), sin abrirla entera. */
    fun dimensions(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation =
            runCatching {
                    ExifInterface(file.path)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swapped =
            orientation in
                setOf(
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSVERSE,
                    ExifInterface.ORIENTATION_ROTATE_270)
        return if (swapped) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    /** Abre la imagen tal como se ve (con la orientación EXIF aplicada), reducida si pasa de [maxPixels]. */
    fun open(file: File, maxPixels: Long): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("No se pudo leer la imagen")
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = ImageCrop.sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
            }
        val raw =
            file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("No se pudo leer la imagen")
        val orientation =
            runCatching {
                    ExifInterface(file.path)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = exifMatrix(orientation)
        if (matrix.isIdentity) return raw
        val oriented = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (oriented !== raw) raw.recycle()
        return oriented
    }

    /**
     * Voltea (de izquierda a derecha), gira [quarterTurns] cuartos de vuelta a la derecha y recorta.
     * El recuadro [crop] está en la imagen ya girada, que es lo que se ve en pantalla.
     */
    fun transform(source: Bitmap, quarterTurns: Int, flip: Boolean, crop: CropBox): Bitmap {
        val matrix = Matrix()
        if (flip) matrix.postScale(-1f, 1f)
        matrix.postRotate(90f * (((quarterTurns % 4) + 4) % 4))
        val turned =
            if (matrix.isIdentity) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val area = ImageCrop.pixels(crop, turned.width, turned.height)
        val whole = area.x == 0 && area.y == 0 && area.width == turned.width && area.height == turned.height
        val out = if (whole) turned else Bitmap.createBitmap(turned, area.x, area.y, area.width, area.height)
        if (turned !== source && turned !== out) turned.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun webp() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.WEBP

    /** Nombre de la copia: «foto.jpg» → «foto (editada).jpg»; lo que no se puede escribir pasa a PNG. */
    fun copyTarget(file: File): File {
        val format = ImageFormat.forExtension(file.extension)
        val ext = if (canReplace(file)) file.extension else format.extension
        return FileOps.uniqueName(file.parentFile ?: File("."), "${file.nameWithoutExtension} (editada).$ext")
    }

    /**
     * Guarda la imagen editada, como copia nueva o sobrescribiendo la original. Se escribe primero en un
     * archivo temporal y se cambia de sitio al final: si algo falla, el original queda como estaba.
     */
    fun save(file: File, quarterTurns: Int, flip: Boolean, crop: CropBox, replace: Boolean): File {
        if (replace && !canReplace(file)) throw IOException("Este formato no se puede sobrescribir: guarda una copia")
        val source = open(file, MAX_PIXELS)
        val result =
            try {
                transform(source, quarterTurns, flip, crop)
            } catch (e: OutOfMemoryError) {
                throw IOException("La imagen es demasiado grande para editarla")
            }
        val format = ImageFormat.forExtension(file.extension)
        val target = if (replace) file else copyTarget(file)
        val temp = File(target.parentFile, ".${target.name}.oi-tmp")
        try {
            val (compress, quality) =
                when (format) {
                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG to 95
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG to 100
                    ImageFormat.WEBP -> webp() to 100
                }
            FileOutputStream(temp).use { out ->
                if (!result.compress(compress, quality, out)) throw IOException("No se pudo guardar la imagen")
                out.fd.sync()
            }
            SafeFiles.commit(temp, target, replace)
        } finally {
            temp.delete()
            if (result !== source) result.recycle()
            source.recycle()
        }
        return target
    }
}
