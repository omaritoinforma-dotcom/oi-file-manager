@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.omaritoinforma.oiarchivos.data.Conflict
import com.omaritoinforma.oiarchivos.data.OpProgress
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
    if (!vm.hasPermission) {
        PermissionScreen(vm)
        return
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    BackHandler(enabled = vm.canGoBack) { vm.back() }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    val screen = vm.screen
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || screen == Screen.Home || screen == Screen.Browser,
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
            is Screen.Remote -> RemoteScreen(vm, screen.id)
            is Screen.Documents -> DocumentsScreen(vm, screen.uri)
            Screen.Sharing -> SharingScreen(vm)
            Screen.Transfers -> TransfersScreen(vm)
            Screen.History -> HistoryScreen(vm)
            Screen.RootTools -> RootToolsScreen(vm)
            is Screen.VideoEdit -> VideoEditScreen(vm, screen.path)
            is Screen.DualPane -> DualPaneScreen(vm, screen.path)
        }
    }
    Overlays(vm)
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
    progress?.let { ProgressDialog(it, onCancel = vm::cancelOp) }

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
private fun ProgressDialog(p: OpProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(p.title) },
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
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

private fun formatEta(seconds: Long): String =
    when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
