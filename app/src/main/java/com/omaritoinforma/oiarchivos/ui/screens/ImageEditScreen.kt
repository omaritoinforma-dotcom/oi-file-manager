@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Grab {
    data object Move : Grab

    data class Corner(val corner: CropCorner) : Grab
}

/** Dónde se dibuja la imagen dentro del lienzo: entera, centrada y sin deformarse. */
private fun fitRect(canvas: Size, imageWidth: Int, imageHeight: Int): Rect {
    val scale = minOf(canvas.width / imageWidth, canvas.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (canvas.width - w) / 2f
    val top = (canvas.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun cropRect(image: Rect, box: CropBox) =
    Rect(
        image.left + box.left * image.width,
        image.top + box.top * image.height,
        image.left + box.right * image.width,
        image.top + box.bottom * image.height)

/**
 * Girar, voltear y recortar una imagen (el editor de imagen de ES): recuadro que se arrastra por el
 * centro o por las esquinas, proporciones fijas y guardado como copia o sobre la original.
 */
@Suppress("DEPRECATION")
@Composable
fun ImageEditScreen(vm: MainViewModel, path: String) {
    val file = remember(path) { File(path) }
    var preview by remember(path) { mutableStateOf<Bitmap?>(null) }
    var dims by remember(path) { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var turns by remember(path) { mutableIntStateOf(0) }
    var flip by remember(path) { mutableStateOf(false) }
    var aspect by remember(path) { mutableStateOf(CropAspect.FREE) }
    var crop by remember(path) { mutableStateOf(CropBox.FULL) }
    var confirmReplace by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        runCatching {
                withContext(Dispatchers.IO) {
                    ImageTools.open(file, ImageTools.PREVIEW_PIXELS) to ImageTools.dimensions(file)
                }
            }
            .onSuccess {
                preview = it.first
                dims = it.second
            }
            .onFailure { error = it.message ?: tr("No se pudo abrir la imagen") }
    }
    val shown = remember(preview, turns, flip) { preview?.let { ImageTools.transform(it, turns, flip, CropBox.FULL) } }
    // Al girar o voltear, el recuadro vuelve a empezar con la proporción elegida.
    LaunchedEffect(shown) { shown?.let { crop = ImageCrop.fit(aspect, it.width, it.height) } }
    val latestCrop by rememberUpdatedState(crop)
    val latestAspect by rememberUpdatedState(aspect)

    fun save(replace: Boolean) {
        val t = turns
        val f = flip
        val c = crop
        vm.runTask(tr("Guardando imagen")) {
            val out = ImageTools.save(file, t, f, c, replace)
            OperationResult(if (replace) tr("Imagen guardada") else tr("Guardada como «{0}»", out.name), listOf(out))
        }
        vm.back()
    }

    ToolPage(tr("Editar imagen"), vm) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val image = shown
            if (image == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(error ?: tr("Abriendo la imagen…"))
                }
            } else {
                Canvas(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .pointerInput(image) {
                            var grab: Grab? = null
                            detectDragGestures(
                                onDragStart = { start ->
                                    val area = fitRect(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                                    val box = cropRect(area, latestCrop)
                                    val reach = 32.dp.toPx()
                                    val corners =
                                        mapOf(
                                            CropCorner.TOP_LEFT to Offset(box.left, box.top),
                                            CropCorner.TOP_RIGHT to Offset(box.right, box.top),
                                            CropCorner.BOTTOM_LEFT to Offset(box.left, box.bottom),
                                            CropCorner.BOTTOM_RIGHT to Offset(box.right, box.bottom))
                                    val nearest = corners.minByOrNull { (it.value - start).getDistance() }
                                    grab =
                                        when {
                                            nearest != null && (nearest.value - start).getDistance() <= reach ->
                                                Grab.Corner(nearest.key)
                                            box.contains(start) -> Grab.Move
                                            else -> null
                                        }
                                },
                                onDragEnd = { grab = null },
                                onDragCancel = { grab = null },
                                onDrag = { change, delta ->
                                    val g = grab ?: return@detectDragGestures
                                    change.consume()
                                    val area = fitRect(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                                    val dx = delta.x / area.width
                                    val dy = delta.y / area.height
                                    crop =
                                        when (g) {
                                            Grab.Move -> ImageCrop.move(latestCrop, dx, dy)
                                            is Grab.Corner ->
                                                ImageCrop.resize(
                                                    latestCrop, g.corner, dx, dy, latestAspect, image.width, image.height)
                                        }
                                })
                        }) {
                        val area = fitRect(this.size, image.width, image.height)
                        drawImage(
                            image.asImageBitmap(),
                            dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()),
                            dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()))
                        val box = cropRect(area, crop)
                        val dim = Color.Black.copy(alpha = 0.6f)
                        drawRect(dim, Offset(area.left, area.top), Size(area.width, box.top - area.top))
                        drawRect(dim, Offset(area.left, box.bottom), Size(area.width, area.bottom - box.bottom))
                        drawRect(dim, Offset(area.left, box.top), Size(box.left - area.left, box.height))
                        drawRect(dim, Offset(box.right, box.top), Size(area.right - box.right, box.height))
                        drawRect(Color.White, box.topLeft, box.size, style = Stroke(2.dp.toPx()))
                        val guide = Color.White.copy(alpha = 0.45f)
                        for (i in 1..2) {
                            val x = box.left + box.width * i / 3f
                            val y = box.top + box.height * i / 3f
                            drawLine(guide, Offset(x, box.top), Offset(x, box.bottom), 1.dp.toPx())
                            drawLine(guide, Offset(box.left, y), Offset(box.right, y), 1.dp.toPx())
                        }
                        for (corner in listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight))
                            drawCircle(Color.White, 7.dp.toPx(), corner)
                    }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                val real = dims?.let { (w, h) -> if (turns % 2 != 0) h to w else w to h }
                if (real != null) {
                    val px = ImageCrop.pixels(crop, real.first, real.second)
                    Text(
                        tr("Recorte: {0} × {1} px de {2} × {3}", px.width, px.height, real.first, real.second),
                        style = MaterialTheme.typography.labelLarge)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CropAspect.entries.forEach { option ->
                        FilterChip(
                            selected = option == aspect,
                            onClick = {
                                aspect = option
                                shown?.let { crop = ImageCrop.fit(option, it.width, it.height) }
                            },
                            label = { Text(option.label) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { turns = (turns + 3) % 4 }) {
                            Icon(Icons.Filled.RotateLeft, tr("Girar a la izquierda"))
                        }
                        IconButton(onClick = { turns = (turns + 1) % 4 }) {
                            Icon(Icons.Filled.RotateRight, tr("Girar a la derecha"))
                        }
                        IconButton(onClick = { flip = !flip }) { Icon(Icons.Filled.Flip, tr("Voltear")) }
                    }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(false) }, enabled = shown != null, modifier = Modifier.weight(1f)) {
                        Text(tr("Guardar copia"))
                    }
                    OutlinedButton(
                        onClick = { confirmReplace = true },
                        enabled = shown != null && ImageTools.canReplace(file),
                        modifier = Modifier.weight(1f)) {
                            Text(tr("Reemplazar"))
                        }
                }
            }
        }
    }
    if (confirmReplace)
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(tr("¿Reemplazar la imagen?")) },
            text = { Text(tr("«{0}» se sustituirá por la versión editada y no se podrá recuperar.", file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        save(true)
                    }) {
                        Text(tr("Reemplazar"))
                    }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(tr("Cancelar")) } })
}
