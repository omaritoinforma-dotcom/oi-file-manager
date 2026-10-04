@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.data.FileItem
import com.omaritoinforma.oiarchivos.data.Location
import com.omaritoinforma.oiarchivos.data.ViewMode
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import com.omaritoinforma.oiarchivos.ui.TabState
import com.omaritoinforma.oiarchivos.ui.components.BarAction
import com.omaritoinforma.oiarchivos.ui.components.Breadcrumb
import com.omaritoinforma.oiarchivos.ui.components.FileRow
import com.omaritoinforma.oiarchivos.ui.components.GridCell
import com.omaritoinforma.oiarchivos.ui.components.MenuItem
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.PathUtil
import kotlinx.coroutines.delay

fun locationTitle(loc: Location): String =
    when (loc) {
        is Location.Folder -> PathUtil.displayName(loc.path)
        is Location.Category -> loc.category.label
        is Location.Search -> "Buscar: ${loc.query}"
    }

@Composable
fun BrowserScreen(vm: MainViewModel, openDrawer: () -> Unit) {
    val tab = vm.currentTab
    if (tab == null) {
        LaunchedEffect(Unit) { vm.goHome() }
        return
    }
    val ctx = LocalContext.current
    val loc = tab.location
    val inSelection = tab.selected.isNotEmpty()
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun openItem(item: FileItem) {
        if (tab.selected.isNotEmpty()) {
            vm.toggleSelect(item)
            return
        }
        if (item.isDirectory) {
            vm.openFolder(item.path)
            return
        }
        val ext = item.extension
        when {
            ArchiveTools.supports(item.file) && ext != "apk" -> vm.goTo(Screen.Archive(item.path))
            ext == "apk" -> dialog = BrowserDialog.OpenApk(item)
            Kinds.isEditable(ext) -> vm.openEditor(item.path)
            else -> vm.openFile(item.path)
        }
    }

    Scaffold(
        topBar = {
            when {
                inSelection -> SelectionTopBar(vm, tab)
                searching ->
                    SearchTopBar(
                        onSubmit = { q ->
                            searching = false
                            vm.search(q)
                        },
                        onClose = { searching = false },
                    )
                else ->
                    BrowserTopBar(
                        vm,
                        loc,
                        openDrawer,
                        onSearch = { searching = true },
                        onDialog = { dialog = it })
            }
        },
        bottomBar = {
            if (inSelection) {
                SelectionBottomBar(vm, tab, ctx) { dialog = it }
            } else if ((vm.clipboard != null || com.omaritoinforma.oiarchivos.data.DocumentClipboard.value != null) && loc is Location.Folder) {
                PasteBar(vm)
            }
        },
        floatingActionButton = {
            if (!inSelection && loc is Location.Folder) {
                FloatingActionButton(onClick = { dialog = BrowserDialog.CreateMenu }) {
                    Icon(Icons.Filled.Add, "Crear")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.tabs.size > 1) TabsRow(vm)
            LocationHeader(vm, tab, loc)
            if (tab.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().then(browserSwipeGestures(vm))) {
                if (!tab.loading && tab.items.isEmpty()) {
                    EmptyState(
                        when (loc) {
                            is Location.Folder -> "Esta carpeta está vacía"
                            is Location.Category -> "No hay archivos en esta categoría"
                            is Location.Search -> "Sin resultados"
                        },
                    )
                } else {
                    FileListing(
                        vm = vm,
                        tab = tab,
                        showPath = loc !is Location.Folder,
                        onClick = { openItem(it) },
                        onLongClick = { vm.toggleSelect(it) },
                    )
                }
            }
        }
    }

    dialog?.let { d -> BrowserDialogs(vm, d) { dialog = it } }
}

@Composable
private fun BrowserTopBar(
    vm: MainViewModel,
    loc: Location,
    openDrawer: () -> Unit,
    onSearch: () -> Unit,
    onDialog: (BrowserDialog) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, "Menú") } },
        title = { Text(locationTitle(loc), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Buscar") }
            IconButton(onClick = { vm.cycleViewMode() }) {
                @Suppress("DEPRECATION")
                val icon =
                    when (vm.viewMode) {
                        ViewMode.LIST -> Icons.Filled.ViewHeadline
                        ViewMode.DETAILS -> Icons.Filled.ViewList
                        ViewMode.GRID -> Icons.Filled.GridView
                    }
                Icon(icon, "Cambiar vista")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "Más opciones")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Búsqueda avanzada", Icons.Filled.Search) {
                        menu = false
                        vm.goTo(
                            Screen.AdvancedSearch(
                                (loc as? Location.Folder)?.path ?: PathUtil.internalRoot))
                    }
                    MenuItem("Analizar esta carpeta", Icons.Filled.Info) {
                        menu = false
                        vm.goTo(
                            Screen.Analysis(
                                (loc as? Location.Folder)?.path ?: PathUtil.internalRoot))
                    }
                    MenuItem("Doble panel", Icons.Filled.ViewList) {
                        menu = false
                        vm.goTo(
                            Screen.DualPane(
                                (loc as? Location.Folder)?.path ?: PathUtil.internalRoot))
                    }
                    MenuItem("Red, nube y USB", Icons.Filled.Link) {
                        menu = false
                        vm.goTo(Screen.Connections)
                    }
                    if (NetworkClipboard.value != null && loc is Location.Folder) {
                        MenuItem("Pegar desde red / nube", Icons.Filled.Download) {
                            menu = false
                            vm.pasteNetwork(loc.path)
                        }
                    }
                    MenuItem("Ordenar…", Icons.Filled.Sort) {
                        menu = false
                        onDialog(BrowserDialog.Sort)
                    }
                    MenuItem(
                        if (vm.showHidden) "Ocultar archivos ocultos"
                        else "Mostrar archivos ocultos",
                        if (vm.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    ) {
                        menu = false
                        vm.toggleHidden()
                    }
                    if (loc is Location.Folder) {
                        MenuItem("Carpeta superior", Icons.Filled.ArrowUpward) {
                            menu = false
                            vm.up()
                        }
                        MenuItem("Acceso directo en Android", Icons.Filled.OpenInNew) {
                            menu = false
                            runCatching {
                                    com.omaritoinforma.oiarchivos.util.FolderActions.pin(
                                        context, loc.path)
                                }
                                .onFailure { vm.toast(it.message.orEmpty()) }
                        }
                        val marked = loc.path in vm.bookmarks
                        MenuItem(
                            if (marked) "Quitar de marcadores" else "Agregar a marcadores",
                            if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        ) {
                            menu = false
                            vm.toggleBookmark(loc.path)
                        }
                    }
                    MenuItem("Nueva pestaña", Icons.Filled.Tab) {
                        menu = false
                        vm.addTab()
                    }
                    MenuItem("Seleccionar todo", Icons.Filled.SelectAll) {
                        menu = false
                        vm.selectAll()
                    }
                    MenuItem("Actualizar", Icons.Filled.Refresh) {
                        menu = false
                        vm.refresh()
                    }
                    MenuItem("Inicio", Icons.Filled.Home) {
                        menu = false
                        vm.goHome()
                    }
                }
            }
        },
    )
}

