package com.omaritoinforma.oiarchivos.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omaritoinforma.oiarchivos.data.AppInfo
import com.omaritoinforma.oiarchivos.data.AppInstaller
import com.omaritoinforma.oiarchivos.data.AppLock
import com.omaritoinforma.oiarchivos.data.CacheCleaner
import com.omaritoinforma.oiarchivos.data.AppsRepo
import com.omaritoinforma.oiarchivos.data.AnalysisTools
import com.omaritoinforma.oiarchivos.data.ArchiveTools
import com.omaritoinforma.oiarchivos.data.BackgroundImage
import com.omaritoinforma.oiarchivos.data.Categories
import com.omaritoinforma.oiarchivos.data.ShareService
import com.omaritoinforma.oiarchivos.data.DocumentType
import com.omaritoinforma.oiarchivos.data.Clipboard
import com.omaritoinforma.oiarchivos.data.Conflict
import com.omaritoinforma.oiarchivos.data.CryptoTools
import com.omaritoinforma.oiarchivos.data.DurableCopy
import com.omaritoinforma.oiarchivos.data.FileCategory
import com.omaritoinforma.oiarchivos.data.FileItem
import com.omaritoinforma.oiarchivos.data.FileOps
import com.omaritoinforma.oiarchivos.data.FileRepo
import com.omaritoinforma.oiarchivos.data.GestureAction
import com.omaritoinforma.oiarchivos.data.Location
import com.omaritoinforma.oiarchivos.data.OpProgress
import com.omaritoinforma.oiarchivos.data.OperationResult
import com.omaritoinforma.oiarchivos.data.Prefs
import com.omaritoinforma.oiarchivos.data.RecycleBin
import com.omaritoinforma.oiarchivos.data.RemoteEntry
import com.omaritoinforma.oiarchivos.data.RenameRules
import com.omaritoinforma.oiarchivos.data.SafeFiles
import com.omaritoinforma.oiarchivos.data.SearchFilter
import com.omaritoinforma.oiarchivos.data.SortBy
import com.omaritoinforma.oiarchivos.data.Sorter
import com.omaritoinforma.oiarchivos.data.StorageInfo
import com.omaritoinforma.oiarchivos.data.StartWindow
import com.omaritoinforma.oiarchivos.data.StorageVolumeInfo
import com.omaritoinforma.oiarchivos.data.ThemeMode
import com.omaritoinforma.oiarchivos.data.TransferService
import com.omaritoinforma.oiarchivos.data.ViewMode
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.Media
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.Perms
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

sealed interface Screen {
    data object Home : Screen

    data object Browser : Screen

    data class Editor(val path: String) : Screen

    data object Apps : Screen

    data object Trash : Screen

    data object Settings : Screen

    data class Viewer(val path: String) : Screen

    data class Archive(val path: String) : Screen

    data class Analysis(val root: String) : Screen

    data class AdvancedSearch(val root: String) : Screen

    data object Connections : Screen

    data object Bluetooth : Screen

    data class Remote(val id: String) : Screen

    data class Documents(val uri: String) : Screen

    data object Sharing : Screen

    data object Transfers : Screen

    data object Nearby : Screen

    data object History : Screen

    data object RootTools : Screen

    data class VideoEdit(val path: String) : Screen

    /** Girar, voltear y recortar una imagen. */
    data class ImageEdit(val path: String) : Screen

    data class DualPane(val path: String) : Screen

    /** Reproducir un audio o vídeo de la red sin descargarlo. */
    data class Stream(val url: String, val title: String) : Screen

    /** Enviar una foto, música o vídeo a la TV por DLNA. */
    data object Cast : Screen

    /** Instalar y manejar una Android TV por ADB. */
    data object AdbTv : Screen

    /** Limpiar basura. */
    data object Cleaner : Screen

    /** Listas de reproducción guardadas. */
    data object Playlists : Screen

    /** Lo que se ocultó desde la app; se abre con contraseña si así se eligió. */
    data object HiddenList : Screen

    /** Qué apps piden permisos delicados. */
    data object AppAnalysis : Screen

    data class Playlist(val name: String) : Screen

    /** Reproduce una lista guardada empezando por la pista [start]. */
    data class PlayPlaylist(val name: String, val start: Int = 0) : Screen
}

data class PendingPaste(
    val sources: List<File>,
    val dest: File,
    val move: Boolean,
    val conflicts: Int
)

/** Una pestaña (ventana) del explorador, con su propio historial y selección. */
class TabState(start: Location) {
    val id: Long = nextId.incrementAndGet()
    val history = mutableStateListOf(start)
    val location: Location
        get() = history.last()

    val items = mutableStateListOf<FileItem>()
    var loading by mutableStateOf(false)
    val selected = mutableStateMapOf<String, FileItem>()

    /** Modo de selección empezado con el botón de la barra, aunque aún no haya nada marcado. */
    var selectMode by mutableStateOf(false)
    internal val cache = HashMap<Location, List<FileItem>>()
    internal val scroll = HashMap<Location, Pair<Int, Int>>()
    internal var job: Job? = null

