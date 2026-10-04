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

    /** Archivos y carpetas fijados arriba («Fijar elementos arriba» de ES), una ruta por línea. */
    var pinned: List<String>
        get() = sp.getString("pinned", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("pinned", v.joinToString("\n")).apply()

    /** Último nivel de compresión elegido al comprimir. */
    var compressionLevel: CompressionLevel
        get() = enumOr(sp.getString("compression_level", null), CompressionLevel.NORMAL)
        set(v) = sp.edit().putString("compression_level", v.name).apply()

    var accent: AccentColor
        get() = enumOr(sp.getString("accent", null), AccentColor.DYNAMIC)
        set(v) = sp.edit().putString("accent", v.name).apply()

    /** Con el tema oscuro, fondo negro puro (ahorra batería en pantallas OLED). */
    var pureBlack: Boolean
        get() = sp.getBoolean("pure_black", false)
        set(v) = sp.edit().putBoolean("pure_black", v).apply()

    /** Orden del menú lateral (nombres de [DrawerEntry]); vacío = el de fábrica. */
    var drawerOrder: List<String>
        get() = sp.getString("drawer_order", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("drawer_order", v.joinToString(",")).apply()

    var drawerHidden: Set<String>
        get() = sp.getString("drawer_hidden", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("drawer_hidden", v.joinToString(",")).apply()

    /** Orden de las secciones de Inicio (nombres de [HomeSection]); vacío = el de fábrica. */
    var homeOrder: List<String>
        get() = sp.getString("home_order", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("home_order", v.joinToString(",")).apply()

    var homeHidden: Set<String>
        get() = sp.getString("home_hidden", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("home_hidden", v.joinToString(",")).apply()

    /** Iconos de Inicio ocultos: «cat:IMAGES», «quick:DOWNLOADS»… (ver [HomeLayout]). */
    var homeHiddenTiles: Set<String>
        get() = sp.getString("home_hidden_tiles", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("home_hidden_tiles", v.joinToString(",")).apply()

    /** Rutas (ya con el punto) de lo que se ocultó desde la app, para la «Lista de ocultos». */
    var hiddenItems: List<String>
        get() = sp.getString("hidden_items", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("hidden_items", v.joinToString("\n")).apply()

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
    /** Puerto del servidor FTP del teléfono; 0 elige uno libre en cada inicio. */
    var ftpPort: Int
        get() = sp.getInt("ftp_port", 0).let { if (it in 1024..65535) it else 0 }
        set(v) = sp.edit().putInt("ftp_port", if (v in 1024..65535) v else 0).apply()

    var ftpEncoding: FtpEncoding
        get() = enumOr(sp.getString("ftp_encoding", null), FtpEncoding.UTF8)
        set(v) = sp.edit().putString("ftp_encoding", v.name).apply()

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

    /** Informe diario de archivos nuevos («Informe diario» de ES). */
    var dailyReport: Boolean
        get() = sp.getBoolean("daily_report", false)
        set(v) = sp.edit().putBoolean("daily_report", v).apply()

    /** Fecha (segundos) desde la que cuenta el próximo informe. */
    var reportSince: Long
        get() = sp.getLong("report_since", 0L)
        set(v) = sp.edit().putLong("report_since", v).apply()

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

    /** Subir solos los archivos remotos editados en otra app («Enable remote synchronize» de ES). */
    var remoteSync: Boolean
        get() = sp.getBoolean("remote_sync", true)
        set(v) = sp.edit().putBoolean("remote_sync", v).apply()

    // ---- Copia automática ----

    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", false)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()

    /** Id de la conexión de destino (las conexiones no van en la copia de ajustes). */
    var autoBackupConnection: String
        get() = sp.getString("auto_backup_connection", "").orEmpty()
        set(v) = sp.edit().putString("auto_backup_connection", v).apply()

    /** Carpeta dentro de la conexión, relativa a su carpeta inicial. */
    var autoBackupFolder: String
        get() = sp.getString("auto_backup_folder", null) ?: "OI Archivos copia"
        set(v) = sp.edit().putString("auto_backup_folder", v).apply()

    var autoBackupKinds: Set<BackupKind>
        get() =
            sp.getString("auto_backup_kinds", null)
                ?.split(',')
                ?.mapNotNull { n -> BackupKind.entries.firstOrNull { it.name == n } }
                ?.toSet() ?: setOf(BackupKind.PHOTOS, BackupKind.VIDEOS)
        set(v) = sp.edit().putString("auto_backup_kinds", v.joinToString(",") { it.name }).apply()

    /** Otras carpetas del teléfono que se copian enteras. */
    var autoBackupFolders: List<String>
        get() = sp.getString("auto_backup_folders", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("auto_backup_folders", v.joinToString("\n")).apply()

    var autoBackupWifiOnly: Boolean
        get() = sp.getBoolean("auto_backup_wifi", true)
        set(v) = sp.edit().putBoolean("auto_backup_wifi", v).apply()

    /** Resultado de la última copia, para mostrarlo en Ajustes. */
    var autoBackupLast: String
        get() = sp.getString("auto_backup_last", "").orEmpty()
        set(v) = sp.edit().putString("auto_backup_last", v).apply()

    // ---- Editor (opciones del editor de ES) ----

    var editorFont: Int
        get() = sp.getInt("editor_font", 14)
        set(v) = sp.edit().putInt("editor_font", v.coerceIn(10, 28)).apply()

    /** Botones de la barra inferior al seleccionar (nombres de [ToolbarAction], en orden); sin guardar = los de fábrica. */
    var toolbarActions: List<String>
        get() =
            if (sp.contains("toolbar_actions"))
                sp.getString("toolbar_actions", "").orEmpty().split(',').filter { it.isNotBlank() }
            else ToolbarLayout.defaultNames
        set(v) = sp.edit().putString("toolbar_actions", v.joinToString(",")).apply()

    /** Si hay imagen de fondo (la copia está en [BackgroundImage.file]). */
    var backgroundImage: Boolean
        get() = sp.getBoolean("background_image", false)
        set(v) = sp.edit().putBoolean("background_image", v).apply()

    var backgroundStrength: Int
        get() = sp.getInt("background_strength", BackgroundImage.DEFAULT_STRENGTH).coerceIn(BackgroundImage.strengths)
        set(v) = sp.edit().putInt("background_strength", v.coerceIn(BackgroundImage.strengths)).apply()

    /** Tipos que entran en «Documentos» (nombres de [DocumentType]); sin guardar = todos. */
    var documentTypes: Set<String>
        get() =
            if (sp.contains("document_types"))
                sp.getString("document_types", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            else DocumentType.entries.map { it.name }.toSet()
        set(v) = sp.edit().putString("document_types", v.joinToString(",")).apply()

    /** Buscador en la pantalla de inicio («Show Search engine on Homepage» de ES). */
    var homeSearch: Boolean
        get() = sp.getBoolean("home_search", true)
        set(v) = sp.edit().putBoolean("home_search", v).apply()

    /** Contraseña fija del servidor FTP; vacía = una nueva cada vez. Es del dispositivo: no va en la copia de ajustes. */
    var ftpPassword: String
        get() = sp.getString("ftp_password", "").orEmpty()
        set(v) = sp.edit().putString("ftp_password", v).apply()

    /** «Cerrar al salir» del servidor FTP de ES: se detiene al salir de la app. */
    var ftpStopOnExit: Boolean
        get() = sp.getBoolean("ftp_stop_on_exit", false)
        set(v) = sp.edit().putBoolean("ftp_stop_on_exit", v).apply()

    /** Avisar de los permisos delicados de una app recién instalada desde OI Archivos. */
    var appPermissionNotify: Boolean
        get() = sp.getBoolean("app_permission_notify", true)
        set(v) = sp.edit().putBoolean("app_permission_notify", v).apply()

    /** Notificación fija con el uso del almacenamiento («Mostrar tarjeta SD en la barra de estado» de ES). */
    var storageNotification: Boolean
        get() = sp.getBoolean("storage_notification", false)
        set(v) = sp.edit().putBoolean("storage_notification", v).apply()

    /** Botón con el número de pestañas en la barra («Mostrar el botón de Windows» de ES). */
    var showWindowsButton: Boolean
        get() = sp.getBoolean("show_windows_button", false)
        set(v) = sp.edit().putBoolean("show_windows_button", v).apply()

    var folderStyle: FolderStyle
        get() = enumOr(sp.getString("folder_style", null), FolderStyle.CLASSIC)
        set(v) = sp.edit().putString("folder_style", v.name).apply()

    /** «Mostrar el nombre en la barra de herramientas»: el título de la carpeta o categoría. */
    var toolbarShowName: Boolean
        get() = sp.getBoolean("toolbar_show_name", true)
        set(v) = sp.edit().putBoolean("toolbar_show_name", v).apply()

    /** «Mostrar botón de selección»: un botón en la barra para empezar a marcar sin mantener pulsado. */
    var showSelectButton: Boolean
        get() = sp.getBoolean("show_select_button", false)
        set(v) = sp.edit().putBoolean("show_select_button", v).apply()

    var screenOrientation: ScreenOrientation
        get() = enumOr(sp.getString("screen_orientation", null), ScreenOrientation.AUTO)
        set(v) = sp.edit().putString("screen_orientation", v.name).apply()

    /** «Large layout»: todo un poco más grande. */
    var largeLayout: Boolean
        get() = sp.getBoolean("large_layout", false)
        set(v) = sp.edit().putBoolean("large_layout", v).apply()

    /** Tamaño máximo (KB) del archivo al que se le colorea el código («Restringir tamaño del archivo resaltado»). */
    var editorHighlightLimitKb: Int
        get() = sp.getInt("editor_highlight_limit_kb", EditorText.DEFAULT_HIGHLIGHT_LIMIT_KB)
        set(v) = sp.edit().putInt("editor_highlight_limit_kb", v.coerceIn(10, 5000)).apply()

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

    var editorSpacesForTab: Boolean
        get() = sp.getBoolean("editor_spaces_for_tab", false)
        set(v) = sp.edit().putBoolean("editor_spaces_for_tab", v).apply()

    var editorTabSize: Int
        get() = sp.getInt("editor_tab_size", 4)
        set(v) = sp.edit().putInt("editor_tab_size", v.coerceIn(1, 8)).apply()

    var editorAutoCapitalize: Boolean
        get() = sp.getBoolean("editor_auto_capitalize", false)
        set(v) = sp.edit().putBoolean("editor_auto_capitalize", v).apply()

    var editorShowWhitespace: Boolean
        get() = sp.getBoolean("editor_show_whitespace", false)
        set(v) = sp.edit().putBoolean("editor_show_whitespace", v).apply()

    var editorSymbolBar: Boolean
        get() = sp.getBoolean("editor_symbol_bar", true)
        set(v) = sp.edit().putBoolean("editor_symbol_bar", v).apply()

    /** Símbolos de la barra, separados por espacios. */
    var editorSymbols: String
        get() = sp.getString("editor_symbols", null) ?: EditorText.DEFAULT_SYMBOLS
        set(v) = sp.edit().putString("editor_symbols", v).apply()

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
