package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

object StorageInfo {
    fun volumes(ctx: Context): List<StorageVolumeInfo> {
        val result = mutableListOf<StorageVolumeInfo>()
        val internal = Environment.getExternalStorageDirectory()
        result += info(tr("Almacenamiento interno"), internal, removable = false)
        val seen = mutableSetOf(internal.absolutePath)
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { d ->
            val root = d.absolutePath.substringBefore("/Android/data")
            if (seen.add(root) && File(root).canRead()) result += info(tr("Tarjeta SD"), File(root), removable = true)
        }
        return result
    }

    private fun info(name: String, f: File, removable: Boolean): StorageVolumeInfo = try {
        val s = StatFs(f.path)
        StorageVolumeInfo(name, f.absolutePath, s.totalBytes, s.availableBytes, removable)
    } catch (e: Exception) {
        StorageVolumeInfo(name, f.absolutePath, 0, 0, removable)
    }
}
