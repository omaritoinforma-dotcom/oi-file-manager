@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import com.omaritoinforma.oiarchivos.util.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(vm: MainViewModel, path: String) {
    val file = remember(path) { File(path) }
    val kind = Kinds.ofExt(file.extension.lowercase())
    ToolPage(
        file.name,
        vm,
        actions = {
            if (kind == FileKind.VIDEO)
                TextButton(onClick = { vm.goTo(Screen.VideoEdit(path)) }) { Text("Editar") }
            TextButton(onClick = { Opener.share(vm.getApplication(), listOf(file)) }) {
                Text("Compartir")
            }
        }) { pad ->
            when (kind) {
                FileKind.IMAGE -> ImageGallery(path, Modifier.fillMaxSize().padding(pad))
                FileKind.PDF -> PdfViewer(file, Modifier.fillMaxSize().padding(pad))
                else -> MediaViewer(file, Modifier.fillMaxSize().padding(pad))
            }
        }
}

@Composable
private fun ImageGallery(path: String, modifier: Modifier) {
    val images =
        remember(path) {
            File(path)
                .parentFile
                ?.listFiles()
                ?.filter { Kinds.ofExt(it.extension.lowercase()) == FileKind.IMAGE }
                ?.sortedWith { a, b ->
                    com.omaritoinforma.oiarchivos.data.NaturalOrder.compare(a.name, b.name)
                }
                .orEmpty()
        }
    var index by
        remember(path) {
            mutableIntStateOf(images.indexOfFirst { it.path == path }.coerceAtLeast(0))
        }
    var zoom by remember(index) { mutableFloatStateOf(1f) }
    var x by remember(index) { mutableFloatStateOf(0f) }
    var y by remember(index) { mutableFloatStateOf(0f) }
    Column(modifier) {
        AsyncImage(
            model = images.getOrNull(index) ?: File(path),
            contentDescription = "Imagen",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .pointerInput(index) {
                        detectTransformGestures { _, pan, scale, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 8f)
                            if (zoom > 1) {
                                x += pan.x
                                y += pan.y
                            } else {
                                x = 0f
                                y = 0f
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = x
                        translationY = y
                    })
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { index-- }, enabled = index > 0) { Text("Anterior") }
                Text("${index+1} / ${images.size.coerceAtLeast(1)}")
                TextButton(onClick = { index++ }, enabled = index < images.lastIndex) {
                    Text("Siguiente")
                }
            }
        TextButton(
            onClick = {
                zoom = 1f
                x = 0f
                y = 0f
            }) {
                Text("Restablecer zoom")
            }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun MediaViewer(file: File, modifier: Modifier) {
    val ctx = LocalContext.current
    val audio = Kinds.ofExt(file.extension.lowercase()) == FileKind.AUDIO
    val files =
        remember(file) {
            file.parentFile
                ?.listFiles()
                ?.filter {
                    Kinds.ofExt(it.extension.lowercase()) ==
                        if (audio) FileKind.AUDIO else FileKind.VIDEO
                }
                ?.sortedBy { it.name }
                .orEmpty()
                .ifEmpty { listOf(file) }
        }
    var player by remember(file) { mutableStateOf<Player?>(null) }
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    DisposableEffect(file, audio) {
        var disposed = false
        val future =
            if (audio)
                MediaController.Builder(
                        ctx,
                        SessionToken(
                            ctx,
                            ComponentName(
                                ctx,
                                com.omaritoinforma.oiarchivos.data.AudioPlaybackService::class
                                    .java)))
                    .buildAsync()
            else null
        fun prepare(p: Player) {
            if (p.currentMediaItem?.mediaId != file.path || p.mediaItemCount == 0) {
                p.setMediaItems(
                    files.map {
                        MediaItem.Builder()
                            .setUri(Uri.fromFile(it))
                            .setMediaId(it.path)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build())
                            .build()
                    },
                    files.indexOf(file).coerceAtLeast(0),
                    0)
                p.prepare()
            }
            p.playWhenReady = true
            player = p
        }
        if (future != null)
            future.addListener(
                {
                    if (!disposed)
                        runCatching { prepare(future.get()) }
                            .onFailure {
                                playbackError = it.message ?: "No se pudo reproducir el audio"
                            }
                },
                androidx.core.content.ContextCompat.getMainExecutor(ctx))
        else prepare(ExoPlayer.Builder(ctx).build())
        onDispose {
            disposed = true
            if (future != null) MediaController.releaseFuture(future) else player?.release()
            player = null
        }
    }
    var title by remember { mutableStateOf(file.name) }
    var shuffle by remember(player) { mutableStateOf(player?.shuffleModeEnabled ?: false) }
    var repeat by
        remember(player) { mutableIntStateOf(player?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    DisposableEffect(player) {
        val currentPlayer = player
        val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    title = File(item?.mediaId ?: file.path).name
                }
            }
        currentPlayer?.addListener(listener)
        onDispose { currentPlayer?.removeListener(listener) }
    }
    Column(modifier) {
        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(title, Modifier.padding(16.dp))
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                }
            },
            update = { it.player = player },
            modifier = Modifier.weight(1f).fillMaxWidth())
        Row {
            FilterChip(
                shuffle,
                onClick = {
                    shuffle = !shuffle
                    player?.shuffleModeEnabled = shuffle
                },
                label = { Text("Aleatorio") })
            Spacer(Modifier.width(12.dp))
            FilterChip(
                repeat != Player.REPEAT_MODE_OFF,
                onClick = {
                    repeat = (repeat + 1) % 3
                    player?.repeatMode = repeat
                },
                label = {
                    Text(
                        when (repeat) {
                            Player.REPEAT_MODE_ONE -> "Repetir uno"
                            Player.REPEAT_MODE_ALL -> "Repetir todos"
                            else -> "Sin repetición"
                        })
                })
        }
    }
}

@Composable
private fun PdfViewer(file: File, modifier: Modifier) {
    var page by remember(file) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var image by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, page) {
        image = null
        error = null
        withContext(Dispatchers.IO) {
            runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { pdf ->
                            count = pdf.pageCount
                            pdf.openPage(page).use { p ->
                                val width = 1400
                                Bitmap.createBitmap(
                                        width,
                                        (width.toDouble() * p.height / p.width)
                                            .toInt()
                                            .coerceIn(1, 4000),
                                        Bitmap.Config.ARGB_8888)
                                    .apply {
                                        eraseColor(android.graphics.Color.WHITE)
                                        p.render(
                                            this,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                            }
                        }
                    }
                }
                .onSuccess { image = it }
                .onFailure { error = it.message }
        }
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            image?.let {
                Image(
                    it.asImageBitmap(),
                    "Página ${page+1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            } ?: if (error != null) Text(error!!) else CircularProgressIndicator()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Anterior") }
            Text("${page+1} / $count", Modifier.padding(top = 12.dp))
            TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text("Siguiente") }
        }
    }
}
