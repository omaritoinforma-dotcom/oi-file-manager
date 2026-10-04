package com.omaritoinforma.oiarchivos.data

/** Ayudas del editor de texto, como las opciones del editor de ES. */
object EditorText {
    /** Tamaño máximo para colorear el código (ES: «Restringir tamaño del archivo resaltado»). */
    const val HIGHLIGHT_LIMIT = 500 * 1024

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
