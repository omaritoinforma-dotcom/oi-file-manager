package com.omaritoinforma.oiarchivos.data

/** Secciones de la pantalla de inicio que se pueden ocultar y reordenar («Distribución» de ES). */
enum class HomeSection(private val labelEs: String) {
    STORAGE(trKey("Almacenamiento")),
    CATEGORIES(trKey("Categorías")),
    QUICK(trKey("Accesos rápidos")),
    BOOKMARKS(trKey("Marcadores")),

    /** Los últimos archivos que aparecieron («Mostrar nuevos archivos en la página de inicio» de ES). */
    NEW_FILES(trKey("Archivos nuevos"));

    val label: String
        get() = tr(labelEs)
}

/** Iconos de «Accesos rápidos». El nombre es la clave que se guarda; la etiqueta, lo que se ve. */
enum class QuickTile(private val labelEs: String) {
    DOWNLOADS(trKey("Descargas")),
    CAMERA(trKey("Cámara")),
    PICTURES(trKey("Imágenes")),
    DOCUMENTS(trKey("Documentos")),
    TRASH(trKey("Papelera")),
    APPS(trKey("Apps")),
    NETWORK(trKey("Red / nube")),
    ANALYZE(trKey("Analizar")),
    HISTORY(trKey("Historial")),
    TRANSFERS(trKey("Transferir")),
    ROOT(trKey("Raíz")),
    SETTINGS(trKey("Ajustes"));

    val label: String
        get() = tr(labelEs)
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

    /** Cuántos archivos nuevos se muestran en Inicio. */
    const val NEW_FILES_COUNT = 5

    /** Los más recientes que interesan: sin carpetas, sin ocultos y sin datos internos de otras apps. */
    fun newFiles(recent: List<FileItem>, limit: Int = NEW_FILES_COUNT): List<FileItem> =
        recent
            .filter {
                !it.isDirectory &&
                    !it.name.startsWith(".") &&
                    "/Android/data/" !in it.path &&
                    "/Android/obb/" !in it.path &&
                    "/." !in it.path
            }
            .take(limit)

    fun categories(hiddenTiles: Set<String>): List<FileCategory> =
        FileCategory.entries.filter { categoryKey(it) !in hiddenTiles }

    fun quick(hiddenTiles: Set<String>): List<QuickTile> =
        QuickTile.entries.filter { quickKey(it) !in hiddenTiles }
}
