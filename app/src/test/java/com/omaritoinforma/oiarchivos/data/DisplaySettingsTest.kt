package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class DisplaySettingsTest {
    private fun backup(settings: String) =
        """{"formato":"OI Archivos ajustes","version":1,"ajustes":{$settings}}"""

    @Test
    fun highlightLimitIsInKilobytes() {
        assertTrue(EditorText.highlightApplies(100 * 1024, 100))
        assertFalse(EditorText.highlightApplies(100 * 1024 + 1, 100))
        assertTrue(EditorText.highlightApplies(2000 * 1024, 2000))
        // Sin desbordar con límites grandes.
        assertTrue(EditorText.highlightApplies(Int.MAX_VALUE, 5000 * 1024))
        assertEquals(100, EditorText.DEFAULT_HIGHLIGHT_LIMIT_KB)
    }

    @Test
    fun theBackupAcceptsTheNewDisplaySettings() {
        val parsed =
            SettingsBackup.parse(
                backup(
                    "\"toolbar_show_name\":false,\"show_select_button\":true,\"screen_orientation\":\"LANDSCAPE\"," +
                        "\"large_layout\":true,\"editor_highlight_limit_kb\":500,\"ftp_port\":2299,\"ftp_encoding\":\"GBK\""))
        assertEquals(false, parsed["toolbar_show_name"])
        assertEquals(true, parsed["show_select_button"])
        assertEquals("LANDSCAPE", parsed["screen_orientation"])
        assertEquals(true, parsed["large_layout"])
        assertEquals(500, parsed["editor_highlight_limit_kb"])
        assertEquals(2299, parsed["ftp_port"])
        assertEquals("GBK", parsed["ftp_encoding"])
    }

    @Test
    fun theBackupRejectsWrongValues() {
        for (bad in listOf(
            "\"screen_orientation\":\"DIAGONAL\"",
            "\"toolbar_show_name\":\"si\"",
            "\"editor_highlight_limit_kb\":9",
            "\"editor_highlight_limit_kb\":5001",
            "\"ftp_port\":70000",
            "\"ftp_encoding\":\"EBCDIC\""))
            assertThrows(bad, java.io.IOException::class.java) { SettingsBackup.parse(backup(bad)) }
    }

    @Test
    fun backgroundImageStrengthDecidesHowMuchOfTheScreenColourIsKept() {
        assertEquals(0.75f, BackgroundImage.overlayAlpha(25), 1e-6f)
        // Fuera de rango se ajusta: nunca queda la imagen tan visible que no se lea.
        assertEquals(0.9f, BackgroundImage.overlayAlpha(0), 1e-6f)
        assertEquals(0.4f, BackgroundImage.overlayAlpha(100), 1e-6f)
        assertTrue(BackgroundImage.DEFAULT_STRENGTH in BackgroundImage.strengths)
    }

    @Test
    fun theBackupKeepsTheStrengthButNeverThePrivateImage() {
        assertEquals(40, SettingsBackup.parse(backup("\"background_strength\":40"))["background_strength"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(backup("\"background_strength\":90")) }
        assertFalse(SettingsBackup.keys.containsKey("background_image"))
    }
}
