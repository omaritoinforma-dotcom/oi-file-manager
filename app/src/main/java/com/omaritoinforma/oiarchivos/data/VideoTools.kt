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
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoTools {
    suspend fun export(
        ctx: Context,
        source: File,
        target: File,
        edit: VideoEdit,
        report: (OpProgress) -> Unit,
    ) {
        val prepared = prepare(source, edit)
        render(ctx, source, target, prepared.first, report)
    }

    private suspend fun render(
        ctx: Context,
        source: File,
        target: File,
        edit: VideoEdit,
        report: (OpProgress) -> Unit,
    ) {
        val part = File.createTempFile(".oi-video-", ".mp4", target.absoluteFile.parentFile)
        part.delete()
        try {
            val cues =
                if (edit.subtitles.isBlank()) emptyList()
                else {
                    val file = File(edit.subtitles)
                    if (file.length() > 2 * 1024 * 1024)
                        throw IOException("Subtítulos demasiado grandes")
                    Subtitles.parseSrt(file.readText(Charsets.UTF_8))
                }
            withContext(Dispatchers.Main.immediate) {
                val effects = ArrayList<Effect>()
                val canvas = ArrayList<Effect>()
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
                            edit.backgroundImage,
                        )
                val overlays = ArrayList<TextureOverlay>()
                if (edit.caption.isNotBlank()) overlays += VideoOverlays.caption(edit.caption)
                if (cues.isNotEmpty()) overlays += VideoOverlays.subtitles(cues)
                if (edit.image.isNotBlank()) overlays += VideoOverlays.image(edit.image)
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
                                .build()
                        )
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
                val sequences = ArrayList<EditedMediaItemSequence>()
                sequences += EditedMediaItemSequence(items)
                if (edit.music.isNotBlank())
                    sequences +=
                        EditedMediaItemSequence.Builder(
                                listOf(
                                    EditedMediaItem.Builder(
                                            MediaItem.fromUri(Uri.fromFile(File(edit.music)))
                                        )
                                        .setRemoveVideo(true)
                                        .build()
                                )
                            )
                            // Background audio follows the video timeline, even when the track
                            // is longer (or shorter) than the edited sequence.
                            .setIsLooping(true)
                            .build()
                val composition =
                    Composition.Builder(sequences)
                        .setEffects(Effects(emptyList(), canvas))
                        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
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
                                    result: ExportResult,
                                ) {
                                    done.complete(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    result: ExportResult,
                                    exception: ExportException,
                                ) {
                                    done.completeExceptionally(exception)
                                }
                            }
                        )
                        .build()
                try {
                    transformer.start(composition, part.path)
                    val p = ProgressHolder()
                    while (!done.isCompleted) {
                        if (transformer.getProgress(p) == Transformer.PROGRESS_STATE_AVAILABLE)
                            report(
                                OpProgress(
                                    "Exportando video",
                                    source.name,
                                    p.progress.toLong(),
                                    100,
                                )
                            )
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
        }
    }

    /** GIF uses the same visual composition as MP4. The format does not carry audio. */
    suspend fun gif(
        ctx: Context,
        source: File,
        target: File,
        edit: VideoEdit,
        report: (OpProgress) -> Unit,
    ) {
        val (prepared, duration) = prepare(source, edit)
        if (duration > GIF_MAX_DURATION_MS)
            throw IOException(
                "El GIF editado debe durar como máximo 10 segundos, contando velocidad y unión"
            )
        val rendered = File.createTempFile(".oi-gif-render-", ".mp4", ctx.cacheDir)
        rendered.delete()
        try {
            render(ctx, source, rendered, prepared.copy(mute = true, music = ""), report)
            withContext(Dispatchers.IO) {
                encodeGif(rendered, target, duration, source.name, report)
            }
        } finally {
            rendered.delete()
        }
    }

    /** A short, real render of the first clip; the exported composition remains unchanged. */
    suspend fun preview(
        ctx: Context,
        source: File,
        target: File,
        edit: VideoEdit,
        report: (OpProgress) -> Unit,
    ) {
        val (prepared, _) = prepare(source, edit.copy(join = emptyList()))
        export(
            ctx,
            source,
            target,
            prepared.copy(
                endMs = minOf(prepared.endMs, prepared.startMs + (2000 * prepared.speed).toLong())
            ),
            report,
        )
    }

    private suspend fun prepare(source: File, edit: VideoEdit): Pair<VideoEdit, Long> =
        withContext(Dispatchers.IO) {
            if (!source.isFile || edit.startMs < 0 || edit.endMs <= edit.startMs)
                throw IOException("Revisa el video y el intervalo de tiempo")
            if (!edit.speed.isFinite() || edit.speed !in 0.25f..4f || !edit.rotation.isFinite())
                throw IOException("La velocidad debe estar entre 0,25 y 4 veces")
            if (
                (edit.canvasWidth != 0 || edit.canvasHeight != 0) &&
                    (edit.canvasWidth !in 2..3840 ||
                        edit.canvasHeight !in 2..3840 ||
                        edit.canvasWidth % 2 != 0 ||
                        edit.canvasHeight % 2 != 0)
            )
                throw IOException("El lienzo debe tener dimensiones pares de hasta 3840 píxeles")
            val extra =
                edit.join +
                    listOf(edit.music, edit.image, edit.subtitles, edit.backgroundImage).filter {
                        it.isNotBlank()
                    }
            if (extra.any { !File(it).isFile })
                throw IOException("Ya no existe uno de los archivos adicionales")
            val end = minOf(edit.endMs, durationMs(source))
            if (end <= edit.startMs) throw IOException("El inicio está fuera del video")
            var duration = (end - edit.startMs).toDouble()
            for (path in edit.join) {
                currentCoroutineContext().ensureActive()
                duration += durationMs(File(path))
            }
            val output = kotlin.math.ceil(duration / edit.speed).toLong().coerceAtLeast(1)
            edit.copy(endMs = end) to output
        }

    private fun durationMs(source: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.path)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes")
                throw IOException("«${source.name}» no contiene video")
            return retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IOException("No se pudo leer la duración de «${source.name}»")
        } finally {
            retriever.release()
        }
    }

    private suspend fun encodeGif(
        source: File,
        target: File,
        requestedDuration: Long,
        displayName: String,
        report: (OpProgress) -> Unit,
    ) {
        val temp = File.createTempFile(".oi-gif-", ".tmp", target.absoluteFile.parentFile)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.path)
            val duration = minOf(requestedDuration, durationMs(source), GIF_MAX_DURATION_MS)
            val frame =
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IOException("No se pudo leer el video")
            val (width, height) = gifDimensions(frame.width, frame.height)
            frame.recycle()
            temp.outputStream().buffered().use { out ->
                val encoder = GifWriter(out, width, height)
                encoder.begin()
                val count = ((duration + 124) / 125).toInt().coerceAtLeast(1)
                repeat(count) { i ->
                    currentCoroutineContext().ensureActive()
                    val raw =
                        if (android.os.Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(
                                i * 125_000L,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                width,
                                height,
                            )
                        else
                            retriever.getFrameAtTime(
                                i * 125_000L,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                            )
                    val original = raw ?: throw IOException("No se pudo leer un fotograma")
                    val bitmap =
                        if (original.width == width && original.height == height) original
                        else
                            android.graphics.Bitmap.createScaledBitmap(
                                    original,
                                    width,
                                    height,
                                    true,
                                )
                                .also { original.recycle() }
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()
                    // GIF delays use centiseconds: 12/13 alternation averages exactly 8 fps.
                    encoder.frame(pixels, if (i % 2 == 0) 12 else 13)
                    report(OpProgress("Creando GIF", displayName, (i + 1).toLong(), count.toLong()))
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

    const val GIF_MAX_DURATION_MS = 10_000L
}

internal fun gifDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 480.0 / maxOf(width, height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal class GifWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
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
