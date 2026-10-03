package com.omaritoinforma.oiarchivos.util

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/** Avisa al sistema de los cambios para que galería, música y categorías se actualicen. */
object Media {
    fun scan(ctx: Context, files: Collection<File>) {
        val paths = ArrayList<String>()
        for (f in files) {
            if (f.isDirectory) {
                f.walkTopDown().take(500).forEach { if (it.isFile) paths += it.absolutePath }
            } else {
                paths += f.absolutePath
            }
            if (paths.size > 1000) break
        }
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(ctx.applicationContext, paths.toTypedArray(), null, null)
        }
    }
}