    private companion object {
        val nextId = AtomicLong()
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context
        get() = getApplication()

    private val prefs = Prefs(app)
    var swipeLeft by mutableStateOf(prefs.swipeLeft)
        private set
    var swipeRight by mutableStateOf(prefs.swipeRight)
        private set
    fun updateGesture(left: Boolean, action: GestureAction) {
        if (left) { swipeLeft = action; prefs.swipeLeft = action }
        else { swipeRight = action; prefs.swipeRight = action }
    }
    fun performGesture(action: GestureAction) {
        when (action) {
            GestureAction.NONE -> {}
            GestureAction.UP -> up()
            GestureAction.HOME -> goHome()
            GestureAction.REFRESH -> refresh()
            GestureAction.NEXT_TAB -> if (tabs.isNotEmpty()) selectTab((activeTab + 1) % tabs.size)
            GestureAction.PREVIOUS_TAB -> if (tabs.isNotEmpty()) selectTab((activeTab - 1 + tabs.size) % tabs.size)
            GestureAction.NEW_TAB -> addTab()
            GestureAction.HIDDEN -> toggleHidden()
            GestureAction.SELECT_ALL -> selectAll()
        }
    }

    var hasPermission by mutableStateOf(Perms.hasStorage(app))
        private set

    val screens = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen
        get() = screens.last()

    val tabs = mutableStateListOf<TabState>()
    var activeTab by mutableIntStateOf(0)
        private set

    val currentTab: TabState?
        get() = tabs.getOrNull(activeTab)

    var viewMode by mutableStateOf(prefs.viewMode)
        private set

    var sortBy by mutableStateOf(prefs.sortBy)
        private set

    var ascending by mutableStateOf(prefs.ascending)
        private set

    var showHidden by mutableStateOf(prefs.showHidden)
        private set

    var useTrash by mutableStateOf(prefs.useTrash)
        private set

    var themeMode by mutableStateOf(prefs.themeMode)
        private set

    var gridSize by mutableIntStateOf(prefs.gridSize)
        private set

    fun updateGridSize(size: Int) {
        gridSize = size
        prefs.gridSize = size
    }

    val bookmarks = mutableStateListOf<String>().apply { addAll(prefs.bookmarks) }

    val compressionLevel =
        PrefState({ prefs.compressionLevel }, { prefs.compressionLevel = it })
    val accent = PrefState({ prefs.accent }, { prefs.accent = it })
    val drawerOrder = PrefState({ prefs.drawerOrder }, { prefs.drawerOrder = it })
    val drawerHidden = PrefState({ prefs.drawerHidden }, { prefs.drawerHidden = it })
    val homeOrder = PrefState({ prefs.homeOrder }, { prefs.homeOrder = it })
    val homeHidden = PrefState({ prefs.homeHidden }, { prefs.homeHidden = it })
    val homeHiddenTiles = PrefState({ prefs.homeHiddenTiles }, { prefs.homeHiddenTiles = it })
    val pureBlack = PrefState({ prefs.pureBlack }, { prefs.pureBlack = it })

    /** Rutas fijadas arriba en las listas de archivos. */
    val pinned = mutableStateListOf<String>().apply { addAll(prefs.pinned) }

    fun togglePin(items: List<FileItem>) {
        if (items.isEmpty()) return
        // Si alguno no está fijado se fijan todos; si todos lo están, se quitan.
        val pin = items.any { it.path !in pinned }
        items.forEach { if (pin) { if (it.path !in pinned) pinned.add(it.path) } else pinned.remove(it.path) }
        prefs.pinned = pinned.toList()
        currentTab?.selected?.clear()
        tabs.forEach { it.cache.clear() }
        refresh()
        toast(if (pin) tr("Fijado arriba") else tr("Ya no está fijado"))
    }

    // ---- Ajustes al estilo de ES ----

    val thumbnails = PrefState({ prefs.thumbnails }, { prefs.thumbnails = it })
    val historyFoldersOnly = PrefState({ prefs.historyFoldersOnly }, { prefs.historyFoldersOnly = it })
    val clearHistoryOnExit = PrefState({ prefs.clearHistoryOnExit }, { prefs.clearHistoryOnExit = it })
    val clearCacheOnExit = PrefState({ prefs.clearCacheOnExit }, { prefs.clearCacheOnExit = it })
    val homeFolder = PrefState({ prefs.homeFolder }, { prefs.homeFolder = it })
    val downloadFolder = PrefState({ prefs.downloadFolder }, { prefs.downloadFolder = it })
    val startWindow = PrefState({ prefs.startWindow }, { prefs.startWindow = it })
    val closeNotificationWhenDone =
        PrefState({ prefs.closeNotificationWhenDone }, { prefs.closeNotificationWhenDone = it })
    // Avisos de almacenamiento: al cambiarlos se programan o cancelan los trabajos en segundo plano.
    val lowSpaceWarning =
        PrefState({ prefs.lowSpaceWarning }, {
            prefs.lowSpaceWarning = it
            scheduleStorageWatch()
            if (it) checkSpaceNow()
        })
    val storageNotification =
        PrefState({ prefs.storageNotification }, {
            prefs.storageNotification = it
            scheduleStorageWatch()
            checkSpaceNow()
        })
    val toolbarActions = PrefState({ prefs.toolbarActions }, { prefs.toolbarActions = it })
    val backgroundImage = PrefState({ prefs.backgroundImage }, { prefs.backgroundImage = it })
    val backgroundStrength = PrefState({ prefs.backgroundStrength }, { prefs.backgroundStrength = it })
    val documentTypes = PrefState({ prefs.documentTypes }, { prefs.documentTypes = it })

    /** Los tipos elegidos para «Documentos», ya como enumerado. */
    fun documentTypeSet(): Set<DocumentType> =
        documentTypes.value.mapNotNull { name -> DocumentType.entries.firstOrNull { it.name == name } }.toSet()
    val cleanAssociatedFolders =
        PrefState({ prefs.cleanAssociatedFolders }, { prefs.cleanAssociatedFolders = it })
    val appPermissionNotify = PrefState({ prefs.appPermissionNotify }, { prefs.appPermissionNotify = it })
    val showWindowsButton = PrefState({ prefs.showWindowsButton }, { prefs.showWindowsButton = it })
    val homeSearch = PrefState({ prefs.homeSearch }, { prefs.homeSearch = it })
    val ftpPassword = PrefState({ prefs.ftpPassword }, { prefs.ftpPassword = it })
    val ftpStopOnExit = PrefState({ prefs.ftpStopOnExit }, { prefs.ftpStopOnExit = it })
    val folderStyle = PrefState({ prefs.folderStyle }, { prefs.folderStyle = it })
    val toolbarShowName = PrefState({ prefs.toolbarShowName }, { prefs.toolbarShowName = it })
    val showSelectButton = PrefState({ prefs.showSelectButton }, { prefs.showSelectButton = it })
    val screenOrientation = PrefState({ prefs.screenOrientation }, { prefs.screenOrientation = it })
    val appLanguage = PrefState({ prefs.appLanguage }, { prefs.appLanguage = it })
    val largeLayout = PrefState({ prefs.largeLayout }, { prefs.largeLayout = it })
    val editorHighlightLimit =
        PrefState({ prefs.editorHighlightLimitKb }, { prefs.editorHighlightLimitKb = it })
    val ftpPort = PrefState({ prefs.ftpPort }, { prefs.ftpPort = it })
    val ftpEncoding = PrefState({ prefs.ftpEncoding }, { prefs.ftpEncoding = it })
    val lowSpaceMb = PrefState({ prefs.lowSpaceMb }, {
        prefs.lowSpaceMb = it
        prefs.lowSpaceWarned = false
        checkSpaceNow()
    })

    private fun checkSpaceNow() {
        runCatching { com.omaritoinforma.oiarchivos.data.StorageWatch.checkNow(ctx) }
    }
    val removableNotice = PrefState({ prefs.removableNotice }, { prefs.removableNotice = it })
    val newFilesNotify =
        PrefState({ prefs.newFilesNotify }, {
            prefs.newFilesNotify = it
            scheduleStorageWatch()
        })
    val newFilesKinds = PrefState({ prefs.newFilesKinds }, { prefs.newFilesKinds = it })
    val dailyReport =
        PrefState({ prefs.dailyReport }, {
            prefs.dailyReport = it
            if (it) prefs.reportSince = 0L
            runCatching { com.omaritoinforma.oiarchivos.data.NewFilesReport.schedule(ctx) }
        })

    fun reportNow() = com.omaritoinforma.oiarchivos.data.NewFilesReport.now(ctx)

    private fun scheduleStorageWatch() {
        runCatching { com.omaritoinforma.oiarchivos.data.StorageWatch.schedule(ctx) }
    }

    // Copia automática: los cambios que afectan a cuándo se copia vuelven a programar el trabajo.
    val remoteSync = PrefState({ prefs.remoteSync }, { prefs.remoteSync = it })

    private val remoteEdits by lazy {
        com.omaritoinforma.oiarchivos.data.RemoteSync.Store(File(ctx.filesDir, "archivos-remotos-abiertos.json"))
    }

    /** Edición con conflicto a la espera de que se elija qué hacer. */
    var remoteConflict by mutableStateOf<com.omaritoinforma.oiarchivos.data.RemoteSync.Edit?>(null)
        private set

    private var syncing = false

    /** Apunta una copia local de un archivo remoto para subirla si se edita. */
    fun trackRemoteEdit(connection: com.omaritoinforma.oiarchivos.data.Connection, parent: String, entry: RemoteEntry, local: File) {
        runCatching {
            remoteEdits.prune()
            remoteEdits.put(
                com.omaritoinforma.oiarchivos.data.RemoteSync.Edit(
                    connection.id, parent, entry.name, local.path, entry.size, local.length(), local.lastModified()))
        }
    }

    /**
     * Sube las copias de archivos remotos que se editaron (en otra app o en el editor propio). Se
     * llama al volver a la app y al salir del editor.
     */
    fun checkRemoteEdits() {
        if (!remoteSync.value || syncing || remoteConflict != null) return
        val pending =
            remoteEdits.all().filter { com.omaritoinforma.oiarchivos.data.RemoteSync.changed(it) }
        val edit = pending.firstOrNull() ?: return
        syncUpload(edit, com.omaritoinforma.oiarchivos.data.RemoteSync.Mode.SAFE)
    }

    fun resolveRemoteConflict(mode: com.omaritoinforma.oiarchivos.data.RemoteSync.Mode?) {
        val edit = remoteConflict ?: return
        remoteConflict = null
        if (mode == null) remoteEdits.remove(edit.local) else syncUpload(edit, mode)
    }

    private fun syncUpload(edit: com.omaritoinforma.oiarchivos.data.RemoteSync.Edit, mode: com.omaritoinforma.oiarchivos.data.RemoteSync.Mode) {
        syncing = true
        toast(tr("Subiendo «{0}» al servidor…", edit.name))
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        com.omaritoinforma.oiarchivos.data.RemoteFiles.connectById(edit.connectionId).use {
                            com.omaritoinforma.oiarchivos.data.RemoteSync.upload(it, edit, mode)
                        }
                    }
                }
            syncing = false
            result
                .onSuccess { outcome ->
                    when (outcome) {
                        is com.omaritoinforma.oiarchivos.data.RemoteSync.Outcome.Updated -> {
                            // Una copia («editado») deja de seguirse; si no, se sigue con lo nuevo.
                            if (mode == com.omaritoinforma.oiarchivos.data.RemoteSync.Mode.COPY)
                                remoteEdits.remove(edit.local)
                            else remoteEdits.put(outcome.edit)
                            toast(tr("«{0}» actualizado en el servidor", outcome.edit.name))
                            tabs.forEach { it.cache.clear() }
                        }
                        com.omaritoinforma.oiarchivos.data.RemoteSync.Outcome.Conflict ->
                            remoteConflict = edit
                        com.omaritoinforma.oiarchivos.data.RemoteSync.Outcome.Gone -> remoteEdits.remove(edit.local)
                    }
                    // Si había más ediciones pendientes, sigue con la siguiente.
                    if (remoteConflict == null) checkRemoteEdits()
                }
                .onFailure { toast(tr("No se pudo subir «{0}»: {1}", edit.name, it.message)) }
        }
    }

    val autoBackup =
        PrefState({ prefs.autoBackup }, {
            prefs.autoBackup = it
            scheduleAutoBackup(replace = true)
        })
    val autoBackupConnection =
        PrefState({ prefs.autoBackupConnection }, {
            prefs.autoBackupConnection = it
            scheduleAutoBackup(replace = true)
        })
    val autoBackupFolder = PrefState({ prefs.autoBackupFolder }, { prefs.autoBackupFolder = it })
    val autoBackupKinds = PrefState({ prefs.autoBackupKinds }, { prefs.autoBackupKinds = it })
    val autoBackupFolders = PrefState({ prefs.autoBackupFolders }, { prefs.autoBackupFolders = it })
    val autoBackupWifiOnly =
        PrefState({ prefs.autoBackupWifiOnly }, {
            prefs.autoBackupWifiOnly = it
            scheduleAutoBackup(replace = true)
        })
    val autoBackupLast = PrefState({ prefs.autoBackupLast }, { prefs.autoBackupLast = it })

    private fun scheduleAutoBackup(replace: Boolean = false) {
        runCatching { com.omaritoinforma.oiarchivos.data.AutoBackup.schedule(ctx, replace) }
    }

    /** Conexiones a las que se puede copiar (no el explorador root ni Bluetooth). */
    fun backupDestinations(): List<com.omaritoinforma.oiarchivos.data.Connection> =
        runCatching { com.omaritoinforma.oiarchivos.data.ConnectionStore(ctx).load() }
            .getOrDefault(emptyList())
            .filter {
                it.protocol != com.omaritoinforma.oiarchivos.data.Protocol.ROOT &&
                    it.protocol != com.omaritoinforma.oiarchivos.data.Protocol.BLUETOOTH
            }

    /** «Copiar ahora»: la misma copia, con progreso y aviso al terminar. */
    fun backupNow() {
        val connectivity =
            ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        if (autoBackupWifiOnly.value && connectivity.isActiveNetworkMetered) {
            toast(tr("Conéctate a una Wi-Fi o desactiva «Solo con Wi-Fi»"))
            return
        }
        runTask(tr("Copia automática")) { report ->
            val outcome = com.omaritoinforma.oiarchivos.data.AutoBackup.run(ctx, report)
            withContext(Dispatchers.Main) { autoBackupLast.reload() }
            outcome.error?.let { throw java.io.IOException(it) }
            OperationResult(
                if (outcome.uploaded == 0) tr("La copia ya estaba al día")
                else tr("{0} archivo(s) copiado(s)", outcome.uploaded))
        }
    }

    val backupBeforeUninstall =
        PrefState({ prefs.backupBeforeUninstall }, { prefs.backupBeforeUninstall = it })
    val appBackupFolder = PrefState({ prefs.appBackupFolder }, { prefs.appBackupFolder = it })
    val editorFont = PrefState({ prefs.editorFont }, { prefs.editorFont = it })
    val editorLineNumbers = PrefState({ prefs.editorLineNumbers }, { prefs.editorLineNumbers = it })
    val editorWrap = PrefState({ prefs.editorWrap }, { prefs.editorWrap = it })
    val editorAutoIndent = PrefState({ prefs.editorAutoIndent }, { prefs.editorAutoIndent = it })
    val editorHighlight = PrefState({ prefs.editorHighlight }, { prefs.editorHighlight = it })
    val editorAutoSave = PrefState({ prefs.editorAutoSave }, { prefs.editorAutoSave = it })
    val editorSpacesForTab = PrefState({ prefs.editorSpacesForTab }, { prefs.editorSpacesForTab = it })
    val editorTabSize = PrefState({ prefs.editorTabSize }, { prefs.editorTabSize = it })
    val editorAutoCapitalize = PrefState({ prefs.editorAutoCapitalize }, { prefs.editorAutoCapitalize = it })
    val editorShowWhitespace = PrefState({ prefs.editorShowWhitespace }, { prefs.editorShowWhitespace = it })
    val editorSymbolBar = PrefState({ prefs.editorSymbolBar }, { prefs.editorSymbolBar = it })
    val editorSymbols = PrefState({ prefs.editorSymbols }, { prefs.editorSymbols = it })
    val lockStart = PrefState({ prefs.lockStart }, { prefs.lockStart = it })
    val lockNetwork = PrefState({ prefs.lockNetwork }, { prefs.lockNetwork = it })
    val lockHidden = PrefState({ prefs.lockHidden }, { prefs.lockHidden = it })
    var hasPassword by mutableStateOf(prefs.lockHash.isNotEmpty())
        private set

    private val esSettings =
        listOf(
            thumbnails,
            historyFoldersOnly,
            clearHistoryOnExit,
            clearCacheOnExit,
            homeFolder,
            downloadFolder,
            startWindow,
            closeNotificationWhenDone,
            lowSpaceWarning,
            lowSpaceMb,
            storageNotification,
            ftpPort,
            ftpEncoding,
            toolbarActions,
            documentTypes,
            showWindowsButton,
            appPermissionNotify,
            cleanAssociatedFolders,
            homeSearch,
            ftpStopOnExit,
            backgroundStrength,
            folderStyle,
            toolbarShowName,
            showSelectButton,
            screenOrientation,
            appLanguage,
            largeLayout,
            editorHighlightLimit,
            newFilesNotify,
            removableNotice,
            newFilesKinds,
            dailyReport,
            remoteSync,
            autoBackup,
            autoBackupFolder,
            autoBackupKinds,
            autoBackupFolders,
            autoBackupWifiOnly,
            backupBeforeUninstall,
            appBackupFolder,
            editorFont,
            editorLineNumbers,
            editorWrap,
            editorAutoIndent,
            editorHighlight,
            editorAutoSave,
            editorSpacesForTab,
            editorTabSize,
            editorAutoCapitalize,
            editorShowWhitespace,
            editorSymbolBar,
            editorSymbols,
            compressionLevel,
            accent,
            pureBlack,
            drawerOrder,
            drawerHidden,
            homeOrder,
            homeHidden,
            homeHiddenTiles,
            lockStart,
            lockNetwork,
            lockHidden)

    /** Pantalla de contraseña al abrir la app («Iniciar protección» de ES). */
    var locked by mutableStateOf(AppLock.needsStart(prefs))
        private set

    /** Petición de contraseña pendiente: al escribirla bien se ejecuta la acción protegida. */
    class UnlockRequest(val reason: String, val onSuccess: () -> Unit)

    var unlockRequest by mutableStateOf<UnlockRequest?>(null)
        private set

    // Declaradas antes de init, que ya abre la ventana inicial.
    private var startOpened = false
    private var exited = false

    val volumes = mutableStateListOf<StorageVolumeInfo>()
    var clipboard by mutableStateOf<Clipboard?>(null)

    /** Archivos elegidos en el explorador para «Enviar a otro teléfono». */
    var nearbyFiles by mutableStateOf<List<String>>(emptyList())

    /** Lo que se va a enviar a la TV con «Enviar a la TV». */
    var castSource by mutableStateOf<com.omaritoinforma.oiarchivos.data.StreamServer.Source?>(null)

    /** Quita una app del sistema para este usuario (root); se devuelve en Explorador root. */
    fun removeSystemApp(app: AppInfo) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { com.omaritoinforma.oiarchivos.data.RootSystem.removeSystemApp(app.packageName) } }
            result
                .onSuccess {
                    toast(tr("«{0}» quitada; se puede devolver en Explorador root", app.label))
                    loadApps(appsIncludeSystem)
                }
                .onFailure { toast(it.message ?: tr("No se pudo desinstalar")) }
        }
    }

    /** Unidad (memoria USB o tarjeta SD) que se quiere expulsar. */
    var ejectRequest by mutableStateOf<String?>(null)

    /** Antes de expulsar se sale de la unidad, para no dejar nada suyo abierto. */
    fun prepareEject(path: String) {
        val here = (currentTab?.location as? Location.Folder)?.path ?: return
        if (here == path || here.startsWith("$path/")) goHome()
    }

    /** APK elegidos en el explorador para instalar en una Android TV. */
    var adbApks by mutableStateOf<List<String>>(emptyList())

    fun installOnTv(files: List<java.io.File>) {
        adbApks = files.map { it.path }
        goTo(Screen.AdbTv)
    }

    fun castTo(source: com.omaritoinforma.oiarchivos.data.StreamServer.Source) {
        castSource = source
        goTo(Screen.Cast)
    }
    var pendingPaste by mutableStateOf<PendingPaste?>(null)
        private set

    var message by mutableStateOf<String?>(null)

    private val _progress = TransferService.progress
    val progress: StateFlow<OpProgress?> = _progress.asStateFlow()

    val apps = mutableStateListOf<AppInfo>()
    var appsLoading by mutableStateOf(false)
        private set

    val trash = mutableStateListOf<RecycleBin.Entry>()

    init {
        if (hasPermission) {
            refreshVolumes()
            openStartWindow()
        }
        scheduleStorageWatch()
        scheduleAutoBackup()
        runCatching { com.omaritoinforma.oiarchivos.data.NewFilesReport.schedule(ctx) }
        viewModelScope.launch {
            // Al conectar o quitar una memoria USB o tarjeta SD se relee la lista de unidades.
            com.omaritoinforma.oiarchivos.data.RemovableStorage.changes.collect { if (hasPermission) refreshVolumes() }
        }
        viewModelScope.launch {
            AppInstaller.finished.collect { summary ->
                toast(summary)
                if (screen == Screen.Apps) loadApps(appsIncludeSystem)
            }
        }
        viewModelScope.launch {
            TransferService.completion.collect { completed ->
                if (completed != null) {
                    toast(completed.second)
                    currentTab?.selected?.clear()
                    tabs.forEach { it.cache.clear() }
                    refresh()
                    if (screen == Screen.Trash) loadTrash()
                }
            }
        }
    }

    private var incoming: android.content.Intent? = null

    /** Destino leído de un código QR, a la espera de que se confirme el envío. */
    var qrPeer by mutableStateOf<com.omaritoinforma.oiarchivos.data.Nearby.Peer?>(null)

    fun receive(intent: android.content.Intent) {
        // El enlace de un código QR de otro teléfono: abre «Enviar a otro teléfono» con ese destino.
        if (intent.action == android.content.Intent.ACTION_VIEW &&
            intent.data?.scheme == com.omaritoinforma.oiarchivos.data.NearbyLink.SCHEME) {
            val peer = com.omaritoinforma.oiarchivos.data.NearbyLink.parse(intent.data.toString())
            if (peer == null) toast(tr("El código QR no es de un teléfono de tu red local"))
            else {
                qrPeer = peer
                goTo(Screen.Nearby)
            }
            intent.data = null
            return
        }
        intent.getStringExtra("folder")?.let { if (File(it).isDirectory) openFolder(it) }
        intent.getStringExtra("eject")?.let {
            ejectRequest = it
            intent.removeExtra("eject")
        }
        when (intent.getStringExtra("screen")) {
            "transfers" -> goTo(Screen.Transfers)
            "cleaner" -> goTo(Screen.Cleaner)
            "recent" -> navigate(Location.Category(FileCategory.RECENT))
            "permissions" -> goTo(Screen.AppAnalysis)
        }
        if (intent.action in
            setOf(
                android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE)) {
            incoming = intent
            if (hasPermission) importIncoming()
        }
    }

    private fun importIncoming() {
        val intent = incoming ?: return
        incoming = null
        @Suppress("DEPRECATION")
        val streams: List<android.net.Uri> =
            if (intent.action == android.content.Intent.ACTION_SEND_MULTIPLE)
                intent
                    .getParcelableArrayListExtra<android.net.Uri>(
                        android.content.Intent.EXTRA_STREAM)
                    .orEmpty()
            else
                listOfNotNull(
                    intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
        val text = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
        runTask(tr("Guardando contenido compartido")) { report ->
            val dest = File(PathUtil.internalRoot, "Download/Compartido con OI").apply { mkdirs() }
            val out = ArrayList<File>()
            for (uri in streams) {
                currentCoroutineContext().ensureActive()
                val document =
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)
                        ?: throw java.io.IOException(tr("No se pudo abrir el archivo compartido"))
                out +=
                    com.omaritoinforma.oiarchivos.ui.screens.importDocument(
                        ctx, document, dest, report)
            }
            if (text != null) {
                val target =
                    FileOps.uniqueName(dest, "Compartido-${System.currentTimeMillis()}.txt")
                SafeFiles.writeAtomic(target) { it.writeText(text) }
                out += target
            }
            OperationResult(tr("Guardado en Descargas/Compartido con OI ({0})", out.size), out)
        }
    }

    fun onResume() {
        val had = hasPermission
        hasPermission = Perms.hasStorage(ctx)
        if (hasPermission) {
            refreshVolumes()
            if (!had) openStartWindow()
            if (incoming != null) importIncoming()
            if (had && screen == Screen.Browser) refresh()
        }
        checkRemoteEdits()
    }

    fun toast(text: String) {
        message = text
    }

    // ---------------- Navegación ----------------

    // ---------------- Ocultar archivos ----------------

    /** Lo ocultado desde la app que sigue existiendo; quita de la lista lo que ya no está. */
    fun hiddenList(): List<File> {
        val all = prefs.hiddenItems
        val alive = all.filter { File(it).exists() }
        if (alive.size != all.size) prefs.hiddenItems = alive
        return alive.map(::File)
    }

    fun hideItems(items: List<FileItem>) {
        viewModelScope.launch {
            val done = ArrayList<File>()
            var failed: String? = null
            withContext(Dispatchers.IO) {
                for (item in items) {
                    runCatching { com.omaritoinforma.oiarchivos.data.HiddenFiles.hide(item.file) }
                        .onSuccess { done += it }
                        .onFailure { failed = it.message }
                }
            }
            prefs.hiddenItems = (prefs.hiddenItems + done.map { it.absolutePath }).distinct()
            currentTab?.selected?.clear()
            afterQuickChange(done + items.map { it.file })
            toast(
                failed?.let { tr("{0} oculto(s). {1}", done.size, it) }
                    ?: tr("{0} oculto(s): se ven en la «Lista de ocultos»", done.size))
        }
    }

    fun unhideFiles(files: List<File>) {
        viewModelScope.launch {
            val shown = ArrayList<File>()
            var failed: String? = null
            withContext(Dispatchers.IO) {
                for (file in files) {
                    runCatching { com.omaritoinforma.oiarchivos.data.HiddenFiles.show(file) }
                        .onSuccess { shown += it }
                        .onFailure { failed = it.message }
                }
            }
            val removed = files.filter { f -> shown.any { it.name == f.name.removePrefix(".") && it.parentFile == f.parentFile } }
            prefs.hiddenItems = prefs.hiddenItems - removed.map { it.absolutePath }.toSet()
            currentTab?.selected?.clear()
            afterQuickChange(shown + files)
            toast(failed?.let { tr("{0} visible(s). {1}", shown.size, it) } ?: tr("{0} visible(s) otra vez", shown.size))
        }
    }

    fun goTo(s: Screen) {
        if (s == Screen.HiddenList && AppLock.needsHidden(prefs)) {
            requestUnlock(tr("Lista de ocultos")) { goTo(s) }
            return
        }
        if (s is Screen.Remote && needsNetworkUnlock(s.id)) {
            requestUnlock(tr("Conexiones de red")) { goTo(s) }
            return
        }
        if (s == Screen.Home) {
            screens.clear()
            screens.add(Screen.Home)
            return
        }
        screens.remove(s)
        screens.add(s)
    }

    fun goHome() = goTo(Screen.Home)

    val canGoBack: Boolean
        get() {
            if (screen == Screen.Browser) {
                val t = currentTab
                if (t != null && (t.selected.isNotEmpty() || t.selectMode || t.history.size > 1)) return true
            }
            return screens.size > 1
        }

    fun back() {
        if (screen == Screen.Browser) {
            val t = currentTab
            if (t != null) {
                if (t.selected.isNotEmpty() || t.selectMode) {
                    t.selected.clear()
                    t.selectMode = false
                    return
                }
                if (t.history.size > 1) {
                    val left = t.history.removeAt(t.history.lastIndex)
                    t.cache.remove(left)
                    load(t)
                    return
                }
            }
        }
        val leaving = screen
        if (screens.size > 1) screens.removeAt(screens.lastIndex)
        if (leaving is Screen.Editor) checkRemoteEdits()
    }

    fun navigate(loc: Location, newTab: Boolean = false) {
        val existing = currentTab
        val tab =
            if (newTab || existing == null) {
                TabState(loc).also {
                    tabs.add(it)
                    activeTab = tabs.lastIndex
                }
            } else {
                if (existing.location != loc) existing.history.add(loc)
                existing
            }
        tab.selected.clear()
        tab.selectMode = false
        load(tab)
        goTo(Screen.Browser)
    }

    fun openFolder(path: String) {
        addToHistory(path)
        navigate(Location.Folder(path))
    }

    private fun addToHistory(path: String) {
        prefs.history = (listOf(path) + prefs.history).distinct().take(100)
    }

    /** Carpetas y archivos abiertos; solo carpetas si así se eligió en Ajustes → Pantalla. */
    fun history(): List<String> =
        prefs.history.let { all ->
            if (historyFoldersOnly.value) all.filter { File(it).isDirectory } else all
        }

    fun clearHistory() {
        prefs.history = emptyList()
        prefs.lastFolder = ""
    }

    fun showResults(items: List<FileItem>, root: String, query: String, filter: SearchFilter) {
        val tab = TabState(Location.Search(root, query, filter))
        tab.items.addAll(Sorter.sort(items, sortBy, ascending, pinned.toSet()))
        tabs.add(tab)
        activeTab = tabs.lastIndex
        goTo(Screen.Browser)
    }

    fun openFile(path: String) {
        val file = File(path)
        if (!file.isDirectory) addToHistory(path)
        when {
            file.isDirectory -> openFolder(path)
            ArchiveTools.supports(file) && file.extension != "apk" -> goTo(Screen.Archive(path))
            Kinds.isEditable(file.extension.lowercase()) -> openEditor(path)
            Kinds.ofExt(file.extension.lowercase()) in
                setOf(FileKind.IMAGE, FileKind.AUDIO, FileKind.VIDEO, FileKind.PDF) ->
                goTo(Screen.Viewer(path))
            else -> com.omaritoinforma.oiarchivos.util.Opener.open(ctx, file)
        }
    }

    fun pasteNetwork(folder: String) {
        val clip = com.omaritoinforma.oiarchivos.data.NetworkClipboard.value ?: return
        downloadDurable(clip.connection, clip.entries, clip.parent, File(folder), clip.move) {
            com.omaritoinforma.oiarchivos.data.NetworkClipboard.value = null
        }
    }

    /** Remoto → local con un registro que Transferencias puede reanudar tras una pausa o un cierre. */
    fun downloadDurable(
        connection: com.omaritoinforma.oiarchivos.data.Connection,
        entries: List<com.omaritoinforma.oiarchivos.data.RemoteEntry>,
        parent: String,
        folder: File,
        move: Boolean,
        planned: () -> Unit = {}
    ) {
        durable(if (move) tr("Moviendo") else tr("Descargando")) {
            com.omaritoinforma.oiarchivos.data.RemoteFiles.connect(connection).use { fs ->
                com.omaritoinforma.oiarchivos.data.DurableRemote.createDownload(
                    TransferService.jobsDirectory(ctx),
                    fs,
                    connection,
                    entries,
                    parent,
                    folder,
                    move,
                    com.omaritoinforma.oiarchivos.data.RemoteFiles::connectById)
            }.also { withContext(Dispatchers.Main) { planned() } }
        }
    }

    /** Local → remoto con registro; cada original se borra solo después de subirlo. */
    fun uploadDurable(
        connection: com.omaritoinforma.oiarchivos.data.Connection,
        sources: List<File>,
        parent: String,
        move: Boolean
    ) {
        durable(if (move) tr("Moviendo") else tr("Subiendo")) {
            com.omaritoinforma.oiarchivos.data.DurableRemote.createUpload(
                    TransferService.jobsDirectory(ctx),
                    connection,
                    sources,
                    parent,
                    move,
                    com.omaritoinforma.oiarchivos.data.RemoteFiles::connectById)
                .also { withContext(Dispatchers.Main) { clipboard = null } }
        }
    }

    private fun durable(
        title: String,
        plan: suspend () -> com.omaritoinforma.oiarchivos.data.DurableJob
    ) {
        try {
            if (!TransferService.submitDurable(ctx, title, plan))
                toast(tr("Espera a que termine la operación actual"))
        } catch (e: Exception) {
            toast(e.message ?: tr("No se pudo iniciar la operación"))
        }
    }

    fun encrypt(item: FileItem, password: String, decrypt: Boolean) {
        val target =
            FileOps.uniqueName(
                item.file.parentFile!!,
                if (decrypt)
                    item.name.removeSuffix(".oienc").let {
                        if (it == item.name) "$it.descifrado" else it
                    }
                else item.name + ".oienc")
        runTask(if (decrypt) tr("Descifrando") else tr("Cifrando")) { report ->
            CryptoTools.transform(item.file, target, password.toCharArray(), decrypt, report)
            OperationResult(tr("Creado «{0}». El original se conserva.", target.name), listOf(target))
        }
    }

    fun up() {
        val loc = currentTab?.location as? Location.Folder ?: return
        val parent = File(loc.path).parentFile ?: return
        navigate(Location.Folder(parent.absolutePath))
    }

    /** Busca por nombre en todo el almacenamiento (el buscador de Inicio). */
    fun searchEverywhere(query: String) {
        navigate(Location.Search(PathUtil.internalRoot, query))
    }

    fun search(query: String) {
        val loc = currentTab?.location
        // Dentro de una categoría (Imágenes, Música…) se busca solo entre sus archivos.
        val category = (loc as? Location.Category)?.category ?: (loc as? Location.Search)?.category
        val root = (loc as? Location.Folder)?.path ?: PathUtil.internalRoot
        navigate(Location.Search(root, query, category = category))
    }

    fun addTab(loc: Location = currentTab?.location ?: Location.Folder(PathUtil.internalRoot)) =
        navigate(loc, newTab = true)

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        load(tabs[index])
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        tabs[index].job?.cancel()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            activeTab = 0
            screens.remove(Screen.Browser)
            return
        }
        if (index < activeTab || activeTab > tabs.lastIndex)
            activeTab = (activeTab - 1).coerceIn(0, tabs.lastIndex)
        currentTab?.let { load(it) }
    }

    fun refresh() {
        currentTab?.let { load(it) }
    }

    private fun load(tab: TabState) {
        val loc = tab.location
        if (loc is Location.Folder) prefs.lastFolder = loc.path
        tab.job?.cancel()
        tab.items.clear()
        tab.cache[loc]?.let { tab.items.addAll(it) }
        tab.loading = true
        tab.job =
            viewModelScope.launch {
                try {
                    if (loc is Location.Search && loc.filter != null) {
                        // Al actualizar resultados avanzados se repiten los mismos filtros, no una
                        // búsqueda por el título de la pestaña.
                        val found =
                            withContext(Dispatchers.IO) {
                                AnalysisTools.search(File(loc.root), loc.filter) {}
                            }
                        tab.items.clear()
                        tab.items.addAll(sorted(found, loc))
                    } else if (loc is Location.Search && loc.category != null) {
                        val found =
                            withContext(Dispatchers.IO) {
                                Categories.byName(
                                    runCatching { Categories.query(ctx, loc.category, showHidden, documentTypeSet()) }
                                        .getOrDefault(emptyList()),
                                    loc.query)
                            }
                        tab.items.clear()
                        tab.items.addAll(sorted(found, loc))
                    } else if (loc is Location.Search) {
                        tab.items.clear()
                        FileRepo.search(File(loc.root), loc.query, showHidden).collect {
                            tab.items.add(it)
                        }
                        val sorted = sorted(tab.items.toList(), loc)
                        tab.items.clear()
                        tab.items.addAll(sorted)
                    } else {
                        val list =
                            withContext(Dispatchers.IO) {
                                when (loc) {
                                    is Location.Folder -> FileRepo.list(File(loc.path), showHidden)
                                    is Location.Category ->
                                        runCatching {
                                                Categories.query(ctx, loc.category, showHidden, documentTypeSet())
                                            }
                                            .getOrDefault(emptyList<FileItem>())
                                    is Location.Search -> emptyList<FileItem>()
                                }
                            }
                        if (list == null) {
                            toast(tr("No se puede leer esta carpeta"))
                            tab.items.clear()
                        } else {
                            val sorted = sorted(list, loc)
                            tab.items.clear()
                            tab.items.addAll(sorted)
                            tab.cache[loc] = sorted
                        }
                    }
                } finally {
                    if (isActive) tab.loading = false
                }
            }
    }

    private fun sorted(list: List<FileItem>, loc: Location): List<FileItem> =
        if (loc is Location.Category && loc.category == FileCategory.RECENT) list
        else Sorter.sort(list, sortBy, ascending, pinned.toSet())

    // ---------------- Selección ----------------

    fun toggleSelect(item: FileItem) {
        val t = currentTab ?: return
        if (t.selected.containsKey(item.path)) {
            t.selected.remove(item.path)
        } else {
            t.selected[item.path] = item
        }
    }

    fun selectAll() {
        val t = currentTab ?: return
        t.items.forEach { t.selected[it.path] = it }
    }

    /** Selecciona todo lo que hay entre el primer y el último elemento marcado (como ES). */
    fun selectRange() {
        val t = currentTab ?: return
        val idx = t.items.indices.filter { t.selected.containsKey(t.items[it].path) }
        if (idx.size < 2) {
            toast(tr("Marca el primero y el último elemento del rango"))
            return
        }
        for (i in idx.first()..idx.last()) t.items[i].let { t.selected[it.path] = it }
    }

    fun invertSelection() {
        val t = currentTab ?: return
        val inverted = t.items.filter { !t.selected.containsKey(it.path) }
        t.selected.clear()
        t.selectMode = false
        inverted.forEach { t.selected[it.path] = it }
    }

    fun clearSelection() {
        currentTab?.selected?.clear()
        currentTab?.selectMode = false
    }

    /** Empieza a marcar con el botón «Seleccionar» de la barra (sin mantener pulsado). */
    fun startSelecting() {
        currentTab?.selectMode = true
    }

    // ---------------- Vista y ajustes ----------------

    fun cycleViewMode() {
        viewMode = ViewMode.entries[(viewMode.ordinal + 1) % ViewMode.entries.size]
        prefs.viewMode = viewMode
    }

    fun setSort(by: SortBy, asc: Boolean) {
        sortBy = by
        ascending = asc
        prefs.sortBy = by
        prefs.ascending = asc
        tabs.forEach { t ->
            val s = sorted(t.items.toList(), t.location)
            t.items.clear()
            t.items.addAll(s)
            t.cache.clear()
        }
    }

    /** Buscar entre los ocultos pide la misma contraseña que mostrarlos («Proteger los archivos ocultos»). */
    fun allowHiddenSearch(onAllowed: () -> Unit) {
        if (showHidden || !AppLock.needsHidden(prefs)) onAllowed()
        else requestUnlock(tr("Archivos ocultos"), onAllowed)
    }

    fun toggleHidden() {
        // Mostrar los ocultos puede requerir la contraseña («Protección de la lista de ocultos»).
        if (!showHidden && AppLock.needsHidden(prefs)) {
            requestUnlock(tr("Archivos ocultos")) { toggleHidden() }
            return
        }
        showHidden = !showHidden
        prefs.showHidden = showHidden
        tabs.forEach { it.cache.clear() }
        refresh()
    }

    /** Pone [source] como fondo de la app; avisa si no se pudo. */
    fun setBackground(source: File) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BackgroundImage.set(ctx, source) } }
                .onSuccess {
                    backgroundVersion++
                    backgroundImage.value = true
                    toast(tr("Fondo cambiado"))
                }
                .onFailure { toast(it.message ?: tr("No se pudo usar esa imagen")) }
        }
    }

    fun clearBackground() {
        BackgroundImage.clear(ctx)
        backgroundImage.value = false
        backgroundVersion++
    }

    /** Cambia con cada imagen nueva, para que la pantalla vuelva a leerla. */
    var backgroundVersion by mutableIntStateOf(0)
        private set

    fun updateUseTrash(value: Boolean) {
        useTrash = value
        prefs.useTrash = value
    }

    fun updateTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.themeMode = mode
    }

    fun toggleBookmark(path: String) {
        if (path in bookmarks) bookmarks.remove(path) else bookmarks.add(path)
        prefs.bookmarks = bookmarks.toList()
    }

    fun refreshVolumes() {
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { StorageInfo.volumes(ctx) }
            volumes.clear()
            volumes.addAll(v)
        }
    }

    // ---------------- Operaciones con archivos ----------------

    private fun currentFolder(): File? =
        (currentTab?.location as? Location.Folder)?.let { File(it.path) }

    private fun nameError(name: String): String? =
        when {
            name.isBlank() -> tr("El nombre no puede estar vacío")
            !SafeFiles.validName(name.trim()) -> tr("Nombre de archivo no válido")
            name.trim() == "." || name.trim() == ".." -> tr("Nombre no válido")
            else -> null
        }

    fun create(name: String, folder: Boolean) {
        val dir = currentFolder() ?: return
        nameError(name)?.let {
            toast(it)
            return
        }
        val f = File(dir, name.trim())
        if (f.exists()) {
            toast(tr("Ya existe «{0}»", f.name))
            return
        }
        val ok = runCatching { if (folder) f.mkdir() else f.createNewFile() }.getOrDefault(false)
        toast(if (ok) tr("Creado «{0}»", f.name) else tr("No se pudo crear «{0}»", f.name))
        if (ok) afterQuickChange(listOf(f))
    }

    fun rename(item: FileItem, newName: String) {
        nameError(newName)?.let {
            toast(it)
            return
        }
        val target = File(item.file.parentFile, newName.trim())
        if (target.name == item.name) return
        if (target.exists() && !target.name.equals(item.name, ignoreCase = true)) {
            toast(tr("Ya existe «{0}»", target.name))
            return
        }
        if (item.file.renameTo(target)) {
            val bi = bookmarks.indexOf(item.path)
            if (bi >= 0) {
                bookmarks[bi] = target.absolutePath
                prefs.bookmarks = bookmarks.toList()
            }
            val pi = pinned.indexOf(item.path)
            if (pi >= 0) {
                pinned[pi] = target.absolutePath
                prefs.pinned = pinned.toList()
            }
            currentTab?.selected?.clear()
            afterQuickChange(listOf(item.file, target))
        } else {
            toast(tr("No se pudo renombrar"))
        }
    }

    fun batchRename(items: List<FileItem>, rules: RenameRules) {
        runOp(tr("Renombrando")) { report ->
            var done = 0
            var skipped = 0
            val changed = ArrayList<File>()
            for ((i, it) in items.withIndex()) {
                report(OpProgress(tr("Renombrando"), it.name, doneFiles = i, totalFiles = items.size))
                val newName = rules.apply(it.name, i, it.isDirectory)
                val target = File(it.file.parentFile, newName)
                if (newName == it.name || newName.isBlank() || newName.contains('/')) {
                    skipped++
                    continue
                }
                if (target.exists() || !it.file.renameTo(target)) {
                    skipped++
                } else {
                    done++
                    changed += it.file
                    changed += target
                }
            }
            OpResult(
                tr("Renombrados: {0}", done) + (if (skipped > 0) tr(" · omitidos: {0}", skipped) else ""), changed)
        }
    }

    fun copySelection(move: Boolean) {
        val t = currentTab ?: return
        val paths = t.selected.keys.toList()
        if (paths.isEmpty()) return
        clipboard = Clipboard(paths, move)
        t.selected.clear()
        t.selectMode = false
        toast(
            if (move) tr("{0} elemento(s) listos para mover. Ve al destino y toca «Pegar aquí».", paths.size)
            else tr("{0} elemento(s) listos para copiar. Ve al destino y toca «Pegar aquí».", paths.size))
    }

    /** «Poner como tono»; si falta el permiso de ajustes del sistema, abre la pantalla para darlo. */
    // ---------------- Listas de reproducción ----------------

    val playlists = com.omaritoinforma.oiarchivos.data.Playlists(File(ctx.filesDir, "listas"))

    var playlistNames by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshPlaylists() {
        playlistNames = runCatching { playlists.names() }.getOrDefault(emptyList())
    }

    /** Hace un cambio en las listas; si falla, avisa con el motivo y devuelve false. */
    fun editPlaylists(done: String? = null, change: () -> Unit): Boolean {
        val ok =
            runCatching(change)
                .onFailure { toast(it.message ?: tr("No se pudo cambiar la lista")) }
                .isSuccess
        if (ok && done != null) toast(done)
        refreshPlaylists()
        return ok
    }

    fun addToPlaylist(name: String, paths: List<String>) {
        var added = 0
        if (editPlaylists { added = playlists.add(name, paths) }) {
            clearSelection()
            toast(
                if (added == 0) tr("Ya estaban en «{0}»", name.trim())
                else tr("{0} añadido(s) a «{1}»", added, name.trim()))
        }
    }

    fun setRingtone(file: File, kind: com.omaritoinforma.oiarchivos.util.Ringtones.Kind) {
        val tones = com.omaritoinforma.oiarchivos.util.Ringtones
        if (!tones.canWrite(ctx)) {
            toast(tr("Permite a OI Archivos cambiar los ajustes del sistema y vuelve a intentarlo"))
            runCatching { tones.askPermission(ctx) }
            return
        }
        viewModelScope.launch {
            runCatching { tones.set(ctx, file, kind) }
                .onSuccess { toast(tr("«{0}» es ahora el {1}", file.name, kind.label.lowercase())) }
                .onFailure { toast(it.message ?: tr("No se pudo poner como tono")) }
        }
    }

    /** Como en ES: junta en el portapapeles archivos de varias carpetas antes de pegar. */
    fun addSelectionToClipboard() {
        val t = currentTab ?: return
        val clip = clipboard ?: return copySelection(move = false)
        val paths = t.selected.keys.toList()
        if (paths.isEmpty()) return
        clipboard = Clipboard((clip.paths + paths).distinct(), clip.move)
        t.selected.clear()
        t.selectMode = false
        toast(tr("En el portapapeles: {0} elemento(s)", clipboard!!.paths.size))
    }

    fun removeFromClipboard(path: String) {
        val clip = clipboard ?: return
        val rest = clip.paths - path
        clipboard = if (rest.isEmpty()) null else Clipboard(rest, clip.move)
    }

    fun paste() {
        val dest = currentFolder() ?: return
        pasteInto(dest.path)
    }

    fun pasteInto(path: String) {
        val clip = clipboard ?: return
        val dest = File(path)
        val sources = clip.paths.map(::File).filter { it.exists() }
        if (sources.isEmpty()) {
            toast(tr("Los archivos del portapapeles ya no existen"))
            clipboard = null
            return
        }
        val conflicts =
            sources.count { s ->
                val t = File(dest, s.name)
                t.exists() && t.canonicalPath != s.canonicalPath
            }
        if (conflicts > 0) pendingPaste = PendingPaste(sources, dest, clip.move, conflicts)
        else doPaste(sources, dest, clip.move, Conflict.RENAME)
    }

    /** null = cancelar. */
    fun resolvePaste(conflict: Conflict?) {
        val p = pendingPaste ?: return
        pendingPaste = null
        if (conflict != null) doPaste(p.sources, p.dest, p.move, conflict)
    }

    private fun doPaste(sources: List<File>, dest: File, move: Boolean, conflict: Conflict) {
        clipboard = null
        viewModelScope.launch {
            runCatching {
                    val job =
                        withContext(Dispatchers.IO) {
                            DurableCopy.create(
                                TransferService.jobsDirectory(ctx), sources, dest, move, conflict)
                        }
                    if (!TransferService.submitDurable(ctx, job))
                        toast(
                            tr("Transferencia guardada en la cola. Abre Transferencias para iniciarla."))
                }
                .onFailure { toast(it.message ?: tr("No se pudo preparar la copia")) }
        }
    }

    fun delete(items: List<FileItem>, toTrash: Boolean) {
        val files = items.map { it.file }
        runOp(if (toTrash) tr("Moviendo a la papelera") else tr("Eliminando")) { report ->
            val failed = FileOps.deleteAll(files, toTrash, report)
            val ok = files.size - failed
            val msg = buildString {
                append(if (toTrash) tr("Enviados a la papelera: {0}", ok) else tr("Eliminados: {0}", ok))
                if (failed > 0) append(tr(" · fallaron: {0}", failed))
            }
            OpResult(msg, files)
        }
    }

    fun compress(
        items: List<FileItem>,
        name: String,
        password: String = "",
        level: com.omaritoinforma.oiarchivos.data.CompressionLevel = compressionLevel.value
    ) {
        val dir = currentFolder() ?: items.firstOrNull()?.file?.parentFile ?: return
        val clean =
            name.trim().let {
                if (it.lowercase().let { n ->
                    n.endsWith(".zip") ||
                        n.endsWith(".7z") ||
                        n.endsWith(".tar") ||
                        n.endsWith(".tar.gz")
                })
                    it
                else "$it.zip"
            }
        nameError(clean)?.let {
            toast(it)
            return
        }
        val target = FileOps.uniqueName(dir, clean)
        compressionLevel.value = level
        runOp(tr("Comprimiendo")) { report ->
            ArchiveTools.compress(items.map { it.file }, target, password, level, report)
            OpResult(tr("Creado «{0}»", target.name), listOf(target))
        }
    }

    fun extract(item: FileItem, password: String = "") {
        val parent = item.file.parentFile ?: return
        val dest = FileOps.uniqueName(parent, item.name.substringBeforeLast('.'))
        runOp(tr("Extrayendo")) { report ->
            val n = ArchiveTools.extract(item.file, dest, password, report)
            OpResult(tr("Extraídos {0} archivos en «{1}»", n, dest.name), listOf(dest))
        }
    }

    fun cancelOp() {
        TransferService.cancel(ctx)
    }

    fun openEditor(path: String) = goTo(Screen.Editor(path))

    private fun afterQuickChange(changed: List<File>) {
        tabs.forEach { it.cache.clear() }
        refresh()
        viewModelScope.launch(Dispatchers.IO) { Media.scan(ctx, changed) }
    }

    data class OpResult(val message: String?, val changed: List<File> = emptyList())

    /** La operación pertenece al servicio: salir de la Activity no la cancela. */
    fun runTask(title: String, block: suspend ((OpProgress) -> Unit) -> OperationResult) {
        try {
            if (!TransferService.submit(ctx, title, block))
                toast(tr("Espera a que termine la operación actual"))
        } catch (e: Exception) {
            toast(e.message ?: tr("No se pudo iniciar la operación"))
        }
    }

    private fun runOp(
        title: String,
        onDone: () -> Unit = {},
        block: suspend ((OpProgress) -> Unit) -> OpResult
    ) {
        runTask(title) { report ->
            val result = block(report)
            withContext(Dispatchers.Main) { onDone() }
            OperationResult(result.message, result.changed)
        }
    }

    // ---------------- Aplicaciones ----------------

    private var appsIncludeSystem = false

    fun loadApps(includeSystem: Boolean) {
        appsIncludeSystem = includeSystem
        appsLoading = true
        viewModelScope.launch {
            val list =
                withContext(Dispatchers.IO) {
                    runCatching { AppsRepo.list(ctx, includeSystem) }
                        .getOrDefault(emptyList<AppInfo>())
                }
            apps.clear()
            apps.addAll(list)
            appsLoading = false
        }
    }

    fun backupApps(list: List<AppInfo>) {
        if (list.isEmpty()) return
        runOp(tr("Respaldando apps")) { report ->
            val out = ArrayList<File>()
            for ((i, a) in list.withIndex()) {
                currentCoroutineContext().ensureActive()
                report(
                    OpProgress(tr("Respaldando apps"), a.label, doneFiles = i, totalFiles = list.size))
                runCatching { AppsRepo.backup(a, File(appBackupFolder.value)) }.onSuccess { out += it }
            }
            OpResult(tr("APK guardados en «{0}» ({1} de {2})", PathUtil.displayName(appBackupFolder.value), out.size, list.size), out)
        }
    }

    // ---------------- Instalar y desinstalar por lotes ----------------

    /** Instala varios APK seguidos; Android pide confirmar cada uno. */
    fun installApks(files: List<File>) {
        if (files.isEmpty()) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            toast(tr("Permite a OI Archivos instalar apps y vuelve a intentarlo"))
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        if (AppInstaller.start(ctx, files.map { AppInstaller.Install(it) })) {
            clearSelection()
            toast(tr("Instalando {0} APK: confirma cada uno", files.size))
        } else toast(tr("Espera a que termine la tanda de instalación en curso"))
    }

    /**
     * Desinstala varias apps seguidas (solo las que no son del sistema). Si en Ajustes →
     * Aplicaciones está activada la copia, antes se guardan sus APK.
     */
    fun uninstallApps(list: List<AppInfo>) {
        val user = list.filter { !it.isSystem }
        if (user.isEmpty()) {
            toast(tr("Las apps del sistema no se pueden desinstalar sin root"))
            return
        }
        if (AppInstaller.busy) {
            toast(tr("Espera a que termine la tanda en curso"))
            return
        }
        val jobs = user.map { AppInstaller.Uninstall(it.packageName, it.label) }
        val skipped = list.size - user.size
        val start = {
            AppInstaller.start(ctx, jobs)
            if (skipped > 0) toast(tr("Se omiten {0} app(s) del sistema", skipped))
        }
        if (!backupBeforeUninstall.value) return start()
        val folder = File(appBackupFolder.value)
        runTask(tr("Copia antes de desinstalar")) { report ->
            val copies = ArrayList<File>()
            for ((i, app) in user.withIndex()) {
                report(OpProgress(tr("Copia antes de desinstalar"), app.label, doneFiles = i, totalFiles = user.size))
                copies += AppsRepo.backup(app, folder)
            }
            withContext(Dispatchers.Main) { start() }
            OperationResult(tr("Copias guardadas: {0}", copies.size), copies)
        }
    }

    /** Desinstala [app]; antes guarda su APK si así se eligió en Ajustes → Aplicaciones. */
    fun uninstall(app: AppInfo) {
        // Con PackageInstaller (como la tanda) se sabe cuándo terminó: hace falta para ofrecer
        // limpiar las carpetas que deja la app. Android pide confirmar igual que con ACTION_DELETE.
        val startUninstall = {
            if (!AppInstaller.start(ctx, listOf(AppInstaller.Uninstall(app.packageName, app.label))))
                toast(tr("Espera a que termine la tanda en curso"))
        }
        if (!backupBeforeUninstall.value) return startUninstall()
        val folder = File(appBackupFolder.value)
        runTask(tr("Copia antes de desinstalar")) { report ->
            report(OpProgress(tr("Copia antes de desinstalar"), app.label))
            val copy = AppsRepo.backup(app, folder)
            withContext(Dispatchers.Main) { startUninstall() }
            OperationResult(tr("Copia guardada: {0}", copy.name), listOf(copy))
        }
    }

    // ---------------- Ajustes: ventana inicial, salir y limpieza ----------------

    /** Abre la ventana elegida en Ajustes → Ventana inicial, una vez por apertura de la app. */
    private fun openStartWindow() {
        if (startOpened) return
        startOpened = true
        val target =
            when (startWindow.value) {
                StartWindow.HOME -> null
                StartWindow.HOME_FOLDER -> homeFolder.value
                StartWindow.LAST_FOLDER -> prefs.lastFolder.ifBlank { null }
            }
        if (target != null && File(target).isDirectory) navigate(Location.Folder(target))
    }

    /** «Salir» del menú lateral: limpia lo elegido en Ajustes → Limpieza y cierra la sesión. */
    fun exit() {
        if (exited) return
        exited = true
        if (ftpStopOnExit.value) ShareService.stop(ctx)
        if (clearHistoryOnExit.value) clearHistory()
        if (clearCacheOnExit.value) {
            val app = ctx
            Thread { runCatching { CacheCleaner.clear(app) } }.start()
        }
        AppLock.lock()
    }

    fun cacheSize(): Long = CacheCleaner.size(ctx)

    fun clearCache(onDone: (Long) -> Unit) {
        if (TransferService.isBusy) {
            toast(tr("Espera a que termine la operación actual"))
            return
        }
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { CacheCleaner.clear(ctx) }
            toast(tr("Caché eliminada: {0}", com.omaritoinforma.oiarchivos.util.formatSize(freed)))
            onDone(freed)
        }
    }

    // ---------------- Ajustes: contraseña ----------------

    private fun needsNetworkUnlock(id: String): Boolean {
        if (!AppLock.needsNetwork(prefs)) return false
        // El explorador root no es un recurso de red.
        val protocol =
            runCatching {
                    com.omaritoinforma.oiarchivos.data.ConnectionStore(ctx).load()
                        .firstOrNull { it.id == id }
                        ?.protocol
                }
                .getOrNull()
        return protocol != com.omaritoinforma.oiarchivos.data.Protocol.ROOT
    }

    fun requestUnlock(reason: String, onSuccess: () -> Unit) {
        unlockRequest = UnlockRequest(reason, onSuccess)
    }

    fun dismissUnlock() {
        unlockRequest = null
    }

    /** Comprueba la contraseña; si es correcta, quita el bloqueo y hace la acción pendiente. */
    fun unlock(password: String): Boolean {
        if (!AppLock.unlock(prefs, password)) return false
        locked = false
        val pending = unlockRequest
        unlockRequest = null
        pending?.onSuccess?.invoke()
        return true
    }

    fun onForeground() {
        AppLock.onForeground()
        if (AppLock.needsStart(prefs)) locked = true
    }

    fun onBackground() = AppLock.onBackground()

    /** Verdadero solo la primera vez: el permiso de notificaciones se pide una sola vez. */
    fun askNotificationPermissionOnce(): Boolean {
        if (prefs.notificationPermissionAsked) return false
        prefs.notificationPermissionAsked = true
        return true
    }

    /** Crea la contraseña (si aún no hay) y activa una protección. */
    fun enableLock(option: PrefState<Boolean>, newPassword: String? = null): String? {
        if (!hasPassword) {
            if (newPassword.isNullOrEmpty()) return tr("Escribe una contraseña")
            prefs.lockHash = AppLock.encode(newPassword)
            hasPassword = true
            AppLock.unlock(prefs, newPassword)
        }
        option.value = true
        return null
    }

    /** Desactiva una protección si [password] es correcta; sin protecciones se borra la contraseña. */
    fun disableLock(option: PrefState<Boolean>, password: String): String? {
        if (!AppLock.matches(password, prefs.lockHash)) return tr("Contraseña incorrecta")
        option.value = false
        if (!AppLock.anyEnabled(prefs)) removePassword()
        return null
    }

    /** Como en ES: una contraseña nueva vacía quita la contraseña y todas las protecciones. */
    fun changePassword(old: String, new: String, confirm: String): String? {
        if (!AppLock.matches(old, prefs.lockHash)) return tr("La contraseña actual no es correcta")
        if (new != confirm) return tr("Las contraseñas nuevas no coinciden")
        if (new.isEmpty()) {
            lockStart.value = false
            lockNetwork.value = false
            lockHidden.value = false
            removePassword()
            toast(tr("Contraseña quitada; ya no hay protecciones"))
        } else {
            prefs.lockHash = AppLock.encode(new)
            toast(tr("Contraseña cambiada"))
        }
        return null
    }

    private fun removePassword() {
        prefs.lockHash = ""
        hasPassword = false
    }

    // ---------------- Ajustes: copia y restauración ----------------

    /** Guarda los ajustes (sin contraseña ni conexiones) en [folder]. */
    fun exportSettings(folder: File) {
        runTask(tr("Copia de ajustes")) {
            val target = File(folder, com.omaritoinforma.oiarchivos.data.SettingsBackup.FILE_NAME)
            if (!folder.isDirectory && !folder.mkdirs())
                throw java.io.IOException(tr("No se pudo crear la carpeta"))
            val json = com.omaritoinforma.oiarchivos.data.SettingsBackup.export(prefs.snapshot())
            SafeFiles.writeAtomic(target) { it.writeText(json) }
            OperationResult(tr("Ajustes guardados en {0}", target.absolutePath), listOf(target))
        }
    }

    /** Restaura los ajustes desde una copia; si no es válida, no se cambia nada. */
    fun importSettings(file: File) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        com.omaritoinforma.oiarchivos.data.SettingsBackup.parse(file.readText())
                    }
                }
            result
                .onSuccess { values ->
                    prefs.restore(values)
                    reloadSettings()
                    toast(tr("Ajustes restaurados ({0})", values.size))
                }
                .onFailure { toast(it.message ?: tr("No se pudo leer la copia")) }
        }
    }

    private fun reloadSettings() {
        esSettings.forEach { it.reload() }
        scheduleStorageWatch()
        scheduleAutoBackup(replace = true)
        viewMode = prefs.viewMode
        sortBy = prefs.sortBy
        ascending = prefs.ascending
        showHidden = prefs.showHidden
        useTrash = prefs.useTrash
        themeMode = prefs.themeMode
        gridSize = prefs.gridSize
        swipeLeft = prefs.swipeLeft
        swipeRight = prefs.swipeRight
        bookmarks.clear()
        bookmarks.addAll(prefs.bookmarks)
        pinned.clear()
        pinned.addAll(prefs.pinned)
        tabs.forEach { it.cache.clear() }
        refresh()
    }

    // ---------------- Papelera ----------------

    fun loadTrash() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { RecycleBin.list() }
            trash.clear()
            trash.addAll(list)
        }
    }

    fun restore(e: RecycleBin.Entry) {
        viewModelScope.launch {
            val msg =
                withContext(Dispatchers.IO) {
                    val m = RecycleBin.restore(e)
                    Media.scan(ctx, listOf(File(e.originalPath)))
                    m
                }
            toast(msg)
            tabs.forEach { it.cache.clear() }
            loadTrash()
        }
    }

    fun deleteForever(e: RecycleBin.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecycleBin.deleteForever(e) }
            toast(tr("Eliminado definitivamente"))
            loadTrash()
        }
    }

    fun emptyTrash() {
        runOp(tr("Vaciando papelera"), onDone = { loadTrash() }) {
            RecycleBin.empty()
            OpResult(tr("Papelera vaciada"))
        }
    }
}
