package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.*
import androidx.media3.transformer.*
import java.io.*
import kotlinx.coroutines.*

/** Native Media3 export. Originals are untouched; only a completed export is committed. */
data class VideoEdit(
    val startMs: Long = 0,
    val endMs: Long = Long.MAX_VALUE,
    val rotation: Float = 0f,
    val speed: Float = 1f,
    val crop: Boolean = false,
    val caption: String = "",
    val music: String = "",
    val join: List<String> = emptyList(),
    val mute: Boolean = false,
    val image: String = "",
    val subtitles: String = "",
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
    val backgroundColor: Int = android.graphics.Color.BLACK,
    val backgroundImage: String = "",
    /** Intro y outro: texto, imagen o ambos; vacíos si no hay. */
    val introText: String = "",
    val introImage: String = "",
    val outroText: String = "",
    val outroImage: String = "",
    val cardColor: Int = android.graphics.Color.BLACK,
    val cardMs: Long = 3000
) {
    val hasIntro get() = introText.isNotBlank() || introImage.isNotBlank()
    val hasOutro get() = outroText.isNotBlank() || outroImage.isNotBlank()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoTools {
    suspend fun export(
        ctx: Context,
        source: File,
        target: File,
        edit: VideoEdit,
        report: (OpProgress) -> Unit
    ) {
        val part = File.createTempFile(".oi-video-", ".mp4", target.parentFile)
        part.delete()
        val cardFiles = ArrayList<File>()
        try {
            val cues =
                if (edit.subtitles.isBlank()) emptyList()
                else {
                    val file = File(edit.subtitles)
                    if (file.length() > 2 * 1024 * 1024)
                        throw IOException("Subtítulos demasiado grandes")
                    Subtitles.parseSrt(file.readText(Charsets.UTF_8))
                }
            // Intro y outro: se dibujan con el tamaño final del vídeo y se ponen antes y después.
            var size: Pair<Int, Int>? = null
            var intro: File? = null
            var outro: File? = null
            var window = 0L to Long.MAX_VALUE
            if (edit.hasIntro || edit.hasOutro) {
                withContext(Dispatchers.IO) {
                    val (width, height, metadataRotation) = frameInfo(source)
                    val (w, h) =
                        VideoCards.outputSize(
                            width,
                            height,
                            metadataRotation,
                            edit.rotation,
                            edit.crop,
                            edit.canvasWidth,
                            edit.canvasHeight)
                    size = w to h
                    val cacheDir = File(ctx.cacheDir, "video-cards").apply { mkdirs() }
                    fun card(name: String, text: String, image: String) =
                        File.createTempFile("$name-", ".png", cacheDir).also {
                            cardFiles += it
                            VideoCards.render(it, w, h, text, image, edit.cardColor)
                        }
                    if (edit.hasIntro) intro = card("intro", edit.introText, edit.introImage)
                    if (edit.hasOutro) outro = card("outro", edit.outroText, edit.outroImage)
                    val first = durationMs(source)?.let { minOf(it, edit.endMs) - edit.startMs }
                    window =
                        VideoCards.videoWindowUs(
                            if (edit.hasIntro) edit.cardMs else 0,
                            listOf(first) + edit.join.map { durationMs(File(it)) },
                            edit.speed)
                }
            }
            val visible = { timeUs: Long -> timeUs >= window.first && timeUs < window.second }
            withContext(Dispatchers.Main.immediate) {
                val effects = ArrayList<Effect>()
                val canvas = ArrayList<Effect>()
                // Con intro u outro, todo se ajusta al mismo cuadro (sin lienzo, el del vídeo editado).
                if (size != null && (edit.canvasWidth <= 0 || edit.canvasHeight <= 0))
                    canvas +=
                        Presentation.createForWidthAndHeight(
                            size!!.first, size!!.second, Presentation.LAYOUT_SCALE_TO_FIT)
                if (edit.rotation != 0f)
                    effects +=
                        ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(edit.rotation)
                            .build()
                if (edit.crop) effects += Crop(-0.75f, 0.75f, -0.75f, 0.75f)
                if (edit.speed != 1f) effects += SpeedChangeEffect(edit.speed)
                if (edit.canvasWidth > 0 && edit.canvasHeight > 0)
                    canvas +=
                        VideoCanvasEffect(
                            edit.canvasWidth,
                            edit.canvasHeight,
                            edit.backgroundColor,
                            edit.backgroundImage)
                val overlays = ArrayList<TextureOverlay>()
                if (edit.caption.isNotBlank()) overlays += VideoOverlays.caption(edit.caption, visible)
                if (cues.isNotEmpty()) overlays += VideoOverlays.subtitles(cues, window.first, visible)
                if (edit.image.isNotBlank()) overlays += VideoOverlays.image(edit.image, visible)
                if (overlays.isNotEmpty()) canvas += OverlayEffect(overlays)
                val audio =
                    if (edit.speed != 1f)
                        listOf(SonicAudioProcessor().apply { setSpeed(edit.speed) })
                    else emptyList()
                val clip =
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(edit.startMs)
                        .apply { if (edit.endMs != Long.MAX_VALUE) setEndPositionMs(edit.endMs) }
                        .build()
                val first =
                    EditedMediaItem.Builder(
                            MediaItem.Builder()
                                .setUri(Uri.fromFile(source))
                                .setClippingConfiguration(clip)
                                .build())
                        .setRemoveAudio(edit.mute)
                        .setEffects(Effects(audio, effects))
                        .build()
                val items =
                    listOf(first) +
                        edit.join.map { path ->
                            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(path))))
                                .setRemoveAudio(edit.mute)
                                .setEffects(Effects(audio, effects))
                                .build()
                        }
                fun card(file: File?) =
                    file?.let {
                        EditedMediaItem.Builder(
                                MediaItem.Builder()
                                    .setUri(Uri.fromFile(it))
                                    .setMimeType(MimeTypes.IMAGE_PNG)
                                    .setImageDurationMs(edit.cardMs)
                                    .build())
                            .setFrameRate(30)
                            .build()
                    }
                val sequences = ArrayList<EditedMediaItemSequence>()
                sequences += EditedMediaItemSequence(listOfNotNull(card(intro)) + items + listOfNotNull(card(outro)))
                if (edit.music.isNotBlank())
                    sequences +=
                        EditedMediaItemSequence(
                            listOf(
                                EditedMediaItem.Builder(
                                        MediaItem.fromUri(Uri.fromFile(File(edit.music))))
                                    .setRemoveVideo(true)
                                    .build()))
                val composition =
                    Composition.Builder(sequences)
                        .setEffects(Effects(emptyList(), canvas))
                        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                        // Las imágenes no tienen audio: se rellena con silencio para que el del vídeo siga.
                        .experimentalSetForceAudioTrack(intro != null || outro != null)
                        .build()
                val done = CompletableDeferred<Unit>()
                val transformer =
                    Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    result: ExportResult
                                ) {
                                    done.complete(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    result: ExportResult,
                                    exception: ExportException
                                ) {
                                    done.completeExceptionally(exception)
                                }
                            })
                        .build()
                transformer.start(composition, part.path)
                try {
                    val p = ProgressHolder()
                    while (!done.isCompleted) {
                        if (transformer.getProgress(p) == Transformer.PROGRESS_STATE_AVAILABLE)
                            report(
                                OpProgress(
                                    "Exportando video", source.name, p.progress.toLong(), 100))
                        delay(250)
                    }
                    done.await()
                } finally {
                    transformer.cancel()
                }
            }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(part, target, false)
        } finally {
            part.delete()
            cardFiles.forEach { it.delete() }
        }
    }

    /** Ancho, alto y rotación guardada del vídeo. */
    private fun frameInfo(file: File): Triple<Int, Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            return Triple(
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
        } finally {
            retriever.release()
        }
    }

    /** Duración en milisegundos, o null si no se puede leer. */
    private fun durationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Small animated GIFs, uniform RGB palette, bounded duration/resolution and streamed frames.
     */
    suspend fun gif(
        source: File,
        target: File,
        startMs: Long,
        endMs: Long,
        report: (OpProgress) -> Unit
    ) {
        val duration = (endMs - startMs).coerceAtMost(10000)
        if (duration <= 0) throw IOException("Elige un intervalo de hasta 10 segundos")
        val temp = File.createTempFile(".oi-gif-", ".tmp", target.parentFile)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.path)
            val frame =
                retriever.getFrameAtTime(startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IOException("No se pudo leer el video")
            val width = frame.width.coerceAtMost(480)
            val height = (frame.height.toDouble() * width / frame.width).toInt().coerceAtLeast(1)
            frame.recycle()
            temp.outputStream().buffered().use { out ->
                val encoder = GifWriter(out, width, height)
                encoder.begin()
                val count = (duration / 125).toInt().coerceAtLeast(1)
                repeat(count) { i ->
                    currentCoroutineContext().ensureActive()
                    val raw =
                        if (android.os.Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(
                                (startMs + i * 125) * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                width,
                                height)
                        else
                            retriever.getFrameAtTime(
                                (startMs + i * 125) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    val original = raw ?: throw IOException("No se pudo leer un fotograma")
                    val bitmap =
                        if (original.width == width && original.height == height) original
                        else
                            android.graphics.Bitmap.createScaledBitmap(
                                    original, width, height, true)
                                .also { original.recycle() }
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()
                    encoder.frame(pixels, 13)
                    report(OpProgress("Creando GIF", source.name, (i + 1).toLong(), count.toLong()))
                }
                encoder.end()
            }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, target, false)
        } finally {
            retriever.release()
            temp.delete()
        }
    }
}

