package com.omaritoinforma.oiarchivos.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import com.omaritoinforma.oiarchivos.data.tr
import com.omaritoinforma.oiarchivos.data.trKey

/** «Poner como tono» de ES: tono de llamada, de notificación o de alarma. */
object Ringtones {
    enum class Kind(private val labelEs: String, val type: Int) {
        CALL(trKey("Tono de llamada"), RingtoneManager.TYPE_RINGTONE),
        NOTIFICATION(trKey("Sonido de notificación"), RingtoneManager.TYPE_NOTIFICATION),
        ALARM(trKey("Sonido de alarma"), RingtoneManager.TYPE_ALARM);

    val label: String
        get() = tr(labelEs)
}

    /** Android pide un permiso especial para cambiar los ajustes del sistema. */
    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    fun askPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Dirección de MediaStore del archivo: el sistema solo acepta tonos indexados. */
    private suspend fun mediaUri(ctx: Context, file: File): Uri =
        withTimeout(15_000) {
            suspendCancellableCoroutine { done ->
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null) { _, uri ->
                    done.resume(uri)
                }
            }
        } ?: throw IOException(tr("Android no reconoce «{0}» como audio", file.name))

    suspend fun set(ctx: Context, file: File, kind: Kind) {
        if (!canWrite(ctx)) throw IOException(tr("Falta el permiso para cambiar los ajustes del sistema"))
        RingtoneManager.setActualDefaultRingtoneUri(ctx, kind.type, mediaUri(ctx, file))
    }
}
