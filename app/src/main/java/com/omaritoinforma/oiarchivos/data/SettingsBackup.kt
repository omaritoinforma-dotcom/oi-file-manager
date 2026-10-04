package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Copia y restauración de ajustes, como «Copiar Configuraciones» de ES. Se guarda un JSON legible.
 * A diferencia de ES, la copia nunca lleva la contraseña ni las conexiones con sus credenciales, y
 * al restaurar se valida cada valor: un archivo modificado no puede dejar la app en mal estado.
 */
object SettingsBackup {
    const val FORMAT = "OI Archivos ajustes"
    const val VERSION = 1
    const val FILE_NAME = "ajustes-oi-archivos.json"

    sealed interface Kind {
        data object Flag : Kind

        data class Number(val range: IntRange) : Kind

        data class Choice(val names: Set<String>) : Kind

        /** Ruta absoluta de una carpeta. */
        data object Folder : Kind

        /** Rutas absolutas, una por línea. */
        data object Folders : Kind
    }

    private fun <T : Enum<T>> choice(values: Array<T>) = Kind.Choice(values.map { it.name }.toSet())

    val keys: Map<String, Kind> =
        mapOf(
            "view_mode" to choice(ViewMode.entries.toTypedArray()),
            "sort_by" to choice(SortBy.entries.toTypedArray()),
            "ascending" to Kind.Flag,
            "show_hidden" to Kind.Flag,
            "use_trash" to Kind.Flag,
            "theme" to choice(ThemeMode.entries.toTypedArray()),
            "grid_size" to Kind.Number(72..160),
            "swipe_left" to choice(GestureAction.entries.toTypedArray()),
            "swipe_right" to choice(GestureAction.entries.toTypedArray()),
            "bookmarks" to Kind.Folders,
            "thumbnails" to Kind.Flag,
            "history_folders_only" to Kind.Flag,
            "clear_history_on_exit" to Kind.Flag,
            "clear_cache_on_exit" to Kind.Flag,
            "home_folder" to Kind.Folder,
            "download_folder" to Kind.Folder,
            "start_window" to choice(StartWindow.entries.toTypedArray()),
            "close_notification" to Kind.Flag,
            "backup_before_uninstall" to Kind.Flag,
            "app_backup_folder" to Kind.Folder,
            "editor_font" to Kind.Number(10..28),
            "editor_line_numbers" to Kind.Flag,
            "editor_wrap" to Kind.Flag,
            "editor_auto_indent" to Kind.Flag,
            "editor_highlight" to Kind.Flag,
            "editor_auto_save" to Kind.Flag,
        )

    fun export(values: Map<String, Any?>): String {
        val settings = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            if (key in keys && value != null && valid(keys.getValue(key), value))
                settings.put(key, value)
        }
        return JSONObject()
            .put("formato", FORMAT)
            .put("version", VERSION)
            .put("ajustes", settings)
            .toString(2)
    }

    /** Lee una copia y devuelve solo los ajustes conocidos y válidos. */
    fun parse(json: String): Map<String, Any> {
        val root =
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IOException("El archivo no es una copia de ajustes")
            }
        if (root.optString("formato") != FORMAT)
            throw IOException("El archivo no es una copia de ajustes de OI Archivos")
        if (root.optInt("version", -1) !in 1..VERSION)
            throw IOException("Copia de una versión más nueva de OI Archivos")
        val settings = root.optJSONObject("ajustes") ?: throw IOException("La copia no tiene ajustes")
        val out = LinkedHashMap<String, Any>()
        for (key in settings.keys()) {
            val kind = keys[key] ?: continue
            val value = settings.get(key)
            // JSON no distingue números enteros pequeños de otros; se normalizan a Int.
            val normalized = if (kind is Kind.Number && value is Number) value.toInt() else value
            if (!valid(kind, normalized)) throw IOException("Valor no válido en la copia: $key")
            out[key] = normalized
        }
        return out
    }

    private fun valid(kind: Kind, value: Any): Boolean =
        when (kind) {
            Kind.Flag -> value is Boolean
            is Kind.Number -> value is Int && value in kind.range
            is Kind.Choice -> value is String && value in kind.names
            Kind.Folder -> value is String && validPath(value)
            Kind.Folders -> value is String && value.split('\n').filter { it.isNotEmpty() }.all(::validPath)
        }

    private fun validPath(path: String): Boolean =
        path.startsWith("/") && path.length <= 4096 && path.none { it == '\u0000' || it == '\n' }
}
