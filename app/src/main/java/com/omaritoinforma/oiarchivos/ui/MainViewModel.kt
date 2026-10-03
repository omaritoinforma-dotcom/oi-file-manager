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
import com.omaritoinforma.oiarchivos.data.AppsRepo
import com.omaritoinforma.oiarchivos.data.Categories
import com.omaritoinforma.oiarchivos.data.Clipboard
import com.omaritoinforma.oiarchivos.data.Conflict
import com.omaritoinforma.oiarchivos.data.FileCategory
import com.omaritoinforma.oiarchivos.data.FileItem
import com.omaritoinforma.oiarchivos.data.FileOps
import com.omaritoinforma.oiarchivos.data.FileRepo
import com.omaritoinforma.oiarchivos.data.Location
import com.omaritoinforma.oiarchivos.data.OpProgress
import com.omaritoinforma.oiarchivos.data.Prefs
import com.omaritoinforma.oiarchivos.data.RecycleBin
import com.omaritoinforma.oiarchivos.data.RenameRules
import com.omaritoinforma.oiarchivos.data.SortBy
import com.omaritoinforma.oiarchivos.data.Sorter
import com.omaritoinforma.oiarchivos.data.StorageInfo
import com.omaritoinforma.oiarchivos.data.StorageVolumeInfo
import com.omaritoinforma.oiarchivos.data.ThemeMode
import com.omaritoinforma.oiarchivos.data.ViewMode
import com.omaritoinforma.oiarchivos.data.ZipTools
import com.omaritoinforma.oiarchivos.util.Media
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.Perms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

sealed interface Screen {
    data object Home : Screen
    data object Browser : Screen
    data class Editor(val path: String) : Screen
    data object Apps : Screen
    data object Trash : Screen
    data object Settings : Screen
}

data class PendingPaste(val sources: List<File>, val dest: File, val move: Boolean, val conflicts: Int)

/** Una pestaña (ventana) del explorador, con su propio historial y selección. */
class TabState(start: Location) {
    val id: Long = nextId.incrementAndGet()
    val history = mutableStateListOf(start)
    val location: Location get() = history.last()
    val items = mutableStateListOf<FileItem>()
    var loading by mutableStateOf(false)
    val selected = mutableStateMapOf<String, FileItem>()
    internal val cache = HashMap<Location, List<FileItem>>()
    internal val scroll = HashMap<Location, Pair<Int, Int>>()
    internal var job: Job? = null

    private companion object {
        val nextId = AtomicLong()
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = Prefs(app)

    var hasPermission by mutableStateOf(Perms.hasStorage(app))
        private set

    val screens = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = screens.last()

    val tabs = mutableStateListOf<TabState>()
    var activeTab by mutableIntStateOf(0)
        private set
    val currentTab: TabState? get() = tabs.getOrNull(activeTab)

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
    val bookmarks = mutableStateListOf<String>().apply { addAll(prefs.bookmarks) }

    val volumes = mutableStateListOf<StorageVolumeInfo>()
    var clipboard by mutableStateOf<Clipboard?>(null)
    var pendingPaste by mutableStateOf<PendingPaste?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private val _progress = MutableStateFlow<OpProgress?>(null)
    val progress: StateFlow<OpProgress?> = _progress.asStateFlow()
    private var opJob: Job? = null

    val apps = mutableStateListOf<AppInfo>()
    var appsLoading by mutableStateOf(false)
        private set
    val trash = mutableStateListOf<RecycleBin.Entry>()

    init {
        if (hasPermission) refreshVolumes()
    }

    fun onResume() {
        val had = hasPermission
        hasPermission = Perms.hasStorage(ctx)
        if (hasPermission) {
            refreshVolumes()
            if (had && screen == Screen.Browser) refresh()
        }
    }

    fun toast(text: String) {
        message = text
    }

    // ---------------- Navegación ----------------

    fun goTo(s: Screen) {
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
                if (t != null && (t.selected.isNotEmpty() || t.history.size > 1)) return true
            }
            return screens.size > 1
        }

