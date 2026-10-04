package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A transfer whose journal survives process death and can be resumed from Transferencias. */
interface DurableJob {
    val id: String
    val title: String
    val destination: String
    val completed: Int
    val count: Int

    fun discard()

    suspend fun run(report: (OpProgress) -> Unit): OperationResult
}

/**
 * Network/cloud download or upload journal. Only the connection id is stored; credentials stay in
 * the Keystore-encrypted [ConnectionStore] and are resolved through [connector] when resuming.
 */
class DurableRemote
private constructor(
    override val id: String,
    val upload: Boolean,
    val move: Boolean,
    val connectionId: String,
    private val label: String,
    /** Upload: remote parent folder. Download: local destination folder. */
    private val root: String,
    private val entries: MutableList<Entry>,
    private val journal: File,
    private val connector: (String) -> RemoteFs,
) : DurableJob {
    private class Entry(
        /** Download: local target. Upload: local source. */
        var local: String,
        /** Download: remote source. Upload: created remote path, empty until stored. */
        var remote: String,
        /** Download: remote parent used to re-check the source. */
        val remoteParent: String,
        /** Upload: remote name, made unique for top-level items before writing. */
        var name: String,
        /** Upload: index of the parent folder entry, -1 for [root]. */
        val parent: Int,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
        var phase: Int = PENDING,
    )

    override val title
        get() =
            when {
                move -> "Moviendo"
                upload -> "Subiendo"
                else -> "Descargando"
            }

    override val destination
        get() = if (upload) "$label: $root" else root

    override val completed
        get() = entries.count { !it.directory && it.phase >= STORED }

    override val count
        get() = entries.count { !it.directory }

    private fun partial(index: Int) =
        File(File(entries[index].local).parentFile, ".oi-resume-$id-$index.part")

    private fun save() =
        SafeFiles.writeAtomic(journal) { temp ->
            FileOutputStream(temp).use { file ->
                val out = DataOutputStream(BufferedOutputStream(file))
                out.writeUTF(MAGIC)
                out.writeUTF(id)
                out.writeBoolean(upload)
                out.writeBoolean(move)
                out.writeUTF(connectionId)
                out.writeUTF(label)
                out.writeUTF(root)
                out.writeInt(entries.size)
                entries.forEach { e ->
                    out.writeUTF(e.local)
                    out.writeUTF(e.remote)
                    out.writeUTF(e.remoteParent)
                    out.writeUTF(e.name)
                    out.writeInt(e.parent)
                    out.writeBoolean(e.directory)
                    out.writeLong(e.size)
                    out.writeLong(e.modified)
                    out.writeInt(e.phase)
                }
                out.flush()
                file.fd.sync()
            }
        }

    /**
     * Removes the journal and local partial files. Remote writers commit atomically or not at all.
     */
    override fun discard() {
        if (!upload)
            entries.indices.forEach { index ->
                if (!entries[index].directory) {
                    val part = partial(index)
                    if (!java.nio.file.Files.isSymbolicLink(part.toPath())) part.delete()
                }
            }
        journal.delete()
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult {
        val tracker = Tracker(title, report)
        tracker.totalFiles = count
        tracker.totalBytes = entries.filter { !it.directory }.sumOf { maxOf(it.size, 0) }
        val fs =
            try {
                connector(connectionId)
            } catch (e: NoSuchElementException) {
                throw IOException("La conexión «$label» ya no existe")
            }
        val changed = fs.use { if (upload) runUpload(it, tracker) else runDownload(it, tracker) }
        journal.delete()
        return OperationResult(
            "${if (move) "Movidos" else if (upload) "Subidos" else "Descargados"}: $count",
            changed,
        )
    }

    private fun checkedLocal(path: String): File {
        val base = File(root)
        val file = File(path)
        if (
            base.canonicalPath != root ||
                file.canonicalPath != path ||
                !path.startsWith(root + File.separator)
        )
            throw IOException("La ruta de destino cambió desde que se inició la transferencia")
        return file
    }

    private fun remoteEntry(e: Entry) = RemoteEntry(e.remote, e.name, e.directory, e.size)

    private suspend fun runDownload(fs: RemoteFs, tracker: Tracker): List<File> {
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (entry.directory) {
                val dir = checkedLocal(entry.local)
                if (!dir.isDirectory && !dir.mkdirs())
                    throw IOException("No se pudo crear ${dir.name}")
                if (entry.phase < DONE) {
                    entry.phase = DONE
                    save()
                }
                continue
            }
            tracker.current = entry.name
            if (entry.phase >= STORED) {
                if (entry.phase == STORED) if (move) deleteRemote(fs, entry) else markDone(entry)
                tracker.addBytes(maxOf(entry.size, 0))
                tracker.fileDone()
                continue
            }
            val part = partial(index)
            if (java.nio.file.Files.isSymbolicLink(part.toPath()))
                throw IOException("El archivo parcial fue sustituido por un enlace")
            if (entry.phase == READY && !part.isFile) {
                // The process may have died between the rename and saving the journal.
                val stored = checkedLocal(entry.local)
                if (stored.isFile && (entry.size < 0 || stored.length() == entry.size))
                    entry.phase = STORED
                else entry.phase = PENDING
                save()
            }
            if (entry.phase == PENDING || entry.phase == ACTIVE) {
                val parent = checkedLocal(entry.local).parentFile!!
                if (!parent.isDirectory && !parent.mkdirs())
                    throw IOException("No se pudo crear el destino")
                if (entry.phase == PENDING) {
                    entry.phase = ACTIVE
                    save()
                }
                fetch(fs, entry, part, tracker)
                // Fix the final local name before renaming so a restart can recognize it.
                var target = checkedLocal(entry.local)
                if (target.exists()) {
                    target = FileOps.uniqueName(target.parentFile!!, target.name)
                    entry.local = target.path
                }
                entry.phase = READY
                save()
            } else tracker.addBytes(maxOf(entry.size, 0))
            if (entry.phase == READY) {
                currentCoroutineContext().ensureActive()
                var target = checkedLocal(entry.local)
                if (target.exists()) {
                    target = FileOps.uniqueName(target.parentFile!!, target.name)
                    entry.local = target.path
                    save()
                }
                SafeFiles.commit(part, target, replace = false)
                entry.phase = STORED
                save()
            }
            if (move) deleteRemote(fs, entry) else markDone(entry)
            tracker.fileDone()
        }
        if (move)
            entries
                .asReversed()
                .filter { it.directory }
                .forEach { e ->
                    currentCoroutineContext().ensureActive()
                    runCatching { if (fs.list(e.remote).isEmpty()) fs.delete(remoteEntry(e)) }
                }
        return entries.filter { it.parent < 0 || !it.directory }.map { File(it.local) }
    }

    private suspend fun fetch(fs: RemoteFs, entry: Entry, part: File, tracker: Tracker) {
        var offset = if (part.isFile) part.length() else 0L
        if (entry.size in 0 until offset) offset = 0
        var input: InputStream? = null
        if (offset > 0) {
            val current = fs.list(entry.remoteParent).firstOrNull { it.path == entry.remote }
            if (current == null || current.directory)
                throw IOException("El archivo remoto ya no existe: ${entry.name}")
            // A different size means the server copy changed, so the prefix is not reused.
            input = if (current.size == entry.size) fs.readFrom(entry.remote, offset) else null
            if (input == null) offset = 0
        }
        if (offset == 0L) {
            FileOutputStream(part, false).use { it.fd.sync() }
            input = fs.read(entry.remote)
        }
        tracker.addBytes(offset)
        var done = offset
        input!!.use { stream ->
            FileOutputStream(part, true).use { out ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    tracker.addBytes(n.toLong())
                }
                out.fd.sync()
            }
        }
        if (entry.size >= 0 && done != entry.size)
            throw IOException(
                if (done < entry.size) "Descarga incompleta: ${entry.name}"
                else "El archivo remoto cambió durante la transferencia: ${entry.name}"
            )
    }

    private suspend fun deleteRemote(fs: RemoteFs, entry: Entry) {
        currentCoroutineContext().ensureActive()
        try {
            fs.delete(remoteEntry(entry))
        } catch (e: IOException) {
            // A restart after a delete that was not recorded finds the original already gone.
            if (fs.list(entry.remoteParent).any { it.path == entry.remote }) throw e
        }
        markDone(entry)
    }

    private fun markDone(entry: Entry) {
        entry.phase = DONE
        save()
    }

    private fun checkSource(entry: Entry): File {
        val source = File(entry.local)
        SafeFiles.requireRegular(source)
        if (
            source.canonicalPath != entry.local ||
                source.isDirectory != entry.directory ||
                !entry.directory &&
                    (source.length() != entry.size || source.lastModified() != entry.modified)
        )
            throw IOException("El original cambió: ${source.name}. Se conserva sin borrar.")
        return source
    }

    private suspend fun runUpload(fs: RemoteFs, tracker: Tracker): List<File> {
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val parentPath = if (entry.parent < 0) root else entries[entry.parent].remote
            if (parentPath.isEmpty()) throw IOException("Registro de transferencia no válido")
            tracker.current = entry.name
            if (entry.phase == PENDING) {
                checkSource(entry)
                if (entry.parent < 0) entry.name = RemoteFiles.unique(fs, parentPath, entry.name)
                // The name is recorded before writing so a restart can replace our own leftover.
                entry.phase = ACTIVE
                save()
            }
            if (entry.directory) {
                if (entry.phase == ACTIVE) {
                    entry.remote =
                        fs.list(parentPath)
                            .firstOrNull { it.directory && it.name == entry.name }
                            ?.path ?: fs.mkdir(parentPath, entry.name)
                    entry.phase = DONE
                    save()
                }
                continue
            }
            if (entry.phase == ACTIVE) {
                val source = checkSource(entry)
                fs.list(parentPath)
                    .firstOrNull { !it.directory && it.name == entry.name }
                    ?.let { fs.delete(it) }
                val context = currentCoroutineContext()
                source.inputStream().use { raw ->
                    val input =
                        object : FilterInputStream(raw) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                context.ensureActive()
                                val n = super.read(b, off, len)
                                if (n > 0) tracker.addBytes(n.toLong())
                                return n
                            }

                            override fun read(): Int {
                                context.ensureActive()
                                return super.read().also { if (it >= 0) tracker.addBytes(1) }
                            }
                        }
                    entry.remote =
                        try {
                            fs.write(parentPath, entry.name, input, entry.size)
                        } catch (e: Exception) {
                            throw e.cancellation() ?: e
                        }
                }
                checkSource(entry)
                val stored =
                    fs.list(parentPath).firstOrNull { !it.directory && it.name == entry.name }
                if (stored != null && stored.size >= 0 && stored.size != entry.size)
                    throw IOException("El servidor guardó un tamaño distinto: ${entry.name}")
                entry.phase = STORED
                save()
            } else tracker.addBytes(entry.size)
            if (entry.phase == STORED) {
                if (move) {
                    val source = File(entry.local)
                    if (source.exists()) {
                        checkSource(entry)
                        currentCoroutineContext().ensureActive()
                        if (!source.delete())
                            throw IOException(
                                "Subido, pero no se pudo borrar el original: ${source.name}"
                            )
                    }
                }
                markDone(entry)
            }
            tracker.fileDone()
        }
        if (move)
            entries
                .asReversed()
                .filter { it.directory }
                .forEach { e ->
                    val original = File(e.local)
                    if (
                        original.canonicalPath == e.local &&
                            original.isDirectory &&
                            original.list()?.isEmpty() == true
                    )
                        original.delete()
                }
        return emptyList()
    }

    companion object {
        private const val MAGIC = "OI-REMOTE-1"
        private const val PENDING = 0
        private const val ACTIVE = 1
        private const val READY = 2
        private const val STORED = 3
        private const val DONE = 4
        private const val LIMIT = 100000

        private fun prepare(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("No se pudo guardar la transferencia")
        }

        /** Lists the remote tree once and records every file before the first byte is copied. */
        suspend fun createDownload(
            directory: File,
            fs: RemoteFs,
            connection: Connection,
            sources: List<RemoteEntry>,
            parent: String,
            destination: File,
            move: Boolean,
            connector: (String) -> RemoteFs,
        ): DurableRemote {
            prepare(directory)
            if (sources.isEmpty()) throw IOException("Selecciona al menos un archivo")
            if (!destination.isDirectory && !destination.mkdirs())
                throw IOException("Destino no válido")
            val dest = destination.canonicalFile
            val entries = ArrayList<Entry>()
            val reserved = hashSetOf<String>()
            suspend fun plan(
                entry: RemoteEntry,
                remoteParent: String,
                target: File,
                parentIndex: Int,
                depth: Int,
            ) {
                currentCoroutineContext().ensureActive()
                SafeFiles.requireName(entry.name)
                if (depth > 128) throw IOException("La carpeta remota supera 128 niveles")
                entries +=
                    Entry(
                        target.path,
                        entry.path,
                        remoteParent,
                        entry.name,
                        parentIndex,
                        entry.directory,
                        if (entry.directory) 0 else entry.size,
                        0,
                    )
                if (entries.size > LIMIT)
                    throw IOException("Selecciona menos de 100.000 elementos por transferencia")
                if (entry.directory) {
                    val self = entries.lastIndex
                    for (child in fs.list(entry.path)) plan(
                        child,
                        entry.path,
                        File(target, child.name),
                        self,
                        depth + 1,
                    )
                }
            }
            for (entry in sources) {
                SafeFiles.requireName(entry.name)
                var target = FileOps.uniqueName(dest, entry.name)
                var index = 1
                while (target.path in reserved) {
                    target = FileOps.uniqueName(dest, numbered(entry.name, index++))
                }
                reserved += target.path
                plan(entry, parent, target, -1, 0)
            }
            val id = UUID.randomUUID().toString()
            return DurableRemote(
                    id,
                    false,
                    move,
                    connection.id,
                    connection.label,
                    dest.path,
                    entries,
                    File(directory, "$id.rjob"),
                    connector,
                )
                .apply { save() }
        }

        fun createUpload(
            directory: File,
            connection: Connection,
            sources: List<File>,
            parent: String,
            move: Boolean,
            connector: (String) -> RemoteFs,
        ): DurableRemote {
            prepare(directory)
            val roots = sources.distinctBy { it.canonicalPath }
            if (roots.isEmpty()) throw IOException("Selecciona al menos un archivo")
            val entries = ArrayList<Entry>()
            fun plan(source: File, parentIndex: Int, depth: Int) {
                if (depth > 128) throw IOException("La carpeta supera 128 niveles")
                SafeFiles.requireRegular(source)
                if (!source.isFile && !source.isDirectory)
                    throw IOException("No se suben archivos especiales: ${source.name}")
                val src = source.canonicalFile
                entries +=
                    Entry(
                        src.path,
                        "",
                        "",
                        src.name,
                        parentIndex,
                        src.isDirectory,
                        if (src.isDirectory) 0 else src.length(),
                        src.lastModified(),
                    )
                if (entries.size > LIMIT)
                    throw IOException("Selecciona menos de 100.000 elementos por transferencia")
                if (src.isDirectory) {
                    val self = entries.lastIndex
                    for (child in
                        src.listFiles() ?: throw IOException("No se pudo leer ${src.name}")) {
                        if (!java.nio.file.Files.isSymbolicLink(child.toPath()))
                            plan(child, self, depth + 1)
                    }
                }
            }
            roots.forEach { plan(it, -1, 0) }
            val id = UUID.randomUUID().toString()
            return DurableRemote(
                    id,
                    true,
                    move,
                    connection.id,
                    connection.label,
                    parent,
                    entries,
                    File(directory, "$id.rjob"),
                    connector,
                )
                .apply { save() }
        }

        private fun numbered(name: String, index: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) + " ($index)" + name.substring(dot)
            else "$name ($index)"
        }

        fun load(file: File, connector: (String) -> RemoteFs): DurableRemote {
            if (file.length() > 64 * 1024 * 1024)
                throw IOException("Registro de transferencia demasiado grande")
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != MAGIC)
                    throw IOException("Registro de transferencia no válido")
                val id = input.readUTF()
                if (UUID.fromString(id).toString() + ".rjob" != file.name)
                    throw IOException("Identificador de transferencia no válido")
                val upload = input.readBoolean()
                val move = input.readBoolean()
                val connectionId = input.readUTF()
                val label = input.readUTF()
                val root = input.readUTF()
                val size = input.readInt()
                if (size !in 0..LIMIT) throw IOException("Registro no válido")
                val entries =
                    MutableList(size) {
                        Entry(
                            input.readUTF(),
                            input.readUTF(),
                            input.readUTF(),
                            input.readUTF(),
                            input.readInt(),
                            input.readBoolean(),
                            input.readLong(),
                            input.readLong(),
                            input.readInt(),
                        )
                    }
                if (
                    entries.withIndex().any { (i, e) ->
                        e.phase !in PENDING..DONE ||
                            e.parent !in -1 until i ||
                            e.parent >= 0 && !entries[e.parent].directory
                    }
                )
                    throw IOException("Registro de transferencia no válido")
                return DurableRemote(
                    id,
                    upload,
                    move,
                    connectionId,
                    label,
                    root,
                    entries,
                    file,
                    connector,
                )
            }
        }

        fun pending(directory: File, connector: (String) -> RemoteFs): List<DurableRemote> =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "rjob" }
                .mapNotNull { runCatching { load(it, connector) }.getOrNull() }
    }
}
