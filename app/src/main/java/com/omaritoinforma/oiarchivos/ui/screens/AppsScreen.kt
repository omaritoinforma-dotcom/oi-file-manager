@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.omaritoinforma.oiarchivos.data.AppInfo
import com.omaritoinforma.oiarchivos.data.AppInstaller
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.components.MenuItem
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

/**
 * Gestor de aplicaciones: ver, abrir, respaldar APK, compartir y desinstalar. Como en ES, al
 * mantener pulsada una app se pueden elegir varias para respaldarlas o desinstalarlas seguidas.
 */
@Composable
fun AppsScreen(vm: MainViewModel) {
    var showSystem by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val batch by AppInstaller.progress.collectAsState()
    LaunchedEffect(showSystem) { vm.loadApps(showSystem) }
    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    val q = filter.trim().lowercase()
    val shown =
        if (q.isEmpty()) vm.apps.toList()
        else
            vm.apps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }

    val chosen = vm.apps.filter { it.packageName in selected }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) tr("Aplicaciones ({0})", shown.size)
                        else tr("{0} seleccionada(s)", selected.size))
                },
                navigationIcon = {
                    if (selected.isEmpty())
                        IconButton(onClick = vm::back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Atrás"))
                        }
                    else
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Filled.Close, tr("Cancelar selección"))
                        }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selected.clear()
                                selected.addAll(shown.map { it.packageName })
                            }) {
                                Icon(Icons.Filled.SelectAll, tr("Seleccionar todas"))
                            }
                        IconButton(
                            onClick = {
                                vm.backupApps(chosen)
                                selected.clear()
                            }) {
                                Icon(Icons.Filled.Save, tr("Respaldar seleccionadas"))
                            }
                        IconButton(
                            onClick = {
                                vm.uninstallApps(chosen)
                                selected.clear()
                            }) {
                                Icon(Icons.Filled.Delete, tr("Desinstalar seleccionadas"))
                            }
                    } else Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Más")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showSystem) tr("Ocultar apps del sistema")
                                        else tr("Mostrar apps del sistema"))
                                },
                                onClick = {
                                    menu = false
                                    showSystem = !showSystem
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Analizar permisos")) },
                                onClick = {
                                    menu = false
                                    vm.goTo(com.omaritoinforma.oiarchivos.ui.Screen.AppAnalysis)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Respaldar todas las mostradas")) },
                                onClick = {
                                    menu = false
                                    vm.backupApps(shown)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text(tr("Buscar app…")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (vm.appsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            batch?.let { b ->
                Text(
                    if (b.installing) tr("Instalando {0} de {1}: {2}", b.done + 1, b.total, b.current)
                    else tr("Desinstalando {0} de {1}: {2}", b.done + 1, b.total, b.current),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LinearProgressIndicator(
                    progress = { b.done.toFloat() / b.total },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selected
                    val toggle = {
                        if (isSelected) selected.remove(app.packageName)
                        else selected.add(app.packageName)
                        Unit
                    }
                    AppRow(vm, app, selecting = selected.isNotEmpty(), isSelected, toggle)
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    vm: MainViewModel,
    app: AppInfo,
    selecting: Boolean,
    isSelected: Boolean,
    toggle: () -> Unit
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            if (selecting) Checkbox(isSelected, onCheckedChange = { toggle() })
            else AppIcon(app.packageName)
        },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                tr("{0}\nv{1} · {2}", app.packageName, app.versionName, formatSize(app.size)) +
                    (if (app.isSystem) tr(" · Sistema") else ""),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Opciones")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(tr("Abrir"), Icons.Filled.OpenInNew) {
                        menu = false
                        val launch = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launch != null)
                            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else vm.toast(tr("Esta app no tiene pantalla para abrir"))
                    }
                    MenuItem(tr("Respaldar APK"), Icons.Filled.Save) {
                        menu = false
                        vm.backupApps(listOf(app))
                    }
                    MenuItem(tr("Compartir APK"), Icons.Filled.Share) {
                        menu = false
                        Opener.share(ctx, listOf(File(app.apkPath)))
                    }
                    MenuItem(tr("Información de la app"), Icons.Filled.Info) {
                        menu = false
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${app.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    if (!app.isSystem) {
                        MenuItem(tr("Desinstalar"), Icons.Filled.Delete) {
                            menu = false
                            vm.uninstall(app)
                        }
                    }
                }
            }
        },
        modifier =
            Modifier.combinedClickable(
                onClick = { if (selecting) toggle() else menu = true }, onLongClick = toggle),
    )
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    var bmp by remember(pkg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pkg) {
        bmp =
            withContext(Dispatchers.IO) {
                runCatching {
                        ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                    }
                    .getOrNull()
            }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(
            Icons.Filled.Android,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = Color(0xFF7CB342))
    }
}
