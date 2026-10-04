package com.omaritoinforma.oiarchivos.data

/** Opciones del menú lateral que se pueden ocultar y reordenar («Manejo de barra lateral» de ES). */
enum class DrawerEntry(val label: String) {
    DOWNLOADS("Descargas"),
    ROOT_DIR("Raíz del sistema"),
    TRASH("Papelera"),
    APPS("Aplicaciones"),
    NETWORK("Red, nube y USB"),
    ANALYZE("Analizar espacio"),
    PLAYLISTS("Listas de reproducción"),
    CLEANER("Limpiar basura"),
    TRANSFERS("Transferencias"),
    HISTORY("Historial"),
    HIDDEN("Lista de ocultos"),
    ROOT_TOOLS("Root con Magisk")
}

/**
 * Orden y visibilidad del menú lateral. «Inicio», «Ajustes» y «Salir» siempre se ven: si se
 * ocultara «Ajustes» no habría forma de deshacerlo.
 */
object DrawerLayout {
    /** Todas las opciones: primero las guardadas en su orden; las que falten (p. ej. nuevas) van detrás. */
    fun order(stored: List<String>): List<DrawerEntry> {
        val known = stored.mapNotNull { name -> DrawerEntry.entries.firstOrNull { it.name == name } }.distinct()
        return known + DrawerEntry.entries.filter { it !in known }
    }

    fun visible(stored: List<String>, hidden: Set<String>): List<DrawerEntry> =
        order(stored).filter { it.name !in hidden }

    /** Sube (-1) o baja (+1) una opción; devuelve los nombres en el nuevo orden. */
    fun move(stored: List<String>, entry: DrawerEntry, delta: Int): List<String> {
        val list = order(stored).toMutableList()
        val from = list.indexOf(entry)
        val to = from + delta
        if (from < 0 || to !in list.indices) return list.map { it.name }
        list.add(to, list.removeAt(from))
        return list.map { it.name }
    }
}
