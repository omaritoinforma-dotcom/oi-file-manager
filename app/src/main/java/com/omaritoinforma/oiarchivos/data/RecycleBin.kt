package com.omaritoinforma.oiarchivos.data

import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Papelera de reciclaje propia: los archivos se guardan en /.OI_Papelera con su ruta original. */
object RecycleBin {
    val dir: File get() = File(Environment.getExternalStorageDirectory(), ".OI_Papelera")
    private val metaDir: File get() = File(dir, ".meta")

    data class Entry(
        val id: String,
        val name: String,
        val originalPath: String,
        val deletedAt: Long,
        val file: File,
        val size: Long,
        val isDirectory: Boolean,
    )

    private fun ensure() {
        metaDir.mkdirs()
        runCatching { File(dir, ".nomedia").createNewFile() }
    }

    fun trash(f: File): Boolean {
        if (!f.exists()) return false
        val binCanon = dir.canonicalPath
        val fc = f.canonicalPath
        // Lo que ya está dentro de la papelera se borra definitivamente.
        if (fc == binCanon || fc.startsWith(binCanon + File.separator)) return f.deleteRecursively()
        ensure()
        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
        val target = File(dir, id)
        val moved = f.renameTo(target) ||
            runCatching { f.copyRecursively(target, overwrite = true) && f.deleteRecursively() }.getOrDefault(false)
        if (!moved) return false
        val meta = JSONObject()
            .put("name", f.name)
            .put("path", f.absolutePath)
            .put("deletedAt", System.currentTimeMillis())
        File(metaDir, "$id.json").writeText(meta.toString())
        return true
    }

    fun list(): List<Entry> {
        val metas = metaDir.listFiles()?.filter { it.name.endsWith(".json") } ?: return emptyList()
        return metas.mapNotNull { m ->
            runCatching {
                val o = JSONObject(m.readText())
                val id = m.name.removeSuffix(".json")
                val f = File(dir, id)
                if (!f.exists()) {
                    m.delete()
                    null
                } else {
                    Entry(id, o.getString("name"), o.getString("path"), o.getLong("deletedAt"), f, sizeOf(f), f.isDirectory)
                }
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    fun restore(e: Entry): String {
        val original = File(e.originalPath)
        val parent = original.parentFile ?: return "Ruta original no válida"
        parent.mkdirs()
        val target = if (original.exists()) FileOps.uniqueName(parent, original.name) else original
        val ok = e.file.renameTo(target) ||
            runCatching { e.file.copyRecursively(target, overwrite = false) && e.file.deleteRecursively() }.getOrDefault(false)
        if (!ok) return "No se pudo restaurar «${e.name}»"
        File(metaDir, "${e.id}.json").delete()
        return "Restaurado en ${target.parent}"
    }

    fun deleteForever(e: Entry) {
        e.file.deleteRecursively()
        File(metaDir, "${e.id}.json").delete()
    }

    fun empty() {
        dir.listFiles()?.forEach { if (it.name != ".nomedia") it.deleteRecursively() }
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
