package com.omaritoinforma.oiarchivos.util

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Ícono real de los archivos .apk (como hace ES). */
object ApkIcons {
    private val cache = LruCache<String, ImageBitmap>(150)

    @Suppress("DEPRECATION")
    fun load(ctx: Context, path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        val bmp = runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull() ?: return null
        cache.put(path, bmp)
        return bmp
    }
}