internal class GifWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int
) {
    private fun short(value: Int) {
        out.write(value and 255)
        out.write(value ushr 8 and 255)
    }

    fun begin() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width)
        short(height)
        out.write(247)
        out.write(0)
        out.write(0)
        for (i in 0..255) {
            out.write((i ushr 5) * 255 / 7)
            out.write((i ushr 2 and 7) * 255 / 7)
            out.write((i and 3) * 255 / 3)
        }
        out.write(byteArrayOf(33, -1, 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun frame(pixels: IntArray, delay: Int) {
        out.write(byteArrayOf(33, -7, 4, 8))
        short(delay)
        out.write(0)
        out.write(0)
        out.write(44)
        short(0)
        short(0)
        short(width)
        short(height)
        out.write(0)
        out.write(8)
        val data = ByteArrayOutputStream()
        var bits = 0
        var pending = 0
        fun code(value: Int) {
            pending = pending or (value shl bits)
            bits += 9
            while (bits >= 8) {
                data.write(pending and 255)
                pending = pending ushr 8
                bits -= 8
            }
        }
        code(256)
        for (i in pixels.indices) {
            if (i > 0 && i % 200 == 0) code(256)
            val c = pixels[i]
            code((c ushr 16 and 224) or (c ushr 11 and 28) or (c ushr 6 and 3))
        }
        code(257)
        if (bits > 0) data.write(pending and 255)
        val bytes = data.toByteArray()
        var index = 0
        while (index < bytes.size) {
            val n = (bytes.size - index).coerceAtMost(255)
            out.write(n)
            out.write(bytes, index, n)
            index += n
        }
        out.write(0)
    }

    fun end() {
        out.write(59)
    }
}
