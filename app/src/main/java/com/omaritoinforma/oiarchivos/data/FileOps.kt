package com.omaritoinforma.oiarchivos.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Lleva la cuenta del progreso y la velocidad de una operación. */
class Tracker(private val title: String, private val report: (OpProgress) -> Unit) {
    var totalBytes = 0L
    var totalFiles = 0
    var doneBytes = 0L
    var doneFiles = 0
    var current = ""
    private val start = System.currentTimeMillis()
    private var lastEmit = 0L

    fun addBytes(n: Long) {
        doneBytes += n
        emit(force = false)
    }

    fun fileDone() {
        doneFiles++
        emit()
    }

    fun emit(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < 150) return
        lastEmit = now
        val elapsed = (now - start).coerceAtLeast(1)
        report(OpProgress(title, current, doneBytes, totalBytes, doneFiles, totalFiles, doneBytes * 1000 / elapsed))
    }
}

data class TransferResult(val targets: List<File>, val skipped: Int)

object FileOps {
    private const val BUFFER = 256 * 1024

    /** "foto.jpg" -> "foto (1).jpg" si ya existe. */
    fun uniqueName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun measure(files: Collection<File>): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        for (root in files) {
            for (f in root.walkTopDown()) {
                if (f.isFile) {
                    bytes += f.length()
                    count++
                }
            }
        }
        return bytes to count
    }

    suspend fun transfer(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        conflict: Conflict,
        report: (OpProgress) -> Unit,
    ): TransferResult {
        val verb = if (move) "mover" else "copiar"
        val tracker = Tracker(if (move) "Moviendo" else "Copiando", report)
        val destCanon = destDir.canonicalPath
        for (s in sources) {
            val sc = s.canonicalPath
            if (s.isDirectory && (destCanon == sc || destCanon.startsWith(sc + File.separator))) {
                throw IOException("No se puede $verb «${s.name}» dentro de sí misma")
            }
        }
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("No se pudo crear la carpeta de destino")

        val targets = mutableListOf<File>()
        val pending = mutableListOf<Pair<File, File>>()
        var skipped = 0
        for (s in sources) {
            var target = File(destDir, s.name)
            if (target.canonicalPath == s.canonicalPath) {
                if (move) {
                    skipped++
                    continue
                }
                target = uniqueName(destDir, s.name)
            } else if (target.exists()) {
                when (conflict) {
                    Conflict.SKIP -> {
                        skipped++
                        continue
                    }
                    Conflict.RENAME -> target = uniqueName(destDir, s.name)
                    Conflict.OVERWRITE -> if (target.isDirectory != s.isDirectory) target.deleteRecursively()
                }
            }
            // Mover dentro del mismo almacenamiento es instantáneo.
            if (move && !target.exists() && s.renameTo(target)) {
                targets += target
                continue
            }
            pending += s to target
        }

        val (bytes, count) = measure(pending.map { it.first })
        tracker.totalBytes = bytes
        tracker.totalFiles = count
        tracker.emit()
        for ((s, t) in pending) {
            copyRecursive(s, t, tracker)
            if (move && !s.deleteRecursively()) {
                throw IOException("Se copió «${s.name}» pero no se pudo borrar el original")
            }
            targets += t
        }
        return TransferResult(targets, skipped)
    }

    private suspend fun copyRecursive(src: File, dst: File, t: Tracker) {
        currentCoroutineContext().ensureActive()
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("No se pudo crear «${dst.name}»")
            val children = src.listFiles() ?: emptyArray()
            for (child in children) copyRecursive(child, File(dst, child.name), t)
            dst.setLastModified(src.lastModified())
        } else {
            copyFile(src, dst, t)
        }
    }

    private suspend fun copyFile(src: File, dst: File, t: Tracker) {
        t.current = src.name
        t.emit()
        try {
            src.inputStream().use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        t.addBytes(n.toLong())
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        } catch (e: Exception) {
            dst.delete() // no dejar archivos a medias
            throw e
        }
        dst.setLastModified(src.lastModified())
        t.fileDone()
    }

    /** Devuelve cuántos elementos fallaron. */
    suspend fun deleteAll(files: List<File>, toTrash: Boolean, report: (OpProgress) -> Unit): Int {
        var failed = 0
        val title = if (toTrash) "Moviendo a la papelera" else "Eliminando"
        for ((i, f) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            report(OpProgress(title, f.name, doneFiles = i, totalFiles = files.size))
            val ok = if (toTrash) RecycleBin.trash(f) else f.deleteRecursively()
            if (!ok) failed++
        }
        return failed
    }
}
