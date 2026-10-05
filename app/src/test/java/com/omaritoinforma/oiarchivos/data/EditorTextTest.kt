package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class EditorTextTest {
    @Test
    fun newLineKeepsTheIndentOfThePreviousLine() {
        val before = "fun a() {\n    hola"
        val after = before + "\n"
        assertEquals("fun a() {\n    hola\n    " to after.length + 4, EditorText.autoIndent(before, after, after.length))
        val tabs = "\t\tx"
        assertEquals("\t\tx\n\t\t" to 6, EditorText.autoIndent(tabs, tabs + "\n", 4))
    }

    @Test
    fun newLineInTheMiddleIndentsTheRestOfTheLine() {
        val before = "  uno dos"
        val after = "  uno\n dos"
        assertEquals("  uno\n   dos" to 8, EditorText.autoIndent(before, after, 6))
    }

    @Test
    fun onlyReactsToTypingANewLine() {
        assertNull("Sin sangría no cambia nada", EditorText.autoIndent("hola", "hola\n", 5))
        assertNull("Escribir otra letra", EditorText.autoIndent("  a", "  ab", 4))
        assertNull("Pegar varias líneas", EditorText.autoIndent("  a", "  a\nb\n", 6))
        assertNull("Borrar", EditorText.autoIndent("  a\n", "  a", 3))
    }
}
