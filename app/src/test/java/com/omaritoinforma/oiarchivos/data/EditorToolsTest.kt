package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class EditorToolsTest {
    @Test
    fun tabWritesSpacesOrATab() {
        assertEquals("    ", EditorText.tab(spaces = true, size = 4))
        assertEquals("  ", EditorText.tab(spaces = true, size = 2))
        assertEquals("\t", EditorText.tab(spaces = false, size = 4))
        assertEquals(" ".repeat(8), EditorText.tab(spaces = true, size = 99))
    }

    @Test
    fun insertReplacesTheSelection() {
        assertEquals("ab{cd" to 3, EditorText.insert("abcd", 2, 2, "{"))
        assertEquals("a{d" to 2, EditorText.insert("abcd", 3, 1, "{"))
    }

    @Test
    fun caseChangesTheSelectionOrEverything() {
        assertEquals(Triple("hOLA mundo", 1, 4), EditorText.changeCase("hola mundo", 1, 4, upper = true))
        assertEquals(Triple("ÑANDÚ ÁRBOL", 3, 3), EditorText.changeCase("ñandú árbol", 3, 3, upper = true))
        assertEquals(Triple("ñandú", 0, 0), EditorText.changeCase("ÑANDÚ", 0, 0, upper = false))
        // La longitud puede cambiar: la selección se ajusta.
        assertEquals(Triple("STRASSE", 0, 7), EditorText.changeCase("straße", 0, 6, upper = true))
    }

    @Test
    fun duplicatesTheLineOrTheSelectedLines() {
        assertEquals(Triple("uno\ndos\ndos\ntres", 9, 9), EditorText.duplicateLines("uno\ndos\ntres", 5, 5))
        assertEquals(Triple("solo\nsolo", 7, 7), EditorText.duplicateLines("solo", 2, 2))
        // Selección de dos líneas: se duplican las dos.
        assertEquals(Triple("a\nb\na\nb\nc", 4, 7), EditorText.duplicateLines("a\nb\nc", 0, 3))
        // Si la selección acaba tras un salto de línea, no entra la línea siguiente.
        assertEquals(Triple("a\na\nb", 2, 4), EditorText.duplicateLines("a\nb", 0, 2))
    }

    @Test
    fun whitespaceKeepsPositionsAndSymbolsListIsClean() {
        assertEquals("a·b→c", EditorText.showWhitespace("a b\tc"))
        assertEquals(listOf("{", "}", "<>"), EditorText.symbols("  {  } { <> "))
    }
}
