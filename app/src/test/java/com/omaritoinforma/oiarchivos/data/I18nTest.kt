package com.omaritoinforma.oiarchivos.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class I18nTest {
    @After
    fun backToSpanish() = I18n.use(emptyMap())

    @Test
    fun spanishIsShownAsWrittenAndMissingTextsNeverDisappear() {
        assertEquals("Copiar", tr("Copiar"))
        I18n.use(mapOf("Copiar" to "Copy"))
        assertEquals("Copy", tr("Copiar"))
        assertEquals("Pegar", tr("Pegar")) // sin traducción: en español, no vacío
    }

    @Test
    fun placeholdersAreFilledInTheTranslatedOrder() {
        I18n.use(mapOf("{0} de {1} archivos" to "{0} of {1} files", "Mover {0} a {1}" to "Move to {1}: {0}"))
        assertEquals("3 of 7 files", tr("{0} de {1} archivos", 3, 7))
        assertEquals("Move to Música: a.mp3", tr("Mover {0} a {1}", "a.mp3", "Música"))
        // Sin traducción también se rellenan; lo que no es una marca se queda igual.
        assertEquals("Quedan 5 {x} {} {9}", tr("Quedan {0} {x} {} {9}", 5))
    }

    @Test
    fun theCatalogFileIsParsedWithEscapes() {
        val catalog =
            I18n.parse(
                "# comentario\n\nCopiar\tCopy\nLínea 1\\nLínea 2\tLine 1\\nLine 2\r\nBarra \\\\ y tab\\t\tBackslash \\\\ and tab\\t\n")
        assertEquals(
            mapOf("Copiar" to "Copy", "Línea 1\nLínea 2" to "Line 1\nLine 2", "Barra \\ y tab\t" to "Backslash \\ and tab\t"),
            catalog)
    }

    @Test
    fun badCatalogLinesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { I18n.parse("sin tabulador\n") }
        assertThrows(IllegalArgumentException::class.java) { I18n.parse("a\tb\tc\n") }
        assertThrows(IllegalArgumentException::class.java) { I18n.parse("Copiar\tCopy\nCopiar\tCopy again\n") }
    }

    @Test
    fun systemLanguageMeansSpanishOnlyOnSpanishPhones() {
        assertEquals(AppLanguage.SPANISH, I18n.effective(AppLanguage.SYSTEM, Locale("es", "MX")))
        assertEquals(AppLanguage.ENGLISH, I18n.effective(AppLanguage.SYSTEM, Locale.US))
        assertEquals(AppLanguage.ENGLISH, I18n.effective(AppLanguage.SYSTEM, Locale.FRANCE))
        assertEquals(AppLanguage.SPANISH, I18n.effective(AppLanguage.SPANISH, Locale.US))
        assertEquals(AppLanguage.ENGLISH, I18n.effective(AppLanguage.ENGLISH, Locale("es")))
    }
}
