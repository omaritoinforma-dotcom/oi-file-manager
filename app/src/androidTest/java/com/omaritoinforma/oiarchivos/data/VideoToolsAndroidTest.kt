package com.omaritoinforma.oiarchivos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Movie
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android codecs, exported pixels and Android's GIF decoder; no mocked media pipeline. */
@RunWith(AndroidJUnit4::class)
class VideoToolsAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private lateinit var source: File
    private lateinit var initialCacheTemps: Set<String>

    @Before fun setup() {
        initialCacheTemps = cacheTemps().map { it.name }.toSet()
        work = File(context.cacheDir, "video-test-" + UUID.randomUUID())
        assertTrue(work.mkdir())
        source = File(work, "quadrants.mp4")
        createAvcFixture(source)
        assertTrue("The actual Android AVC encoder must produce a video", source.length() > 0)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.path)
            assertEquals("The generated fixture must last exactly one second", "1000",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } finally { retriever.release() }
    }

    @After fun cleanup() {
        work.deleteRecursively()
        cacheTemps().filter { it.name !in initialCacheTemps }.forEach { it.delete() }
    }

    @Test(timeout = 120000) fun mp4RotatesCropsAndFitsOnTheSelectedBackground() = runBlocking {
        val target = File(work, "geometry.mp4")
        VideoTools.export(context, source, target,
            VideoEdit(rotation = 90f, crop = true, mute = true,
                canvasWidth = 320, canvasHeight = 240, backgroundColor = Color.MAGENTA)) {}

        frame(target, 250).useBitmap { image ->
            assertEquals(320, image.width)
            assertEquals(240, image.height)
            assertColor("Left letterbox", Color.MAGENTA, image.getPixel(20, 120))
            assertColor("Right letterbox", Color.MAGENTA, image.getPixel(300, 120))
            // A positive Media3 rotation is counterclockwise. All four quadrants must rotate.
            assertColor("Rotated top-left", GREEN, image.getPixel(124, 60))
            assertColor("Rotated top-right", YELLOW, image.getPixel(196, 60))
            assertColor("Rotated bottom-left", RED, image.getPixel(124, 180))
            assertColor("Rotated bottom-right", BLUE, image.getPixel(196, 180))
            // The original has an eight-pixel white border. Cropping removes it, rather than
            // merely fitting the unmodified source on the new canvas.
            assertColor("Cropped border", GREEN, image.getPixel(95, 12))
        }
        assertNoAudioTrack(target)
        assertNoTemporaryFiles()
    }

    @Test(timeout = 120000) fun mp4ContainsImageCaptionAndTimedSrtPixels() = runBlocking {
        val sticker = sticker()
        val subtitles = subtitles()
        val baseline = File(work, "baseline.mp4")
        val target = File(work, "overlays.mp4")
        val edit = VideoEdit(crop = true, mute = true, canvasWidth = 480, canvasHeight = 320)
        VideoTools.export(context, source, baseline, edit) {}
        VideoTools.export(context, source, target,
            edit.copy(image = sticker.path, caption = "CAP", subtitles = subtitles.path)) {}

        frame(baseline, 250).useBitmap { plain ->
            frame(target, 250).useBitmap { active ->
                assertTrue("The image overlay must paint magenta pixels at the top-right",
                    count(active, 340, 30, 435, 90) { near(it, Color.MAGENTA) } > 1000)
                assertTrue("The caption must change pixels near the center",
                    changedPixels(plain, active, 130, 100, 350, 210) > 150)
                assertTrue("An active SRT cue must change pixels near the bottom",
                    changedPixels(plain, active, 100, 245, 380, 296) > 40)
                frame(target, 625).useBitmap { gap ->
                    assertTrue("The SRT cue must disappear after its end time",
                        changedPixels(active, gap, 100, 245, 380, 296) > 40)
                }
            }
        }
        assertNoTemporaryFiles()
    }

    @Suppress("DEPRECATION")
    @Test(timeout = 120000) fun gifDecodesAllEditedFramesAndCapsThePortraitLongEdge() = runBlocking {
        val target = File(work, "edited.gif")
        VideoTools.gif(context, source, target,
            VideoEdit(rotation = 90f, crop = true, mute = true,
                canvasWidth = 320, canvasHeight = 640, backgroundColor = Color.CYAN,
                image = sticker().path, caption = "GIF", subtitles = subtitles().path)) {}

        val structure = readGif(target)
        assertEquals(240, structure.width)
        assertEquals(480, structure.height)
        assertEquals("A one-second clip produces eight frames", 8, structure.delays.size)
        assertTrue("Frame delays must approximate 8 fps", structure.delays.all { it in 12..13 })
        val movie = Movie.decodeFile(target.path)
            ?: throw AssertionError("Android's GIF decoder rejected the exported GIF")
        assertEquals(structure.width, movie.width())
        assertEquals(structure.height, movie.height())
        assertEquals(structure.delays.sum() * 10, movie.duration())

        gifFrame(movie, 260).useBitmap { active ->
            assertColor("Canvas background must survive GIF conversion", Color.CYAN,
                active.getPixel(10, 10))
            assertColor("Rotation and cropping must survive GIF conversion", GREEN,
                active.getPixel(40, 100))
            assertColor("The other rotated quadrant must survive GIF conversion", YELLOW,
                active.getPixel(200, 100))
            assertTrue("GIF must contain the image overlay",
                count(active, 165, 30, 220, 65) { near(it, Color.MAGENTA) } > 500)
            assertTrue("GIF must contain the central caption",
                count(active, 20, 190, 220, 290) { near(it, Color.WHITE) } > 100)
            gifFrame(movie, 650).useBitmap { gap ->
                assertTrue("GIF frames must preserve subtitle timing",
                    changedPixels(active, gap, 20, 400, 220, 452) > 30)
            }
        }
        assertNoTemporaryFiles()
    }

    @Test(timeout = 120000) fun gifDurationIncludesSpeedAndJoinedClips() = runBlocking {
        val target = File(work, "joined.gif")
        VideoTools.gif(context, source, target,
            VideoEdit(speed = 0.5f, join = listOf(source.path), mute = true)) {}
        val structure = readGif(target)
        assertEquals("Two one-second clips at half speed produce four seconds", 32,
            structure.delays.size)
        assertNoTemporaryFiles()
    }

    @Test(timeout = 120000) fun invalidLimitsPreserveTheDestinationAndLeaveNoTemporaryFiles() = runBlocking {
        val target = File(work, "preserved.gif").apply { writeText("existing destination") }
        var reported = false
        assertRejected {
            // Three one-second clips at quarter speed exceed the GIF duration limit.
            VideoTools.gif(context, source, target,
                VideoEdit(speed = 0.25f, join = listOf(source.path, source.path))) {
                    reported = true
                }
        }
        assertFalse("An invalid GIF duration must be rejected before export", reported)
        assertEquals("existing destination", target.readText())
        assertNoTemporaryFiles()

        val absent = File(work, "invalid.gif")
        for (edit in listOf(VideoEdit(startMs = -1), VideoEdit(startMs = 500, endMs = 500),
            VideoEdit(startMs = 2000), VideoEdit(speed = Float.NaN))) {
            assertRejected { VideoTools.gif(context, source, absent, edit) {} }
            assertFalse("Invalid settings must not create an output", absent.exists())
            assertNoTemporaryFiles()
        }
        val invalidMp4 = File(work, "invalid.mp4")
        assertRejected {
            VideoTools.export(context, source, invalidMp4,
                VideoEdit(canvasWidth = 321, canvasHeight = 240)) {}
        }
        assertFalse(invalidMp4.exists())
        assertNoTemporaryFiles()
    }

    @Test(timeout = 120000) fun cancelledGifPreservesDestinationAndRemovesBothTemporaryStages() = runBlocking {
        val target = File(work, "cancelled.gif").apply { writeText("original GIF destination") }
        var framesReported = 0
        lateinit var task: Deferred<Unit>
        task = async(start = CoroutineStart.LAZY) {
            VideoTools.gif(context, source, target, VideoEdit()) { progress ->
                if (progress.title == "Creando GIF") {
                    framesReported++
                    task.cancel(CancellationException("Cancel after the first encoded GIF frame"))
                }
            }
        }
        task.start()
        try { task.await(); fail("GIF export ignored cancellation") }
        catch (_: CancellationException) {}
        assertEquals("Cancellation must occur during GIF encoding", 1, framesReported)
        assertEquals("original GIF destination", target.readText())
        assertNoTemporaryFiles()
    }

    @Test(timeout = 120000) fun cancelledMp4PreservesDestinationAndRemovesThePartialExport() = runBlocking {
        val target = File(work, "cancelled.mp4").apply { writeText("original MP4 destination") }
        var reported = false
        lateinit var task: Deferred<Unit>
        task = async(start = CoroutineStart.LAZY) {
            // A longer composition keeps the transformer running long enough to report progress.
            VideoTools.export(context, source, target,
                VideoEdit(speed = 0.25f, join = List(31) { source.path })) {
                    reported = true
                    task.cancel(CancellationException("Cancel while Media3 is exporting"))
                }
        }
        task.start()
        try { task.await(); fail("MP4 export ignored cancellation") }
        catch (_: CancellationException) {}
        assertTrue("Cancellation must occur while the transformer is running", reported)
        assertEquals("original MP4 destination", target.readText())
        assertNoTemporaryFiles()
    }

    private fun sticker() = File(work, "sticker.png").apply {
        Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.eraseColor(Color.MAGENTA)
            outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    private fun subtitles() = File(work, "captions.srt").apply {
        writeText("1\n00:00:00,000 --> 00:00:00,400\nVISIBLE\n")
    }

    private fun frame(file: File, timeMs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw AssertionError("Android cannot decode a frame from ${file.name}")
        } finally { retriever.release() }
    }

    @Suppress("DEPRECATION")
    private fun gifFrame(movie: Movie, timeMs: Int) =
        Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888).apply {
            movie.setTime(timeMs)
            movie.draw(Canvas(this), 0f, 0f)
        }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
        try { block(this) } finally { recycle() }

    private fun near(actual: Int, expected: Int) =
        abs(Color.red(actual) - Color.red(expected)) <= 70 &&
            abs(Color.green(actual) - Color.green(expected)) <= 70 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 70

    private fun assertColor(message: String, expected: Int, actual: Int) {
        assertTrue("$message: expected ${Integer.toHexString(expected)}, " +
            "found ${Integer.toHexString(actual)}", near(actual, expected))
    }

    private fun count(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int,
        predicate: (Int) -> Boolean): Int {
        var result = 0
        for (y in y1 until y2) for (x in x1 until x2)
            if (predicate(bitmap.getPixel(x, y))) result++
        return result
    }

    private fun changedPixels(first: Bitmap, second: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var result = 0
        for (y in y1 until y2) for (x in x1 until x2) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            if (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b)) > 100) result++
        }
        return result
    }

    private fun assertNoAudioTrack(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            for (index in 0 until extractor.trackCount)
                assertFalse(extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/"))
        } finally { extractor.release() }
    }

    private fun assertNoTemporaryFiles() {
        assertEquals("Export must clean its MP4 and GIF temporary stages", emptyList<String>(),
            work.listFiles().orEmpty().filter { it.name.startsWith(".oi-") }.map { it.name })
        assertEquals("GIF must also clean the rendered video in the application's cache",
            initialCacheTemps, cacheTemps().map { it.name }.toSet())
    }

    private fun cacheTemps() =
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(".oi-") }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try { block(); fail("Accepted invalid video settings") }
        catch (_: IOException) {}
        catch (_: IllegalArgumentException) {}
    }

    private data class GifStructure(val width: Int, val height: Int, val delays: List<Int>)

    /** Counts actual image descriptors and their delays, rather than searching compressed bytes. */
    private fun readGif(file: File): GifStructure {
        val bytes = file.readBytes()
        var position = 0
        fun byte(): Int {
            assertTrue("Truncated GIF", position < bytes.size)
            return bytes[position++].toInt() and 255
        }
        fun short(): Int = byte() or (byte() shl 8)
        fun skipBlocks() {
            var size = byte()
            while (size != 0) {
                position += size
                assertTrue("Truncated GIF block", position <= bytes.size)
                size = byte()
            }
        }
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        position = 6
        val width = short()
        val height = short()
        val packed = byte()
        byte(); byte()
        if (packed and 128 != 0) position += 3 * (1 shl ((packed and 7) + 1))
        val delays = ArrayList<Int>()
        var delay = 0
        while (true) {
            when (val marker = byte()) {
                0x21 -> if (byte() == 0xf9) {
                    assertEquals(4, byte())
                    byte()
                    delay = short()
                    byte()
                    assertEquals(0, byte())
                } else skipBlocks()
                0x2c -> {
                    short(); short()
                    assertEquals(width, short())
                    assertEquals(height, short())
                    val imagePacked = byte()
                    if (imagePacked and 128 != 0)
                        position += 3 * (1 shl ((imagePacked and 7) + 1))
                    assertTrue("Invalid GIF LZW code size", byte() in 2..8)
                    skipBlocks()
                    delays += delay
                }
                0x3b -> {
                    assertEquals("Unexpected trailing GIF data", bytes.size, position)
                    return GifStructure(width, height, delays)
                }
                else -> throw AssertionError("Unexpected GIF block $marker")
            }
        }
    }

    private fun createAvcFixture(target: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var codecStarted = false
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 160, 96)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 8)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var track = -1
            var ended = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!ended) {
                assertTrue("Android AVC fixture encoder timed out", System.nanoTime() < deadline)
                if (frame <= 8) {
                    val input = codec.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        if (frame < 8) {
                            val image = codec.getInputImage(input)
                                ?: throw AssertionError("AVC encoder must expose flexible YUV input")
                            try { paintYuv(image, frame) } finally { image.close() }
                            codec.queueInputBuffer(input, 0, 160 * 96 * 3 / 2,
                                frame * 125000L, 0)
                        } else codec.queueInputBuffer(input, 0, 0, 1000000,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        frame++
                    }
                }
                when (val output = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        assertFalse("AVC output format changed twice", muxerStarted)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (output >= 0) {
                        val buffer = codec.getOutputBuffer(output)
                            ?: throw AssertionError("AVC encoder returned no output buffer")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            assertTrue("AVC samples arrived before their format", muxerStarted)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(output, false)
                    }
                }
            }
        } finally {
            try { if (codecStarted) codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
        }
    }

    private fun paintYuv(image: Image, frame: Int) {
        assertEquals(ImageFormat.YUV_420_888, image.format)
        val colors = arrayOf(RED, GREEN, BLUE, YELLOW, Color.WHITE, Color.MAGENTA, Color.CYAN)
        val yuv = colors.map { color ->
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            intArrayOf(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16,
                ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128,
                ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
        }
        for ((component, plane) in image.planes.withIndex()) {
            val scale = if (component == 0) 1 else 2
            val buffer = plane.buffer
            val offset = buffer.position()
            for (y in 0 until 96 / scale) for (x in 0 until 160 / scale) {
                val px = x * scale
                val py = y * scale
                val index = when {
                    px < 8 || px >= 152 || py < 8 || py >= 88 -> 4
                    px in 72 until 88 && py in 40 until 56 -> if (frame < 4) 5 else 6
                    py < 48 && px < 80 -> 0
                    py < 48 -> 1
                    px < 80 -> 2
                    else -> 3
                }
                buffer.put(offset + y * plane.rowStride + x * plane.pixelStride,
                    yuv[index][component].coerceIn(0, 255).toByte())
            }
        }
    }

    companion object {
        private val RED = Color.rgb(208, 48, 48)
        private val GREEN = Color.rgb(48, 208, 64)
        private val BLUE = Color.rgb(48, 48, 208)
        private val YELLOW = Color.rgb(208, 200, 48)
    }
}
