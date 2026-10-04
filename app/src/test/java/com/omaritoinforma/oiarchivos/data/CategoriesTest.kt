package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class CategoriesTest {
    private fun inCategory(cat: FileCategory, path: String, mime: String? = null) =
        Categories.matches(cat, path, mime)

    @Test
    fun screenshotsAreRecognisedByFolderOrByName() {
        val s = FileCategory.SCREENSHOTS
        assertTrue(inCategory(s, "/storage/emulated/0/Pictures/Screenshots/a.png", "image/png"))
        assertTrue(inCategory(s, "/storage/emulated/0/DCIM/Screenshots/a.jpg", "image/jpeg"))
        assertTrue(inCategory(s, "/storage/emulated/0/Pictures/Capturas de pantalla/a.png", "image/png"))
        assertTrue(inCategory(s, "/storage/emulated/0/Download/Screenshot_20261004-120000.png", "image/png"))
        // Una foto normal o un archivo que no es imagen no cuentan.
        assertFalse(inCategory(s, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", "image/jpeg"))
        assertFalse(inCategory(s, "/storage/emulated/0/Pictures/Screenshots/notas.txt", "text/plain"))
        // Una carpeta cuyo nombre solo contiene la palabra no es la de capturas.
        assertFalse(inCategory(s, "/storage/emulated/0/Pictures/MisScreenshotsViejos/a.png", "image/png"))
    }

    @Test
    fun recordingsAreAudioInRecorderFolders() {
        val r = FileCategory.RECORDINGS
        assertTrue(inCategory(r, "/storage/emulated/0/Recordings/nota.m4a", "audio/mp4"))
        assertTrue(inCategory(r, "/storage/emulated/0/Music/Voice Recorder/a.amr", "audio/amr"))
        assertTrue(inCategory(r, "/storage/emulated/0/Grabaciones/b.wav"))
        assertFalse(inCategory(r, "/storage/emulated/0/Music/cancion.mp3", "audio/mpeg"))
        assertFalse(inCategory(r, "/storage/emulated/0/Recordings/video.mp4", "video/mp4"))
    }

    @Test
    fun officeIsSplitIntoWordExcelAndPowerPoint() {
        assertTrue(inCategory(FileCategory.WORD, "/a/informe.DOCX"))
        assertTrue(inCategory(FileCategory.WORD, "/a/carta.odt"))
        assertTrue(inCategory(FileCategory.EXCEL, "/a/datos.xlsx"))
        assertTrue(inCategory(FileCategory.EXCEL, "/a/tabla.csv"))
        assertTrue(inCategory(FileCategory.POWERPOINT, "/a/charla.pptx"))
        assertFalse(inCategory(FileCategory.WORD, "/a/datos.xlsx"))
        assertFalse(inCategory(FileCategory.EXCEL, "/a/informe.docx"))
        assertFalse(inCategory(FileCategory.POWERPOINT, "/a/informe.docx"))
        // Documentos sigue incluyéndolo todo.
        assertTrue(inCategory(FileCategory.DOCUMENTS, "/a/informe.docx"))
        assertTrue(inCategory(FileCategory.DOCUMENTS, "/a/charla.pptx"))
    }

    @Test
    fun ebooksAndTheOldCategoriesKeepWorking() {
        assertTrue(inCategory(FileCategory.EBOOKS, "/a/libro.epub"))
        assertTrue(inCategory(FileCategory.EBOOKS, "/a/libro.MOBI"))
        assertFalse(inCategory(FileCategory.EBOOKS, "/a/manual.pdf"))
        assertTrue(inCategory(FileCategory.APKS, "/a/app.apk"))
        assertTrue(inCategory(FileCategory.ARCHIVES, "/a/x.7z"))
        assertTrue(inCategory(FileCategory.IMAGES, "/a/x.png", "image/png"))
        assertFalse(inCategory(FileCategory.IMAGES, "/a/x.png", "audio/mpeg"))
    }
}
