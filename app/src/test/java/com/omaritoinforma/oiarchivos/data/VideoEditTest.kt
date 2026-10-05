package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditTest {
    @Test
    fun identityExportSkipsTransformerOnlyWhenNothingChanges() {
        assertTrue(VideoEdit().isIdentityExport())

        assertFalse(VideoEdit(startMs = 1).isIdentityExport())
        assertFalse(VideoEdit(endMs = 1000).isIdentityExport())
        assertFalse(VideoEdit(rotation = 90f).isIdentityExport())
        assertFalse(VideoEdit(speed = 1.5f).isIdentityExport())
        assertFalse(VideoEdit(crop = true).isIdentityExport())
        assertFalse(VideoEdit(caption = "OI").isIdentityExport())
        assertFalse(VideoEdit(music = "/music.mp3").isIdentityExport())
        assertFalse(VideoEdit(join = listOf("/next.mp4")).isIdentityExport())
        assertFalse(VideoEdit(mute = true).isIdentityExport())
        assertFalse(VideoEdit(image = "/logo.png").isIdentityExport())
        assertFalse(VideoEdit(subtitles = "/captions.srt").isIdentityExport())
        assertFalse(VideoEdit(canvasWidth = 720, canvasHeight = 1280).isIdentityExport())
        assertFalse(VideoEdit(backgroundImage = "/background.jpg").isIdentityExport())
    }
}
