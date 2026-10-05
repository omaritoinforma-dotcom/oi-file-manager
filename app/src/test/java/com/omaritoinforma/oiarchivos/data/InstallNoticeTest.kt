package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class InstallNoticeTest {
    @Test
    fun theNoticeNamesTheAppAndItsSensitiveGroups() {
        val text = InstallNotice.text("Mi linterna", listOf(SensitiveGroup.CAMERA, SensitiveGroup.LOCATION))
        assertNotNull(text)
        assertTrue(text!!.contains("«Mi linterna»"))
        assertTrue(text.contains("Cámara") && text.contains("Ubicación"))
    }

    @Test
    fun anAppWithoutSensitivePermissionsGetsNoNotice() {
        assertNull(InstallNotice.text("Calculadora", emptyList()))
    }

    @Test
    fun theSettingIsInTheBackup() {
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"app_permission_notify":false}}"""
        assertEquals(false, SettingsBackup.parse(ok)["app_permission_notify"])
    }
}
