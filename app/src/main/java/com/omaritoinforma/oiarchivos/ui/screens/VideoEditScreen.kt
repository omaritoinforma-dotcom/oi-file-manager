package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun VideoEditScreen(vm: MainViewModel, path: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember(path) { File(path) }
    val assetStore = remember(path) { VideoAssetStore(ctx) }
    var assets by remember(path) { mutableStateOf(emptyMap<VideoAssetKind, List<VideoAsset>>()) }
    var selectedKind by remember { mutableStateOf(VideoAssetKind.MUSIC) }
    var importing by remember { mutableStateOf(false) }
    var start by remember(path) { mutableStateOf("0") }
    var end by remember(path) { mutableStateOf("") }
    var rotation by remember(path) { mutableFloatStateOf(0f) }
    var speed by remember(path) { mutableFloatStateOf(1f) }
    var crop by remember(path) { mutableStateOf(false) }
    var mute by remember(path) { mutableStateOf(false) }
    var caption by remember(path) { mutableStateOf("") }
    var color by remember(path) { mutableStateOf("#202020") }
    var canvas by remember(path) { mutableIntStateOf(0) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var preview by remember(path) { mutableStateOf<File?>(null) }
    var previewJob by remember(path) { mutableStateOf<Job?>(null) }
    var previewProgress by remember(path) { mutableStateOf("") }
    var previewKey by remember(path) { mutableStateOf<List<Any?>?>(null) }
    val settingsKey =
        listOf(start, end, rotation, speed, crop, mute, caption, color, canvas, assets)
    DisposableEffect(assetStore) {
        onDispose {
            previewJob?.cancel()
            preview?.delete()
            assetStore.close()
        }
    }
    fun importFiles(kind: VideoAssetKind, uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing = true
        error = null
        scope.launch {
            try {
                val chosen =
                    withContext(Dispatchers.IO) {
                        uris.map { uri -> assetStore.importFile(uri, kind) }
                    }
                assets = assets + (kind to chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo abrir el archivo seleccionado"
            } finally {
                importing = false
            }
        }
    }
    val singlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFiles(selectedKind, listOf(it)) }
        }
    val joinPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            importFiles(VideoAssetKind.JOIN, uris)
        }
    fun pick(kind: VideoAssetKind) {
        selectedKind = kind
        if (kind == VideoAssetKind.JOIN) joinPicker.launch(arrayOf("video/*"))
        else singlePicker.launch(kind.mimeTypes)
    }
    fun asset(kind: VideoAssetKind) = assets[kind]?.firstOrNull()?.file?.path.orEmpty()
    fun edit(): VideoEdit? {
        val lo = start.toDoubleOrNull()
        val hi = if (end.isBlank()) null else end.toDoubleOrNull()
        if (
            lo == null ||
                !lo.isFinite() ||
                lo < 0 ||
                lo > Long.MAX_VALUE / 1000.0 ||
                (end.isNotBlank() &&
                    (hi == null || !hi.isFinite() || hi <= lo || hi > Long.MAX_VALUE / 1000.0))
        ) {
            error = "Revisa el intervalo de tiempo"
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
            startMs = (lo * 1000).toLong(),
            endMs = hi?.let { (it * 1000).toLong() } ?: Long.MAX_VALUE,
            rotation = rotation,
            speed = speed,
            crop = crop,
            caption = caption,
            music = asset(VideoAssetKind.MUSIC),
            join = assets[VideoAssetKind.JOIN].orEmpty().map { it.file.path },
            mute = mute,
            image = asset(VideoAssetKind.IMAGE),
            subtitles = asset(VideoAssetKind.SUBTITLES),
            canvasWidth = size.first,
            canvasHeight = size.second,
            backgroundColor = backgroundColor,
            backgroundImage = asset(VideoAssetKind.BACKGROUND),
        )
    }
    fun export(gif: Boolean) {
        val e = edit() ?: return
        error = null
        val target =
            FileOps.uniqueName(
                source.parentFile!!,
                source.nameWithoutExtension + if (gif) "-editado.gif" else "-editado.mp4",
            )
        val lease = assetStore.acquire()
        val accepted =
            vm.runTask(if (gif) "Video a GIF" else "Editando video") { report ->
                try {
                    if (gif) VideoTools.gif(ctx, source, target, e, report)
                    else VideoTools.export(ctx, source, target, e, report)
                    OperationResult("Creado ${target.name}", listOf(target))
                } finally {
                    lease.close()
                }
            }
        if (!accepted) lease.close()
    }
    ToolPage("Editar ${source.name}", vm) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Exporta una copia MP4 o GIF con tus ajustes. El video original se conserva.")
            }
            item {
                OutlinedTextField(
                    start,
                    { start = it },
                    label = { Text("Inicio en segundos") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    end,
                    { end = it },
                    label = { Text("Fin en segundos (vacío: hasta el final)") },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        }
                    ) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            for (kind in
                listOf(
                    VideoAssetKind.MUSIC,
                    VideoAssetKind.JOIN,
                    VideoAssetKind.IMAGE,
                    VideoAssetKind.SUBTITLES,
                )) {
                item {
                    VideoAssetPicker(
                        kind.label,
                        assets[kind].orEmpty(),
                        !importing,
                        { pick(kind) },
                        { assets = assets - kind },
                    )
                    if (kind == VideoAssetKind.MUSIC)
                        Text(
                            "La música se repite o recorta hasta el final del video.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (kind == VideoAssetKind.SUBTITLES)
                        Text(
                            "SRT de hasta 2 MB. Los tiempos corresponden al video exportado desde 00:00.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
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
                            }
                    )
                }
            }
            if (canvas != 0) {
                item {
                    OutlinedTextField(
                        color,
                        { color = it },
                        label = { Text("Color de fondo (#RRGGBB)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    VideoAssetPicker(
                        VideoAssetKind.BACKGROUND.label,
                        assets[VideoAssetKind.BACKGROUND].orEmpty(),
                        !importing,
                        { pick(VideoAssetKind.BACKGROUND) },
                        { assets = assets - VideoAssetKind.BACKGROUND },
                    )
                }
            }
            if (importing)
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Abriendo archivos seleccionados…")
                }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                OutlinedButton(
                    enabled = !importing && previewJob?.isActive != true,
                    onClick = {
                        val e = edit() ?: return@OutlinedButton
                        val key = settingsKey
                        val lease = assetStore.acquire()
                        error = null
                        previewJob =
                            scope.launch {
                                val target =
                                    File.createTempFile(".oi-preview-", ".mp4", ctx.cacheDir)
                                        .apply { delete() }
                                try {
                                    VideoTools.preview(ctx, source, target, e) { progress ->
                                        previewProgress = "${progress.doneBytes}%"
                                    }
                                    preview?.delete()
                                    preview = target
                                    previewKey = key
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "No se pudo previsualizar el video"
                                } finally {
                                    if (preview != target) target.delete()
                                    previewProgress = ""
                                    lease.close()
                                }
                            }
                    },
                ) {
                    Text("Previsualizar ajustes (2 s del primer clip)")
                }
                if (previewJob?.isActive == true) {
                    Text("Preparando vista previa $previewProgress")
                    TextButton(onClick = { previewJob?.cancel() }) { Text("Cancelar vista previa") }
                }
            }
            preview?.let { file ->
                item {
                    if (previewKey != settingsKey)
                        Text(
                            "Los ajustes cambiaron; actualiza la vista previa.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    VideoEditPreview(file)
                }
            }
            item {
                Button(
                    enabled = !importing && previewJob?.isActive != true,
                    onClick = { export(false) },
                ) {
                    Text("Exportar MP4")
                }
            }
            item {
                Text(
                    "GIF: máximo 10 s después de aplicar velocidad y unión, lado mayor de 480 px, 8 fps y sin audio.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    enabled = !importing && previewJob?.isActive != true,
                    onClick = { export(true) },
                ) {
                    Text("Crear GIF con los ajustes")
                }
            }
        }
    }
}

