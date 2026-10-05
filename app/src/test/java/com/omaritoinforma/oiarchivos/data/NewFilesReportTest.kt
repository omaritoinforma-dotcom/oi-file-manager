package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class NewFilesReportTest {
    private fun e(path: String, size: Long = 1000) = NewFilesReport.Entry(path, size)

    @Test
    fun countsByTypeAndIgnoresHiddenAndAppData() {
        val report =
            NewFilesReport.build(
                listOf(
                    e("/sdcard/DCIM/a.jpg", 2_000_000),
                    e("/sdcard/DCIM/b.jpg", 3_000_000),
                    e("/sdcard/Movies/v.mp4", 5_000_000),
                    e("/sdcard/Download/app.apk"),
                    e("/sdcard/Download/datos.bin"),
                    e("/sdcard/.oculta/c.jpg"),
                    e("/sdcard/Android/data/com.x/cache.jpg"),
                    e("/sdcard/DCIM/a.jpg", 2_000_000)))
        assertEquals(5, report.total)
        assertEquals(2, report.perKind[NewFileKind.IMAGES])
        assertEquals(1, report.perKind[NewFileKind.VIDEOS])
        assertEquals(1, report.perKind[NewFileKind.APK])
        assertEquals(1, report.others)
        assertEquals(10_002_000L, report.bytes)
    }

    @Test
    fun textSummarisesWithoutNamingFiles() {
        val report = NewFilesReport.build(listOf(e("/s/a.jpg", 1_048_576), e("/s/b.jpg", 1_048_576), e("/s/x.bin", 0)))
        val text = NewFilesReport.text(report)!!
        assertTrue(text, text.startsWith("3 archivos nuevos ("))
        assertTrue(text, "2 imágenes" in text && "1 otros" in text)
        assertFalse(text, "a.jpg" in text)
        assertEquals("1 archivo nuevo", NewFilesReport.text(NewFilesReport.build(listOf(e("/s/a.jpg"))))!!.substringBefore(" ("))
    }

    @Test
    fun nothingNewMeansNoNotification() {
        assertNull(NewFilesReport.text(NewFilesReport.build(emptyList())))
        assertNull(NewFilesReport.text(NewFilesReport.build(listOf(e("/s/.oculto/a.jpg")))))
    }
}
