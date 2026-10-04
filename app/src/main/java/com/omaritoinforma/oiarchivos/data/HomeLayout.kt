package com.omaritoinforma.oiarchivos.data

/** Secciones de la pantalla de inicio que se pueden ocultar y reordenar («Distribución» de ES). */
enum class HomeSection(val label: String) {
    STORAGE("Almacenamiento"),
    CATEGORIES("Categorías"),
    QUICK("Accesos rápidos"),
    BOOKMARKS("Marcadores")
}

/** Iconos de «Accesos rápidos». El nombre es la clave que se guarda; la etiqueta, lo que se ve. */
enum class QuickTile(val label: String) {
    DOWNLOADS("Descargas"),
    CAMERA("Cámara"),
    PICTURES("Imágenes"),
    DOCUMENTS("Documentos"),
    TRASH("Papelera"),
    APPS("Apps"),
    NETWORK("Red / nube"),
    ANALYZE("Analizar"),
    HISTORY("Historial"),
    TRANSFERS("Transferir"),
    ROOT("Raíz"),
    SETTINGS("Ajustes")
}

/**
 * Orden y visibilidad de lo que sale en Inicio: las secciones (cada una puede ocultarse y moverse) y
 * cada icono de categoría o de acceso rápido. «Ajustes» siempre tiene su botón arriba, así que ocultar
 * todo nunca deja a nadie sin forma de deshacerlo.
 */
object HomeLayout {
    private const val CATEGORY = "cat:"
    private const val QUICK = "quick:"

    fun categoryKey(category: FileCategory) = CATEGORY + category.name

    fun quickKey(tile: QuickTile) = QUICK + tile.name

    /** Todas las claves de icono que se pueden ocultar (para validar la copia de ajustes). */
    val tileKeys: Set<String> =
        FileCategory.entries.map { categoryKey(it) }.toSet() + QuickTile.entries.map { quickKey(it) }

    /** Todas las secciones: primero las guardadas en su orden; las que falten (p. ej. nuevas) van detrás. */
    fun order(stored: List<String>): List<HomeSection> {
        val known = stored.mapNotNull { name -> HomeSection.entries.firstOrNull { it.name == name } }.distinct()
        return known + HomeSection.entries.filter { it !in known }
    }

    fun visible(stored: List<String>, hidden: Set<String>): List<HomeSection> =
        order(stored).filter { it.name !in hidden }

    /** Sube (-1) o baja (+1) una sección; devuelve los nombres en el nuevo orden. */
    fun move(stored: List<String>, section: HomeSection, delta: Int): List<String> {
        val list = order(stored).toMutableList()
        val from = list.indexOf(section)
        val to = from + delta
        if (from < 0 || to !in list.indices) return list.map { it.name }
        list.add(to, list.removeAt(from))
        return list.map { it.name }
    }

    fun categories(hiddenTiles: Set<String>): List<FileCategory> =
        FileCategory.entries.filter { categoryKey(it) !in hiddenTiles }

    fun quick(hiddenTiles: Set<String>): List<QuickTile> =
        QuickTile.entries.filter { quickKey(it) !in hiddenTiles }
}