@Composable
private fun VideoAssetPicker(
    label: String,
    selected: List<VideoAsset>,
    enabled: Boolean,
    pick: () -> Unit,
    clear: () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        selected.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
        Row {
            OutlinedButton(enabled = enabled, onClick = pick) {
                Text(if (selected.isEmpty()) "Elegir archivos" else "Cambiar archivos")
            }
            if (selected.isNotEmpty())
                TextButton(enabled = enabled, onClick = clear) { Text("Quitar") }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun VideoEditPreview(file: File) {
    val ctx = LocalContext.current
    val player =
        remember(file) {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(240.dp),
    )
}

private enum class VideoAssetKind(
    val label: String,
    val mimeTypes: Array<String>,
    val maxBytes: Long,
) {
    MUSIC("Música adicional", arrayOf("audio/*"), 512L * 1024 * 1024),
    JOIN("Videos para unir (en el orden seleccionado)", arrayOf("video/*"), 512L * 1024 * 1024),
    IMAGE("Imagen superpuesta", arrayOf("image/*"), 20L * 1024 * 1024),
    SUBTITLES(
        "Subtítulos SRT",
        arrayOf("application/x-subrip", "text/*", "application/octet-stream"),
        2L * 1024 * 1024,
    ),
    BACKGROUND("Imagen de fondo", arrayOf("image/*"), 20L * 1024 * 1024),
}

private data class VideoAsset(val file: File, val name: String)

/** Screen-owned selections stay alive while an accepted service export holds a lease. */
private class VideoAssetStore(private val context: Context) : AutoCloseable {
    private val folder =
        File(context.cacheDir, "video-assets-${java.util.UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("No se pudo preparar el editor")
        }
    private var leases = 0
    private var closed = false

    @Synchronized
    fun acquire(): AutoCloseable {
        check(!closed) { "El editor ya está cerrado" }
        leases++
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true))
                synchronized(this) {
                    leases--
                    if (closed && leases == 0) folder.deleteRecursively()
                }
        }
    }

    suspend fun importFile(uri: Uri, kind: VideoAssetKind): VideoAsset {
        val lease = acquire()
        var file: File? = null
        try {
            val name =
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.take(160) ?: "Archivo seleccionado"
            val selectedFile = File.createTempFile("asset-", ".bin", folder)
            file = selectedFile
            val budget =
                minOf(
                    kind.maxBytes,
                    1024L * 1024 * 1024 - folder.listFiles().orEmpty().sumOf { it.length() },
                )
            var copied = 0L
            (context.contentResolver.openInputStream(uri)
                    ?: throw IOException("No se pudo leer «$name»"))
                .use { input ->
                    selectedFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > budget)
                                throw IOException(
                                    "«$name» es demasiado grande (máximo ${kind.maxBytes / (1024 * 1024)} MB por archivo y 1 GB en el editor)"
                                )
                            output.write(buffer, 0, read)
                        }
                    }
                }
            if (copied == 0L) throw IOException("«$name» está vacío")
            return VideoAsset(selectedFile, name)
        } catch (e: Exception) {
            file?.delete()
            throw e
        } finally {
            lease.close()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        if (leases == 0) folder.deleteRecursively()
    }
}
