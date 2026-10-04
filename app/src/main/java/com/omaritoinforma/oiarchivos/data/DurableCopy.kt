package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A local copy/move journal with a commit record written before replacing a destination. */
class DurableCopy
private constructor(
    override val id: String,
    override val destination: String,
    val move: Boolean,
    private val roots: List<String>,
    private val entries: MutableList<Entry>,
    private val journal: File
) : DurableJob {
    private data class Entry(
        val source: String,
        val target: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
        val replace: Boolean,
        val oldSize: Long,
        val oldModified: Long,
        var phase: Int = 0,
        var digest: String = ""
    )

    override val title
        get() = if (move) "Moviendo" else "Copiando"

    override val completed
        get() = entries.count { it.phase == 3 && !it.directory }

    override val count
        get() = entries.count { !it.directory && it.phase != 4 }

    private fun partial(index: Int) =
        File(File(entries[index].target).parentFile, ".oi-resume-$id-$index.part")

    private fun save() =
        SafeFiles.writeAtomic(journal) { temp ->
            FileOutputStream(temp).use { file ->
                val out = DataOutputStream(BufferedOutputStream(file))
                out.writeUTF("OI-COPY-2")
                out.writeUTF(id)
                out.writeUTF(destination)
                out.writeBoolean(move)
                out.writeInt(roots.size)
                roots.forEach(out::writeUTF)
                out.writeInt(entries.size)
                entries.forEach { e ->
                    out.writeUTF(e.source)
                    out.writeUTF(e.target)
                    out.writeBoolean(e.directory)
                    out.writeLong(e.size)
                    out.writeLong(e.modified)
                    out.writeBoolean(e.replace)
                    out.writeLong(e.oldSize)
                    out.writeLong(e.oldModified)
                    out.writeInt(e.phase)
                    out.writeUTF(e.digest)
                }
                out.flush()
                file.fd.sync()
            }
        }

    override fun discard() {
        entries.indices.forEach { index ->
            val part = partial(index)
            if (!Files.isSymbolicLink(part.toPath())) part.delete()
        }
        journal.delete()
    }

    private fun checkedTarget(entry: Entry): File {
        val root = File(destination)
        val target = File(entry.target)
        if (root.canonicalPath != destination ||
            target.canonicalPath != entry.target ||
            !entry.target.startsWith(destination + File.separator) ||
            Files.isSymbolicLink(target.toPath()))
            throw IOException("La ruta de destino cambió desde que se inició la transferencia")
        return target
    }

    private fun checkSource(entry: Entry): File {
        val source = File(entry.source)
        SafeFiles.requireRegular(source)
        if (source.canonicalPath != entry.source ||
            !source.isFile ||
            source.length() != entry.size ||
            source.lastModified() != entry.modified)
            throw IOException("El original cambió: ${source.name}. Se conserva sin borrar.")
        return source
    }

    private suspend fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(262144)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult {
        val tracker = Tracker(title, report)
        tracker.totalFiles = count
        tracker.totalBytes = entries.filter { !it.directory && it.phase != 4 }.sumOf { it.size }
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (entry.phase == 4) continue
            val target = checkedTarget(entry)
            if (entry.directory) {
                if (!target.isDirectory && !target.mkdirs())
                    throw IOException("No se pudo crear ${target.name}")
                entry.phase = 3
                continue
            }
            tracker.current = target.name
            if (entry.phase >= 3) {
                if (!target.isFile || digest(target) != entry.digest)
                    throw IOException(
                        "Un archivo ya copiado cambió o fue eliminado: ${target.name}")
                tracker.addBytes(entry.size)
                tracker.fileDone()
                continue
            }
            val part = partial(index)
            if (Files.isSymbolicLink(part.toPath()))
                throw IOException("El archivo parcial fue sustituido por un enlace")
            if (entry.phase == 1) {
                // The process may have died between rename and persisting its completion.
                if (!part.exists() && target.isFile && digest(target) == entry.digest) {
                    entry.phase = 2
                    save()
                } else if (!part.isFile || digest(part) != entry.digest)
                    throw IOException(
                        "La copia parcial cambió; descarta esta transferencia y vuelve a intentarlo")
            }
            if (entry.phase == 0) {
                val source = checkSource(entry)
                if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs())
                    throw IOException("No se pudo crear el destino")
                val resumeAt = if (part.exists()) part.length() else 0L
                if (resumeAt > entry.size) throw IOException("Copia parcial no válida")
                val hash = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    // Validate the entire persisted prefix against the still-unchanged original.
                    if (resumeAt > 0)
                        part.inputStream().use { old ->
                            val a = ByteArray(262144)
                            val b = ByteArray(a.size)
                            var remaining = resumeAt
                            while (remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                val n = minOf(remaining, a.size.toLong()).toInt()
                                DataInputStream(input).readFully(a, 0, n)
                                DataInputStream(old).readFully(b, 0, n)
                                if (!(0 until n).all { a[it] == b[it] })
                                    throw IOException("El original o la copia parcial cambió")
                                hash.update(a, 0, n)
                                tracker.addBytes(n.toLong())
                                remaining -= n
                            }
                        }
                    var bytes = resumeAt
                    FileOutputStream(part, true).use { out ->
                        val buffer = ByteArray(262144)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            hash.update(buffer, 0, n)
                            bytes += n
                            tracker.addBytes(n.toLong())
                        }
                        out.fd.sync()
                    }
                    if (bytes != entry.size)
                        throw IOException("El original cambió durante la copia")
                }
                checkSource(entry)
                entry.digest = hash.digest().joinToString("") { "%02x".format(it) }
                entry.phase = 1
                save()
            } else tracker.addBytes(entry.size)
            if (entry.phase == 1) {
                currentCoroutineContext().ensureActive()
                checkSource(entry)
                if (entry.replace) {
                    if (!target.isFile ||
                        target.length() != entry.oldSize ||
                        target.lastModified() != entry.oldModified)
                        throw IOException("El destino cambió; se conservan ambos archivos")
                } else if (target.exists())
                    throw IOException(
                        "Se creó otro archivo en el destino; se conserva sin sustituir")
                part.setLastModified(entry.modified)
                SafeFiles.commit(part, target, entry.replace)
                entry.phase = 2
                save()
            }
            if (entry.phase == 2 && move && File(entry.source).exists()) {
                val source = checkSource(entry)
                if (digest(source) != entry.digest || digest(target) != entry.digest)
                    throw IOException("El original o el destino cambió; el original se conserva")
                currentCoroutineContext().ensureActive()
                if (!source.delete())
                    throw IOException("Copiado, pero no se pudo borrar el original: ${source.name}")
            }
            entry.phase = 3
            save()
            tracker.fileDone()
        }
        if (move)
            entries
                .asReversed()
                .filter { it.directory && it.phase != 4 }
                .forEach { e ->
                    val original = File(e.source)
                    if (original.canonicalPath == e.source &&
                        original.isDirectory &&
                        original.list()?.isEmpty() == true)
                        original.delete()
                }
        val changed = entries.filter { it.phase == 3 }.map { File(it.target) } + roots.map(::File)
        val skipped = entries.count { it.phase == 4 }
        journal.delete()
        return OperationResult(
            "${if (move) "Movidos" else "Copiados"}: $count${if (skipped > 0) " · omitidos: $skipped" else ""}",
            changed)
    }

    companion object {
        fun create(
            directory: File,
            sources: List<File>,
            destination: File,
            move: Boolean,
            conflict: Conflict
        ): DurableCopy {
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("No se pudo guardar la transferencia")
            val dest = destination.canonicalFile
            val roots =
                sources
                    .distinctBy { it.canonicalPath }
                    .filter { root ->
                        sources.none { other ->
                            other != root &&
                                other.isDirectory &&
                                root.canonicalPath.startsWith(other.canonicalPath + File.separator)
                        }
                    }
            if (roots.isEmpty()) throw IOException("Selecciona al menos un archivo")
            if (roots.any {
                it.isDirectory &&
                    (dest == it.canonicalFile ||
                        dest.path.startsWith(it.canonicalPath + File.separator))
            })
                throw IOException("No puedes copiar una carpeta dentro de sí misma")
            if (!destination.isDirectory && !destination.mkdirs())
                throw IOException("Destino no válido")
            val entries = ArrayList<Entry>()
            val reserved = hashSetOf<String>()
            fun unique(initial: File): File {
                var target = initial
                var index = 1
                while (target.exists() || target.path in reserved) {
                    target =
                        File(
                            initial.parentFile,
                            initial.nameWithoutExtension +
                                " ($index)" +
                                initial.extension
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { ".$it" }
                                    .orEmpty())
                    index++
                }
                return target
            }
            fun plan(source: File, initial: File, depth: Int = 0) {
                if (depth > 128) throw IOException("La carpeta supera 128 niveles")
                SafeFiles.requireRegular(source)
                if (!source.isFile && !source.isDirectory)
                    throw IOException("No se copian archivos especiales: ${source.name}")
                val src = source.canonicalFile
                var target = initial.canonicalFile
                if (!target.path.startsWith(dest.path + File.separator))
                    throw IOException("El destino contiene un enlace fuera de la carpeta elegida")
                val same = src == target
                val existing = target.exists() || target.path in reserved
                var skip = same && move
                if (same && !move || existing && conflict == Conflict.RENAME)
                    target = unique(target)
                else if (existing && !(src.isDirectory && target.isDirectory)) {
                    if (conflict == Conflict.SKIP) skip = true
                    else if (src.isDirectory != target.isDirectory)
                        throw IOException("Tipos incompatibles: ${target.name}")
                }
                reserved += target.path
                entries +=
                    Entry(
                        src.path,
                        target.path,
                        src.isDirectory,
                        if (src.isDirectory) 0 else src.length(),
                        src.lastModified(),
                        target.isFile && conflict == Conflict.OVERWRITE && !same,
                        target.length(),
                        target.lastModified(),
                        if (skip) 4 else 0)
                if (entries.size > 100000)
                    throw IOException("Selecciona menos de 100.000 elementos por transferencia")
                if (src.isDirectory && !skip)
                    for (child in
                        src.listFiles() ?: throw IOException("No se pudo leer ${src.name}")) {
                        if (!Files.isSymbolicLink(child.toPath()))
                            plan(child, File(target, child.name), depth + 1)
                    }
            }
            roots.forEach { plan(it, File(dest, it.name)) }
            val id = UUID.randomUUID().toString()
            return DurableCopy(
                    id,
                    dest.path,
                    move,
                    roots.map { it.canonicalPath },
                    entries,
                    File(directory, "$id.job"))
                .apply { save() }
        }

        fun load(file: File): DurableCopy {
            if (file.length() > 32 * 1024 * 1024)
                throw IOException("Registro de transferencia demasiado grande")
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != "OI-COPY-2")
                    throw IOException("Registro de transferencia no válido")
                val id = input.readUTF()
                if (UUID.fromString(id).toString() + ".job" != file.name)
                    throw IOException("Identificador de transferencia no válido")
                val destination = input.readUTF()
                val move = input.readBoolean()
                fun count(): Int =
                    input.readInt().also {
                        if (it !in 0..100000) throw IOException("Registro no válido")
                    }
                val roots = List(count()) { input.readUTF() }
                val entries =
                    MutableList(count()) {
                        Entry(
                            input.readUTF(),
                            input.readUTF(),
                            input.readBoolean(),
                            input.readLong(),
                            input.readLong(),
                            input.readBoolean(),
                            input.readLong(),
                            input.readLong(),
                            input.readInt(),
                            input.readUTF())
                    }
                if (entries.any { it.size < 0 || it.phase !in 0..4 })
                    throw IOException("Registro de transferencia no válido")
                return DurableCopy(id, destination, move, roots, entries, file)
            }
        }

        fun pending(directory: File): List<DurableCopy> =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "job" }
                .mapNotNull { runCatching { load(it) }.getOrNull() }
    }
}
