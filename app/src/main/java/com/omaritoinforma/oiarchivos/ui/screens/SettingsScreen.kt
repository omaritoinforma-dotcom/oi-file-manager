@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omaritoinforma.oiarchivos.BuildConfig
import com.omaritoinforma.oiarchivos.data.AccentColor
import com.omaritoinforma.oiarchivos.data.BackgroundImage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.omaritoinforma.oiarchivos.data.AutoBackup
import com.omaritoinforma.oiarchivos.data.BackupKind
import com.omaritoinforma.oiarchivos.data.DrawerLayout
import com.omaritoinforma.oiarchivos.data.DocumentType
import com.omaritoinforma.oiarchivos.data.FileCategory
import com.omaritoinforma.oiarchivos.data.FolderStyle
import com.omaritoinforma.oiarchivos.data.HomeLayout
import com.omaritoinforma.oiarchivos.data.QuickTile
import com.omaritoinforma.oiarchivos.data.ToolbarAction
import com.omaritoinforma.oiarchivos.data.ToolbarLayout
import com.omaritoinforma.oiarchivos.data.ScreenOrientation
import com.omaritoinforma.oiarchivos.data.GestureAction
import com.omaritoinforma.oiarchivos.data.NewFileKind
import com.omaritoinforma.oiarchivos.data.Prefs
import com.omaritoinforma.oiarchivos.data.SettingsBackup
import com.omaritoinforma.oiarchivos.data.StartWindow
import com.omaritoinforma.oiarchivos.data.StorageWatch
import com.omaritoinforma.oiarchivos.data.ThemeMode
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.PrefState
import com.omaritoinforma.oiarchivos.ui.components.FolderPickerDialog
import com.omaritoinforma.oiarchivos.ui.components.SectionTitle
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatDate
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Secciones de Ajustes, con el mismo orden y agrupación que la configuración de ES. */
private enum class Section(val group: String, val title: String, val summary: String) {
    DISPLAY("General", "Pantalla", "Archivos ocultos, miniaturas, historial, tamaño y tema"),
    CLEANUP("General", "Limpieza", "Borrar el historial y la caché"),
    FOLDERS("General", "Carpetas", "Carpeta de inicio y carpeta de descargas"),
    START("General", "Ventana inicial", "Qué se abre al iniciar la app"),
    HOME("General", "Pantalla de inicio", "Ocultar y ordenar las secciones y los iconos de Inicio"),
    TOOLBAR("General", "Barra de herramientas", "Elegir y ordenar los botones de la barra al seleccionar"),
    DRAWER("General", "Barra lateral", "Ocultar y ordenar las opciones del menú lateral"),
    NOTIFICATIONS("General", "Notificaciones", "Aviso al terminar, espacio bajo y archivos nuevos"),
    REMOTE("Red y nube", "Archivos remotos", "Subir lo que edites en otra app"),
    AUTO_BACKUP("Red y nube", "Copia automática", "Subir fotos, vídeos, música y carpetas a una conexión"),
    PASSWORD("Seguridad", "Contraseña", "Proteger la app, las conexiones y los archivos ocultos"),
    BACKUP("Seguridad", "Copia de ajustes", "Guardar y restaurar los ajustes"),
    APPS("Herramientas", "Aplicaciones", "Copia del APK y carpeta de copias"),
    EDITOR("Herramientas", "Editor de texto", "Letra, líneas, sangría, tabulador, símbolos y guardado"),
    DOCUMENTS("Herramientas", "Documentos", "Qué tipos de archivo entran en la categoría Documentos"),
    TRASH("Herramientas", "Papelera", "Usar la papelera al eliminar"),
    GESTURES("Herramientas", "Gestos", "Deslizar en el explorador"),
    ABOUT("Sistema", "Acerca de", "Versión de OI Archivos")
}

/** Dónde se guarda la copia de ajustes. */
private val settingsBackupFolder: File
    get() = File(PathUtil.internalRoot, "OI Archivos")

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val section = open?.let { name -> Section.entries.firstOrNull { it.name == name } }
    BackHandler(enabled = section != null) { open = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.title ?: "Ajustes") },
                navigationIcon = {
                    IconButton(onClick = { if (section != null) open = null else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                null -> sectionList { open = it.name }
                Section.DISPLAY -> item { DisplaySettings(vm) }
                Section.CLEANUP -> item { CleanupSettings(vm) }
                Section.FOLDERS -> item { FolderSettings(vm) }
                Section.START -> item { StartSettings(vm) }
                Section.HOME -> item { HomeSettings(vm) }
                Section.TOOLBAR -> item { ToolbarSettings(vm) }
                Section.DRAWER -> item { DrawerSettings(vm) }
                Section.NOTIFICATIONS -> item { NotificationSettings(vm) }
                Section.REMOTE ->
                    item {
                        Column {
                            SwitchRow(
                                "Subir los cambios automáticamente",
                                "Al abrir un archivo de un servidor con otra app y editarlo, los cambios se suben solos al volver a OI Archivos. Si el archivo cambió en el servidor, se pregunta antes de sustituirlo.",
                                vm.remoteSync)
                        }
                    }
                Section.AUTO_BACKUP -> item { AutoBackupSettings(vm) }
                Section.PASSWORD -> item { PasswordSettings(vm) }
                Section.BACKUP -> item { BackupSettings(vm) }
                Section.APPS -> item { AppSettings(vm) }
                Section.EDITOR -> item { EditorSettings(vm) }
                Section.DOCUMENTS -> item { DocumentSettings(vm) }
                Section.TRASH -> item {
                    SwitchRow(
                        "Usar la papelera",
                        "Al eliminar, mover a la papelera para poder restaurar",
                        vm.useTrash) {
                            vm.updateUseTrash(it)
                        }
                }
                Section.GESTURES -> item { GestureSettings(vm) }
                Section.ABOUT -> item { About() }
            }
        }
    }
}

