package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File

@Composable
fun VideoEditScreen(vm: MainViewModel, path: String) {
    val ctx = LocalContext.current
    val source = remember(path) { File(path) }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("") }
    var rotation by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var crop by remember { mutableStateOf(false) }
    var mute by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var music by remember { mutableStateOf("") }
    var join by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var subtitles by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#202020") }
    var canvas by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    fun edit(): VideoEdit? {
        val lo = start.toDoubleOrNull()
        val hi = if (end.isBlank()) null else end.toDoubleOrNull()
        if (lo == null ||
            !lo.isFinite() ||
            lo < 0 ||
            (end.isNotBlank() && (hi == null || !hi.isFinite() || hi <= lo))) {
            error = "Revisa el intervalo de tiempo"
            return null
        }
        val additional = join.lines().map { it.trim() }.filter { it.isNotBlank() }
        if ((additional + listOf(music, image, subtitles, background).filter { it.isNotBlank() })
            .any { !File(it).isFile }) {
            error = "Revisa los archivos adicionales"
            return null
        }
        val backgroundColor =
            runCatching { android.graphics.Color.parseColor(color) }
                .getOrElse {
                    error = "Revisa el color de fondo, por ejemplo #202020"
                    return null
                }
        val size =
            when (canvas) {
                1 -> 1280 to 720
                2 -> 720 to 1280
                3 -> 1080 to 1080
                else -> 0 to 0
            }
        return VideoEdit(
            (lo * 1000).toLong(),
            hi?.let { (it * 1000).toLong() } ?: Long.MAX_VALUE,
            rotation,
            speed,
            crop,
            caption,
            music,
            additional,
            mute,
            image,
            subtitles,
            size.first,
            size.second,
            backgroundColor,
            background)
    }
    ToolPage("Editar ${source.name}", vm) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Exporta una copia MP4. El video original se conserva.") }
                item {
                    OutlinedTextField(
                        start,
                        { start = it },
                        label = { Text("Inicio en segundos") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        end,
                        { end = it },
                        label = { Text("Fin en segundos (vacío: hasta el final)") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row {
                        TextButton(onClick = { rotation = (rotation + 90) % 360 }) {
                            Text("Rotación: ${rotation.toInt()}°")
                        }
                        TextButton(
                            onClick = {
                                speed =
                                    when (speed) {
                                        0.5f -> 1f
                                        1f -> 1.5f
                                        1.5f -> 2f
                                        else -> 0.5f
                                    }
                            }) {
                                Text("Velocidad: ${speed}x")
                            }
                    }
                }
                item {
                    Row {
                        Checkbox(crop, { crop = it })
                        Text("Recortar 12,5 % de cada borde", Modifier.padding(top = 12.dp))
                    }
                }
                item {
                    Row {
                        Checkbox(mute, { mute = it })
                        Text("Quitar audio original", Modifier.padding(top = 12.dp))
                    }
                }
                item {
                    OutlinedTextField(
                        caption,
                        { caption = it },
                        label = { Text("Texto sobre el video") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        music,
                        { music = it },
                        label = { Text("Ruta de música para añadir (opcional)") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        join,
                        { join = it },
                        label = { Text("Rutas de videos a unir, una por línea") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        image,
                        { image = it },
                        label = { Text("Imagen superpuesta (ruta opcional)") },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(
                        subtitles,
                        { subtitles = it },
                        label = { Text("Archivo SRT (ruta opcional)") },
                        supportingText = {
                            Text("Los tiempos corresponden al video exportado, desde 00:00.")
                        },
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    TextButton(onClick = { canvas = (canvas + 1) % 4 }) {
                        Text(
                            "Lienzo: " +
                                when (canvas) {
                                    1 -> "Horizontal 1280 × 720"
                                    2 -> "Vertical 720 × 1280"
                                    3 -> "Cuadrado 1080 × 1080"
                                    else -> "Tamaño original"
                                })
                    }
                }
                if (canvas != 0) {
                    item {
                        OutlinedTextField(
                            color,
                            { color = it },
                            label = { Text("Color de fondo (#RRGGBB)") },
                            modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(
                            background,
                            { background = it },
                            label = { Text("Imagen de fondo (ruta opcional)") },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item {
                    Button(
                        onClick = {
                            val e = edit() ?: return@Button
                            val target =
                                FileOps.uniqueName(
                                    source.parentFile!!,
                                    source.nameWithoutExtension + "-editado.mp4")
                            vm.runTask("Editando video") { report ->
                                VideoTools.export(ctx, source, target, e, report)
                                OperationResult("Creado ${target.name}", listOf(target))
                            }
                        }) {
                            Text("Exportar MP4")
                        }
                }
                item {
                    TextButton(
                        onClick = {
                            val e = edit() ?: return@TextButton
                            if (e.endMs == Long.MAX_VALUE || e.endMs - e.startMs > 10000) {
                                error =
                                    "Para GIF, elige un fin de hasta 10 segundos después del inicio"
                                return@TextButton
                            }
                            val target =
                                FileOps.uniqueName(
                                    source.parentFile!!, source.nameWithoutExtension + ".gif")
                            vm.runTask("Video a GIF") { report ->
                                VideoTools.gif(source, target, e.startMs, e.endMs, report)
                                OperationResult("Creado ${target.name}", listOf(target))
                            }
                        }) {
                            Text("Crear GIF (máx. 10 s)")
                        }
                }
            }
    }
}
