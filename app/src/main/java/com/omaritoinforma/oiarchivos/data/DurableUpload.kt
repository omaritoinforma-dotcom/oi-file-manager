package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A durable copy to a remote filesystem with an explicit no-replace publication operation.
 * Incomplete files restart in a new UUID stage; confirmed files are checked and never resent.
 * No local source is deleted. Providers without safe publication must use the ordinary copy UI.
 */
class DurableUpload private constructor(
    override val id: String,
    override val destination: String,
    private val connectionId: String,
    private val connectionStamp: String,
    private val entries: List<Entry>,
    private val journal: File,
    private val opener: (String, String) -> RemoteFs,
    private val stableObjectIds: Boolean,
    private var cleaning: Boolean = false,
    private var retainedStages: Boolean = false
) : DurableTransfer {
    private data class Entry(
        val source: String,
        val parentIndex: Int,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
        val digest: String,
        var phase: Int = 0,
        var attempt: Int = 0,
        var stagePath: String = "",
        var stageRevision: String = "",
        var remotePath: String = "",
        var remoteRevision: String = ""
    )

    data class PublishedSource(
        val source: File,
        val parent: String,
        val remote: RemoteEntry,
        val hash: String,
        val size: Long,
        val modified: Long
    )

    override val title = "Subiendo archivos remotos"
    override val count get() = entries.count { !it.directory }
    override val completed get() = entries.count { !it.directory && it.phase == 2 }
    override val disconnectOnPause = true

    private fun stageName(index: Int) = ".oi-upload-$id-$index-${entries[index].attempt}.part"
    private fun markerName(index: Int) = ".oi-upload-owner-$id-$index"
    private fun markerBytes(index: Int) = "OI-UPLOAD-1:$id:$index".toByteArray(Charsets.UTF_8)
    private fun parent(entry: Entry) =
        if (entry.parentIndex < 0) destination else entries[entry.parentIndex].remotePath.also {
            if (it.isBlank()) throw IOException("La carpeta de subida todavía no está publicada")
        }

    private fun save() = SafeFiles.writeAtomic(journal) { temporary ->
        FileOutputStream(temporary).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.writeUTF("OI-UPLOAD-1")
            out.writeUTF(id)
            out.writeUTF(destination)
            out.writeUTF(connectionId)
            out.writeUTF(connectionStamp)
            out.writeBoolean(stableObjectIds)
            out.writeBoolean(cleaning)
            out.writeBoolean(retainedStages)
            out.writeInt(entries.size)
            entries.forEach { e ->
                out.writeUTF(e.source)
                out.writeInt(e.parentIndex)
                out.writeUTF(e.name)
                out.writeBoolean(e.directory)
                out.writeLong(e.size)
                out.writeLong(e.modified)
                out.writeUTF(e.digest)
                out.writeInt(e.phase)
                out.writeInt(e.attempt)
                out.writeUTF(e.stagePath)
                out.writeUTF(e.stageRevision)
                out.writeUTF(e.remotePath)
                out.writeUTF(e.remoteRevision)
            }
            out.flush()
            file.fd.sync()
        }
    }

    private fun named(fs: RemoteFs, parent: String, name: String): RemoteEntry? {
        val matches = fs.list(parent).filter { it.name == name }
        if (matches.size > 1) throw IOException("El servidor devolvió nombres duplicados: $name")
        return matches.singleOrNull()
    }

    private fun local(entry: Entry): File {
        val file = File(entry.source)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalPath != entry.source ||
            (if (entry.directory) !file.isDirectory else !file.isFile) ||
            file.lastModified() != entry.modified || (!entry.directory && file.length() != entry.size))
            throw IOException("El original local cambió: ${file.name}; no se borra ni mezcla su contenido")
        return file
    }

    private suspend fun checkLocal(entry: Entry): File {
        val file = local(entry)
        if (!entry.directory && hash(file.inputStream(), entry.size) != entry.digest)
            throw IOException("El contenido del original cambió: ${file.name}")
        if (entry.directory) {
            val expected = entries.filter { it.parentIndex >= 0 && entries[it.parentIndex] === entry }
                .map { File(it.source).name }.toSet()
            val current = file.listFiles() ?: throw IOException("No se puede leer ${file.name}")
            if (current.any { Files.isSymbolicLink(it.toPath()) } || current.map { it.name }.toSet() != expected)
                throw IOException("El contenido de la carpeta local cambió: ${file.name}")
        }
        return file
    }

    private suspend fun hash(input: InputStream, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        input.use {
            val buffer = ByteArray(262144)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                if (n < 0) break
                length = Math.addExact(length, n.toLong())
                if (length > size) throw IOException("El archivo cambió de tamaño")
                digest.update(buffer, 0, n)
            }
        }
        if (length != size) throw IOException("El archivo está incompleto")
        return hex(digest.digest())
    }

    private suspend fun checkFile(fs: RemoteFs, remote: RemoteEntry, entry: Entry) {
        if (remote.directory || (remote.size >= 0 && remote.size != entry.size) ||
            hash(fs.read(remote.path), entry.size) != entry.digest)
            throw IOException("El archivo remoto no coincide con la subida: ${entry.name}")
    }

    private fun marker(fs: RemoteFs, folder: RemoteEntry, index: Int): RemoteEntry? {
        if (!folder.directory) throw IOException("La carpeta remota fue sustituida")
        val marker = named(fs, folder.path, markerName(index)) ?: return null
        if (marker.directory) throw IOException("El marcador de subida fue sustituido")
        val expected = markerBytes(index)
        val bytes = fs.read(marker.path).use { input ->
            val data = ByteArray(expected.size + 1)
            var offset = 0
            while (offset < data.size) {
                val n = input.read(data, offset, data.size - offset)
                if (n < 0) break
                offset += n
            }
            data.copyOf(offset)
        }
        if (!bytes.contentEquals(expected)) throw IOException("El marcador de subida no pertenece al trabajo")
        return marker
    }

    private suspend fun checkPublished(fs: RemoteFs, entry: Entry, index: Int): RemoteEntry {
        val actual = named(fs, parent(entry), entry.name)
            ?: throw IOException("Una subida publicada ya no existe: ${entry.name}")
        if (actual.path != entry.remotePath || actual.directory != entry.directory ||
            (!entry.directory && entry.remoteRevision.isNotBlank() && actual.revision != entry.remoteRevision))
            throw IOException("Una subida publicada fue sustituida: ${entry.name}")
        if (entry.directory) {
            if (!cleaning && marker(fs, actual, index) == null)
                throw IOException("La identidad de la carpeta subida cambió")
        } else checkFile(fs, actual, entry)
        return actual
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult = runInternal(report, false)

    suspend fun runKeepingJournal(report: (OpProgress) -> Unit): OperationResult = runInternal(report, true)

    private suspend fun runInternal(report: (OpProgress) -> Unit, keepJournal: Boolean): OperationResult {
        val tracker = Tracker(title, report)
        tracker.totalFiles = count
        tracker.totalBytes = entries.filter { !it.directory }.fold(0L) { n, e -> Math.addExact(n, e.size) }
        val fs = opener(connectionId, connectionStamp)
        try {
            if (!fs.supportsDurableUploads) throw IOException("Este servidor no admite publicación segura recuperable")
            for ((index, entry) in entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                tracker.current = entry.name
                if (entry.phase == 2) {
                    checkPublished(fs, entry, index)
                    if (!entry.directory) { tracker.addBytes(entry.size); tracker.fileDone() }
                    continue
                }
                val source = checkLocal(entry)
                val parent = parent(entry)
                var sentThisRun = false
                var stage = if (entry.attempt > 0) named(fs, parent, stageName(index)) else null
                val final = named(fs, parent, entry.name)
                if (entry.phase == 1 && stage == null && final != null) {
                    // Publication happened before its completion record reached disk. Stable-ID
                    // providers must still refer to the exact staged object, not a same-name copy.
                    if (stableObjectIds && final.path != entry.stagePath)
                        throw IOException("El destino pertenece a otro archivo de la cuenta")
                    if (entry.directory) {
                        if (marker(fs, final, index) == null) throw IOException("No se puede identificar la carpeta publicada")
                    } else checkFile(fs, final, entry)
                    entry.remotePath = final.path
                    entry.remoteRevision = final.revision
                    entry.phase = 2
                    save()
                    if (!entry.directory) { tracker.addBytes(entry.size); tracker.fileDone() }
                    continue
                }
                if (final != null) throw IOException("El destino apareció después de preparar la subida: ${entry.name}")
                if (entry.phase == 1) {
                    val prepared = stage ?: throw IOException("El temporal remoto preparado desapareció")
                    if (prepared.path != entry.stagePath ||
                        (entry.stageRevision.isNotBlank() && prepared.revision != entry.stageRevision))
                        throw IOException("El temporal remoto preparado cambió")
                    if (entry.directory) {
                        if (marker(fs, prepared, index) == null) throw IOException("La carpeta temporal cambió")
                    } else checkFile(fs, prepared, entry)
                } else {
                    // A failed write may have committed a complete stage before returning its ID.
                    // A partial or unrecognizable stage is left intact; retry uses another UUID name.
                    if (stage != null) {
                        val complete = runCatching {
                            if (entry.directory) marker(fs, stage!!, index) != null
                            else { checkFile(fs, stage!!, entry); true }
                        }.getOrDefault(false)
                        currentCoroutineContext().ensureActive()
                        if (!complete) {
                            retainedStages = true
                            save()
                            stage = null
                        }
                    }
                    if (stage == null) {
                        do {
                            entry.attempt = Math.addExact(entry.attempt, 1)
                            save()
                        } while (named(fs, parent, stageName(index)) != null)
                        if (entry.directory) {
                            val path = fs.mkdir(parent, stageName(index))
                            val bytes = markerBytes(index)
                            fs.write(path, markerName(index), ByteArrayInputStream(bytes), bytes.size.toLong())
                        } else {
                            sentThisRun = true
                            val context = currentCoroutineContext()
                            source.inputStream().use { input ->
                                val tracked = object : FilterInputStream(input) {
                                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                        context.ensureActive()
                                        val n = super.read(bytes, offset, length)
                                        if (n > 0) tracker.addBytes(n.toLong())
                                        return n
                                    }
                                    override fun read(): Int {
                                        context.ensureActive()
                                        return super.read().also { if (it >= 0) tracker.addBytes(1) }
                                    }
                                }
                                fs.write(parent, stageName(index), tracked, entry.size)
                            }
                            checkLocal(entry)
                        }
                        stage = named(fs, parent, stageName(index))
                            ?: throw IOException("El servidor no devolvió el temporal subido")
                    }
                    if (entry.directory) {
                        if (marker(fs, stage!!, index) == null) throw IOException("El temporal no tiene identidad verificable")
                    } else checkFile(fs, stage!!, entry)
                    entry.stagePath = stage!!.path
                    entry.stageRevision = stage!!.revision
                    entry.phase = 1
                    save()
                }
                currentCoroutineContext().ensureActive()
                checkLocal(entry)
                if (!entry.directory && !sentThisRun) tracker.addBytes(entry.size)
                val published = fs.publishUpload(stage!!, parent, entry.name)
                val actual = named(fs, parent, entry.name)
                    ?: throw IOException("El servidor no confirmó la publicación")
                if (actual.path != published) throw IOException("La publicación devolvió otra identidad")
                if (stableObjectIds && actual.path != entry.stagePath)
                    throw IOException("La identidad del archivo cambió durante la publicación")
                if (entry.directory) {
                    if (marker(fs, actual, index) == null) throw IOException("La carpeta publicada no coincide")
                } else checkFile(fs, actual, entry)
                entry.remotePath = actual.path
                entry.remoteRevision = actual.revision
                entry.phase = 2
                save()
                if (!entry.directory) tracker.fileDone()
            }
            // Check every already-confirmed object again before declaring the whole batch complete.
            entries.forEachIndexed { index, entry -> checkPublished(fs, entry, index) }
            if (!cleaning) { cleaning = true; save() }
            entries.forEachIndexed { index, entry ->
                if (entry.directory) {
                    val folder = named(fs, parent(entry), entry.name)
                        ?: throw IOException("La carpeta publicada desapareció")
                    marker(fs, folder, index)?.let {
                        if (!fs.deleteIfUnchanged(it)) retainedStages = true
                    }
                }
            }
        } finally { runCatching { fs.close() } }
        if (!keepJournal) journal.delete()
        return OperationResult(if (retainedStages) "Subidos: $count; temporales conservados para revisión"
            else "Subidos: $count")
    }

    /** Snapshots are useful to a separate move workflow; this class never deletes originals. */
    fun publishedSources(): List<PublishedSource> = entries.filter { it.phase == 2 }.map {
        PublishedSource(File(it.source), parent(it),
            RemoteEntry(it.remotePath, it.name, it.directory, it.size, it.remoteRevision),
            it.digest, it.size, it.modified)
    }

    override fun discard() {
        // Only complete UUID stages with verifiable contents/markers may be removed. Interrupted
        // writes that cannot be identified safely can remain on an offline or incompatible server.
        runCatching {
            opener(connectionId, connectionStamp).use { fs ->
                entries.forEachIndexed { index, e ->
                    runCatching entryCleanup@{
                        if (e.phase == 1) {
                            val stage = named(fs, parent(e), stageName(index)) ?: return@entryCleanup
                            if (stage.path != e.stagePath ||
                                (e.stageRevision.isNotBlank() && stage.revision != e.stageRevision))
                                return@entryCleanup
                            if (e.directory) {
                                val ownedMarker = marker(fs, stage, index) ?: return@entryCleanup
                                if (fs.deleteIfUnchanged(ownedMarker)) fs.deleteEmptyDirectory(stage)
                            } else {
                                val expected = e.digest
                                val remoteHash = MessageDigest.getInstance("SHA-256")
                                var bytes = 0L
                                fs.read(stage.path).use { input ->
                                    val buffer = ByteArray(262144)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        bytes = Math.addExact(bytes, n.toLong())
                                        if (bytes > e.size) throw IOException("Temporal sustituido")
                                        remoteHash.update(buffer, 0, n)
                                    }
                                }
                                if (bytes == e.size && hex(remoteHash.digest()) == expected)
                                    fs.deleteIfUnchanged(stage)
                            }
                        } else if (e.phase == 2 && e.directory) {
                            val folder = named(fs, parent(e), e.name) ?: return@entryCleanup
                            if (folder.path == e.remotePath)
                                marker(fs, folder, index)?.let { fs.deleteIfUnchanged(it) }
                        }
                    }
                }
            }
        }
        journal.delete()
    }

    companion object {
        private const val MAX_ENTRIES = 100000
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun storedConnection(id: String, expected: String): RemoteFs {
            val connection = ConnectionStore(RemoteFiles.appContext).load().singleOrNull { it.id == id }
                ?: throw IOException("La conexión guardada ya no existe")
            if (DurableDownload.stamp(connection) != expected)
                throw IOException("La cuenta o servidor cambió; se conserva la subida")
            return RemoteFiles.connect(connection)
        }

        suspend fun create(
            directory: File,
            connection: Connection,
            fs: RemoteFs,
            parent: String,
            sources: List<File>,
            opener: (String, String) -> RemoteFs = ::storedConnection
        ): DurableUpload {
            if (!fs.supportsDurableUploads) throw IOException("Este proveedor no admite subidas recuperables sin reemplazar archivos")
            if (sources.isEmpty() || parent.isBlank() || parent.contains('\u0000')) throw IOException("Subida no válida")
            if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs()))
                throw IOException("No se pudo guardar la subida")
            sources.forEach {
                if (Files.isSymbolicLink(it.toPath()) || (!it.isFile && !it.isDirectory))
                    throw IOException("No se admiten enlaces ni archivos especiales en una subida")
            }
            val roots = sources.distinctBy { it.canonicalPath }.filter { source ->
                sources.none { other -> other.isDirectory && other.canonicalPath != source.canonicalPath &&
                    source.canonicalPath.startsWith(other.canonicalPath.trimEnd(File.separatorChar) + File.separator) }
            }
            val entries = ArrayList<Entry>()
            val occupied = fs.list(parent).map { it.name }.toMutableSet()
            fun reserve(name: String): String {
                SafeFiles.requireName(name)
                val base = name.substringBeforeLast('.', name)
                val suffix = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var result = name
                var number = 1
                while (!occupied.add(result)) result = "$base (${number++})$suffix"
                return result
            }
            suspend fun visit(source: File, parentIndex: Int, targetName: String, depth: Int) {
                currentCoroutineContext().ensureActive()
                SafeFiles.requireName(source.name)
                if (depth > 128 || entries.size >= MAX_ENTRIES || Files.isSymbolicLink(source.toPath()))
                    throw IOException("La subida excede el recorrido seguro o contiene enlaces")
                val file = source.canonicalFile
                if (!file.isFile && !file.isDirectory) throw IOException("No se admite este archivo")
                val isDirectory = file.isDirectory
                val size = if (isDirectory) 0 else file.length()
                val modified = file.lastModified()
                val hash = if (isDirectory) "" else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(262144)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    if (file.length() != size || file.lastModified() != modified)
                        throw IOException("El archivo cambió al preparar la subida")
                    hex(digest.digest())
                }
                val index = entries.size
                entries += Entry(file.path, parentIndex, targetName, isDirectory, size, modified, hash)
                if (isDirectory) {
                    val children = file.listFiles() ?: throw IOException("No se puede leer ${file.name}")
                    children.sortedBy { it.name }.forEach { visit(it, index, it.name, depth + 1) }
                }
            }
            roots.forEach { visit(it, -1, reserve(it.name), 0) }
            if (entries.isEmpty()) throw IOException("La subida no tiene archivos")
            val id = UUID.randomUUID().toString()
            return DurableUpload(id, parent, connection.id, DurableDownload.stamp(connection), entries,
                File(directory, "$id.upload-job"), opener,
                connection.protocol in setOf(Protocol.DRIVE, Protocol.ONEDRIVE, Protocol.BOX)).also { it.save() }
        }

        fun load(file: File, opener: (String, String) -> RemoteFs = ::storedConnection): DurableUpload {
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Registro de subida no válido")
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readUTF() != "OI-UPLOAD-1") throw IOException("Formato de subida no compatible")
                val id = input.readUTF()
                UUID.fromString(id)
                if (file.name != "$id.upload-job") throw IOException("Identificador de subida no válido")
                val destination = input.readUTF()
                val connection = input.readUTF()
                val stamp = input.readUTF()
                val stableObjectIds = input.readBoolean()
                val cleaning = input.readBoolean()
                val retainedStages = input.readBoolean()
                val count = input.readInt()
                if (count !in 1..MAX_ENTRIES) throw IOException("Registro de subida no válido")
                val entries = List(count) {
                    Entry(input.readUTF(), input.readInt(), input.readUTF(), input.readBoolean(),
                        input.readLong(), input.readLong(), input.readUTF(), input.readInt(), input.readInt(),
                        input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
                }
                if (destination.isBlank() || destination.contains('\u0000') || connection.isBlank() ||
                    !stamp.matches(Regex("[a-f0-9]{64}")) || input.read() != -1 ||
                    entries.map { it.source }.distinct().size != entries.size ||
                    entries.withIndex().any { (index, e) ->
                        e.parentIndex !in -1 until index || (e.parentIndex >= 0 && !entries[e.parentIndex].directory) ||
                        !SafeFiles.validName(e.name) || !File(e.source).isAbsolute || e.source.contains('\u0000') ||
                        e.phase !in 0..2 || e.attempt < 0 || e.size < 0 ||
                        (!e.directory && !e.digest.matches(Regex("[a-f0-9]{64}"))) ||
                        (e.phase > 0 && (e.attempt == 0 || e.stagePath.isBlank())) ||
                        (e.phase == 2 && e.remotePath.isBlank())
                    } || entries.groupBy { it.parentIndex }.values.any { siblings ->
                        siblings.map { it.name }.distinct().size != siblings.size
                    }) throw IOException("Registro de subida no válido")
                return DurableUpload(id, destination, connection, stamp, entries, file, opener,
                    stableObjectIds, cleaning, retainedStages)
            }
        }

        fun pending(directory: File): List<DurableUpload> = directory.listFiles().orEmpty()
            .filter { it.extension == "upload-job" }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
    }
}