private fun LazyListScope.sectionList(onOpen: (Section) -> Unit) {
    Section.entries.groupBy { it.group }.forEach { (group, sections) ->
        item { SectionTitle(group, Modifier.padding(start = 16.dp, top = 16.dp)) }
        items(sections) { s ->
            ListItem(
                headlineContent = { Text(s.title) },
                supportingContent = { Text(s.summary) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { onOpen(s) })
        }
    }
}

@Composable
private fun DisplaySettings(vm: MainViewModel) {
    Column {
        // Arriba del todo: así se puede deshacer aunque la pantalla esté en horizontal.
        Text(
            "Orientación de la pantalla:",
            Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            ScreenOrientation.entries.forEach { o ->
                FilterChip(
                    vm.screenOrientation.value == o,
                    onClick = { vm.screenOrientation.value = o },
                    label = { Text(o.label) })
            }
        }
        SwitchRow(
            "Mostrar el nombre en la barra de herramientas",
            "El título de la carpeta o categoría que se ve arriba",
            vm.toolbarShowName)
        SwitchRow(
            "Mostrar el botón de pestañas",
            "Un botón con el número de pestañas abiertas, para cambiar de una a otra o abrir otra",
            vm.showWindowsButton)
        SwitchRow(
            "Mostrar botón de selección",
            "Un botón en la barra para empezar a marcar archivos sin mantener pulsado",
            vm.showSelectButton)
        SwitchRow(
            "Diseño grande",
            "Textos y controles un 20 % más grandes",
            vm.largeLayout)
        SwitchRow(
            "Mostrar archivos ocultos",
            "Archivos y carpetas que empiezan con punto",
            vm.showHidden) {
                vm.toggleHidden()
            }
        SwitchRow(
            "Miniaturas",
            "Vista previa de imágenes, videos y APK en lugar de iconos",
            vm.thumbnails)
        SwitchRow(
            "Solo carpetas en el historial",
            "No mostrar en el historial los archivos abiertos",
            vm.historyFoldersOnly)
        Column(Modifier.padding(16.dp)) {
            Text("Tamaño de las celdas: ${vm.gridSize}")
            Slider(
                value = vm.gridSize.toFloat(),
                onValueChange = { vm.updateGridSize(it.toInt()) },
                valueRange = 72f..160f,
                steps = 10)
        }
        SectionTitle("Tema", Modifier.padding(start = 16.dp, top = 8.dp))
        ThemeMode.entries.forEach { m ->
            ListItem(
                headlineContent = { Text(m.label) },
                leadingContent = {
                    RadioButton(selected = vm.themeMode == m, onClick = { vm.updateTheme(m) })
                },
                modifier = Modifier.clickable { vm.updateTheme(m) },
            )
        }
        SwitchRow(
            "Fondo negro puro",
            "Con el tema oscuro, fondo totalmente negro (ahorra batería en pantallas OLED)",
            vm.pureBlack)
        BackgroundSettings(vm)
        Text(
            "Estilo de las carpetas:",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            FolderStyle.entries.forEach { f ->
                FilterChip(
                    vm.folderStyle.value == f,
                    onClick = { vm.folderStyle.value = f },
                    label = { Text(f.label) })
            }
        }
        Text(
            "Color de la app:",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            AccentColor.entries
                .filter { it != AccentColor.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                .forEach { a ->
                    FilterChip(
                        vm.accent.value == a,
                        onClick = { vm.accent.value = a },
                        label = { Text(a.label) },
                        leadingIcon = {
                            if (a != AccentColor.DYNAMIC)
                                Box(
                                    Modifier.size(14.dp)
                                        .clip(CircleShape)
                                        .background(
                                            com.omaritoinforma.oiarchivos.ui.theme.accentPrimary(
                                                a, isSystemInDarkTheme())))
                        })
                }
        }
    }
}

@Composable
private fun CleanupSettings(vm: MainViewModel) {
    var size by remember { mutableLongStateOf(-1L) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { size = withContext(Dispatchers.IO) { vm.cacheSize() } }
    Column {
        SwitchRow(
            "Borrar el historial al salir",
            "Al tocar «Salir» en el menú lateral se borra el historial",
            vm.clearHistoryOnExit)
        SwitchRow(
            "Borrar la caché al salir",
            "Al tocar «Salir» se borran las miniaturas y vistas previas guardadas",
            vm.clearCacheOnExit)
        ListItem(
            headlineContent = { Text("Borrar la caché ahora") },
            supportingContent = {
                Text(
                    if (size < 0) "Calculando…"
                    else "Miniaturas y vistas previas guardadas: ${formatSize(size)}")
            },
            modifier = Modifier.clickable { vm.clearCache { reload++ } })
        ListItem(
            headlineContent = { Text("Borrar el historial ahora") },
            supportingContent = { Text("Carpetas y archivos abiertos") },
            modifier =
                Modifier.clickable {
                    vm.clearHistory()
                    vm.toast("Historial borrado")
                })
    }
}

@Composable
private fun FolderSettings(vm: MainViewModel) {
    var picking by remember { mutableStateOf<PrefState<String>?>(null) }
    Column {
        ListItem(
            headlineContent = { Text("Carpeta de inicio") },
            supportingContent = {
                Text("${vm.homeFolder.value}\nSe abre con la ventana inicial «Carpeta de inicio»")
            },
            trailingContent = { ResetButton(vm.homeFolder, PathUtil.internalRoot) },
            modifier = Modifier.clickable { picking = vm.homeFolder })
        ListItem(
            headlineContent = { Text("Carpeta de descargas") },
            supportingContent = {
                Text(
                    "${vm.downloadFolder.value}\nDescargas de red y nube, lo recibido de otro " +
                        "teléfono y lo copiado desde USB")
            },
            trailingContent = { ResetButton(vm.downloadFolder, Prefs.defaultDownloadFolder) },
            modifier = Modifier.clickable { picking = vm.downloadFolder })
    }
    picking?.let { setting ->
        FolderPickerDialog(
            title =
                if (setting === vm.homeFolder) "Carpeta de inicio" else "Carpeta de descargas",
            start = setting.value,
            onDismiss = { picking = null },
            onPick = {
                setting.value = it
                picking = null
            })
    }
}

@Composable
private fun DrawerSettings(vm: MainViewModel) {
    val order = DrawerLayout.order(vm.drawerOrder.value)
    Column {
        Text(
            "Elige qué opciones salen en el menú lateral y en qué orden. «Inicio», «Ajustes» y " +
                "«Salir» siempre se ven.",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        order.forEachIndexed { index, entry ->
            val shown = entry.name !in vm.drawerHidden.value
            ListItem(
                headlineContent = { Text(entry.label) },
                leadingContent = {
                    Switch(
                        checked = shown,
                        onCheckedChange = { on ->
                            vm.drawerHidden.value =
                                if (on) vm.drawerHidden.value - entry.name
                                else vm.drawerHidden.value + entry.name
                        })
                },
                modifier =
                    Modifier.clickable {
                        vm.drawerHidden.value =
                            if (shown) vm.drawerHidden.value + entry.name
                            else vm.drawerHidden.value - entry.name
                    },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = {
                                vm.drawerOrder.value =
                                    DrawerLayout.move(vm.drawerOrder.value, entry, -1)
                            },
                            enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, "Subir ${entry.label}")
                            }
                        IconButton(
                            onClick = {
                                vm.drawerOrder.value =
                                    DrawerLayout.move(vm.drawerOrder.value, entry, 1)
                            },
                            enabled = index < order.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, "Bajar ${entry.label}")
                            }
                    }
                })
        }
        if (vm.drawerOrder.value.isNotEmpty() || vm.drawerHidden.value.isNotEmpty())
            TextButton(
                onClick = {
                    vm.drawerOrder.value = emptyList()
                    vm.drawerHidden.value = emptySet()
                },
                modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Restablecer")
                }
    }
}

