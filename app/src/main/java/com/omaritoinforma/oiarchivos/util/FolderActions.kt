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
object FolderActions {
    fun pin(ctx:Context,path:String){val manager=ctx.getSystemService(ShortcutManager::class.java);if(!manager.isRequestPinShortcutSupported)throw IllegalStateException("El lanzador no permite fijar accesos directos");val shortcut=ShortcutInfo.Builder(ctx,"folder-"+path.hashCode()).setShortLabel(PathUtil.displayName(path).take(40)).setIcon(Icon.createWithResource(ctx,R.drawable.ic_launcher_foreground)).setIntent(Intent(ctx,MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("folder",path)).build();if(!manager.requestPinShortcut(shortcut,null))throw IllegalStateException("No se pudo solicitar el acceso directo")}
    fun wallpaper(ctx:Context,file:File){file.inputStream().use{WallpaperManager.getInstance(ctx).setStream(it)}}
}
