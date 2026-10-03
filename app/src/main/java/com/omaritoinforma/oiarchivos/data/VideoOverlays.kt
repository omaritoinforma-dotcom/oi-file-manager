package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.*
import java.io.IOException

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoOverlays {
    fun subtitles(cues: List<SubtitleCue>): TextOverlay =
        object : TextOverlay() {
            private var fontSize = 32

            override fun configure(videoSize: Size) {
                fontSize = (videoSize.height * 0.055f).toInt().coerceIn(18, 64)
            }

            override fun getText(presentationTimeUs: Long): SpannableString =
                SpannableString(Subtitles.textAt(cues, presentationTimeUs / 1000)).apply {
                    setSpan(AbsoluteSizeSpan(fontSize), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(
                        ForegroundColorSpan(Color.WHITE),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(
                        BackgroundColorSpan(0xb0000000.toInt()),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, -0.8f)
                    .setOverlayFrameAnchor(0f, -1f)
                    .build()
        }

    fun image(path: String): BitmapOverlay =
        object : BitmapOverlay() {
            private val bitmap = decode(path)
            private var scale = 1f

            override fun configure(videoSize: Size) {
                scale =
                    minOf(
                        videoSize.width * 0.3f / bitmap.width,
                        videoSize.height * 0.3f / bitmap.height)
            }

            override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                OverlaySettings.Builder()
                    .setScale(scale, scale)
                    .setBackgroundFrameAnchor(0.9f, 0.9f)
                    .setOverlayFrameAnchor(1f, 1f)
                    .build()

            override fun release() {
                try {
                    super.release()
                } finally {
                    bitmap.recycle()
                }
            }
        }

    fun decode(path: String): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0)
            throw IOException("No se pudo abrir la imagen")
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1920) options
            .inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
            ?: throw IOException("No se pudo leer la imagen")
    }
}

/** Fits the video on a user-selected canvas, filling the remaining space with color or an image. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class VideoCanvasEffect(
    private val width: Int,
    private val height: Int,
    private val color: Int,
    private val image: String
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CanvasShader(width, height, color, image, useHdr)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class CanvasShader(
    private val width: Int,
    private val height: Int,
    color: Int,
    image: String,
    hdr: Boolean
) : BaseGlShaderProgram(hdr, 1) {
    private val program =
        GlProgram(
            """attribute vec4 aPosition; varying vec2 vUv; void main(){ gl_Position=aPosition; vUv=aPosition.xy*0.5+0.5; }""",
            """precision mediump float; varying vec2 vUv; uniform sampler2D uVideo; uniform sampler2D uBackground; uniform vec2 uFit; uniform vec3 uColor; uniform float uImage;
        void main(){ vec2 p=(vUv-0.5)/uFit+0.5; vec3 bg=mix(uColor,pow(texture2D(uBackground,vec2(vUv.x,1.0-vUv.y)).rgb,vec3(2.2)),uImage); if(p.x>=0.0 && p.x<=1.0 && p.y>=0.0 && p.y<=1.0) gl_FragColor=texture2D(uVideo,p); else gl_FragColor=vec4(bg,1.0); }""")
    private val background: Int

    init {
        program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        program.setFloatsUniform(
            "uColor",
            floatArrayOf(
                    Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
                .map { Math.pow(it.toDouble(), 2.2).toFloat() }
                .toFloatArray())
        program.setFloatUniform("uImage", if (image.isBlank()) 0f else 1f)
        val bitmap =
            if (image.isBlank())
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            else VideoOverlays.decode(image)
        background =
            try {
                GlUtil.createTexture(bitmap)
            } finally {
                bitmap.recycle()
            }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = minOf(width.toFloat() / inputWidth, height.toFloat() / inputHeight)
        program.setFloatsUniform(
            "uFit", floatArrayOf(inputWidth * scale / width, inputHeight * scale / height))
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uVideo", inputTexId, 0)
            program.setSamplerTexIdUniform("uBackground", background, 1)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            program.delete()
            GlUtil.deleteTexture(background)
        } finally {
            super.release()
        }
    }
}