@Composable
private fun HomeSettings(vm: MainViewModel) {
    val order = HomeLayout.order(vm.homeOrder.value)
    Column {
        Text(
            "Elige qué secciones salen en Inicio y en qué orden, y qué iconos se ven dentro de " +
                "«Categorías» y «Accesos rápidos». El botón de Ajustes de arriba siempre está.",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        order.forEachIndexed { index, section ->
            ListItem(
                headlineContent = { Text(section.label) },
                leadingContent = {
                    Switch(
                        checked = section.name !in vm.homeHidden.value,
                        onCheckedChange = { on ->
                            vm.homeHidden.value =
                                if (on) vm.homeHidden.value - section.name
                                else vm.homeHidden.value + section.name
                        })
                },
                modifier =
                    Modifier.clickable {
                        vm.homeHidden.value =
                            if (section.name in vm.homeHidden.value) vm.homeHidden.value - section.name
                            else vm.homeHidden.value + section.name
                    },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = {
                                vm.homeOrder.value = HomeLayout.move(vm.homeOrder.value, section, -1)
                            },
                            enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, "Subir ${section.label}")
                            }
                        IconButton(
                            onClick = {
                                vm.homeOrder.value = HomeLayout.move(vm.homeOrder.value, section, 1)
                            },
                            enabled = index < order.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, "Bajar ${section.label}")
                            }
                    }
                })
        }
        SwitchRow(
            "Mostrar el buscador en Inicio",
            "Una barra para buscar por nombre en todo el almacenamiento",
            vm.homeSearch)
        SectionTitle("Iconos de «Categorías»", Modifier.padding(start = 16.dp, top = 8.dp))
        FileCategory.entries.forEach { category ->
            TileSwitch(category.label, HomeLayout.categoryKey(category), vm)
        }
        SectionTitle("Iconos de «Accesos rápidos»", Modifier.padding(start = 16.dp, top = 8.dp))
        QuickTile.entries.forEach { tile -> TileSwitch(tile.label, HomeLayout.quickKey(tile), vm) }
        if (vm.homeOrder.value.isNotEmpty() ||
            vm.homeHidden.value.isNotEmpty() ||
            vm.homeHiddenTiles.value.isNotEmpty())
            TextButton(
                onClick = {
                    vm.homeOrder.value = emptyList()
                    vm.homeHidden.value = emptySet()
                    vm.homeHiddenTiles.value = emptySet()
                },
                modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Restablecer Inicio")
                }
    }
}