    fun back() {
        if (screen == Screen.Browser) {
            val t = currentTab
            if (t != null) {
                if (t.selected.isNotEmpty()) {
                    t.selected.clear()
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
        if (screens.size > 1) screens.removeAt(screens.lastIndex)
    }

    fun navigate(loc: Location, newTab: Boolean = false) {
        val existing = currentTab
        val tab = if (newTab || existing == null) {
            TabState(loc).also {
                tabs.add(it)
                activeTab = tabs.lastIndex
            }
        } else {
            if (existing.location != loc) existing.history.add(loc)
            existing
        }
        tab.selected.clear()
        load(tab)
        goTo(Screen.Browser)
    }

    fun openFolder(path: String) = navigate(Location.Folder(path))

    fun up() {
        val loc = currentTab?.location as? Location.Folder ?: return
        val parent = File(loc.path).parentFile ?: return
        navigate(Location.Folder(parent.absolutePath))
    }

    fun search(query: String) {
        val root = (currentTab?.location as? Location.Folder)?.path ?: PathUtil.internalRoot
        navigate(Location.Search(root, query))
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
        if (index < activeTab || activeTab > tabs.lastIndex) activeTab = (activeTab - 1).coerceIn(0, tabs.lastIndex)
        currentTab?.let { load(it) }
    }

    fun refresh() {
        currentTab?.let { load(it) }
    }

    private fun load(tab: TabState) {
        val loc = tab.location
        tab.job?.cancel()
        tab.items.clear()
        tab.cache[loc]?.let { tab.items.addAll(it) }
        tab.loading = true
        tab.job = viewModelScope.launch {
            try {
                if (loc is Location.Search) {
                    tab.items.clear()
                    FileRepo.search(File(loc.root), loc.query, showHidden).collect { tab.items.add(it) }
                    val sorted = sorted(tab.items.toList(), loc)
                    tab.items.clear()
                    tab.items.addAll(sorted)
                } else {
                    val list = withContext(Dispatchers.IO) {
                        when (loc) {
                            is Location.Folder -> FileRepo.list(File(loc.path), showHidden)
                            is Location.Category -> runCatching { Categories.query(ctx, loc.category, showHidden) }
                                .getOrDefault(emptyList<FileItem>())
                            is Location.Search -> emptyList<FileItem>()
                        }
                    }
                    if (list == null) {
                        toast("No se puede leer esta carpeta")
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
        else Sorter.sort(list, sortBy, ascending)

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
            toast("Marca el primero y el último elemento del rango")
            return
        }
        for (i in idx.first()..idx.last()) t.items[i].let { t.selected[it.path] = it }
    }

    fun invertSelection() {
        val t = currentTab ?: return
        val inverted = t.items.filter { !t.selected.containsKey(it.path) }
        t.selected.clear()
        inverted.forEach { t.selected[it.path] = it }
    }

    fun clearSelection() {
        currentTab?.selected?.clear()
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

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.showHidden = showHidden
        tabs.forEach { it.cache.clear() }
        refresh()
    }

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

    private fun currentFolder(): File? = (currentTab?.location as? Location.Folder)?.let { File(it.path) }

    private fun nameError(name: String): String? = when {
        name.isBlank() -> "El nombre no puede estar vacío"
        name.contains('/') -> "El nombre no puede contener «/»"
        name.trim() == "." || name.trim() == ".." -> "Nombre no válido"
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
            toast("Ya existe «${f.name}»")
            return
        }
        val ok = runCatching { if (folder) f.mkdir() else f.createNewFile() }.getOrDefault(false)
        toast(if (ok) "Creado «${f.name}»" else "No se pudo crear «${f.name}»")
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
            toast("Ya existe «${target.name}»")
            return
        }
        if (item.file.renameTo(target)) {
            val bi = bookmarks.indexOf(item.path)
            if (bi >= 0) {
                bookmarks[bi] = target.absolutePath
                prefs.bookmarks = bookmarks.toList()
            }
            currentTab?.selected?.clear()
            afterQuickChange(listOf(item.file, target))
        } else {
            toast("No se pudo renombrar")
        }
    }

    fun batchRename(items: List<FileItem>, rules: RenameRules) {
        runOp("Renombrando") { report ->
            var done = 0
            var skipped = 0
            val changed = ArrayList<File>()
            for ((i, it) in items.withIndex()) {
                report(OpProgress("Renombrando", it.name, doneFiles = i, totalFiles = items.size))
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
            OpResult("Renombrados: $done" + (if (skipped > 0) " · omitidos: $skipped" else ""), changed)
        }
    }

    fun copySelection(move: Boolean) {
        val t = currentTab ?: return
        val paths = t.selected.keys.toList()
        if (paths.isEmpty()) return
        clipboard = Clipboard(paths, move)
        t.selected.clear()
        toast("${paths.size} elemento(s) listos para ${if (move) "mover" else "copiar"}. Ve al destino y toca «Pegar aquí».")
    }

    fun paste() {
        val clip = clipboard ?: return
        val dest = currentFolder() ?: return
        val sources = clip.paths.map(::File).filter { it.exists() }
        if (sources.isEmpty()) {
            toast("Los archivos del portapapeles ya no existen")
            clipboard = null
            return
        }
        val conflicts = sources.count { s ->
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
        runOp(if (move) "Moviendo" else "Copiando") { report ->
            val r = FileOps.transfer(sources, dest, move, conflict, report)
            val msg = buildString {
                append(if (move) "Movidos: ${r.targets.size}" else "Copiados: ${r.targets.size}")
                if (r.skipped > 0) append(" · omitidos: ${r.skipped}")
            }
            OpResult(msg, r.targets + (if (move) sources else emptyList()))
        }
    }

    fun delete(items: List<FileItem>, toTrash: Boolean) {
        val files = items.map { it.file }
        runOp(if (toTrash) "Moviendo a la papelera" else "Eliminando") { report ->
            val failed = FileOps.deleteAll(files, toTrash, report)
            val ok = files.size - failed
            val msg = buildString {
                append(if (toTrash) "Enviados a la papelera: $ok" else "Eliminados: $ok")
                if (failed > 0) append(" · fallaron: $failed")
            }
            OpResult(msg, files)
        }
    }

    fun compress(items: List<FileItem>, name: String) {
        val dir = currentFolder() ?: items.firstOrNull()?.file?.parentFile ?: return
        val clean = name.trim().let { if (it.lowercase().endsWith(".zip")) it else "$it.zip" }
        nameError(clean)?.let {
            toast(it)
            return
        }
        val target = FileOps.uniqueName(dir, clean)
        runOp("Comprimiendo") { report ->
            ZipTools.compress(items.map { it.file }, target, report)
            OpResult("Creado «${target.name}»", listOf(target))
        }
    }

    fun extract(item: FileItem) {
        val parent = item.file.parentFile ?: return
        val dest = FileOps.uniqueName(parent, item.name.substringBeforeLast('.'))
        runOp("Extrayendo") { report ->
            val n = ZipTools.extract(item.file, dest, report)
            OpResult("Extraídos $n archivos en «${dest.name}»", listOf(dest))
        }
    }

    fun cancelOp() {
        opJob?.cancel()
    }

    fun openEditor(path: String) = goTo(Screen.Editor(path))

    private fun afterQuickChange(changed: List<File>) {
        tabs.forEach { it.cache.clear() }
        refresh()
        viewModelScope.launch(Dispatchers.IO) { Media.scan(ctx, changed) }
    }

    data class OpResult(val message: String?, val changed: List<File> = emptyList())

    /** Ejecuta una operación larga en segundo plano con diálogo de progreso cancelable. */
    private fun runOp(
        title: String,
        onDone: () -> Unit = {},
        block: suspend (report: (OpProgress) -> Unit) -> OpResult,
    ) {
        if (opJob?.isActive == true) {
            toast("Espera a que termine la operación actual")
            return
        }
        _progress.value = OpProgress(title)
        opJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block { p -> _progress.value = p } }
                result.message?.let { toast(it) }
                if (result.changed.isNotEmpty()) withContext(Dispatchers.IO) { Media.scan(ctx, result.changed) }
            } catch (e: CancellationException) {
                toast("Operación cancelada")
                throw e
            } catch (e: Exception) {
                toast("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _progress.value = null
                currentTab?.selected?.clear()
                tabs.forEach { it.cache.clear() }
                refresh()
                onDone()
            }
        }
    }

    // ---------------- Aplicaciones ----------------

    fun loadApps(includeSystem: Boolean) {
        appsLoading = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { AppsRepo.list(ctx, includeSystem) }.getOrDefault(emptyList<AppInfo>())
            }
            apps.clear()
            apps.addAll(list)
            appsLoading = false
        }
    }

    fun backupApps(list: List<AppInfo>) {
        if (list.isEmpty()) return
        runOp("Respaldando apps") { report ->
            val out = ArrayList<File>()
            for ((i, a) in list.withIndex()) {
                currentCoroutineContext().ensureActive()
                report(OpProgress("Respaldando apps", a.label, doneFiles = i, totalFiles = list.size))
                runCatching { AppsRepo.backup(a) }.onSuccess { out += it }
            }
            OpResult("APK guardados en «OI Archivos/Apps» (${out.size} de ${list.size})", out)
        }
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
            val msg = withContext(Dispatchers.IO) {
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
            toast("Eliminado definitivamente")
            loadTrash()
        }
    }

    fun emptyTrash() {
        runOp("Vaciando papelera", onDone = { loadTrash() }) {
            RecycleBin.empty()
            OpResult("Papelera vaciada")
        }
    }
}
