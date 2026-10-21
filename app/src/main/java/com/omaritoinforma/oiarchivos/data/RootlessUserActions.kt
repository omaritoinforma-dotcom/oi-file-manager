package com.omaritoinforma.oiarchivos.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omaritoinforma.oiarchivos.UserActionActivity
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** No shell: notifications lead to a visible review and Android's own confirmation. */
object RootlessUserActions {
    fun store(context: Context) = PendingUserActionStore(File(context.cacheDir, "oi-pending-installs"))
    fun requireAuthorization(context: Context, action: String, remote: Boolean) {
        if (!remote) return
        val config = BridgeConfig(context)
        if (!BridgeStatus.running || !config.allowDebug || !config.allowChanges)
            throw IOException("La solicitud remota fue revocada o el conector está desconectado")
        if (action == "uninstall" && !config.allowDelete) throw IOException("El borrado definitivo está desactivado")
    }
    @SuppressLint("MissingPermission") // Explicit Android 13 permission and channel checks below.
    fun request(context: Context, action: String, args: JSONObject, fromRemote: Boolean): JSONObject {
        if (action !in setOf("install", "uninstall")) throw IOException("Solicitud no reconocida")
        requireAuthorization(context, action, fromRemote)
        val queue = store(context)
        val pending = if (action == "install") {
            val source = FileAgent(context, false, false).resolveReadTarget(args.getString("value"))
            queue.stageInstall(source, args.optString("packageName"), fromRemote).also { request ->
                try {
                    val info = context.packageManager.getPackageArchiveInfo(request.apk!!.path, 0)
                    if (info == null || (request.packageName.isNotBlank() && request.packageName != info.packageName))
                        throw IOException("La APK no es válida o no coincide con el paquete solicitado")
                } catch (error: Exception) { queue.discard(request.id); throw error }
            }
        } else {
            val pkg = DebugCommands.packageName(args.getString("packageName"))
            context.packageManager.getApplicationInfo(pkg, 0)
            queue.stageUninstall(pkg, fromRemote)
        }
        val notificationId = pending.id.hashCode()
        val launch = Intent(context, UserActionActivity::class.java).putExtra("requestId", pending.id)
            .setAction("oi-user-action:${pending.id}")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("oi_user_actions", "Solicitudes de aplicaciones", NotificationManager.IMPORTANCE_DEFAULT))
        val canNotify = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel("oi_user_actions")?.importance != NotificationManager.IMPORTANCE_NONE
        if (canNotify) {
            val open = PendingIntent.getActivity(context, notificationId, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(notificationId, NotificationCompat.Builder(context, "oi_user_actions")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (action == "install") "Revisar instalación de APK" else "Revisar desinstalación")
                .setContentText("Toca para revisar. Android solicitará tu confirmación.")
                .setContentIntent(open).setAutoCancel(true).build())
        }
        return JSONObject().put("executor", "android_user_interface").put("requestId", pending.id)
            .put("status", "requires_user_action").put("completed", false).put("requiresUserAction", true)
            .put("exitCode", JSONObject.NULL).put("truncated", false).put("timedOut", false)
            .put("delivery", if (canNotify) "notification_and_pending_list" else "pending_list")
            .put("stdout", "Solicitud preparada, no ejecutada. Abre OI Archivos > Depurador > Solicitudes pendientes o toca la notificación. Android conserva la confirmación final.")
    }
}