/** «Fondo» de los temas de ES: una imagen detrás de la interfaz, con su visibilidad. */
@Composable
private fun BackgroundSettings(vm: MainViewModel) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("") }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                // Se copia a la caché y desde ahí se reduce y se guarda: el selector da un enlace, no un archivo.
                runCatching {
                        val temp = File(ctx.cacheDir, "fondo-elegido")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { input.copyTo(it) }
                        } ?: error("No se pudo leer la imagen")
                        vm.setBackground(temp)
                    }
                    .onFailure { vm.toast(it.message ?: "No se pudo leer la imagen") }
            }
        }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Fondo de la app:", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            path,
            { path = it },
            label = { Text("Ruta de la imagen (JPG, PNG…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.setBackground(File(path.trim())) },
                enabled = path.isNotBlank()) {
                    Text("Usar como fondo")
                }
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Elegir imagen…") }
        }
        if (vm.backgroundImage.value) {
            Text("Visibilidad de la imagen: ${vm.backgroundStrength.value} %")
            Slider(
                value = vm.backgroundStrength.value.toFloat(),
                onValueChange = { vm.backgroundStrength.value = it.toInt() },
                valueRange = BackgroundImage.strengths.first.toFloat()..BackgroundImage.strengths.last.toFloat())
            TextButton(onClick = { vm.clearBackground() }) { Text("Quitar el fondo") }
        }
    }
}

@Composable
private fun DocumentSettings(vm: MainViewModel) {
    val chosen = vm.documentTypeSet()
    Column {
        Text(
            "Elige qué tipos de archivo salen en la categoría «Documentos». Tiene que quedar al menos uno.",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DocumentType.entries.forEach { type ->
            val on = type in chosen
            ListItem(
                headlineContent = { Text(type.label) },
                supportingContent = { Text(type.extensions.joinToString(" ") { ".$it" }) },
                trailingContent = {
                    Switch(
                        checked = on,
                        enabled = !on || chosen.size > 1,
                        onCheckedChange = { want ->
                            vm.documentTypes.value =
                                (if (want) chosen + type else chosen - type).map { it.name }.toSet()
                        })
                },
                modifier =
                    Modifier.clickable(enabled = !on || chosen.size > 1) {
                        vm.documentTypes.value =
                            (if (on) chosen - type else chosen + type).map { it.name }.toSet()
                    })
        }
    }
}

@Composable
private fun ToolbarSettings(vm: MainViewModel) {
    val chosen = ToolbarLayout.actions(vm.toolbarActions.value)
    val rest = ToolbarAction.entries.filter { it !in chosen }
    Column {
        Text(
            "Elige qué botones salen en la barra de abajo al seleccionar archivos (hasta " +
                "${ToolbarLayout.MAX}) y en qué orden. Lo demás sigue en «Más».",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        chosen.forEachIndexed { index, action ->
            ListItem(
                headlineContent = { Text(action.label) },
                leadingContent = {
                    Switch(
                        checked = true,
                        onCheckedChange = {
                            vm.toolbarActions.value = ToolbarLayout.toggle(vm.toolbarActions.value, action)
                        })
                },
                modifier =
                    Modifier.clickable {
                        vm.toolbarActions.value = ToolbarLayout.toggle(vm.toolbarActions.value, action)
                    },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = {
                                vm.toolbarActions.value =
                                    ToolbarLayout.move(vm.toolbarActions.value, action, -1)
                            },
                            enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, "Subir ${action.label}")
                            }
                        IconButton(
                            onClick = {
                                vm.toolbarActions.value =
                                    ToolbarLayout.move(vm.toolbarActions.value, action, 1)
                            },
                            enabled = index < chosen.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, "Bajar ${action.label}")
                            }
                    }
                })
        }
        rest.forEach { action ->
            ListItem(
                headlineContent = { Text(action.label) },
                supportingContent = {
                    if (chosen.size >= ToolbarLayout.MAX) Text("Quita otro botón para poder añadir este")
                },
                leadingContent = {
                    Switch(
                        checked = false,
                        enabled = chosen.size < ToolbarLayout.MAX,
                        onCheckedChange = {
                            vm.toolbarActions.value = ToolbarLayout.toggle(vm.toolbarActions.value, action)
                        })
                },
                modifier =
                    Modifier.clickable(enabled = chosen.size < ToolbarLayout.MAX) {
                        vm.toolbarActions.value = ToolbarLayout.toggle(vm.toolbarActions.value, action)
                    })
        }
        if (chosen != ToolbarLayout.DEFAULT)
            TextButton(
                onClick = { vm.toolbarActions.value = ToolbarLayout.defaultNames },
                modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Restablecer barra")
                }
    }
}

