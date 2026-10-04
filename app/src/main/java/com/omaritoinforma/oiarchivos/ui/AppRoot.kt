@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.omaritoinforma.oiarchivos.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.omaritoinforma.oiarchivos.data.Conflict
import com.omaritoinforma.oiarchivos.data.GestureAction
import com.omaritoinforma.oiarchivos.data.OpProgress
import com.omaritoinforma.oiarchivos.ui.components.LocalThumbnails
import com.omaritoinforma.oiarchivos.ui.screens.*
import com.omaritoinforma.oiarchivos.ui.screens.AppsScreen
import com.omaritoinforma.oiarchivos.ui.screens.BrowserScreen
import com.omaritoinforma.oiarchivos.ui.screens.EditorScreen
import com.omaritoinforma.oiarchivos.ui.screens.HomeScreen
import com.omaritoinforma.oiarchivos.ui.screens.SettingsScreen
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: MainViewModel) {
    // Las etiquetas de prueba (testTag) aparecen como resource-id para las pruebas en Android.
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        when {
            vm.locked -> LockScreen(vm)
            !vm.hasPermission -> PermissionScreen(vm)
            else ->
                CompositionLocalProvider(LocalThumbnails provides vm.thumbnails.value) {
                    MainContent(vm)
                }
        }
    }
}

@Composable
private fun MainContent(vm: MainViewModel) {
    NotificationPermission(vm)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    BackHandler(enabled = vm.canGoBack) { vm.back() }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    val screen = vm.screen
    val customBrowserGestures =
        screen == Screen.Browser &&
            (vm.swipeLeft != GestureAction.NONE || vm.swipeRight != GestureAction.NONE)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled =
            drawerState.isOpen ||
                screen == Screen.Home ||
                (screen == Screen.Browser && !customBrowserGestures),
        drawerContent = { AppDrawer(vm, closeDrawer) },
    ) {
        when (screen) {
            Screen.Home -> HomeScreen(vm, openDrawer)
            Screen.Browser -> BrowserScreen(vm, openDrawer)
            is Screen.Editor -> EditorScreen(vm, screen.path)
            Screen.Apps -> AppsScreen(vm)
            Screen.Trash -> TrashScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            is Screen.Viewer -> ViewerScreen(vm, screen.path)
            is Screen.Archive -> ArchiveScreen(vm, screen.path)
            is Screen.Analysis -> AnalysisScreen(vm, screen.root)
            is Screen.AdvancedSearch -> AdvancedSearchScreen(vm, screen.root)
            Screen.Connections -> ConnectionsScreen(vm)
            Screen.Bluetooth -> BluetoothScreen(vm)
            is Screen.Remote -> RemoteScreen(vm, screen.id)
            is Screen.Documents -> DocumentsScreen(vm, screen.uri)
            Screen.Sharing -> SharingScreen(vm)
            Screen.Transfers -> TransfersScreen(vm)
            Screen.Nearby -> NearbyScreen(vm)
            Screen.History -> HistoryScreen(vm)
            Screen.RootTools -> RootToolsScreen(vm)
            is Screen.VideoEdit -> VideoEditScreen(vm, screen.path)
            is Screen.DualPane -> DualPaneScreen(vm, screen.path)
            is Screen.Stream -> StreamScreen(vm, screen.url, screen.title)
            Screen.Cast -> CastScreen(vm)
            Screen.Cleaner -> CleanerScreen(vm)
        }
    }
    Overlays(vm)
}

/** Android 13 y posteriores piden permiso para las notificaciones de progreso y de tarea terminada. */
@Composable
private fun NotificationPermission(vm: MainViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted && vm.askNotificationPermissionOnce())
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** «Iniciar protección» de ES: la app no muestra nada hasta escribir la contraseña. */
@Composable
private fun LockScreen(vm: MainViewModel) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val unlock = {
        if (!vm.unlock(password)) {
            error = "Contraseña incorrecta"
            password = ""
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(
            "OI Archivos está protegido",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Escribe la contraseña para continuar.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = { Text("Contraseña") },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { unlock() }),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = unlock, enabled = password.isNotEmpty()) { Text("Desbloquear") }
    }
}

/** Pide la contraseña antes de una acción protegida (conexiones de red, archivos ocultos). */
@Composable
private fun UnlockDialog(vm: MainViewModel, request: MainViewModel.UnlockRequest) {
    var password by remember(request) { mutableStateOf("") }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val confirm = {
        if (!vm.unlock(password)) {
            error = "Contraseña incorrecta"
            password = ""
        }
    }
    AlertDialog(
        onDismissRequest = vm::dismissUnlock,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Contraseña") },
        text = {
            Column {
                Text("«${request.reason}» está protegido con contraseña.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = password.isNotEmpty()) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = vm::dismissUnlock) { Text("Cancelar") } })
}

@Composable
private fun PermissionScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onResume()
        }
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(
            "OI Archivos necesita acceso a tus archivos",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Para explorar, copiar, mover y organizar todo tu almacenamiento, concede el permiso " +
                "«Acceso a todos los archivos». Las conexiones de red solo se usan cuando tú las activas.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")),
                        )
                    } catch (e: ActivityNotFoundException) {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (e2: Exception) {
                            Toast.makeText(
                                    ctx,
                                    "Abre Ajustes > Apps > OI Archivos > Permisos",
                                    Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                } else {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    )
                }
            }) {
                Text("Conceder permiso")
            }
    }
}

