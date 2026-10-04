@file:OptIn(ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.FileCategory
import com.omaritoinforma.oiarchivos.data.Location
import com.omaritoinforma.oiarchivos.data.StorageVolumeInfo
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import com.omaritoinforma.oiarchivos.ui.components.SectionTitle
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlin.math.roundToInt

private data class Tile(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(vm: MainViewModel, openDrawer: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshVolumes() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OI Archivos") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, "Menú") }
                },
                actions = {
                    IconButton(onClick = { vm.goTo(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, "Ajustes")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(vm.volumes.toList(), key = { "vol:" + it.path }) { v ->
                StorageCard(v) { vm.openFolder(v.path) }
            }
            item { SectionTitle("Categorías") }
            item { TileGrid(categoryTiles(vm)) }
            item { SectionTitle("Accesos rápidos") }
            item { TileGrid(quickTiles(vm)) }
            if (vm.bookmarks.isNotEmpty()) {
                item { SectionTitle("Marcadores") }
                items(vm.bookmarks.toList(), key = { "bm:$it" }) { b ->
                    ListItem(
                        headlineContent = { Text(PathUtil.displayName(b)) },
                        supportingContent = {
                            Text(b, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Bookmark,
                                null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.toggleBookmark(b) }) {
                                Icon(Icons.Filled.Close, "Quitar marcador")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.openFolder(b) },
                    )
                }
            }
        }
    }
}

private fun categoryTiles(vm: MainViewModel): List<Tile> =
    FileCategory.entries.map { c ->
        val (icon, color) =
            when (c) {
                FileCategory.IMAGES -> Icons.Filled.Image to Color(0xFF43A047)
                FileCategory.MUSIC -> Icons.Filled.MusicNote to Color(0xFF8E24AA)
                FileCategory.VIDEOS -> Icons.Filled.Movie to Color(0xFFE53935)
                FileCategory.DOCUMENTS -> Icons.Filled.Description to Color(0xFF1E88E5)
                FileCategory.APKS -> Icons.Filled.Android to Color(0xFF7CB342)
                FileCategory.ARCHIVES -> Icons.Filled.Archive to Color(0xFF8D6E63)
                FileCategory.EBOOKS -> Icons.AutoMirrored.Filled.MenuBook to Color(0xFF6D4C41)
                FileCategory.SCREENSHOTS -> Icons.Filled.Screenshot to Color(0xFF00897B)
                FileCategory.RECORDINGS -> Icons.Filled.Mic to Color(0xFFD81B60)
                FileCategory.WORD -> Icons.Filled.Description to Color(0xFF1565C0)
                FileCategory.EXCEL -> Icons.Filled.TableChart to Color(0xFF2E7D32)
                FileCategory.POWERPOINT -> Icons.Filled.Slideshow to Color(0xFFEF6C00)
                FileCategory.RECENT -> Icons.Filled.History to Color(0xFFFB8C00)
            }
        Tile(c.label, icon, color) { vm.navigate(Location.Category(c)) }
    }

@Suppress("DEPRECATION")
private fun publicDir(type: String): String =
    Environment.getExternalStoragePublicDirectory(type).absolutePath

private fun quickTiles(vm: MainViewModel): List<Tile> =
    listOf(
        Tile("Descargas", Icons.Filled.Download, Color(0xFF1E88E5)) {
            vm.openFolder(publicDir(Environment.DIRECTORY_DOWNLOADS))
        },
        Tile("Cámara", Icons.Filled.CameraAlt, Color(0xFF00897B)) {
            vm.openFolder(publicDir(Environment.DIRECTORY_DCIM))
        },
        Tile("Imágenes", Icons.Filled.Collections, Color(0xFF43A047)) {
            vm.openFolder(publicDir(Environment.DIRECTORY_PICTURES))
        },
        Tile("Documentos", Icons.Filled.Description, Color(0xFF3949AB)) {
            vm.openFolder(publicDir(Environment.DIRECTORY_DOCUMENTS))
        },
        Tile("Papelera", Icons.Filled.Delete, Color(0xFF757575)) { vm.goTo(Screen.Trash) },
        Tile("Apps", Icons.Filled.Apps, Color(0xFF7CB342)) { vm.goTo(Screen.Apps) },
        Tile("Red / nube", Icons.Filled.Dns, Color(0xFF1E88E5)) { vm.goTo(Screen.Connections) },
        Tile("Analizar", Icons.Filled.SdCard, Color(0xFF8E24AA)) {
            vm.goTo(Screen.Analysis(PathUtil.internalRoot))
        },
        Tile("Historial", Icons.Filled.History, Color(0xFFFB8C00)) { vm.goTo(Screen.History) },
        Tile("Transferir", Icons.Filled.Download, Color(0xFF00897B)) { vm.goTo(Screen.Transfers) },
        Tile("Raíz", Icons.Filled.Dns, Color(0xFF6D4C41)) { vm.openFolder("/") },
        Tile("Ajustes", Icons.Filled.Settings, Color(0xFF546E7A)) { vm.goTo(Screen.Settings) },
    )

@Composable
private fun TileGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { t -> TileView(t, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = t.onClick)
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(t.color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, contentDescription = null, tint = t.color)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StorageCard(v: StorageVolumeInfo, onClick: () -> Unit) {
    val used = (v.total - v.free).coerceAtLeast(0)
    val fraction = if (v.total > 0) used.toFloat() / v.total else 0f
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (v.removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatSize(v.free)} libres de ${formatSize(v.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${(fraction * 100).roundToInt()} %",
                    style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}