@Composable
private fun TileSwitch(label: String, key: String, vm: MainViewModel) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                checked = key !in vm.homeHiddenTiles.value,
                onCheckedChange = { on ->
                    vm.homeHiddenTiles.value =
                        if (on) vm.homeHiddenTiles.value - key else vm.homeHiddenTiles.value + key
                })
        },
        modifier =
            Modifier.clickable {
                vm.homeHiddenTiles.value =
                    if (key in vm.homeHiddenTiles.value) vm.homeHiddenTiles.value - key
                    else vm.homeHiddenTiles.value + key
            })
}

@Composable
private fun StartSettings(vm: MainViewModel) {
    Column {
        Text(
            "Qué se muestra al abrir OI Archivos:",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        StartWindow.entries.forEach { w ->
            ListItem(
                headlineContent = { Text(w.label) },
                supportingContent = {
                    when (w) {
                        StartWindow.HOME -> Text("Categorías, almacenamiento y accesos")
                        StartWindow.HOME_FOLDER -> Text(vm.homeFolder.value)
                        StartWindow.LAST_FOLDER -> Text("La carpeta que estaba abierta al salir")
                    }
                },
                leadingContent = {
                    RadioButton(
                        selected = vm.startWindow.value == w,
                        onClick = { vm.startWindow.value = w })
                },
                modifier = Modifier.clickable { vm.startWindow.value = w })
        }
    }
}

@Composable
private fun AutoBackupSettings(vm: MainViewModel) {
    val destinations = remember { vm.backupDestinations() }
    val current = destinations.firstOrNull { it.id == vm.autoBackupConnection.value }
    var choosing by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf(false) }
    var addingFolder by remember { mutableStateOf(false) }
    // La copia en segundo plano pudo terminar con la pantalla cerrada.
    LaunchedEffect(Unit) { vm.autoBackupLast.reload() }
    Column {
        SwitchRow(
            "Copiar automáticamente",
            "Sube lo nuevo a una conexión guardada en cuanto aparece y, además, cada 6 horas",
            vm.autoBackup)
        ListItem(
            headlineContent = { Text("Destino") },
            supportingContent = {
                Text(
                    current?.let { "${it.label} (${it.protocol.label})" }
                        ?: if (destinations.isEmpty())
                            "No hay conexiones: crea una en «Red, nube y USB»"
                        else "Sin elegir")
            },
            modifier = Modifier.clickable(enabled = destinations.isNotEmpty()) { choosing = true })
        ListItem(
            headlineContent = { Text("Carpeta en el destino") },
            supportingContent = { Text(vm.autoBackupFolder.value) },
            modifier = Modifier.clickable { editingFolder = true })
        Text(
            "Qué copiar:",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            BackupKind.entries.forEach { kind ->
                val on = kind in vm.autoBackupKinds.value
                FilterChip(
                    on,
                    onClick = {
                        vm.autoBackupKinds.value =
                            if (on) vm.autoBackupKinds.value - kind else vm.autoBackupKinds.value + kind
                    },
                    label = { Text(kind.label) })
            }
        }
        Text(
            "Otras carpetas del teléfono (se copian enteras):",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.autoBackupFolders.value.forEach { folder ->
            ListItem(
                headlineContent = { Text(File(folder).name) },
                supportingContent = { Text(folder) },
                trailingContent = {
                    IconButton(
                        onClick = { vm.autoBackupFolders.value = vm.autoBackupFolders.value - folder }) {
                            Icon(Icons.Filled.Close, "Quitar ${File(folder).name}")
                        }
                })
        }
        TextButton(onClick = { addingFolder = true }, Modifier.padding(horizontal = 8.dp)) {
            Text("Añadir carpeta")
        }
        SwitchRow("Solo con Wi-Fi", "No gastar datos móviles", vm.autoBackupWifiOnly)
        Button(
            onClick = { vm.backupNow() },
            enabled = current != null,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Copiar ahora")
            }
        if (vm.autoBackupLast.value.isNotEmpty())
            Text(
                "Última copia: ${vm.autoBackupLast.value}",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing)
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Destino de la copia") },
            text = {
                Column {
                    destinations.forEach { c ->
                        ListItem(
                            headlineContent = { Text(c.label) },
                            supportingContent = { Text(c.protocol.label) },
                            leadingContent = {
                                RadioButton(
                                    selected = c.id == vm.autoBackupConnection.value,
                                    onClick = {
                                        vm.autoBackupConnection.value = c.id
                                        choosing = false
                                    })
                            },
                            modifier =
                                Modifier.clickable {
                                    vm.autoBackupConnection.value = c.id
                                    choosing = false
                                })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancelar") } })
    if (editingFolder) {
        var text by remember { mutableStateOf(vm.autoBackupFolder.value) }
        val problem = runCatching { AutoBackup.checkFolder(text) }.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = { editingFolder = false },
            title = { Text("Carpeta en el destino") },
            text = {
                Column {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Carpeta") },
                        singleLine = true,
                        isError = problem != null)
                    Text(
                        problem ?: "Dentro de la carpeta inicial de la conexión; puede tener subcarpetas (a/b)",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (problem != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.autoBackupFolder.value = AutoBackup.checkFolder(text).joinToString("/")
                        editingFolder = false
                    },
                    enabled = problem == null) {
                        Text("Aceptar")
                    }
            },
            dismissButton = { TextButton(onClick = { editingFolder = false }) { Text("Cancelar") } })
    }
    if (addingFolder)
        FolderPickerDialog(
            title = "Carpeta para copiar",
            start = PathUtil.internalRoot,
            onDismiss = { addingFolder = false },
            onPick = {
                if (it !in vm.autoBackupFolders.value)
                    vm.autoBackupFolders.value = vm.autoBackupFolders.value + it
                addingFolder = false
            })
}

@Composable
private fun NotificationSettings(vm: MainViewModel) {
    val ctx = LocalContext.current
    Column {
        SwitchRow(
            "Cerrar la notificación al terminar",
            "Si está desactivado, al terminar una copia, descarga u otra tarea queda un aviso con el resultado",
            vm.closeNotificationWhenDone)
        SwitchRow(
            "Advertencia de espacio bajo",
            "Avisar cuando quede poco espacio libre en el teléfono (se revisa cada hora)",
            vm.lowSpaceWarning)
        if (vm.lowSpaceWarning.value) {
            Text(
                "Avisar con menos de:",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow {
                StorageWatch.thresholdsMb.forEach { mb ->
                    FilterChip(
                        vm.lowSpaceMb.value == mb,
                        onClick = { vm.lowSpaceMb.value = mb },
                        label = { Text(if (mb < 1024) "$mb MB" else "${mb / 1024} GB") })
                }
            }
        }
        SwitchRow(
            "Mostrar el uso del almacenamiento",
            "Una notificación fija con el espacio libre del teléfono y de la tarjeta SD (se actualiza cada hora)",
            vm.storageNotification)
        SwitchRow(
            "Avisar de archivos nuevos",
            "Como el Registrador de ES: una notificación cuando aparecen fotos, vídeos, música, documentos o APK nuevos",
            vm.newFilesNotify)
        if (vm.newFilesNotify.value) {
            Text(
                "Tipos de archivo:",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow {
                NewFileKind.entries.forEach { kind ->
                    val on = kind in vm.newFilesKinds.value
                    FilterChip(
                        on,
                        onClick = {
                            val next =
                                if (on) vm.newFilesKinds.value - kind else vm.newFilesKinds.value + kind
                            if (next.isNotEmpty()) vm.newFilesKinds.value = next
                        },
                        label = { Text(kind.label) })
                }
            }
        }
        SwitchRow(
            "Informe diario de archivos nuevos",
            "Una vez al día, un resumen de cuántos archivos aparecieron y de qué tipo",
            vm.dailyReport)
        if (vm.dailyReport.value)
            TextButton(onClick = { vm.reportNow() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Ver el informe ahora")
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            ListItem(
                headlineContent = { Text("Permiso de notificaciones") },
                supportingContent = {
                    Text(
                        if (granted) "Permitidas"
                        else "Bloqueadas: no se verá el progreso ni el aviso al terminar. Toca para permitirlas")
                },
                modifier =
                    Modifier.clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
        }
    }
}

private enum class PasswordDialog { CREATE, CHECK, CHANGE }

@Composable
private fun PasswordSettings(vm: MainViewModel) {
    var dialog by remember { mutableStateOf<Pair<PasswordDialog, PrefState<Boolean>?>?>(null) }
    fun toggle(option: PrefState<Boolean>, enable: Boolean) {
        dialog =
            when {
                !enable -> PasswordDialog.CHECK to option
                !vm.hasPassword -> PasswordDialog.CREATE to option
                else -> {
                    vm.enableLock(option)
                    null
                }
            }
    }
    Column {
        SwitchRow(
            "Proteger al abrir la app",
            "Pedir la contraseña para entrar en OI Archivos",
            vm.lockStart.value) {
                toggle(vm.lockStart, it)
            }
        SwitchRow(
            "Proteger las conexiones de red",
            "Pedir la contraseña para abrir servidores, nubes y Bluetooth",
            vm.lockNetwork.value) {
                toggle(vm.lockNetwork, it)
            }
        SwitchRow(
            "Proteger los archivos ocultos",
            "Pedir la contraseña para mostrar los archivos ocultos",
            vm.lockHidden.value) {
                toggle(vm.lockHidden, it)
            }
        ListItem(
            headlineContent = { Text("Cambiar la contraseña") },
            supportingContent = {
                Text(
                    if (vm.hasPassword) "Déjala vacía para quitar la contraseña y las protecciones"
                    else "Aún no hay contraseña")
            },
            modifier =
                Modifier.clickable(enabled = vm.hasPassword) {
                    dialog = PasswordDialog.CHANGE to null
                })
        Text(
            "Basta con escribirla una vez mientras uses la app; se vuelve a pedir tras cinco " +
                "minutos fuera de ella o al tocar «Salir». Si la olvidas, solo se puede quitar " +
                "borrando los datos de OI Archivos en los ajustes de Android.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    dialog?.let { (kind, option) ->
        PasswordFormDialog(
            kind,
            onDismiss = { dialog = null },
            onSubmit = { old, new, confirm ->
                when (kind) {
                    PasswordDialog.CREATE ->
                        if (new != confirm) "Las contraseñas no coinciden"
                        else vm.enableLock(option!!, new)
                    PasswordDialog.CHECK -> vm.disableLock(option!!, old)
                    PasswordDialog.CHANGE -> vm.changePassword(old, new, confirm)
                }.also { if (it == null) dialog = null }
            })
    }
}

/** Formulario de contraseña; [onSubmit] devuelve un mensaje de error o null si todo fue bien. */
@Composable
private fun PasswordFormDialog(
    kind: PasswordDialog,
    onDismiss: () -> Unit,
    onSubmit: (old: String, new: String, confirm: String) -> String?
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onChange(it)
                error = null
            },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kind) {
                    PasswordDialog.CREATE -> "Crear contraseña"
                    PasswordDialog.CHECK -> "Quitar protección"
                    PasswordDialog.CHANGE -> "Cambiar la contraseña"
                })
        },
        text = {
            Column {
                if (kind != PasswordDialog.CREATE) field("Contraseña actual", old) { old = it }
                if (kind != PasswordDialog.CHECK) {
                    field("Contraseña nueva", new) { new = it }
                    field("Repetir contraseña", confirm) { confirm = it }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { error = onSubmit(old, new, confirm) },
                enabled =
                    when (kind) {
                        PasswordDialog.CREATE -> new.isNotEmpty()
                        PasswordDialog.CHECK -> old.isNotEmpty()
                        PasswordDialog.CHANGE -> old.isNotEmpty()
                    }) {
                    Text("Aceptar")
                }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun BackupSettings(vm: MainViewModel) {
    val file = File(settingsBackupFolder, SettingsBackup.FILE_NAME)
    var confirm by remember { mutableStateOf(false) }
    var modified by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { modified = withContext(Dispatchers.IO) { file.lastModified() } }
    Column {
        ListItem(
            headlineContent = { Text("Guardar copia de los ajustes") },
            supportingContent = { Text("En ${file.absolutePath}") },
            modifier =
                Modifier.clickable {
                    vm.exportSettings(settingsBackupFolder)
                    modified = System.currentTimeMillis()
                })
        ListItem(
            headlineContent = { Text("Restaurar los ajustes") },
            supportingContent = {
                Text(
                    if (modified > 0) "Copia del ${formatDate(modified)}"
                    else "No hay ninguna copia guardada")
            },
            modifier = Modifier.clickable(enabled = modified > 0) { confirm = true })
        Text(
            "La copia no incluye la contraseña ni las conexiones de red y nube, que llevan " +
                "credenciales.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restaurar los ajustes") },
            text = { Text("Los ajustes actuales se sustituirán por los de la copia.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.importSettings(file)
                    }) {
                        Text("Restaurar")
                    }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}

@Composable
private fun AppSettings(vm: MainViewModel) {
    var picking by remember { mutableStateOf(false) }
    Column {
        SwitchRow(
            "Copia antes de desinstalar",
            "Guardar el APK de una app antes de desinstalarla",
            vm.backupBeforeUninstall)
        SwitchRow(
            "Limpiar carpetas al desinstalar",
            "Tras desinstalar una app desde OI Archivos, proponer mover a la papelera las carpetas con su nombre que dejó en el almacenamiento",
            vm.cleanAssociatedFolders)
        SwitchRow(
            "Avisar de los permisos al instalar",
            "Al instalar una app desde OI Archivos, una notificación dice qué permisos delicados pide",
            vm.appPermissionNotify)
        val ctx = LocalContext.current
        ListItem(
            headlineContent = { Text("Apps predeterminadas") },
            supportingContent = {
                Text("Cambiar con qué app se abre cada tipo de archivo (ajustes de Android)")
            },
            modifier =
                Modifier.clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
        ListItem(
            headlineContent = { Text("Carpeta de copias de apps") },
            supportingContent = { Text(vm.appBackupFolder.value) },
            trailingContent = { ResetButton(vm.appBackupFolder, Prefs.defaultAppBackupFolder) },
            modifier = Modifier.clickable { picking = true })
    }
    if (picking)
        FolderPickerDialog(
            title = "Carpeta de copias de apps",
            start = vm.appBackupFolder.value,
            onDismiss = { picking = false },
            onPick = {
                vm.appBackupFolder.value = it
                picking = false
            })
}

@Composable
private fun EditorSettings(vm: MainViewModel) {
    var editingSymbols by remember { mutableStateOf(false) }
    if (editingSymbols) {
        var text by remember { mutableStateOf(vm.editorSymbols.value) }
        AlertDialog(
            onDismissRequest = { editingSymbols = false },
            title = { Text("Lista de símbolos") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it.replace("\n", " ").take(300) }, label = { Text("Símbolos") })
                    Text(
                        "Separados por espacios. Cada uno es un botón encima del teclado.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.editorSymbols.value = com.omaritoinforma.oiarchivos.data.EditorText.symbols(text).joinToString(" ")
                        editingSymbols = false
                    }) {
                        Text("Aceptar")
                    }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        text = com.omaritoinforma.oiarchivos.data.EditorText.DEFAULT_SYMBOLS
                    }) {
                        Text("Restablecer")
                    }
            })
    }
    Column {
        Column(Modifier.padding(16.dp)) {
            Text("Tamaño de la letra: ${vm.editorFont.value}")
            Slider(
                value = vm.editorFont.value.toFloat(),
                onValueChange = { vm.editorFont.value = it.toInt() },
                valueRange = 10f..28f,
                steps = 17)
        }
        SwitchRow("Números de línea", "Mostrar el número de cada línea", vm.editorLineNumbers)
        SwitchRow(
            "Saltos de línea automáticos",
            "Partir las líneas largas en vez de desplazar a los lados",
            vm.editorWrap)
        SwitchRow(
            "Sangría automática",
            "Al pulsar Intro, la línea nueva empieza con la sangría de la anterior",
            vm.editorAutoIndent)
        SwitchRow(
            "Resaltado de sintaxis",
            "Colorear el código (solo en archivos de hasta ${vm.editorHighlightLimit.value} KB)",
            vm.editorHighlight)
        Text(
            "Tamaño máximo del archivo resaltado:",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            listOf(50, 100, 500, 1000, 2000).forEach { kb ->
                FilterChip(
                    vm.editorHighlightLimit.value == kb,
                    onClick = { vm.editorHighlightLimit.value = kb },
                    label = { Text(if (kb >= 1000) "${kb / 1000} MB" else "$kb KB") })
            }
        }
        SwitchRow(
            "Guardado automático",
            "Guardar al salir del editor sin preguntar",
            vm.editorAutoSave)
        SwitchRow(
            "Mostrar espacios en blanco",
            "Dibujar los espacios como · y los tabuladores como →",
            vm.editorShowWhitespace)
        SwitchRow(
            "Mayúscula automática de la primera letra",
            "El teclado empieza cada frase en mayúscula",
            vm.editorAutoCapitalize)
        SwitchRow("Barra de símbolos", "Tab y símbolos encima del teclado", vm.editorSymbolBar)
        SwitchRow(
            "Usar espacios en lugar de tabuladores",
            "La tecla Tab de la barra escribe espacios",
            vm.editorSpacesForTab)
        Text(
            "Tamaño de tabulación:",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            listOf(2, 4, 8).forEach { size ->
                FilterChip(
                    vm.editorTabSize.value == size,
                    onClick = { vm.editorTabSize.value = size },
                    label = { Text("$size espacios") })
            }
        }
        ListItem(
            headlineContent = { Text("Lista de símbolos personalizada") },
            supportingContent = { Text(vm.editorSymbols.value, maxLines = 2) },
            modifier = Modifier.clickable { editingSymbols = true })
    }
}

/** Vuelve a la carpeta predeterminada; solo se muestra si se cambió. */
@Composable
private fun ResetButton(setting: PrefState<String>, default: String) {
    if (setting.value != default)
        TextButton(onClick = { setting.value = default }) { Text("Restablecer") }
}

@Composable
private fun GestureSettings(vm: MainViewModel) {
    Column {
        GestureRow("Deslizar a la izquierda", vm.swipeLeft) { vm.updateGesture(true, it) }
        GestureRow("Deslizar a la derecha", vm.swipeRight) { vm.updateGesture(false, it) }
        Text(
            "Cuando actives un gesto, abre el menú lateral con su botón.",
            Modifier.padding(16.dp))
    }
}

@Composable
private fun About() {
    ListItem(
        headlineContent = { Text("OI Archivos ${BuildConfig.VERSION_NAME}") },
        supportingContent = {
            Text(
                "Uso personal. Sin anuncios ni rastreo. La red se usa para las conexiones y transferencias que activas.")
        },
    )
}

@Composable
private fun GestureRow(title: String, action: GestureAction, onChange: (GestureAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(action.label) },
            modifier = Modifier.clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GestureAction.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onChange(choice)
                        expanded = false
                    })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, setting: PrefState<Boolean>) =
    SwitchRow(title, subtitle, setting.value) { setting.value = it }

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
