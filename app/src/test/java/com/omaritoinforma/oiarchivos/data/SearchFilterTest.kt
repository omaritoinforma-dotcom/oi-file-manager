package com.omaritoinforma.oiarchivos.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchFilterTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("raiz")
        File(root, "foto.PNG").writeBytes(ByteArray(10))
        File(root, "nota.txt").writeText("hola")
        File(root, "cancion.mp3").writeBytes(ByteArray(10))
        File(root, "datos.zip").writeBytes(ByteArray(10))
        File(root, "app.apk").writeBytes(ByteArray(10))
        File(root, "informe.pdf").writeBytes(ByteArray(10))
        File(root, "main.kt").writeText("fun main() {}")
        File(root, ".secreto.txt").writeText("oculto")
        File(root, "carpeta").mkdir()
        File(root, "carpeta/dentro.txt").writeText("dentro")
        File(root, ".privada").mkdir()
        File(root, ".privada/escondido.png").writeBytes(ByteArray(10))
    }

    private fun names(filter: SearchFilter): Set<String> =
        runBlocking { AnalysisTools.search(root, filter) {} }.map { it.name }.toSet()

    @Test
    fun noTypeMeansAnyType() {
        val all = names(SearchFilter())
        assertTrue("foto.PNG" in all && "carpeta" in all && "dentro.txt" in all)
    }

    @Test
    fun typesAreMatchedByExtensionIgnoringCase() {
        assertEquals(setOf("foto.PNG", "escondido.png"), names(SearchFilter(types = setOf(SearchKind.IMAGES))))
        assertEquals(setOf("cancion.mp3"), names(SearchFilter(types = setOf(SearchKind.AUDIO))))
        assertEquals(setOf("datos.zip"), names(SearchFilter(types = setOf(SearchKind.ARCHIVES))))
        assertEquals(setOf("app.apk"), names(SearchFilter(types = setOf(SearchKind.APK))))
        assertEquals(setOf("informe.pdf"), names(SearchFilter(types = setOf(SearchKind.DOCUMENTS))))
        assertEquals(
            setOf("nota.txt", "main.kt", "dentro.txt", ".secreto.txt"),
            names(SearchFilter(types = setOf(SearchKind.TEXT))))
    }

    @Test
    fun foldersAreAType() {
        assertEquals(setOf("carpeta", ".privada"), names(SearchFilter(types = setOf(SearchKind.FOLDERS))))
    }

    @Test
    fun severalTypesAreAddedTogether() {
        assertEquals(
            setOf("foto.PNG", "escondido.png", "cancion.mp3"),
            names(SearchFilter(types = setOf(SearchKind.IMAGES, SearchKind.AUDIO))))
    }

    @Test
    fun hiddenItemsAndWhatIsInsideHiddenFoldersCanBeLeftOut() {
        val shown = names(SearchFilter(hidden = false))
        assertFalse(".secreto.txt" in shown)
        assertFalse(".privada" in shown)
        assertFalse("escondido.png" in shown)
        assertTrue("foto.PNG" in shown && "dentro.txt" in shown)
        assertEquals(setOf("foto.PNG"), names(SearchFilter(types = setOf(SearchKind.IMAGES), hidden = false)))
    }

    @Test
    fun hiddenRootFolderStillSearchedWhenYouAreInsideIt() {
        val inside = File(root, ".privada")
        val found = runBlocking { AnalysisTools.search(inside, SearchFilter(hidden = false)) {} }
        assertEquals(listOf("escondido.png"), found.map { it.name })
    }

    @Test
    fun subfoldersCanBeLeftOut() {
        val top = names(SearchFilter(subfolders = false))
        assertTrue("carpeta" in top && "nota.txt" in top)
        assertFalse("dentro.txt" in top)
        assertFalse("escondido.png" in top)
    }

    @Test
    fun typeCombinesWithNameSizeAndText() {
        assertEquals(setOf("nota.txt"), names(SearchFilter(name = "not", types = setOf(SearchKind.TEXT))))
        assertEquals(emptySet<String>(), names(SearchFilter(name = "foto", types = setOf(SearchKind.AUDIO))))
        assertEquals(
            setOf("nota.txt"),
            names(SearchFilter(text = "hola", types = setOf(SearchKind.TEXT), hidden = false)))
        // Entre 5 y 20 bytes: «nota.txt» (4 bytes) se queda fuera.
        assertEquals(
            setOf("foto.PNG", "main.kt", "dentro.txt"),
            names(SearchFilter(min = 5, max = 20, types = setOf(SearchKind.IMAGES, SearchKind.TEXT), hidden = false)))
    }
}
