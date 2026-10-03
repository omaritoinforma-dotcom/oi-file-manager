package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        val firstChunk = doneBytes == 0L
        doneBytes += n
        emit(force = firstChunk)
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
        report(
            OpProgress(
                title,
                current,
                doneBytes,
                totalBytes,
                doneFiles,
                totalFiles,
                doneBytes * 1000 / elapsed))
    }
}

data class TransferResult(val targets: List<File>, val skipped: Int)

object FileOps {
    private const val BUFFER = 256 * 1024

    /** "foto.jpg" -> "foto (1).jpg" si ya existe. */
    fun uniqueName(dir: File, name: String): File {
        SafeFiles.requireName(name)
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
            for (f in SafeFiles.walk(root)) {
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
        if (!destDir.exists() && !destDir.mkdirs())
            throw IOException("No se pudo crear la carpeta de destino")

        val targets = mutableListOf<File>()
        val roots =
            sources
                .distinctBy { it.canonicalPath }
                .filter { source ->
                    sources.none { other ->
                        other != source &&
                            other.isDirectory &&
                            source.canonicalPath.startsWith(other.canonicalPath + File.separator)
                    }
                }
        val (bytes, count) = measure(roots)
        tracker.totalBytes = bytes
        tracker.totalFiles = count
        var skipped = 0
        for (s in roots) {
            currentCoroutineContext().ensureActive()
            SafeFiles.requireRegular(s)
            var target = File(destDir, s.name)
            if (target.canonicalPath == s.canonicalPath) {
                if (move) {
                    skipped++
                    continue
                }
                target = uniqueName(destDir, s.name)
            } else if (target.exists()) {
                when (conflict) {
                    Conflict.SKIP ->
                        if (!s.isDirectory || !target.isDirectory) {
                            skipped++
                            continue
                        }
                    Conflict.RENAME -> target = uniqueName(destDir, s.name)
                    Conflict.OVERWRITE ->
                        if (target.isDirectory != s.isDirectory) {
                            throw IOException(
                                "«${target.name}» es de otro tipo; renómbralo o conserva ambos")
                        }
                }
            }
            // Mover dentro del mismo almacenamiento es instantáneo.
            if (move && !target.exists() && s.renameTo(target)) {
                targets += target
                continue
            }
            val omitted = copyRecursive(s, target, tracker, conflict, move)
            skipped += omitted
            targets += target
        }
        return TransferResult(targets, skipped)
    }

    private suspend fun copyRecursive(
        src: File,
        initialDst: File,
        t: Tracker,
        conflict: Conflict,
        move: Boolean
    ): Int {
        currentCoroutineContext().ensureActive()
        SafeFiles.requireRegular(src)
        var dst = initialDst
        if (dst.exists() && !(src.isDirectory && dst.isDirectory)) {
            when (conflict) {
                Conflict.SKIP -> return 1
                Conflict.RENAME -> dst = uniqueName(dst.parentFile!!, dst.name)
                Conflict.OVERWRITE ->
                    if (dst.isDirectory != src.isDirectory)
                        throw IOException("Tipos incompatibles: ${dst.name}")
            }
        }
        var skipped = 0
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("No se pudo crear «${dst.name}»")
            val children = src.listFiles() ?: throw IOException("No se puede leer «${src.name}»")
            for (child in children) skipped +=
                copyRecursive(child, File(dst, child.name), t, conflict, move)
            dst.setLastModified(src.lastModified())
            // Keep the original folder if any of its children were skipped.
            if (move && src.list()?.isEmpty() == true && !src.delete())
                throw IOException("No se pudo borrar ${src.name}")
        } else {
            copyFile(src, dst, t)
            currentCoroutineContext().ensureActive()
            if (move && !src.delete())
                throw IOException("Copiado, pero no se pudo borrar el original: ${src.name}")
        }
        return skipped
    }

    private suspend fun copyFile(src: File, dst: File, t: Tracker) {
        val originalSize = src.length()
        val originalModified = src.lastModified()
        var copied = 0L
        t.current = src.name
        t.emit()
        val temp = File.createTempFile(".oi-part-", ".tmp", dst.parentFile)
        try {
            src.inputStream().use { input ->
                temp.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        t.addBytes(n.toLong())
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            if (copied != originalSize ||
                src.length() != originalSize ||
                src.lastModified() != originalModified) {
                throw IOException(
                    "El archivo cambió durante la copia: ${src.name}; vuelve a intentarlo")
            }
            temp.setLastModified(originalModified)
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, dst)
        } finally {
            temp.delete()
        }
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
