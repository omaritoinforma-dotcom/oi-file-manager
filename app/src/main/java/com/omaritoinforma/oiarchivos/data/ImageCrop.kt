package com.omaritoinforma.oiarchivos.data

import kotlin.math.max
import kotlin.math.min

/** Proporciones del recuadro de recorte (como las de ES: libre, cuadrado, 4:3, 16:9…). */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Libre", null),
    SQUARE("1:1", 1f),
    LANDSCAPE_4_3("4:3", 4f / 3f),
    PORTRAIT_3_4("3:4", 3f / 4f),
    LANDSCAPE_16_9("16:9", 16f / 9f),
    PORTRAIT_9_16("9:16", 9f / 16f)
}

/** Esquina del recuadro que se arrastra. */
enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Recuadro de recorte en fracciones (0 a 1) del ancho y el alto de la imagen: no depende del
 * tamaño en pantalla ni de la resolución real.
 */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    companion object {
        val FULL = CropBox(0f, 0f, 1f, 1f)
    }
}

/** Zona de recorte en píxeles de la imagen. */
data class CropPixels(val x: Int, val y: Int, val width: Int, val height: Int)

/** Cuentas del recorte; no usa Android para poder probarlas. */
object ImageCrop {
    /** Lado mínimo del recuadro, como fracción de la imagen. */
    const val MIN = 0.05f

    /** El recuadro más grande y centrado con la proporción pedida; libre: la imagen entera. */
    fun fit(aspect: CropAspect, imageWidth: Int, imageHeight: Int): CropBox {
        val ratio = aspect.ratio ?: return CropBox.FULL
        val imageRatio = imageWidth.toFloat() / imageHeight
        val w: Float
        val h: Float
        if (imageRatio > ratio) {
            h = 1f
            w = ratio / imageRatio
        } else {
            w = 1f
            h = imageRatio / ratio
        }
        val left = (1f - w) / 2f
        val top = (1f - h) / 2f
        return CropBox(left, top, left + w, top + h)
    }

    /** Desplaza el recuadro sin cambiarle el tamaño ni dejar que salga de la imagen. */
    fun move(box: CropBox, dx: Float, dy: Float): CropBox {
        val x = (box.left + dx).coerceIn(0f, 1f - box.width)
        val y = (box.top + dy).coerceIn(0f, 1f - box.height)
        return CropBox(x, y, x + box.width, y + box.height)
    }

    /**
     * Arrastra una esquina; la de enfrente se queda quieta. Con proporción fija el recuadro la
     * conserva (en píxeles, no en fracciones). Nunca sale de la imagen ni baja de [MIN].
     */
    fun resize(
        box: CropBox,
        corner: CropCorner,
        dx: Float,
        dy: Float,
        aspect: CropAspect,
        imageWidth: Int,
        imageHeight: Int
    ): CropBox {
        val movesLeft = corner == CropCorner.TOP_LEFT || corner == CropCorner.BOTTOM_LEFT
        val movesTop = corner == CropCorner.TOP_LEFT || corner == CropCorner.TOP_RIGHT
        val anchorX = if (movesLeft) box.right else box.left
        val anchorY = if (movesTop) box.bottom else box.top
        val cornerX = (if (movesLeft) box.left else box.right) + dx
        val cornerY = (if (movesTop) box.top else box.bottom) + dy
        // Sitio que hay desde el ancla hasta el borde de la imagen, hacia donde se arrastra.
        val roomX = if (movesLeft) anchorX else 1f - anchorX
        val roomY = if (movesTop) anchorY else 1f - anchorY
        // Con signo: si la esquina pasa al otro lado del ancla, el recuadro se queda en el mínimo.
        var w = (if (movesLeft) anchorX - cornerX else cornerX - anchorX).coerceIn(MIN, max(MIN, roomX))
        var h = (if (movesTop) anchorY - cornerY else cornerY - anchorY).coerceIn(MIN, max(MIN, roomY))
        val ratio = aspect.ratio
        if (ratio != null) {
            // En fracciones: ancho / alto = proporción · alto de la imagen / ancho de la imagen.
            val fractionRatio = ratio * imageHeight / imageWidth
            val byWidth = w / fractionRatio
            if (byWidth <= h) h = byWidth else w = h * fractionRatio
            // Si al ajustar se pasa de los límites, se encoge por el lado que falta.
            val scale = min(1f, min(roomX / w, roomY / h))
            w *= scale
            h *= scale
            if (w < MIN) {
                w = MIN
                h = w / fractionRatio
            }
            if (h < MIN) {
                h = MIN
                w = h * fractionRatio
            }
        }
        val left = if (movesLeft) anchorX - w else anchorX
        val top = if (movesTop) anchorY - h else anchorY
        return CropBox(left, top, left + w, top + h)
    }

    /** Zona en píxeles: dentro de la imagen y de al menos 1×1. */
    fun pixels(box: CropBox, imageWidth: Int, imageHeight: Int): CropPixels {
        val x = Math.round(box.left * imageWidth).coerceIn(0, imageWidth - 1)
        val y = Math.round(box.top * imageHeight).coerceIn(0, imageHeight - 1)
        val right = Math.round(box.right * imageWidth).coerceIn(x + 1, imageWidth)
        val bottom = Math.round(box.bottom * imageHeight).coerceIn(y + 1, imageHeight)
        return CropPixels(x, y, right - x, bottom - y)
    }

    /** Menor potencia de dos con la que la imagen cabe en [maxPixels] píxeles al abrirla. */
    fun sampleSize(imageWidth: Int, imageHeight: Int, maxPixels: Long): Int {
        var sample = 1
        while ((imageWidth.toLong() / sample) * (imageHeight.toLong() / sample) > maxPixels) sample *= 2
        return sample
    }
}

/** Formatos en que se guarda una imagen editada. */
enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp");

    companion object {
        /** El mismo formato que el original; lo demás (gif, bmp, heic…) se guarda como PNG, sin pérdida. */
        fun forExtension(ext: String): ImageFormat =
            when (ext.lowercase()) {
                "jpg", "jpeg" -> JPEG
                "webp" -> WEBP
                else -> PNG
            }
    }
}
