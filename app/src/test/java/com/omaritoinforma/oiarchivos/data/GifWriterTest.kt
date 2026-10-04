package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayOutputStream
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Node

class GifWriterTest {
    @Test
    fun animatedGifIsReadableAcrossLzwClearCodesAndPreservesFrameTimingAndColors() {
        val output = ByteArrayOutputStream()
        val width = 40
        val height = 24
        val first =
            IntArray(width * height) {
                if (it % width < width / 2) 0xffff0000.toInt() else 0xff0000ff.toInt()
            }
        val second = IntArray(width * height) { 0xff00ff00.toInt() }
        GifWriter(output, width, height).apply {
            begin()
            frame(first, 12)
            frame(second, 13)
            end()
        }
        // Android's compile API excludes java.desktop. The host test JVM still provides its
        // independent ImageIO GIF decoder; reflection keeps desktop types out of that API.
        val imageIoType = Class.forName("javax.imageio.ImageIO")
        val readerType = Class.forName("javax.imageio.ImageReader")
        val imageType = Class.forName("java.awt.image.BufferedImage")
        val metadataType = Class.forName("javax.imageio.metadata.IIOMetadata")
        val intType = Int::class.javaPrimitiveType!!
        val readers =
            imageIoType
                .getMethod("getImageReadersByFormatName", String::class.java)
                .invoke(null, "gif") as Iterator<*>
        val reader = requireNotNull(readers.next())
        val input =
            imageIoType
                .getMethod("createImageInputStream", Any::class.java)
                .invoke(null, output.toByteArray().inputStream()) as Closeable
        fun frame(index: Int): Any =
            requireNotNull(readerType.getMethod("read", intType).invoke(reader, index))
        fun pixel(image: Any, x: Int, y: Int): Int =
            imageType.getMethod("getRGB", intType, intType).invoke(image, x, y) as Int
        try {
            input.use {
                readerType.getMethod("setInput", Any::class.java).invoke(reader, input)
                assertEquals(
                    2,
                    readerType
                        .getMethod("getNumImages", Boolean::class.javaPrimitiveType!!)
                        .invoke(reader, true),
                )
                val image = frame(0)
                assertEquals(width, imageType.getMethod("getWidth").invoke(image))
                assertEquals(height, imageType.getMethod("getHeight").invoke(image))
                assertEquals(0xffff0000.toInt(), pixel(image, 0, 0))
                assertEquals(0xff0000ff.toInt(), pixel(image, width - 1, height - 1))
                assertEquals(0xff00ff00.toInt(), pixel(frame(1), width / 2, height / 2))
                for (index in 0..1) {
                    val metadata =
                        readerType.getMethod("getImageMetadata", intType).invoke(reader, index)
                    val root =
                        metadataType
                            .getMethod("getAsTree", String::class.java)
                            .invoke(metadata, "javax_imageio_gif_image_1.0") as Node
                    val control =
                        (0 until root.childNodes.length)
                            .map { root.childNodes.item(it) }
                            .single { it.nodeName == "GraphicControlExtension" }
                    assertEquals(
                        if (index == 0) "12" else "13",
                        control.attributes.getNamedItem("delayTime").nodeValue,
                    )
                }
            }
        } finally {
            readerType.getMethod("dispose").invoke(reader)
        }
    }

    @Test
    fun gifScalesPortraitAndLandscapeToTheSameMaximumSideWithoutEnlargingSmallClips() {
        assertEquals(480 to 270, gifDimensions(1920, 1080))
        assertEquals(270 to 480, gifDimensions(1080, 1920))
        assertEquals(160 to 96, gifDimensions(160, 96))
        assertEquals(1 to 480, gifDimensions(1, 3840))
    }
}