@Composable
private fun SelectionTopBar(vm: MainViewModel, tab: TabState) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Filled.Close, "Cancelar selección")
            }
        },
        title = { Text("${tab.selected.size} seleccionado(s)") },
        actions = {
            IconButton(onClick = vm::selectRange) {
                Icon(Icons.Filled.UnfoldMore, "Seleccionar rango")
            }
            IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Invertir selección", Icons.Filled.SelectAll) {
                        menu = false
                        vm.invertSelection()
                    }
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun SearchTopBar(onSubmit: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
    val submit = { if (query.isNotBlank()) onSubmit(query.trim()) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar búsqueda")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar aquí y en subcarpetas…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = { IconButton(onClick = submit) { Icon(Icons.Filled.Search, "Buscar") } },
    )
}

@Composable
private fun SelectionBottomBar(
    vm: MainViewModel,
    tab: TabState,
    ctx: Context,
    setDialog: (BrowserDialog) -> Unit
) {
    val selectedItems = tab.items.filter { tab.selected.containsKey(it.path) }
    val single = selectedItems.singleOrNull()
    val files = selectedItems.filter { !it.isDirectory }
    var menu by remember { mutableStateOf(false) }
    BottomAppBar {
        BarAction(Icons.Filled.ContentCopy, "Copiar", Modifier.weight(1f)) {
            vm.copySelection(move = false)
        }
        BarAction(Icons.Filled.ContentCut, "Cortar", Modifier.weight(1f)) {
            vm.copySelection(move = true)
        }
        BarAction(Icons.Filled.Delete, "Eliminar", Modifier.weight(1f)) {
            setDialog(BrowserDialog.Delete(selectedItems))
        }
        BarAction(Icons.Filled.Edit, "Renombrar", Modifier.weight(1f)) {
            setDialog(
                if (single != null) BrowserDialog.Rename(single)
                else BrowserDialog.BatchRename(selectedItems))
        }
        Box(Modifier.weight(1f)) {
            BarAction(Icons.Filled.MoreVert, "Más", Modifier.fillMaxWidth()) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val allPinned = selectedItems.all { it.path in vm.pinnedPaths }
                MenuItem(if (allPinned) "Desfijar del principio" else "Fijar al principio", Icons.Filled.PushPin) {
                    menu = false
                    vm.setPinned(selectedItems.map { it.path }, !allPinned)
                }
                if (files.isNotEmpty()) {
                    MenuItem("Compartir", Icons.Filled.Share) {
                        menu = false
                        Opener.share(ctx, files.map { it.file })
                    }
                }
                MenuItem("Comprimir en ZIP", Icons.Filled.Archive) {
                    menu = false
                    setDialog(BrowserDialog.Compress(selectedItems))
                }
                if (single != null && ArchiveTools.supports(single.file)) {
                    MenuItem("Extraer aquí", Icons.Filled.Unarchive) {
                        menu = false
                        vm.extract(single)
                    }
                }
                if (single != null && !single.isDirectory) {
                    MenuItem(
                        if (single.extension == "oienc") "Descifrar con contraseña"
                        else "Cifrar con contraseña",
                        Icons.Filled.Info) {
                            menu = false
                            setDialog(BrowserDialog.Encrypt(single, single.extension == "oienc"))
                        }
                    if (single.extension == "apk")
                        MenuItem("Inspeccionar APK", Icons.Filled.Info) {
                            menu = false
                            setDialog(BrowserDialog.InspectApk(single))
                        }
                    if (Kinds.ofExt(single.extension) ==
                        com.omaritoinforma.oiarchivos.util.FileKind.IMAGE)
                        MenuItem("Establecer fondo de pantalla", Icons.Filled.Image) {
                            menu = false
                            vm.runTask("Estableciendo fondo") {
                                com.omaritoinforma.oiarchivos.util.FolderActions.wallpaper(
                                    ctx, single.file)
                                OperationResult("Fondo de pantalla actualizado")
                            }
                        }
                    MenuItem("Abrir con…", Icons.Filled.OpenInNew) {
                        menu = false
                        Opener.open(ctx, single.file, chooser = true)
                    }
                    MenuItem("Editar como texto", Icons.Filled.Edit) {
                        menu = false
                        vm.clearSelection()
                        vm.openEditor(single.path)
                    }
                }
                if (single != null && tab.location !is Location.Folder) {
                    MenuItem("Abrir ubicación", Icons.Filled.FolderOpen) {
                        menu = false
                        vm.clearSelection()
                        vm.openFolder(single.file.parent ?: "/")
                    }
                }
                if (single != null && single.isDirectory) {
                    val marked = single.path in vm.bookmarks
                    MenuItem(
                        if (marked) "Quitar de marcadores" else "Agregar a marcadores",
                        Icons.Filled.Bookmark) {
                            menu = false
                            vm.toggleBookmark(single.path)
                        }
                    MenuItem("Abrir en pestaña nueva", Icons.Filled.Tab) {
                        menu = false
                        vm.clearSelection()
                        vm.addTab(Location.Folder(single.path))
                    }
                }
                if (selectedItems.size > 1) {
                    MenuItem("Renombrar en lote", Icons.Filled.Edit) {
                        menu = false
                        setDialog(BrowserDialog.BatchRename(selectedItems))
                    }
                }
                if (single != null)
                    MenuItem(
                        if (single.name.startsWith('.')) "Mostrar (quitar punto)"
                        else "Ocultar (añadir punto)",
                        Icons.Filled.Visibility) {
                            menu = false
                            vm.rename(
                                single,
                                if (single.name.startsWith('.')) single.name.trimStart('.')
                                else "." + single.name)
                        }
                if (single?.isDirectory == true)
                    MenuItem("Alternar .nomedia", Icons.Filled.VisibilityOff) {
                        menu = false
                        vm.runTask("Actualizando .nomedia") {
                            val marker = java.io.File(single.file, ".nomedia")
                            val ok =
                                if (marker.exists()) marker.delete() else marker.createNewFile()
                            if (!ok) throw java.io.IOException("No se pudo cambiar .nomedia")
                            OperationResult(
                                "Configuración de medios actualizada", listOf(single.file))
                        }
                    }
                MenuItem("Copiar ruta", Icons.Filled.Link) {
                    menu = false
                    Opener.copyText(ctx, selectedItems.joinToString("\n") { it.path })
                    vm.toast("Ruta copiada")
                }
                MenuItem("Propiedades", Icons.Filled.Info) {
                    menu = false
                    setDialog(BrowserDialog.Properties(selectedItems))
                }
            }
        }
    }
}