@Composable
private fun AppDrawer(vm: MainViewModel, close: () -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "OI Archivos",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
            )
            DrawerItem("Inicio", Icons.Filled.Home, vm.screen == Screen.Home) {
                vm.goHome()
                close()
            }
            vm.volumes.forEach { v ->
                DrawerItem(
                    v.name, if (v.removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid) {
                        vm.openFolder(v.path)
                        close()
                    }
            }
            DrawerItem("Descargas", Icons.Filled.Download) {
                vm.openFolder(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .absolutePath)
                close()
            }
            DrawerItem("Raíz del sistema", Icons.Filled.Dns) {
                vm.openFolder("/")
                close()
            }
            if (vm.bookmarks.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                Text(
                    "Marcadores",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
                vm.bookmarks.forEach { b ->
                    DrawerItem(PathUtil.displayName(b), Icons.Filled.Bookmark) {
                        vm.openFolder(b)
                        close()
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            DrawerItem("Papelera", Icons.Filled.Delete, vm.screen == Screen.Trash) {
                vm.goTo(Screen.Trash)
                close()
            }
            DrawerItem("Aplicaciones", Icons.Filled.Apps, vm.screen == Screen.Apps) {
                vm.goTo(Screen.Apps)
                close()
            }
            DrawerItem("Red, nube y USB", Icons.Filled.Dns, vm.screen == Screen.Connections) {
                vm.goTo(Screen.Connections)
                close()
            }
            DrawerItem("Analizar espacio", Icons.Filled.SdCard) {
                vm.goTo(Screen.Analysis(PathUtil.internalRoot))
                close()
            }
            DrawerItem("Limpiar basura", Icons.Filled.Delete, vm.screen == Screen.Cleaner) {
                vm.goTo(Screen.Cleaner)
                close()
            }
            DrawerItem("Transferencias", Icons.Filled.Download, vm.screen == Screen.Transfers) {
                vm.goTo(Screen.Transfers)
                close()
            }
            DrawerItem("Historial", Icons.Filled.Folder, vm.screen == Screen.History) {
                vm.goTo(Screen.History)
                close()
            }
            DrawerItem("Root con Magisk", Icons.Filled.Dns, vm.screen == Screen.RootTools) {
                vm.goTo(Screen.RootTools)
                close()
            }
            DrawerItem("Ajustes", Icons.Filled.Settings, vm.screen == Screen.Settings) {
                vm.goTo(Screen.Settings)
                close()
            }
            val activity = LocalContext.current as? android.app.Activity
            DrawerItem("Salir", Icons.AutoMirrored.Filled.ExitToApp) {
                close()
                vm.exit()
                activity?.finishAndRemoveTask()
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun Overlays(vm: MainViewModel) {
    val ctx = LocalContext.current
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    val progress by vm.progress.collectAsState()
    val paused by com.omaritoinforma.oiarchivos.data.TransferService.paused.collectAsState()
    val pausable by
        com.omaritoinforma.oiarchivos.data.TransferService.supportsPause.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(progress == null) { if (progress == null) hidden = false }
    if (!hidden)
        progress?.let {
            ProgressDialog(
                it,
                onCancel = vm::cancelOp,
                onHide = { hidden = true },
                paused = paused,
                onPause =
                    if (pausable)
                        ({ com.omaritoinforma.oiarchivos.data.TransferService.pause(ctx, !paused) })
                    else null)
        }

    vm.unlockRequest?.let { UnlockDialog(vm, it) }

    vm.pendingPaste?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.resolvePaste(null) },
            title = { Text("Ya existen elementos con ese nombre") },
            text = {
                Text(
                    "${p.conflicts} de ${p.sources.size} elemento(s) ya existen en la carpeta de destino. ¿Qué quieres hacer?")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.resolvePaste(Conflict.RENAME) }) {
                        Text("Conservar ambos (renombrar)")
                    }
                    TextButton(onClick = { vm.resolvePaste(Conflict.OVERWRITE) }) {
                        Text("Reemplazar / combinar")
                    }
                    TextButton(onClick = { vm.resolvePaste(Conflict.SKIP) }) {
                        Text("Omitir los que existen")
                    }
                    TextButton(onClick = { vm.resolvePaste(null) }) { Text("Cancelar") }
                }
            },
        )
    }
}

@Composable
private fun ProgressDialog(
    p: OpProgress,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    paused: Boolean,
    onPause: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(p.title + if (paused) " · En pausa" else "") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.current.isNotEmpty())
                    Text(p.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val fraction: Float? =
                    when {
                        p.totalBytes > 0 -> (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                        p.totalFiles > 0 -> (p.doneFiles.toFloat() / p.totalFiles).coerceIn(0f, 1f)
                        else -> null
                    }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (p.totalFiles > 0) {
                    Text(
                        "${p.doneFiles} de ${p.totalFiles} archivos",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (p.totalBytes > 0) {
                    val remaining =
                        if (p.bytesPerSec > 0) (p.totalBytes - p.doneBytes) / p.bytesPerSec else -1
                    Text(
                        "${formatSize(p.doneBytes)} de ${formatSize(p.totalBytes)} · ${formatSize(p.bytesPerSec)}/s" +
                            (if (remaining >= 0) " · faltan ${formatEta(remaining)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Column {
                onPause?.let {
                    TextButton(onClick = it) { Text(if (paused) "Reanudar" else "Pausar") }
                }
                TextButton(onClick = onHide) { Text("Continuar navegando") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

private fun formatEta(seconds: Long): String =
    when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
