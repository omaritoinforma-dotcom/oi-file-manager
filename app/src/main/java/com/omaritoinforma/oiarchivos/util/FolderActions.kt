package com.omaritoinforma.oiarchivos.util

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.omaritoinforma.oiarchivos.MainActivity
import com.omaritoinforma.oiarchivos.R
import java.io.File
import com.omaritoinforma.oiarchivos.data.tr

object FolderActions {
    fun pin(ctx: Context, path: String) {
        val manager = ctx.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported)
            throw IllegalStateException(tr("El lanzador no permite fijar accesos directos"))
        val shortcut =
            ShortcutInfo.Builder(ctx, "folder-" + path.hashCode())
                .setShortLabel(PathUtil.displayName(path).take(40))
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_launcher_foreground))
                .setIntent(
                    Intent(ctx, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra("folder", path))
                .build()
        if (!manager.requestPinShortcut(shortcut, null))
            throw IllegalStateException(tr("No se pudo solicitar el acceso directo"))
    }

    /** Acceso directo de un toque: abre OI Archivos y arranca el servidor FTP con la última carpeta. */
    fun pinFtpServer(ctx: Context) {
        val manager = ctx.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported)
            throw IllegalStateException(tr("El lanzador no permite fijar accesos directos"))
        val shortcut =
            ShortcutInfo.Builder(ctx, "ftp-server")
                .setShortLabel(tr("Servidor FTP"))
                .setLongLabel(tr("Servidor FTP"))
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_launcher_foreground))
                .setIntent(
                    Intent(ctx, MainActivity::class.java)
                        .setAction("com.omaritoinforma.oiarchivos.START_FTP")
                        .putExtra("start_ftp_server", true))
                .build()
        if (!manager.requestPinShortcut(shortcut, null))
            throw IllegalStateException(tr("No se pudo solicitar el acceso directo"))
    }

    fun wallpaper(ctx: Context, file: File) {
        file.inputStream().use { WallpaperManager.getInstance(ctx).setStream(it) }
    }
}