@Composable
private fun PasteBar(vm: MainViewModel) {
    val clip = vm.clipboard
    val documents = com.omaritoinforma.oiarchivos.data.DocumentClipboard.value
    if (clip == null && documents == null) return
    BottomAppBar {
        Text(
            "${documents?.ids?.size ?: clip!!.paths.size} elemento(s) para ${if (documents?.move ?: clip!!.move) "mover" else "copiar"}",
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = {
            vm.clipboard = null
            com.omaritoinforma.oiarchivos.data.DocumentClipboard.value = null
        }) { Text("Cancelar") }
        Button(onClick = { vm.paste() }, modifier = Modifier.padding(end = 8.dp)) {
            Text("Pegar aquí")
        }
    }
}

@Composable
private fun TabsRow(vm: MainViewModel) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(vm.tabs.toList(), key = { _, t -> t.id }) { i, t ->
            val active = i == vm.activeTab
            Surface(
                onClick = { vm.selectTab(i) },
                shape = RoundedCornerShape(50),
                color =
                    if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        locationTitle(t.location),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Cerrar pestaña", Modifier.size(16.dp))
                    }
                }
            }
        }
        item { IconButton(onClick = { vm.addTab() }) { Icon(Icons.Filled.Add, "Nueva pestaña") } }
    }
}

