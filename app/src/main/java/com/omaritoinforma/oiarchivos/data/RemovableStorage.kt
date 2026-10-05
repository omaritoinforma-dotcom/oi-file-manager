package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.app.NotificationCompat
import com.omaritoinforma.oiarchivos.MainActivity
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Memorias USB y tarjetas SD: aviso al conectarlas (como el de ES) con «Abrir» y «Expulsar», y
 * aviso si se quitan sin expulsar. Android no deja a las apps desmontar una unidad, así que
 * «Expulsar» comprueba que no quede ninguna copia en curso y lleva a Ajustes → Almacenamiento.
 */
object RemovableStorage {
    private const val CHANNEL = "removable_storage"

    /** Cambia cada vez que se monta o se quita una unidad, para que la app relea la lista. */
    val changes = MutableStateFlow(0)

    /** Carpeta raíz de una unidad montada. */
    fun directory(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= 30) volume.directory
        else runCatching { volume.javaClass.getMethod("getPathFile").invoke(volume) as File? }.getOrNull()

    /** Unidades extraíbles montadas: nombre que les da Android y carpeta. */
    fun mounted(ctx: Context): List<Pair<String, File>> =
        ctx.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { v -> directory(v)?.let { name(ctx, v) to it } }

    private fun name(ctx: Context, v: StorageVolume) = v.getDescription(ctx).orEmpty().ifBlank { tr("Tarjeta SD") }

    /** Identificador de la notificación de cada unidad. */
    private fun id(path: String) = 41_000 + (path.hashCode() and 0xFFF)

    fun onMounted(ctx: Context, path: String) {
        changes.value++
        if (!Prefs(ctx).removableNotice) return
        val volume =
            ctx.getSystemService(StorageManager::class.java).getStorageVolume(File(path))
                ?.takeIf { it.isRemovable } ?: return
        val name = name(ctx, volume)
        channel(ctx)
        val open = activity(ctx, id(path), Intent().putExtra("folder", path))
        val eject = activity(ctx, id(path) + 0x1000, Intent().putExtra("eject", path))
        notify(
            ctx, id(path),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sdcard)
                .setContentTitle(tr("«{0}» conectada", name))
                .setContentText(tr("Toca para ver sus archivos"))
                .setContentIntent(open)
                .addAction(0, tr("Abrir"), open)
                .addAction(0, tr("Expulsar"), eject)
                .build()) // sigue mientras la unidad esté conectada
    }

    fun onRemoved(ctx: Context, path: String, bad: Boolean) {
        changes.value++
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.cancel(id(path))
        if (!bad || !Prefs(ctx).removableNotice) return
        channel(ctx)
        notify(
            ctx, id(path),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sdcard_usb)
                .setContentTitle(tr("Se quitó una unidad sin expulsarla"))
                .setContentText(tr("Si se estaba copiando algo, puede haber quedado incompleto. Usa «Expulsar» antes de quitarla."))
                .setStyle(NotificationCompat.BigTextStyle())
                .setAutoCancel(true)
                .build())
    }

    /** Ajustes del sistema donde se expulsa la unidad. */
    fun settingsIntent() =
        Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activity(ctx: Context, request: Int, extras: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            request,
            Intent(ctx, MainActivity::class.java)
                .putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Memorias USB y tarjetas SD"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}

/** Avisos del sistema al montar o quitar una unidad (están permitidos en el manifiesto desde Android 8). */
class RemovableStorageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.path ?: return
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> RemovableStorage.onMounted(context, path)
            Intent.ACTION_MEDIA_BAD_REMOVAL -> RemovableStorage.onRemoved(context, path, bad = true)
            Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED ->
                RemovableStorage.onRemoved(context, path, bad = false)
        }
    }
}
