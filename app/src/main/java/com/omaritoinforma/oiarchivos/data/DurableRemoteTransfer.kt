package com.omaritoinforma.oiarchivos.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Persistent local <-> remote transfer journal.
 *
 * Credentials are never written to disk here. A journal only stores the id of a Connection that
 * remains encrypted by ConnectionStore/Android Keystore.
 */
class DurableRemoteTransfer
private constructor(
    private val appContext: Context,
    override val id: String,
    private val direction: Direction,
    private val connectionId: String,
    override val destination: String,
    private val move: Boolean,
    private val entries: MutableList<Entry>,
    private val journal: File
) : DurableTransfer {
    enum class Direction {
        DOWNLOAD,
        UPLOAD
    }

    private data class Entry(
        val source: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
        val target: String,
        val parentIndex: Int,
        var remotePath: String = "",
        var actualSize: Long = -1,
        var phase: Int = 0
    )

    override val title: String
        get() =
            when (direction) {
                Direction.DOWNLOAD -> if (move) "Moviendo desde red / nube" else "Descargando"
                Direction.UPLOAD -> if (move) "Moviendo a red / nube" else "Subiendo"
            }

    override val completed: Int
        get() = entries.count { !it.directory && it.phase == 3 }

    override val count: Int
        get() = entries.count { !it.directory }

    private fun save() =
        SafeFiles.writeAtomic(journal) { temp ->
            FileOutputStream(temp).use { file ->
                val out = DataOutputStream(BufferedOutputStream(file))
                out.writeUTF(MAGIC)
                out.writeUTF(id)
                out.writeInt(direction.ordinal)
                out.writeUTF(connectionId)
                out.writeUTF(destination)
                out.writeBoolean(move)
                out.writeInt(entries.size)
                entries.forEach { entry ->
                    out.writeUTF(entry.source)
                    out.writeUTF(entry.name)
                    out.writeBoolean(entry.directory)
                    out.writeLong(entry.size)
                    out.writeLong(entry.modified)
                    out.writeUTF(entry.target)
                    out.writeInt(entry.parentIndex)
                    out.writeUTF(entry.remotePath)
                    out.writeLong(entry.actualSize)
                    out.writeInt(entry.phase)
                }
                out.flush()
                file.fd.sync()
            }
        }

    private fun connection(): Connection =
        ConnectionStore(appContext).load().firstOrNull { it.id == connectionId }
            ?: throw IOException(
                "La conexión guardada para esta transferencia ya no existe. Descarta el trabajo o vuelve a crear la conexión.")

    private fun effectiveSize(entry: Entry): Long =
        if (entry.size >= 0) entry.size else entry.actualSize

    private fun partial(index: Int): File {
        val target = checkedLocalTarget(entries[index])
        return File(target.parentFile, ".oi-remote-$id-$index.part")
    }

    private fun checkedLocalTarget(entry: Entry): File {
        val root = File(destination)
        val target = File(entry.target)
        val rootCanonical = root.canonicalFile
        val targetCanonical = target.canonicalFile
        if (rootCanonical.path != destination ||
            targetCanonical.path != entry.target ||
            !targetCanonical.path.startsWith(rootCanonical.path + File.separator) ||
            Files.isSymbolicLink(target.toPath()))
            throw IOException("La ruta local cambió desde que se preparó la transferencia")
        return targetCanonical
    }

    private fun checkedLocalSource(entry: Entry): File {
        val source = File(entry.source)
        if (!source.exists()) throw IOException("El original ya no existe: ${entry.name}")
        if (source.canonicalPath != entry.source || Files.isSymbolicLink(source.toPath()))
            throw IOException("El original cambió de ubicación: ${entry.name}")
        if (entry.directory) {
            if (!source.isDirectory) throw IOException("El original cambió de tipo: ${entry.name}")
        } else {
            SafeFiles.requireRegular(source)
            if (!source.isFile ||
                source.length() != entry.size ||
                source.lastModified() != entry.modified)
                throw IOException("El original cambió: ${entry.name}. Se conserva sin borrar.")
        }
        return source
    }

    private fun remoteParent(entry: Entry): String {
        if (entry.parentIndex < 0) return destination
        val parent = entries.getOrNull(entry.parentIndex)
            ?: throw IOException("Registro remoto dañado")
        if (!parent.directory || parent.phase < 2 || parent.remotePath.isBlank())
            throw IOException("La carpeta remota padre no está preparada")
        return parent.remotePath
    }

    private fun tempName(index: Int) = ".oi-$id-$index.part"

    private fun findRemote(
        fs: RemoteFs,
        parent: String,
        name: String,
        directory: Boolean? = null
    ): RemoteEntry? =
        fs.list(parent).firstOrNull {
            it.name == name && (directory == null || it.directory == directory)
        }

    private fun missing(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "404" in message ||
            "no existe" in message ||
            "not found" in message ||
            "no such file" in message ||
            "does not exist" in message ||
            "file not found" in message
    }

    private fun deleteRemote(fs: RemoteFs, entry: Entry) {
        try {
            fs.delete(RemoteEntry(entry.source, entry.name, entry.directory, entry.size))
        } catch (error: Exception) {
            if (!missing(error)) throw error
        }
    }

    private suspend fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("El archivo remoto cambió durante la reanudación")
            remaining -= n
        }
    }

    private fun tracker(report: (OpProgress) -> Unit): Tracker =
        Tracker(title, report).also { tracker ->
            tracker.totalFiles = count
            tracker.totalBytes =
                entries.filter { !it.directory }.sumOf { maxOf(0L, effectiveSize(it)) }
        }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult {
        val tracker = tracker(report)
        RemoteFiles.connect(connection()).use { fs ->
            return when (direction) {
                Direction.DOWNLOAD -> runDownload(fs, tracker)
                Direction.UPLOAD -> runUpload(fs, tracker)
            }
        }
    }

    private suspend fun runDownload(fs: RemoteFs, tracker: Tracker): OperationResult {
        val changed = ArrayList<File>()
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            tracker.current = entry.name

            if (entry.directory) {
                val target = checkedLocalTarget(entry)
                if (entry.phase == 0) {
                    entry.phase = 1
                    save()
                }
                if (entry.phase == 1) {
                    if (target.exists() && !target.isDirectory)
                        throw IOException("El destino cambió: ${target.name}")
                    if (!target.isDirectory && !target.mkdirs())
                        throw IOException("No se pudo crear ${target.name}")
                    entry.phase = 2
                    save()
                    changed += target
                }
                if (!move && entry.phase == 2) {
                    entry.phase = 3
                    save()
                }
                continue
            }

            val target = checkedLocalTarget(entry)
            val part = partial(index)
            var bytesCounted = false
            var fileCounted = false
            if (entry.phase >= 1) {
                val known = effectiveSize(entry)
                if (known > 0) tracker.doneBytes += known
                bytesCounted = true
            }
            if (entry.phase >= 2) {
                tracker.doneFiles++
                fileCounted = true
            }

            if (entry.phase == 0) {
                if (target.exists())
                    throw IOException("Apareció otro archivo en el destino: ${target.name}")
                if (Files.isSymbolicLink(part.toPath()))
                    throw IOException("La copia parcial fue sustituida por un enlace")
                val resumeAt = if (part.isFile) part.length() else 0L
                if (entry.size >= 0 && resumeAt > entry.size)
                    throw IOException("Copia parcial remota no válida")
                if (resumeAt > 0) {
                    tracker.addBytes(resumeAt)
                    bytesCounted = true
                }
                var bytes = resumeAt
                fs.read(entry.source).use { raw ->
                    val input = BufferedInputStream(raw)
                    skipExactly(input, resumeAt)
                    FileOutputStream(part, true).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                            tracker.addBytes(n.toLong())
                            bytesCounted = true
                        }
                        output.fd.sync()
                    }
                }
                if (entry.size >= 0 && bytes != entry.size)
                    throw IOException("El archivo remoto cambió o la descarga quedó incompleta")
                entry.actualSize = bytes
                entry.phase = 1
                save()
            }

            if (entry.phase == 1) {
                val expected = effectiveSize(entry)
                if (part.isFile) {
                    if (expected >= 0 && part.length() != expected)
                        throw IOException("La copia parcial cambió: ${entry.name}")
                    if (target.exists())
                        throw IOException("Apareció otro archivo en el destino: ${target.name}")
                    SafeFiles.commit(part, target, replace = false)
                    entry.phase = 2
                    save()
                    changed += target
                } else if (
                    target.isFile && (expected < 0 || target.length() == expected)
                ) {
                    // The process may have died after the atomic commit and before persisting phase 2.
                    entry.phase = 2
                    save()
                    changed += target
                } else {
                    throw IOException("Falta la copia parcial de ${entry.name}")
                }
            }

            if (!bytesCounted) {
                val known = effectiveSize(entry)
                if (known > 0) tracker.addBytes(known)
            }
            if (entry.phase >= 2 && !fileCounted) {
                tracker.fileDone()
                fileCounted = true
            }

            if (entry.phase == 2) {
                val expected = effectiveSize(entry)
                if (!target.isFile || (expected >= 0 && target.length() != expected))
                    throw IOException("El archivo descargado cambió: ${entry.name}")
                if (move) deleteRemote(fs, entry)
                entry.phase = 3
                save()
            }
        }

        if (move) {
            for (entry in entries.asReversed().filter { it.directory && it.phase == 2 }) {
                currentCoroutineContext().ensureActive()
                deleteRemote(fs, entry)
                entry.phase = 3
                save()
            }
        }

        journal.delete()
        return OperationResult(
            if (move) "Transferencia remota movida" else "Descarga completada",
            changed.distinctBy { it.path })
    }

    private suspend fun runUpload(fs: RemoteFs, tracker: Tracker): OperationResult {
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            tracker.current = entry.name
            val parent = remoteParent(entry)

            if (entry.directory) {
                if (entry.phase == 0) {
                    // Persist intent before mkdir so a crash after creation can be recognized.
                    entry.phase = 1
                    save()
                }
                if (entry.phase == 1) {
                    val existing = findRemote(fs, parent, entry.name)
                    if (existing != null) {
                        if (!existing.directory)
                            throw IOException("Apareció un archivo llamado ${entry.name} en el destino")
                        entry.remotePath = existing.path
                    } else {
                        entry.remotePath = fs.mkdir(parent, entry.name)
                    }
                    entry.phase = 2
                    save()
                }
                if (!move && entry.phase == 2) {
                    entry.phase = 3
                    save()
                }
                continue
            }

            var bytesCounted = false
            var fileCounted = false
            if (entry.phase >= 1) {
                if (entry.size > 0) tracker.doneBytes += entry.size
                bytesCounted = true
            }
            if (entry.phase >= 2) {
                tracker.doneFiles++
                fileCounted = true
            }

            if (entry.phase == 0) {
                val source = checkedLocalSource(entry)
                if (findRemote(fs, parent, entry.name) != null)
                    throw IOException("Apareció otro archivo en el destino: ${entry.name}")
                val temporaryName = tempName(index)
                val oldTemp = findRemote(fs, parent, temporaryName, false)
                if (oldTemp != null) {
                    if (oldTemp.size >= 0 && oldTemp.size != entry.size)
                        throw IOException("La subida parcial remota cambió")
                    entry.remotePath = oldTemp.path
                    entry.actualSize = entry.size
                    entry.phase = 1
                    save()
                    if (entry.size > 0) tracker.addBytes(entry.size)
                    bytesCounted = true
                } else {
                    val context = currentCoroutineContext()
                    var done = 0L
                    source.inputStream().use { raw ->
                        val input =
                            object : FilterInputStream(raw) {
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    context.ensureActive()
                                    val n = super.read(b, off, len)
                                    if (n > 0) {
                                        done += n
                                        tracker.addBytes(n.toLong())
                                    }
                                    return n
                                }

                                override fun read(): Int {
                                    context.ensureActive()
                                    val value = super.read()
                                    if (value >= 0) {
                                        done++
                                        tracker.addBytes(1)
                                    }
                                    return value
                                }
                            }
                        entry.remotePath =
                            fs.write(parent, temporaryName, input, source.length())
                    }
                    if (done != entry.size)
                        throw IOException("El original cambió durante la subida")
                    checkedLocalSource(entry)
                    entry.actualSize = done
                    entry.phase = 1
                    save()
                    bytesCounted = true
                }
            }

            if (entry.phase == 1) {
                val final = findRemote(fs, parent, entry.name, false)
                if (final != null) {
                    if (final.size >= 0 && final.size != entry.size)
                        throw IOException("El destino remoto cambió: ${entry.name}")
                    entry.remotePath = final.path
                } else {
                    val temporaryName = tempName(index)
                    val temp =
                        findRemote(fs, parent, temporaryName, false)
                            ?: if (entry.remotePath.isNotBlank())
                                RemoteEntry(entry.remotePath, temporaryName, false, entry.size)
                            else null
                    if (temp == null)
                        throw IOException("Falta la subida parcial de ${entry.name}")
                    fs.rename(temp, entry.name)
                    val committed =
                        findRemote(fs, parent, entry.name, false)
                            ?: throw IOException("No se pudo confirmar la subida de ${entry.name}")
                    if (committed.size >= 0 && committed.size != entry.size)
                        throw IOException("La subida no tiene el tamaño esperado")
                    entry.remotePath = committed.path
                }
                entry.phase = 2
                save()
            }

            if (!bytesCounted && entry.size > 0) tracker.addBytes(entry.size)
            if (entry.phase >= 2 && !fileCounted) {
                tracker.fileDone()
                fileCounted = true
            }

            if (entry.phase == 2) {
                if (move) {
                    val source = File(entry.source)
                    if (source.exists()) {
                        checkedLocalSource(entry)
                        if (!source.delete())
                            throw IOException(
                                "Subido, pero no se pudo borrar el original: ${entry.name}")
                    }
                }
                entry.phase = 3
                save()
            }
        }

        if (move) {
            for (entry in entries.asReversed().filter { it.directory && it.phase == 2 }) {
                currentCoroutineContext().ensureActive()
                val source = File(entry.source)
                if (source.exists()) {
                    if (source.canonicalPath != entry.source || !source.isDirectory)
                        throw IOException("La carpeta original cambió: ${entry.name}")
                    if (source.list()?.isNotEmpty() == true)
                        throw IOException(
                            "La carpeta ${entry.name} cambió durante la transferencia; se conserva")
                    if (!source.delete())
                        throw IOException("No se pudo borrar la carpeta original ${entry.name}")
                }
                entry.phase = 3
                save()
            }
        }

        journal.delete()
        return OperationResult(if (move) "Elementos movidos a red / nube" else "Subida completada")
    }

    override suspend fun discard() {
        if (direction == Direction.DOWNLOAD) {
            entries.indices
                .filter { !entries[it].directory }
                .forEach { index ->
                    val part = runCatching { partial(index) }.getOrNull()
                    if (part != null && !Files.isSymbolicLink(part.toPath())) part.delete()
                }
        } else {
            // Only remove stable temporary objects. Completed final objects are deliberately kept,
            // matching DurableCopy's rule that cancel/discard never destroys already committed output.
            runCatching {
                RemoteFiles.connect(connection()).use { fs ->
                    for ((index, entry) in entries.withIndex()) {
                        if (entry.directory || entry.phase >= 2) continue
                        val parent = runCatching { remoteParent(entry) }.getOrNull() ?: continue
                        val temp =
                            runCatching { findRemote(fs, parent, tempName(index), false) }.getOrNull()
                        if (temp != null) runCatching { fs.delete(temp) }
                    }
                }
            }
        }
        journal.delete()
    }

    companion object {
        private const val MAGIC = "OI-REMOTE-1"
        private const val MAX_ENTRIES = 100000
        private const val MAX_JOURNAL = 32L * 1024 * 1024

        private fun uniqueName(existing: MutableSet<String>, original: String): String {
            SafeFiles.requireName(original)
            if (existing.add(original)) return original
            val dot = original.lastIndexOf('.')
            val base = if (dot > 0) original.substring(0, dot) else original
            val ext = if (dot > 0) original.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (existing.add(candidate)) return candidate
                n++
            }
        }

        fun createDownload(
            ctx: Context,
            directory: File,
            connection: Connection,
            roots: List<RemoteEntry>,
            destination: File,
            move: Boolean = false
        ): DurableRemoteTransfer {
            if (roots.isEmpty()) throw IOException("Selecciona al menos un elemento remoto")
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("No se pudo guardar la transferencia")
            val dest = destination.canonicalFile
            if (!dest.isDirectory && !dest.mkdirs())
                throw IOException("No se pudo crear el destino local")
            val id = UUID.randomUUID().toString()
            val entries = ArrayList<Entry>()
            val reserved = dest.list()?.toMutableSet() ?: mutableSetOf()

            RemoteFiles.connect(connection).use { fs ->
                fun plan(remote: RemoteEntry, target: File, depth: Int) {
                    if (depth > 128) throw IOException("La carpeta remota supera 128 niveles")
                    SafeFiles.requireName(remote.name)
                    val canonical = target.canonicalFile
                    if (!canonical.path.startsWith(dest.path + File.separator))
                        throw IOException("Ruta remota no válida")
                    entries +=
                        Entry(
                            remote.path,
                            remote.name,
                            remote.directory,
                            remote.size,
                            0,
                            canonical.path,
                            -1)
                    if (entries.size > MAX_ENTRIES)
                        throw IOException("Selecciona menos de 100.000 elementos por transferencia")
                    if (remote.directory) {
                        for (child in fs.list(remote.path))
                            plan(child, File(canonical, child.name), depth + 1)
                    }
                }

                roots.forEach { root ->
                    val rootName = uniqueName(reserved, root.name)
                    plan(root, File(dest, rootName), 0)
                }
            }

            return DurableRemoteTransfer(
                    ctx.applicationContext,
                    id,
                    Direction.DOWNLOAD,
                    connection.id,
                    dest.path,
                    move,
                    entries,
                    File(directory, "$id.rjob"))
                .apply { save() }
        }

        fun createUpload(
            ctx: Context,
            directory: File,
            connection: Connection,
            sources: List<File>,
            parent: String,
            move: Boolean = false
        ): DurableRemoteTransfer {
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("No se pudo guardar la transferencia")
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
            if (roots.isEmpty()) throw IOException("Selecciona al menos un archivo")
            val id = UUID.randomUUID().toString()
            val entries = ArrayList<Entry>()

            RemoteFiles.connect(connection).use { fs ->
                val reserved = fs.list(parent).map { it.name }.toMutableSet()

                fun plan(source: File, name: String, parentIndex: Int, depth: Int) {
                    if (depth > 128) throw IOException("La carpeta supera 128 niveles")
                    SafeFiles.requireRegular(source)
                    val canonical = source.canonicalFile
                    if (Files.isSymbolicLink(source.toPath()))
                        throw IOException("No se suben enlaces simbólicos")
                    val index = entries.size
                    entries +=
                        Entry(
                            canonical.path,
                            name,
                            canonical.isDirectory,
                            if (canonical.isFile) canonical.length() else 0,
                            canonical.lastModified(),
                            "",
                            parentIndex)
                    if (entries.size > MAX_ENTRIES)
                        throw IOException("Selecciona menos de 100.000 elementos por transferencia")
                    if (canonical.isDirectory) {
                        for (child in
                            canonical.listFiles()
                                ?: throw IOException("No se pudo leer ${canonical.name}")) {
                            plan(child, child.name, index, depth + 1)
                        }
                    }
                }

                roots.forEach { root ->
                    val name = uniqueName(reserved, root.name)
                    plan(root, name, -1, 0)
                }
            }

            return DurableRemoteTransfer(
                    ctx.applicationContext,
                    id,
                    Direction.UPLOAD,
                    connection.id,
                    parent,
                    move,
                    entries,
                    File(directory, "$id.rjob"))
                .apply { save() }
        }

        fun load(ctx: Context, file: File): DurableRemoteTransfer {
            if (file.length() > MAX_JOURNAL)
                throw IOException("Registro de transferencia remota demasiado grande")
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readUTF() != MAGIC)
                    throw IOException("Registro de transferencia remota no válido")
                val id = input.readUTF()
                if (UUID.fromString(id).toString() + ".rjob" != file.name)
                    throw IOException("Identificador de transferencia remota no válido")
                val direction =
                    Direction.entries.getOrNull(input.readInt())
                        ?: throw IOException("Dirección de transferencia no válida")
                val connectionId = input.readUTF()
                val destination = input.readUTF()
                val move = input.readBoolean()
                val count = input.readInt()
                if (count !in 1..MAX_ENTRIES)
                    throw IOException("Registro de transferencia remota no válido")
                val entries =
                    MutableList(count) {
                        Entry(
                            input.readUTF(),
                            input.readUTF(),
                            input.readBoolean(),
                            input.readLong(),
                            input.readLong(),
                            input.readUTF(),
                            input.readInt(),
                            input.readUTF(),
                            input.readLong(),
                            input.readInt())
                    }
                if (entries.any {
                        it.size < -1 ||
                            it.parentIndex !in -1 until entries.size ||
                            it.actualSize < -1 ||
                            it.phase !in 0..3
                    })
                    throw IOException("Registro de transferencia remota no válido")
                return DurableRemoteTransfer(
                    ctx.applicationContext,
                    id,
                    direction,
                    connectionId,
                    destination,
                    move,
                    entries,
                    file)
            }
        }

        fun pending(ctx: Context, directory: File): List<DurableRemoteTransfer> =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "rjob" }
                .mapNotNull { runCatching { load(ctx, it) }.getOrNull() }
    }
}
