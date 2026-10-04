package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class ImageCropTest {
    private val eps = 1e-4f

    private fun ratio(box: CropBox, w: Int, h: Int) = (box.width * w) / (box.height * h)

    @Test
    fun freeIsTheWholeImageAndFixedRatiosAreCentredAndAsBigAsPossible() {
        assertEquals(CropBox.FULL, ImageCrop.fit(CropAspect.FREE, 800, 600))
        // 800×600 con 1:1 → 600×600 centrado.
        val square = ImageCrop.fit(CropAspect.SQUARE, 800, 600)
        assertEquals(1f, square.height, eps)
        assertEquals(0.75f, square.width, eps)
        assertEquals(0.125f, square.left, eps)
        // 800×600 con 16:9 → 800×450.
        val wide = ImageCrop.fit(CropAspect.LANDSCAPE_16_9, 800, 600)
        assertEquals(1f, wide.width, eps)
        assertEquals(0.75f, wide.height, eps)
        assertEquals(16f / 9f, ratio(wide, 800, 600), 1e-3f)
        // Una imagen vertical con 9:16 casi se queda entera.
        val tall = ImageCrop.fit(CropAspect.PORTRAIT_9_16, 600, 1000)
        assertEquals(9f / 16f, ratio(tall, 600, 1000), 1e-3f)
        assertTrue(tall.left >= 0f && tall.right <= 1f && tall.top >= 0f && tall.bottom <= 1f)
    }

    @Test
    fun movingKeepsTheSizeAndStaysInsideTheImage() {
        val box = CropBox(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = ImageCrop.move(box, 0.1f, -0.1f)
        assertEquals(0.4f, moved.width, eps)
        assertEquals(0.3f, moved.height, eps)
        assertEquals(0.3f, moved.left, eps)
        assertEquals(0.1f, moved.top, eps)
        val stuck = ImageCrop.move(box, 5f, 5f)
        assertEquals(1f, stuck.right, eps)
        assertEquals(1f, stuck.bottom, eps)
        assertEquals(0.4f, stuck.width, eps)
        val corner = ImageCrop.move(box, -5f, -5f)
        assertEquals(0f, corner.left, eps)
        assertEquals(0f, corner.top, eps)
    }

    @Test
    fun resizingFreelyMovesOnlyTheDraggedCorner() {
        val box = CropBox(0.2f, 0.2f, 0.6f, 0.6f)
        val bigger = ImageCrop.resize(box, CropCorner.BOTTOM_RIGHT, 0.1f, 0.2f, CropAspect.FREE, 1000, 1000)
        assertEquals(0.2f, bigger.left, eps)
        assertEquals(0.2f, bigger.top, eps)
        assertEquals(0.7f, bigger.right, eps)
        assertEquals(0.8f, bigger.bottom, eps)
        val smaller = ImageCrop.resize(box, CropCorner.TOP_LEFT, 0.1f, 0.1f, CropAspect.FREE, 1000, 1000)
        assertEquals(0.3f, smaller.left, eps)
        assertEquals(0.3f, smaller.top, eps)
        assertEquals(0.6f, smaller.right, eps)
        assertEquals(0.6f, smaller.bottom, eps)
    }

    @Test
    fun resizingNeverLeavesTheImageOrGetsTinyOrFlips() {
        val box = CropBox(0.2f, 0.2f, 0.6f, 0.6f)
        val out = ImageCrop.resize(box, CropCorner.BOTTOM_RIGHT, 9f, 9f, CropAspect.FREE, 1000, 1000)
        assertEquals(1f, out.right, eps)
        assertEquals(1f, out.bottom, eps)
        // Arrastrar la esquina más allá de la de enfrente no da un recuadro al revés: queda el mínimo.
        val tiny = ImageCrop.resize(box, CropCorner.BOTTOM_RIGHT, -9f, -9f, CropAspect.FREE, 1000, 1000)
        assertEquals(ImageCrop.MIN, tiny.width, eps)
        assertEquals(ImageCrop.MIN, tiny.height, eps)
        assertEquals(0.2f, tiny.left, eps)
        val topLeft = ImageCrop.resize(box, CropCorner.TOP_LEFT, -9f, -9f, CropAspect.FREE, 1000, 1000)
        assertEquals(0f, topLeft.left, eps)
        assertEquals(0f, topLeft.top, eps)
        assertEquals(0.6f, topLeft.right, eps)
    }

    @Test
    fun fixedRatioIsKeptWhileResizingInEveryCorner() {
        // En una imagen apaisada, el 1:1 en fracciones no es cuadrado: la proporción cuenta en píxeles.
        val w = 800
        val h = 600
        val start = ImageCrop.fit(CropAspect.SQUARE, w, h)
        val small = ImageCrop.resize(start, CropCorner.BOTTOM_RIGHT, -0.3f, -0.1f, CropAspect.SQUARE, w, h)
        for (corner in CropCorner.entries) {
            for ((dx, dy) in listOf(0.05f to 0.02f, -0.1f to 0.3f, 0.4f to -0.4f, -0.5f to -0.5f)) {
                val out = ImageCrop.resize(small, corner, dx, dy, CropAspect.SQUARE, w, h)
                assertEquals("$corner $dx,$dy", 1f, ratio(out, w, h), 1e-3f)
                assertTrue(
                    "$corner $dx,$dy dentro: $out",
                    out.left >= -eps && out.top >= -eps && out.right <= 1f + eps && out.bottom <= 1f + eps)
                assertTrue("$corner $dx,$dy mínimo", out.width >= ImageCrop.MIN - eps && out.height >= ImageCrop.MIN - eps)
            }
        }
    }

    @Test
    fun fixedRatioResizeKeepsTheOppositeCornerStill() {
        val w = 1600
        val h = 900
        val start = CropBox(0.25f, 0.25f, 0.75f, 0.75f * 1f)
        val out = ImageCrop.resize(start, CropCorner.BOTTOM_RIGHT, 0.1f, 0.0f, CropAspect.LANDSCAPE_16_9, w, h)
        assertEquals(0.25f, out.left, eps)
        assertEquals(0.25f, out.top, eps)
        assertEquals(16f / 9f, ratio(out, w, h), 1e-3f)
    }

    @Test
    fun pixelsAreInsideTheImageAndAtLeastOne() {
        assertEquals(CropPixels(0, 0, 640, 480), ImageCrop.pixels(CropBox.FULL, 640, 480))
        assertEquals(CropPixels(160, 120, 320, 240), ImageCrop.pixels(CropBox(0.25f, 0.25f, 0.75f, 0.75f), 640, 480))
        val tiny = ImageCrop.pixels(CropBox(0.5f, 0.5f, 0.5001f, 0.5001f), 100, 100)
        assertEquals(1, tiny.width)
        assertEquals(1, tiny.height)
        val edge = ImageCrop.pixels(CropBox(0.9999f, 0.9999f, 1f, 1f), 100, 100)
        assertTrue(edge.x + edge.width <= 100 && edge.y + edge.height <= 100 && edge.width >= 1 && edge.height >= 1)
    }

    @Test
    fun hugeImagesAreOpenedReduced() {
        assertEquals(1, ImageCrop.sampleSize(4000, 3000, 24_000_000))
        assertEquals(2, ImageCrop.sampleSize(8000, 6000, 24_000_000))
        assertEquals(4, ImageCrop.sampleSize(16000, 12000, 24_000_000))
    }

    @Test
    fun savedFormatFollowsTheOriginal() {
        assertEquals(ImageFormat.JPEG, ImageFormat.forExtension("JPG"))
        assertEquals(ImageFormat.JPEG, ImageFormat.forExtension("jpeg"))
        assertEquals(ImageFormat.PNG, ImageFormat.forExtension("png"))
        assertEquals(ImageFormat.WEBP, ImageFormat.forExtension("webp"))
        // Lo que Android no sabe escribir (gif, bmp, heic…) pasa a PNG.
        for (ext in listOf("gif", "bmp", "heic", "tiff", "svg")) assertEquals(ImageFormat.PNG, ImageFormat.forExtension(ext))
    }
}
