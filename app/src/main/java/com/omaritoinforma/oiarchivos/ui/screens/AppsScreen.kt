@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.components.MenuItem
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gestor de aplicaciones: ver, abrir, respaldar APK, compartir y desinstalar. */
@Composable
fun AppsScreen(vm: MainViewModel) {
    var showSystem by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(showSystem) { vm.loadApps(showSystem) }

    val q = filter.trim().lowercase()
    val shown =
        if (q.isEmpty()) vm.apps.toList()
        else
            vm.apps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aplicaciones (${shown.size})") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showSystem) "Ocultar apps del sistema"
                                        else "Mostrar apps del sistema")
                                },
                                onClick = {
                                    menu = false
                                    showSystem = !showSystem
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Respaldar todas las mostradas") },
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
                placeholder = { Text("Buscar app…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (vm.appsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.packageName }) { app -> AppRow(vm, app) }
            }
        }
    }
}

@Composable
private fun AppRow(vm: MainViewModel, app: AppInfo) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { AppIcon(app.packageName) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${app.packageName}\nv${app.versionName} · ${formatSize(app.size)}" +
                    (if (app.isSystem) " · Sistema" else ""),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Abrir", Icons.Filled.OpenInNew) {
                        menu = false
                        val launch = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launch != null)
                            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else vm.toast("Esta app no tiene pantalla para abrir")
                    }
                    MenuItem("Respaldar APK", Icons.Filled.Save) {
                        menu = false
                        vm.backupApps(listOf(app))
                    }
                    MenuItem("Compartir APK", Icons.Filled.Share) {
                        menu = false
                        Opener.share(ctx, listOf(File(app.apkPath)))
                    }
                    MenuItem("Información de la app", Icons.Filled.Info) {
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
                        MenuItem("Desinstalar", Icons.Filled.Delete) {
                            menu = false
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                            Intent.ACTION_DELETE,
                                            Uri.parse("package:${app.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.clickable { menu = true },
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
