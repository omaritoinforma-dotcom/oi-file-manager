package com.omaritoinforma.oiarchivos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Intro y outro del editor de vídeo («Intro & Outro» de ES): una imagen fija de unos segundos al
 * principio o al final del vídeo exportado. Puede ser una foto, recortada al centro para llenar el
 * cuadro sin deformarse, un texto sobre un color de fondo o el texto encima de la foto.
 *
 * Se dibuja al exportar con el tamaño final del vídeo, así que no depende de si es horizontal o vertical.
 */
object VideoCards {
    /** Duraciones que se pueden elegir, en milisegundos. */
    val durationsMs = listOf(2000L, 3000L, 5000L)

    fun nextDuration(current: Long): Long =
        durationsMs[(durationsMs.indexOf(current) + 1) % durationsMs.size]

    /**
     * Tamaño en píxeles del vídeo exportado: el lienzo elegido o, si no hay, el cuadro del vídeo tal
     * como se ve ([metadataRotation] es la rotación guardada en el archivo), girado con [rotation] y
     * recortado al 75 % si se pidió [crop]. Los codificadores piden medidas pares.
     */
    fun outputSize(
        width: Int,
        height: Int,
        metadataRotation: Int,
        rotation: Float,
        crop: Boolean,
        canvasWidth: Int,
        canvasHeight: Int
    ): Pair<Int, Int> {
        if (canvasWidth > 0 && canvasHeight > 0) return even(canvasWidth) to even(canvasHeight)
        require(width > 0 && height > 0) { "No se pudo leer el tamaño del vídeo" }
        var w = width
        var h = height
        if (metadataRotation % 180 != 0) w = h.also { h = w }
        if (rotation.roundToInt() % 180 != 0) w = h.also { h = w }
        if (crop) {
            w = (w * 0.75f).roundToInt()
            h = (h * 0.75f).roundToInt()
        }
        return even(w) to even(h)
    }

    private fun even(value: Int) = maxOf(2, value - value % 2)

    /**
     * La parte central de una imagen de [srcW]×[srcH] que llena un cuadro de [dstW]×[dstH] sin
     * deformarla: se recortan los lados que sobran. Devuelve izquierda, arriba, derecha y abajo.
     */
    fun coverRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        // Se compara srcW/srcH con dstW/dstH sin divisiones para no perder precisión.
        return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
            val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
            val left = (srcW - w) / 2
            intArrayOf(left, 0, left + w, srcH)
        } else {
            val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
            val top = (srcH - h) / 2
            intArrayOf(0, top, srcW, top + h)
        }
    }

    /**
     * Tramo del vídeo dentro del resultado, en microsegundos: empieza al acabar la intro y dura lo
     * que suman los trozos ([clipsMs]) a la velocidad elegida. Si falta alguna duración, el final es
     * desconocido y se devuelve Long.MAX_VALUE como fin.
     */
    fun videoWindowUs(introMs: Long, clipsMs: List<Long?>, speed: Float): Pair<Long, Long> {
        val start = introMs * 1000
        if (clipsMs.any { it == null || it < 0 }) return start to Long.MAX_VALUE
        val total = clipsMs.sumOf { it!! } * 1000
        return start to start + (total / speed).toLong()
    }

    /** Si el texto va en blanco o en negro para leerse sobre [background]. */
    fun lightText(background: Int): Boolean {
        val red = (background shr 16) and 0xFF
        val green = (background shr 8) and 0xFF
        val blue = background and 0xFF
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 150
    }

    /** Dibuja la tarjeta con el tamaño del vídeo y la guarda como PNG en [target]. */
    fun render(target: File, width: Int, height: Int, text: String, image: String, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color or 0xFF000000.toInt())
            if (image.isNotBlank()) {
                // Hasta el doble de píxeles que el cuadro (para recortar sin perder nitidez), con un tope de memoria.
                val photo = ImageTools.open(File(image), minOf(width.toLong() * height * 2, 12_000_000L))
                try {
                    val r = coverRect(photo.width, photo.height, width, height)
                    canvas.drawBitmap(
                        photo,
                        Rect(r[0], r[1], r[2], r[3]),
                        Rect(0, 0, width, height),
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                } finally {
                    photo.recycle()
                }
            }
            if (text.isNotBlank())
                drawTitle(canvas, text.trim(), width, height, image.isNotBlank() || lightText(color))
            FileOutputStream(target).use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    throw IOException("No se pudo crear la intro o el outro")
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Texto centrado, del mayor tamaño que quepa en el 84 % del ancho y el 70 % del alto. */
    private fun drawTitle(canvas: Canvas, text: String, width: Int, height: Int, light: Boolean) {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                this.color = if (light) Color.WHITE else Color.BLACK
            }
        val maxWidth = (width * 0.84f).toInt().coerceAtLeast(1)
        var size = minOf(width, height) * 0.12f
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            paint.setShadowLayer(size / 12, 0f, size / 24, if (light) 0xB0000000.toInt() else 0x80FFFFFF.toInt())
            layout =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
            if (layout.height <= height * 0.7f || size <= 10f) break
            size *= 0.85f
        }
        canvas.save()
        canvas.translate((width - maxWidth) / 2f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}
