package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.omaritoinforma.oiarchivos.data.CacheCleaner
import com.omaritoinforma.oiarchivos.data.JunkScanner
import com.omaritoinforma.oiarchivos.data.toItem
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Versión instalada de un paquete, o null si no está. */
private fun installedVersion(ctx: Context, packageName: String): Long? =
    runCatching {
            val info =
                if (Build.VERSION.SDK_INT >= 33)
                    ctx.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        }
        .getOrNull()

private fun apkInfo(ctx: Context, file: File): JunkScanner.ApkInfo? {
    val pm = ctx.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageArchiveInfo(file.path, 0) ?: return null
    val app = info.applicationInfo
    val label =
        app?.let {
            it.sourceDir = file.path
            it.publicSourceDir = file.path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        } ?: info.packageName
    return JunkScanner.ApkInfo(info.packageName, PackageInfoCompat.getLongVersionCode(info), label)
}

/** «Limpiar basura», como el limpiador de ES: se revisa y lo elegido va a la papelera. */
@Composable
fun CleanerScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JunkScanner.Item>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var cacheSize by remember { mutableLongStateOf(0L) }
    var cacheChosen by remember { mutableStateOf(true) }
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    val open = remember { mutableStateMapOf<JunkScanner.Kind, Boolean>() }
    var confirm by remember { mutableStateOf(false) }

    fun scan() {
        scanning = true
        scope.launch {
            val root = File(PathUtil.internalRoot)
            val found =
                withContext(Dispatchers.IO) {
                    cacheSize = CacheCleaner.size(ctx)
                    runCatching {
                            JunkScanner.scan(
                                root, { installedVersion(ctx, it) }, { apkInfo(ctx, it) }) { p ->
                                progress = p.current
                            }
                        }
                        .getOrElse {
                            vm.toast(it.message ?: "No se pudo buscar")
                            emptyList()
                        }
                }
            chosen.clear()
            found.forEach { chosen[it.file.path] = true }
            items = found
            scanning = false
        }
    }

    val selected = items.orEmpty().filter { chosen[it.file.path] == true }
    val total = selected.sumOf { it.size } + if (cacheChosen) cacheSize else 0
    ToolPage("Limpiar basura", vm) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "Busca temporales, miniaturas guardadas, APK de apps ya instaladas y restos de apps " +
                        "desinstaladas. Lo que elijas va a la papelera, así que se puede recuperar.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = ::scan, enabled = !scanning) {
                    Text(if (items == null) "Buscar basura" else "Buscar otra vez")
                }
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(progress, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
            val found = items
            if (found != null && !scanning) {
                item {
                    Text(
                        "Se pueden liberar ${formatSize(total)}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 12.dp))
                    ListItem(
                        headlineContent = { Text("Caché de OI Archivos") },
                        supportingContent = { Text("Miniaturas y vistas previas · ${formatSize(cacheSize)}") },
                        leadingContent = { Checkbox(cacheChosen, { cacheChosen = it }) })
                }
                JunkScanner.Kind.entries.forEach { kind ->
                    val group = found.filter { it.kind == kind }
                    if (group.isEmpty()) return@forEach
                    val all = group.all { chosen[it.file.path] == true }
                    item(key = "kind:${kind.name}") {
                        ListItem(
                            headlineContent = { Text("${kind.label} (${group.size})") },
                            supportingContent = {
                                Text("${kind.description} · ${formatSize(group.sumOf { it.size })}")
                            },
                            leadingContent = {
                                Checkbox(all, { on -> group.forEach { chosen[it.file.path] = on } })
                            },
                            trailingContent = {
                                TextButton(onClick = { open[kind] = open[kind] != true }) {
                                    Text(if (open[kind] == true) "Ocultar" else "Ver")
                                }
                            })
                    }
                    if (open[kind] == true)
                        items(group, key = { "item:" + it.file.path }) { junk ->
                            ListItem(
                                headlineContent = { Text(junk.file.name) },
                                supportingContent = {
                                    Text(
                                        listOf(junk.detail, junk.file.parent.orEmpty())
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "))
                                },
                                leadingContent = {
                                    Checkbox(
                                        chosen[junk.file.path] == true,
                                        { chosen[junk.file.path] = it })
                                },
                                trailingContent = { Text(formatSize(junk.size)) },
                                modifier =
                                    Modifier.padding(start = 24.dp).clickable {
                                        chosen[junk.file.path] = chosen[junk.file.path] != true
                                    })
                        }
                }
                item {
                    Button(
                        onClick = { confirm = true },
                        enabled = selected.isNotEmpty() || (cacheChosen && cacheSize > 0),
                        modifier = Modifier.padding(top = 16.dp)) {
                            Text("Limpiar ${formatSize(total)}")
                        }
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Limpiar basura") },
            text = {
                Text(
                    "${selected.size} elemento(s) irán a la papelera" +
                        if (cacheChosen) " y se borrará la caché de la app." else ".")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        if (cacheChosen) vm.clearCache {}
                        if (selected.isNotEmpty()) vm.delete(selected.map { it.file.toItem() }, true)
                        items = null
                    }) {
                        Text("Limpiar")
                    }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}
