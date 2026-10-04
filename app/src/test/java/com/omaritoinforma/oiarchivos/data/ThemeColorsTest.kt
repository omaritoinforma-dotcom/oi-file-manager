package com.omaritoinforma.oiarchivos.data

import androidx.compose.ui.graphics.Color
import com.omaritoinforma.oiarchivos.ui.theme.accentPrimary
import com.omaritoinforma.oiarchivos.ui.theme.staticScheme
import com.omaritoinforma.oiarchivos.ui.theme.withPureBlack
import org.junit.Assert.*
import org.junit.Test

class ThemeColorsTest {
    @Test
    fun everyAccentHasItsOwnPrimaryInLightAndDark() {
        val light = AccentColor.entries.filter { it != AccentColor.DYNAMIC }.map { accentPrimary(it, dark = false) }
        assertEquals("Dos colores comparten el mismo tono claro", light.size, light.toSet().size)
        for (accent in AccentColor.entries) {
            assertEquals(accentPrimary(accent, false), staticScheme(accent, false).primary)
            assertEquals(accentPrimary(accent, true), staticScheme(accent, true).primary)
        }
    }

    @Test
    fun darkPrimariesAreLighterThanLightOnesForContrast() {
        fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        for (accent in AccentColor.entries.filter { it != AccentColor.DYNAMIC })
            assertTrue("$accent: en oscuro el principal debe ser más claro", luminance(accentPrimary(accent, true)) > luminance(accentPrimary(accent, false)))
    }

    @Test
    fun pureBlackOnlyChangesBackgroundsNotTheAccent() {
        val dark = staticScheme(AccentColor.RED, dark = true)
        val black = withPureBlack(dark)
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(dark.primary, black.primary)
        assertEquals(dark.onSurface, black.onSurface)
    }

    @Test
    fun accentAndPureBlackAreValidatedInTheSettingsBackup() {
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"accent":"RED","pure_black":true}}"""
        val parsed = SettingsBackup.parse(ok)
        assertEquals("RED", parsed["accent"])
        assertEquals(true, parsed["pure_black"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(ok.replace("RED", "FUCSIA")) }
    }
}
