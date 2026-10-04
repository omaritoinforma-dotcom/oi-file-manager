package com.omaritoinforma.oiarchivos.data

import java.util.Locale

/** Ayudas del editor de texto, como las opciones del editor de ES. */
object EditorText {
    /** Símbolos de la barra de símbolos si no se eligen otros (ES: «Lista de símbolos personalizada»). */
    const val DEFAULT_SYMBOLS = "{ } ( ) [ ] < > = ; : \" ' / \\ | & ! ? # $ % * + - _ ~ ^ @ ,"

    /** Lo que escribe la tecla Tab: un tabulador o [size] espacios (ES: «Usar espacios en lugar de tabuladores»). */
    fun tab(spaces: Boolean, size: Int): String = if (spaces) " ".repeat(size.coerceIn(1, 8)) else "\t"

    /** Símbolos de la lista personalizada: separados por espacios, sin repetir. */
    fun symbols(list: String): List<String> = list.split(' ').filter { it.isNotEmpty() }.distinct().take(60)

    /** Sustituye la selección por [insert]; devuelve el texto y el cursor, que queda detrás. */
    fun insert(text: String, start: Int, end: Int, insert: String): Pair<String, Int> {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        return (text.substring(0, a) + insert + text.substring(b)) to (a + insert.length)
    }

    /**
     * Mayúsculas o minúsculas de la selección o, si no hay selección, de todo el texto. Devuelve el
     * texto y la selección nueva (la longitud puede cambiar: «ß» pasa a «SS»).
     */
    fun changeCase(text: String, start: Int, end: Int, upper: Boolean, locale: Locale = Locale("es")): Triple<String, Int, Int> {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        fun convert(part: String) = if (upper) part.uppercase(locale) else part.lowercase(locale)
        if (a == b) {
            val all = convert(text)
            val cursor = a.coerceAtMost(all.length)
            return Triple(all, cursor, cursor)
        }
        val part = convert(text.substring(a, b))
        return Triple(text.substring(0, a) + part + text.substring(b), a, a + part.length)
    }

    /** Duplica debajo la línea del cursor, o las líneas que toca la selección; el cursor pasa a la copia. */
    fun duplicateLines(text: String, start: Int, end: Int): Triple<String, Int, Int> {
        val a = minOf(start, end).coerceIn(0, text.length)
        val original = maxOf(start, end).coerceIn(0, text.length)
        // Una selección que acaba justo detrás de un salto de línea no incluye la línea siguiente.
        val b = if (original > a && text[original - 1] == '\n') original - 1 else original
        val lineStart = text.lastIndexOf('\n', a - 1) + 1
        val lineEnd = text.indexOf('\n', b).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val shift = block.length + 1
        return Triple(text.substring(0, lineEnd) + "\n" + block + text.substring(lineEnd), a + shift, original + shift)
    }

    /** Dibuja los espacios como «·» y los tabuladores como «→» sin cambiar las posiciones. */
    fun showWhitespace(text: String): String = text.replace(' ', '·').replace('\t', '→')

    /**
     * Tamaño máximo por omisión (KB) para colorear el código (ES: «Restringir tamaño del archivo
     * resaltado»). Colorear se repite con cada tecla, así que el límite se puede subir en Ajustes.
     */
    const val DEFAULT_HIGHLIGHT_LIMIT_KB = 100

    /** Si un texto de [length] caracteres se colorea con un límite de [limitKb] KB. */
    fun highlightApplies(length: Int, limitKb: Int): Boolean = length <= limitKb.toLong() * 1024

    /**
     * Sangría automática: si el cambio fue escribir un salto de línea en [cursor], copia al principio
     * de la línea nueva los espacios y tabuladores de la anterior. Devuelve el texto y el cursor
     * nuevos, o null si no hay nada que hacer.
     */
    fun autoIndent(before: String, after: String, cursor: Int): Pair<String, Int>? {
        if (after.length != before.length + 1 || cursor <= 0 || cursor > after.length) return null
        if (after[cursor - 1] != '\n') return null
        if (after.substring(0, cursor - 1) != before.substring(0, cursor - 1)) return null
        val lineStart = after.lastIndexOf('\n', cursor - 2) + 1
        val indent = after.substring(lineStart, cursor - 1).takeWhile { it == ' ' || it == '\t' }
        if (indent.isEmpty()) return null
        return (after.substring(0, cursor) + indent + after.substring(cursor)) to (cursor + indent.length)
    }
}
