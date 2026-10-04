package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class VideoCardsTest {
    @Test
    fun theCardHasTheSizeOfTheEditedVideo() {
        // Vídeo vertical grabado de lado (rotación 90 en el archivo): se ve de 720×1280.
        assertEquals(720 to 1280, VideoCards.outputSize(1280, 720, 90, 0f, false, 0, 0))
        // Girado 90° en el editor vuelve a quedar horizontal; 270° también.
        assertEquals(1280 to 720, VideoCards.outputSize(1280, 720, 90, 90f, false, 0, 0))
        assertEquals(1280 to 720, VideoCards.outputSize(720, 1280, 0, 270f, false, 0, 0))
        // El recorte deja el 75 % de cada lado y las medidas siempre son pares.
        assertEquals(270 to 480, VideoCards.outputSize(360, 640, 0, 0f, true, 0, 0))
        assertEquals(272 to 480, VideoCards.outputSize(363, 641, 0, 180f, true, 0, 0))
        // Con lienzo manda el lienzo.
        assertEquals(1080 to 1080, VideoCards.outputSize(360, 640, 90, 90f, true, 1080, 1080))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVideoWithoutSizeIsRejected() {
        VideoCards.outputSize(0, 0, 0, 0f, false, 0, 0)
    }

    @Test
    fun thePictureIsCroppedAtTheCenterToFillTheFrame() {
        // Foto apaisada 2000×1000 en un vídeo vertical 720×1280: se toma una franja central.
        val r = VideoCards.coverRect(2000, 1000, 720, 1280)
        assertEquals(1000, r[3] - r[1])
        assertEquals(562, r[2] - r[0])
        assertEquals(2000 - r[2], r[0]) // centrada
        // Foto vertical en un vídeo horizontal: se recortan arriba y abajo.
        val v = VideoCards.coverRect(1000, 2000, 1280, 720)
        assertArrayEquals(intArrayOf(0, 719, 1000, 1281), v)
        // Misma proporción: la foto entera.
        assertArrayEquals(intArrayOf(0, 0, 640, 360), VideoCards.coverRect(640, 360, 1280, 720))
    }

    @Test
    fun theVideoStartsAfterTheIntroAndLastsItsTrimmedLengthAtTheChosenSpeed() {
        assertEquals(3_000_000L to 5_000_000L, VideoCards.videoWindowUs(3000, listOf(2000L), 1f))
        // Dos trozos (2 s y 4 s) a doble velocidad: 3 s.
        assertEquals(2_000_000L to 5_000_000L, VideoCards.videoWindowUs(2000, listOf(2000L, 4000L), 2f))
        // Sin intro empieza en 0; si no se sabe cuánto dura un trozo, el final queda abierto.
        assertEquals(0L to Long.MAX_VALUE, VideoCards.videoWindowUs(0, listOf(2000L, null), 1f))
    }

    @Test
    fun durationsCycleThroughTheChoices() {
        assertEquals(5000L, VideoCards.nextDuration(3000))
        assertEquals(2000L, VideoCards.nextDuration(5000))
        assertEquals(3000L, VideoCards.nextDuration(2000))
        assertEquals(2000L, VideoCards.nextDuration(1234)) // un valor raro vuelve al primero
    }

    @Test
    fun textIsWhiteOnDarkColorsAndBlackOnLightOnes() {
        assertTrue(VideoCards.lightText(0xFF202020.toInt()))
        assertTrue(VideoCards.lightText(0xFF1565C0.toInt()))
        assertFalse(VideoCards.lightText(0xFFFFFFFF.toInt()))
        assertFalse(VideoCards.lightText(0xFFFFEB3B.toInt()))
    }
}
