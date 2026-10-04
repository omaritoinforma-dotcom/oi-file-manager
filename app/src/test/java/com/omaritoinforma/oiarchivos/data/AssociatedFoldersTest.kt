package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssociatedFoldersTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun foldersNamedLikeTheAppOrItsPackageAreFound() {
        val children = listOf("WhatsApp", "DCIM", "Download", "Telegram", "com.whatsapp", "whatsapp_viejo")
        assertEquals(listOf("WhatsApp", "com.whatsapp"), AssociatedFolders.matching(children, "WhatsApp", "com.whatsapp"))
    }

    @Test
    fun accentsSpacesAndCaseDoNotMatter() {
        val children = listOf("Prueba limpia", "PRUEBA_LIMPIA", "prueba-límpia", "Prueba", "limpiar")
        assertEquals(
            listOf("Prueba limpia", "PRUEBA_LIMPIA", "prueba-límpia"),
            AssociatedFolders.matching(children, "Prueba límpia", "com.omaritoinforma.prueba.otra"))
    }

    @Test
    fun androidFoldersAndGenericNamesAreNeverProposed() {
        // Una app llamada «Music» o con paquete …camera no se lleva las carpetas comunes.
        assertTrue(AssociatedFolders.matching(listOf("Music", "Download", "DCIM"), "Music", "com.example.music").isEmpty())
        assertTrue(AssociatedFolders.matching(listOf("Camera", "camera"), "Cámara", "com.example.camera").isEmpty())
        assertTrue(AssociatedFolders.matching(listOf("Android", "OI Archivos"), "Android", "android").isEmpty())
    }

    @Test
    fun hiddenFoldersAreLeftAlone() {
        assertTrue(AssociatedFolders.matching(listOf(".whatsapp"), "WhatsApp", "com.whatsapp").isEmpty())
    }

    @Test
    fun theLastPartOfThePackageCountsOnlyIfItIsSpecific() {
        assertEquals(
            listOf("Spotify"),
            AssociatedFolders.matching(listOf("Spotify", "Other"), "Música Plus", "com.spotify"))
        // «app» o «lite» no identifican a nadie.
        assertTrue(AssociatedFolders.matching(listOf("app", "Lite"), "Mi programa", "org.example.lite").isEmpty())
    }

    @Test
    fun findOnlyLooksAtFirstLevelDirectories() {
        val root = temp.newFolder("sdcard")
        File(root, "Prueba limpia").mkdirs()
        File(root, "Prueba limpia/datos.txt").writeText("x")
        File(root, "Otra/Prueba limpia").mkdirs()
        File(root, "prueba limpia.txt").writeText("un archivo, no una carpeta")
        val found = AssociatedFolders.find(root, "Prueba limpia", "com.omaritoinforma.prueba.limpia")
        assertEquals(listOf(File(root, "Prueba limpia")), found)
    }
}
