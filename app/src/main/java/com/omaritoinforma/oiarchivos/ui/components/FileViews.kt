@file:OptIn(ExperimentalFoundationApi::class)

package com.omaritoinforma.oiarchivos.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omaritoinforma.oiarchivos.data.FileItem
import com.omaritoinforma.oiarchivos.data.FolderStyle
import com.omaritoinforma.oiarchivos.util.ApkIcons
import com.omaritoinforma.oiarchivos.util.FileKind
import com.omaritoinforma.oiarchivos.util.Kinds
import com.omaritoinforma.oiarchivos.util.PathUtil
import com.omaritoinforma.oiarchivos.util.formatDate
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omaritoinforma.oiarchivos.data.tr

/** «Miniaturas» de Ajustes → Pantalla: si es falso se muestran iconos en vez de vistas previas. */
val LocalThumbnails = compositionLocalOf { true }

/** «Estilo de carpetas» de Ajustes → Pantalla. */
val LocalFolderStyle = compositionLocalOf { FolderStyle.CLASSIC }

/** Rutas fijadas arriba; las filas y celdas las marcan con un alfiler. */
val LocalPinned = compositionLocalOf<Set<String>> { emptySet() }

fun subtitle(item: FileItem): String =
    if (item.isDirectory) tr("{0} · {1} elementos", formatDate(item.lastModified), item.childCount)
    else "${formatDate(item.lastModified)} · ${formatSize(item.size)}"

@Composable
fun FileRow(
    item: FileItem,
    details: Boolean,
    showPath: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = if (details) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(item, if (details) 44.dp else 34.dp, selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (details) {
                Text(
                    subtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (showPath) {
                Text(
                    item.file.parent ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.path in LocalPinned.current) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.PushPin,
                contentDescription = tr("Fijado"),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        if (!details && !item.isDirectory) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatSize(item.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun GridCell(item: FileItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        modifier =
            Modifier.padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileThumb(item, 64.dp, selected)
        Spacer(Modifier.height(6.dp))
        if (item.path in LocalPinned.current)
            Icon(
                Icons.Filled.PushPin,
                contentDescription = tr("Fijado"),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FileThumb(item: FileItem, size: Dp, selected: Boolean) {
    val kind = Kinds.of(item)
    val previews = LocalThumbnails.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            selected ->
                Box(
                    Modifier.fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = tr("Seleccionado"),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            previews && (kind == FileKind.IMAGE || kind == FileKind.VIDEO) -> {
                val fallback = rememberVectorPainter(Kinds.icon(kind))
                AsyncImage(
                    model = item.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = fallback,
                    error = fallback,
                    modifier =
                        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).testTag("miniatura"),
                )
            }
            previews && kind == FileKind.APK -> ApkThumb(item.path)
            else ->
                Icon(
                    Kinds.icon(kind),
                    contentDescription = null,
                    tint =
                        if (kind != FileKind.FOLDER) Kinds.color(kind)
                        else
                            when (LocalFolderStyle.current) {
                                FolderStyle.CLASSIC -> Kinds.color(kind)
                                FolderStyle.ACCENT -> MaterialTheme.colorScheme.primary
                                FolderStyle.GREY -> Color(0xFF8A8F94)
                            },
                    modifier = Modifier.fillMaxSize(0.9f),
                )
        }
    }
}

@Composable
private fun ApkThumb(path: String) {
    val ctx = LocalContext.current
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) { runCatching { ApkIcons.load(ctx, path) }.getOrNull() }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize())
    } else {
        Icon(
            Icons.Filled.Android,
            contentDescription = null,
            tint = Kinds.color(FileKind.APK),
            modifier = Modifier.fillMaxSize(0.9f),
        )
    }
}

/** Barra de ruta tocable: Interno > DCIM > Camera */
@Composable
fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) { PathUtil.segments(path) }
    val state = rememberLazyListState()
    LaunchedEffect(path) { if (segments.isNotEmpty()) state.scrollToItem(segments.lastIndex) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(segments, key = { _, s -> s.path }) { i, seg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val last = i == segments.lastIndex
                Text(
                    seg.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color =
                        if (last) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigate(seg.path) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Botón con ícono y texto para la barra inferior de acciones. */
@Composable
fun BarAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 4.dp),
    )
}
