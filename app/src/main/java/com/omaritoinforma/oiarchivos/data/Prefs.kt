package com.omaritoinforma.oiarchivos.data

import android.annotation.SuppressLint
import android.content.Context
import com.omaritoinforma.oiarchivos.util.PathUtil

/** Qué se muestra al abrir la app («Elegir la ventana por defecto» de ES). */
enum class StartWindow(val label: String) {
    HOME("Inicio (categorías)"),
    HOME_FOLDER("Carpeta de inicio"),
    LAST_FOLDER("Última carpeta abierta")
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var viewMode: ViewMode
        get() = enumOr(sp.getString("view_mode", null), ViewMode.DETAILS)
        set(v) = sp.edit().putString("view_mode", v.name).apply()

    var sortBy: SortBy
        get() = enumOr(sp.getString("sort_by", null), SortBy.NAME)
        set(v) = sp.edit().putString("sort_by", v.name).apply()

    var ascending: Boolean
        get() = sp.getBoolean("ascending", true)
        set(v) = sp.edit().putBoolean("ascending", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit().putBoolean("show_hidden", v).apply()

    var useTrash: Boolean
        get() = sp.getBoolean("use_trash", true)
        set(v) = sp.edit().putBoolean("use_trash", v).apply()

    var themeMode: ThemeMode
        get() = enumOr(sp.getString("theme", null), ThemeMode.SYSTEM)
        set(v) = sp.edit().putString("theme", v.name).apply()

    /** Carpetas y archivos abiertos, el más reciente primero. */
    var history: List<String>
        get() = sp.getString("history", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("history", v.joinToString("\n")).apply()

    var gridSize: Int
        get() = sp.getInt("grid_size", 96)
        set(v) = sp.edit().putInt("grid_size", v.coerceIn(72, 160)).apply()

    var swipeLeft: GestureAction
        get() = enumOr(sp.getString("swipe_left", null), GestureAction.NONE)
        set(v) = sp.edit().putString("swipe_left", v.name).apply()

    var swipeRight: GestureAction
        get() = enumOr(sp.getString("swipe_right", null), GestureAction.NONE)
        set(v) = sp.edit().putString("swipe_right", v.name).apply()

    var bookmarks: List<String>
        get() = sp.getString("bookmarks", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("bookmarks", v.joinToString("\n")).apply()

    // ---- Pantalla ----

    var thumbnails: Boolean
        get() = sp.getBoolean("thumbnails", true)
        set(v) = sp.edit().putBoolean("thumbnails", v).apply()

    var historyFoldersOnly: Boolean
        get() = sp.getBoolean("history_folders_only", false)
        set(v) = sp.edit().putBoolean("history_folders_only", v).apply()

    // ---- Limpieza ----

    var clearHistoryOnExit: Boolean
        get() = sp.getBoolean("clear_history_on_exit", false)
        set(v) = sp.edit().putBoolean("clear_history_on_exit", v).apply()

    var clearCacheOnExit: Boolean
        get() = sp.getBoolean("clear_cache_on_exit", false)
        set(v) = sp.edit().putBoolean("clear_cache_on_exit", v).apply()

    // ---- Carpetas y ventana inicial ----

    var homeFolder: String
        get() = sp.getString("home_folder", null) ?: PathUtil.internalRoot
        set(v) = sp.edit().putString("home_folder", v).apply()

    var downloadFolder: String
        get() = sp.getString("download_folder", null) ?: defaultDownloadFolder
        set(v) = sp.edit().putString("download_folder", v).apply()

    var startWindow: StartWindow
        get() = enumOr(sp.getString("start_window", null), StartWindow.HOME)
        set(v) = sp.edit().putString("start_window", v.name).apply()

    var lastFolder: String
        get() = sp.getString("last_folder", "").orEmpty()
        set(v) = sp.edit().putString("last_folder", v).apply()

    // ---- Notificaciones ----

    /** Como en ES: si está activado, no queda aviso al terminar una tarea. */
    var closeNotificationWhenDone: Boolean
        get() = sp.getBoolean("close_notification", false)
        set(v) = sp.edit().putBoolean("close_notification", v).apply()

    /** «Mostrar advertencia de espacio bajo» de ES. */
    var lowSpaceWarning: Boolean
        get() = sp.getBoolean("low_space_warning", true)
        set(v) = sp.edit().putBoolean("low_space_warning", v).apply()

    /** Se avisa cuando quedan menos de estos MB libres. */
    var lowSpaceMb: Int
        get() = sp.getInt("low_space_mb", 1024)
        set(v) = sp.edit().putInt("low_space_mb", v.coerceIn(100, 102400)).apply()

    /** Ya se avisó y el espacio no se ha recuperado: no se repite el aviso. */
    var lowSpaceWarned: Boolean
        get() = sp.getBoolean("low_space_warned", false)
        set(v) = sp.edit().putBoolean("low_space_warned", v).apply()

    /** «Notificación del Registrador» de ES: aviso cuando aparecen archivos nuevos. */
    var newFilesNotify: Boolean
        get() = sp.getBoolean("new_files_notify", false)
        set(v) = sp.edit().putBoolean("new_files_notify", v).apply()

    var newFilesKinds: Set<NewFileKind>
        get() =
            sp.getString("new_files_kinds", null)
                ?.split(',')
                ?.mapNotNull { n -> NewFileKind.entries.firstOrNull { it.name == n } }
                ?.toSet() ?: NewFileKind.entries.toSet()
        set(v) = sp.edit().putString("new_files_kinds", v.joinToString(",") { it.name }).apply()

    /** Fecha (segundos) del último archivo ya avisado. */
    var newFilesSince: Long
        get() = sp.getLong("new_files_since", 0L)
        set(v) = sp.edit().putLong("new_files_since", v).apply()

    var notificationPermissionAsked: Boolean
        get() = sp.getBoolean("notification_permission_asked", false)
        set(v) = sp.edit().putBoolean("notification_permission_asked", v).apply()

    // ---- Aplicaciones ----

    var backupBeforeUninstall: Boolean
        get() = sp.getBoolean("backup_before_uninstall", false)
        set(v) = sp.edit().putBoolean("backup_before_uninstall", v).apply()

    var appBackupFolder: String
        get() = sp.getString("app_backup_folder", null) ?: defaultAppBackupFolder
        set(v) = sp.edit().putString("app_backup_folder", v).apply()

    // ---- Editor (opciones del editor de ES) ----

    var editorFont: Int
        get() = sp.getInt("editor_font", 14)
        set(v) = sp.edit().putInt("editor_font", v.coerceIn(10, 28)).apply()

    var editorLineNumbers: Boolean
        get() = sp.getBoolean("editor_line_numbers", true)
        set(v) = sp.edit().putBoolean("editor_line_numbers", v).apply()

    var editorWrap: Boolean
        get() = sp.getBoolean("editor_wrap", true)
        set(v) = sp.edit().putBoolean("editor_wrap", v).apply()

    var editorAutoIndent: Boolean
        get() = sp.getBoolean("editor_auto_indent", true)
        set(v) = sp.edit().putBoolean("editor_auto_indent", v).apply()

    var editorHighlight: Boolean
        get() = sp.getBoolean("editor_highlight", true)
        set(v) = sp.edit().putBoolean("editor_highlight", v).apply()

    var editorAutoSave: Boolean
        get() = sp.getBoolean("editor_auto_save", false)
        set(v) = sp.edit().putBoolean("editor_auto_save", v).apply()

    // ---- Contraseña ----

    /** Hash PBKDF2 de la contraseña (ver [AppLock]); vacío si no hay contraseña. */
    var lockHash: String
        get() = sp.getString("lock_hash", "").orEmpty()
        // Se escribe al momento: si la app se cerrara justo después, la protección no se perdería.
        @SuppressLint("ApplySharedPref")
        set(v) {
            sp.edit().putString("lock_hash", v).commit()
        }

    var lockStart: Boolean
        get() = sp.getBoolean("lock_start", false)
        set(v) = sp.edit().putBoolean("lock_start", v).apply()

    var lockNetwork: Boolean
        get() = sp.getBoolean("lock_network", false)
        set(v) = sp.edit().putBoolean("lock_network", v).apply()

    var lockHidden: Boolean
        get() = sp.getBoolean("lock_hidden", false)
        set(v) = sp.edit().putBoolean("lock_hidden", v).apply()

    // ---- Copia de ajustes ----

    /** Valores que se pueden copiar (ver [SettingsBackup.keys]). */
    fun snapshot(): Map<String, Any?> = sp.all.filterKeys { it in SettingsBackup.keys }

    /** Sustituye los ajustes copiables por [values]; los que no vienen vuelven a su valor inicial. */
    fun restore(values: Map<String, Any>) {
        val edit = sp.edit()
        SettingsBackup.keys.keys.forEach { edit.remove(it) }
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
            }
        }
        edit.apply()
    }

    companion object {
        const val NAME = "oi_archivos"

        val defaultDownloadFolder: String
            get() = "${PathUtil.internalRoot}/Download/OI Archivos"

        val defaultAppBackupFolder: String
            get() = "${PathUtil.internalRoot}/OI Archivos/Apps"
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