@Composable
private fun LocationHeader(vm: MainViewModel, tab: TabState, loc: Location) {
    when (loc) {
        is Location.Folder -> Breadcrumb(loc.path) { vm.openFolder(it) }
        is Location.Category -> HeaderText("${loc.category.label} · ${tab.items.size} archivos")
        is Location.Search ->
            HeaderText(
                "«${loc.query}» en ${PathUtil.displayName(loc.root)} · ${tab.items.size} resultados" +
                    (if (tab.loading) " (buscando…)" else ""),
            )
    }
}

@Composable
private fun HeaderText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun browserSwipeGestures(vm: MainViewModel): Modifier {
    val left = vm.swipeLeft
    val right = vm.swipeRight
    if (left == GestureAction.NONE && right == GestureAction.NONE) return Modifier
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    return Modifier.pointerInput(vm, left, right, threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                when {
                    distance <= -threshold -> vm.performGesture(left)
                    distance >= threshold -> vm.performGesture(right)
                }
                distance = 0f
            },
            onHorizontalDrag = { change, amount ->
                distance += amount
                change.consume()
            })
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileListing(
    vm: MainViewModel,
    tab: TabState,
    showPath: Boolean,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
) {
    val loc = tab.location
    val items = tab.items
    if (vm.viewMode == ViewMode.GRID) {
        val state =
            remember(tab.id, loc) {
                val s = tab.scroll[loc]
                LazyGridState(s?.first ?: 0, s?.second ?: 0)
            }
        DisposableEffect(state) {
            onDispose {
                tab.scroll[loc] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(vm.gridSize.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 96.dp),
        ) {
            items(items, key = { it.path }) { item ->
                GridCell(
                    item = item,
                    selected = tab.selected.containsKey(item.path),
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    } else {
        val state =
            remember(tab.id, loc) {
                val s = tab.scroll[loc]
                LazyListState(s?.first ?: 0, s?.second ?: 0)
            }
        DisposableEffect(state) {
            onDispose {
                tab.scroll[loc] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            }
        }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(items, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    details = vm.viewMode == ViewMode.DETAILS,
                    showPath = showPath,
                    selected = tab.selected.containsKey(item.path),
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}
