package com.omaritoinforma.oiarchivos.data

/** Acciones que se pueden poner como botón en la barra inferior al seleccionar («Barra de herramientas» de ES). */
enum class ToolbarAction(val label: String) {
    COPY("Copiar"),
    CUT("Cortar"),
    DELETE("Eliminar"),
    RENAME("Renombrar"),
    SHARE("Compartir"),
    COMPRESS("Comprimir"),
    PIN("Fijar arriba"),
    PROPERTIES("Propiedades")
}

/**
 * Qué botones salen en la barra inferior y en qué orden. «Más» siempre está al final y lleva el
 * resto de acciones, así que quitar un botón nunca hace perder una función. Las acciones que están en
 * la barra no se repiten dentro de «Más».
 */
object ToolbarLayout {
    /** Con más botones los textos no caben en una pantalla de teléfono. */
    const val MAX = 5

    val DEFAULT: List<ToolbarAction> =
        listOf(ToolbarAction.COPY, ToolbarAction.CUT, ToolbarAction.DELETE, ToolbarAction.RENAME)

    val defaultNames: List<String> = DEFAULT.map { it.name }

    /** Los botones guardados, sin repetidos ni nombres desconocidos y como mucho [MAX]. */
    fun actions(stored: List<String>): List<ToolbarAction> =
        stored
            .mapNotNull { name -> ToolbarAction.entries.firstOrNull { it.name == name } }
            .distinct()
            .take(MAX)

    /** Pone o quita [action] de la barra; para ponerla hay que tener sitio. */
    fun toggle(stored: List<String>, action: ToolbarAction): List<String> {
        val current = actions(stored)
        return when {
            action in current -> current - action
            current.size < MAX -> current + action
            else -> current
        }.map { it.name }
    }

    /** Sube (-1) o baja (+1) un botón que ya está en la barra. */
    fun move(stored: List<String>, action: ToolbarAction, delta: Int): List<String> {
        val list = actions(stored).toMutableList()
        val from = list.indexOf(action)
        val to = from + delta
        if (from < 0 || to !in list.indices) return list.map { it.name }
        list.add(to, list.removeAt(from))
        return list.map { it.name }
    }
}
