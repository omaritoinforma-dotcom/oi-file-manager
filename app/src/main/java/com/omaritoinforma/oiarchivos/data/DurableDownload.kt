package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Recoverable downloads for every RemoteFs, including providers without byte ranges.
 * A reopened stream must match every persisted byte before its remainder is appended.
 * Pausing closes the connection; resuming retrieves current credentials from Keystore.
 */
class DurableDownload private constructor(
    override val id: String,
    override val destination: String,
    private val connectionId: String,
    private val connectionStamp: String,
    private val entries: List<Entry>,
    private val journal: File,
    private val opener: (String, String) -> RemoteFs
) : DurableTransfer {
    private data class Entry(
        val parent: String,
        val source: RemoteEntry,
        val target: String,
        var phase: Int = 0,
        var length: Long = 0,
        var digest: String = ""
    )

    override val title = "Descargando archivos remotos"
    override val completed get() = entries.count { !it.source.directory && it.phase == 2 }
    override val count get() = entries.count { !it.source.directory }
    override val disconnectOnPause = true

    private fun part(index: Int) =
        File(File(entries[index].target).parentFile, ".oi-download-$id-$index.part")

    private fun checkedTarget(entry: Entry): File {
        val root = File(destination)
        val target = File(entry.target)
        if (!root.isDirectory || root.canonicalPath != destination ||
            target.canonicalPath != entry.target ||
            !entry.target.startsWith(destination.trimEnd(File.separatorChar) + File.separator) ||
            Files.isSymbolicLink(target.toPath()))
            throw IOException("La ruta de destino cambió; se conserva la descarga parcial")
        return target
    }

    private fun save() = SafeFiles.writeAtomic(journal) { temp ->
        FileOutputStream(temp).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.writeUTF("OI-DOWNLOAD-1")
            out.writeUTF(id)
            out.writeUTF(destination)
            out.writeUTF(connectionId)
            out.writeUTF(connectionStamp)
            out.writeInt(entries.size)
            entries.forEach { entry ->
                out.writeUTF(entry.parent)
                out.writeUTF(entry.source.path)
                out.writeUTF(entry.source.name)
                out.writeBoolean(entry.source.directory)
                out.writeLong(entry.source.size)
                out.writeUTF(entry.source.revision)
                out.writeUTF(entry.target)
                out.writeInt(entry.phase)
                out.writeLong(entry.length)
                out.writeUTF(entry.digest)
            }
            out.flush()
            file.fd.sync()
        }
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
        return hex(hash.digest())
    }

    private fun checkSource(fs: RemoteFs, entry: Entry) {
        val actual = fs.list(entry.parent).singleOrNull { it.path == entry.source.path }
        if (actual != entry.source)
            throw IOException("El original remoto cambió o ya no existe: ${entry.source.name}")
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult =
        runInternal(report, keepJournal = false)

    /** Parent transfers retain the committed manifest until their next phase is durable. */
    suspend fun runKeepingJournal(report: (OpProgress) -> Unit): OperationResult =
        runInternal(report, keepJournal = true)

    data class DownloadedSource(val parent: String, val source: RemoteEntry, val local: File)

    fun downloadedSources(): List<DownloadedSource> = entries.map {
        DownloadedSource(it.parent, it.source, File(it.target))
    }

    private suspend fun runInternal(
        report: (OpProgress) -> Unit,
        keepJournal: Boolean
    ): OperationResult {
        val tracker = Tracker(title, report)
        tracker.totalFiles = count
        val files = entries.filter { !it.source.directory }
        tracker.totalBytes = if (files.any { it.source.size < 0 }) 0 else
            files.fold(0L) { sum, entry -> Math.addExact(sum, entry.source.size) }
        var remote: RemoteFs? = null
        fun filesystem(): RemoteFs = remote ?: opener(connectionId, connectionStamp).also { remote = it }
        try {
            for ((index, entry) in entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                val target = checkedTarget(entry)
                tracker.current = target.name
                if (entry.source.directory) {
                    if (!target.isDirectory && !target.mkdirs())
                        throw IOException("No se pudo crear ${target.name}")
                    if (entry.phase != 2) { entry.phase = 2; save() }
                    continue
                }
                if (entry.phase == 2) {
                    if (!target.isFile || target.length() != entry.length || digest(target) != entry.digest)
                        throw IOException("Una descarga completada cambió: ${target.name}")
                    tracker.addBytes(entry.length)
                    tracker.fileDone()
                    continue
                }
                val partial = part(index)
                if (Files.isSymbolicLink(partial.toPath()) || (partial.exists() && !partial.isFile))
                    throw IOException("El archivo parcial fue sustituido")
                if (entry.phase == 1 && !partial.exists() && target.isFile &&
                    target.length() == entry.length && digest(target) == entry.digest) {
                    // Commit succeeded but the process died before recording completion.
                    entry.phase = 2
                    save()
                    tracker.addBytes(entry.length)
                    tracker.fileDone()
                    continue
                }
                if (target.exists()) throw IOException("El destino ya existe: ${target.name}. No se sobrescribe.")
                if (entry.phase == 0) {
                    if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs())
                        throw IOException("No se pudo crear el destino")
                    val fs = filesystem()
                    checkSource(fs, entry)
                    val prefix = if (partial.exists()) partial.length() else 0L
                    if (entry.source.size >= 0 && prefix > entry.source.size)
                        throw IOException("Descarga parcial no válida")
                    val hash = MessageDigest.getInstance("SHA-256")
                    var bytes = prefix
                    fs.read(entry.source.path).use { raw ->
                        val input = DataInputStream(raw)
                        if (prefix > 0) partial.inputStream().use { previous ->
                            val old = DataInputStream(previous)
                            val a = ByteArray(262144)
                            val b = ByteArray(a.size)
                            var remaining = prefix
                            while (remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                val n = minOf(remaining, a.size.toLong()).toInt()
                                input.readFully(a, 0, n)
                                old.readFully(b, 0, n)
                                if (!(0 until n).all { a[it] == b[it] })
                                    throw IOException("El original o la descarga parcial cambió; no se mezclan sus contenidos")
                                hash.update(a, 0, n)
                                tracker.addBytes(n.toLong())
                                remaining -= n
                            }
                        }
                        FileOutputStream(partial, true).use { out ->
                            try {
                                val buffer = ByteArray(262144)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    hash.update(buffer, 0, n)
                                    bytes = Math.addExact(bytes, n.toLong())
                                    if (entry.source.size >= 0 && bytes > entry.source.size)
                                        throw IOException("El original remoto cambió de tamaño")
                                    tracker.addBytes(n.toLong())
                                }
                            } finally { out.fd.sync() }
                        }
                    }
                    if (entry.source.size >= 0 && bytes != entry.source.size)
                        throw IOException("Descarga incompleta; el parcial se conserva para reanudar")
                    currentCoroutineContext().ensureActive()
                    checkSource(fs, entry)
                    entry.length = bytes
                    entry.digest = hex(hash.digest())
                    entry.phase = 1
                    save()
                } else {
                    tracker.addBytes(entry.length)
                }
                if (!partial.isFile || partial.length() != entry.length || digest(partial) != entry.digest)
                    throw IOException("La descarga preparada cambió; no se reemplaza el destino")
                currentCoroutineContext().ensureActive()
                checkedTarget(entry)
                SafeFiles.commit(partial, target, replace = false)
                entry.phase = 2
                save()
                tracker.fileDone()
            }
        } finally { runCatching { remote?.close() } }
        if (!keepJournal) journal.delete()
        return OperationResult("Descargados: $count", entries.map { File(it.target) })
    }

    override fun discard() {
        entries.forEachIndexed { index, entry ->
            if (runCatching { checkedTarget(entry) }.isSuccess) {
                val partial = part(index)
                if (!Files.isSymbolicLink(partial.toPath()) && partial.isFile) partial.delete()
            }
        }
        journal.delete()
    }

    companion object {
        private const val MAX_ENTRIES = 100000
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun stamp(connection: Connection): String = hex(MessageDigest.getInstance("SHA-256").digest(
            listOf(connection.protocol.name, connection.host, connection.port.toString(), connection.user,
                connection.root, connection.fingerprint, connection.clientId, connection.googleAccount)
                .joinToString("\u0000").toByteArray(Charsets.UTF_8)))

        private fun storedConnection(id: String, expected: String): RemoteFs {
            val connection = ConnectionStore(RemoteFiles.appContext).load().singleOrNull { it.id == id }
                ?: throw IOException("La conexión guardada ya no existe; vuelve a agregarla")
            if (stamp(connection) != expected)
                throw IOException("La conexión cambió; se conserva el parcial sin usar otro servidor o cuenta")
            return RemoteFiles.connect(connection)
        }

        suspend fun create(
            directory: File,
            connection: Connection,
            fs: RemoteFs,
            parent: String,
            sources: List<RemoteEntry>,
            destination: File,
            opener: (String, String) -> RemoteFs = ::storedConnection
        ): DurableDownload {
            if (sources.isEmpty()) throw IOException("Selecciona al menos un archivo")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo guardar la transferencia")
            if (Files.isSymbolicLink(destination.toPath())) throw IOException("El destino es un enlace simbólico")
            if (!destination.isDirectory && !destination.mkdirs()) throw IOException("No se pudo crear el destino")
            val root = destination.canonicalFile
            val entries = ArrayList<Entry>()
            val reserved = HashSet<String>()
            val visited = HashSet<String>()
            fun reserve(dir: File, name: String): File {
                SafeFiles.requireName(name)
                var candidate = File(dir, name)
                val base = name.substringBeforeLast('.', name)
                val extension = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var index = 1
                while (candidate.exists() || Files.isSymbolicLink(candidate.toPath()) || candidate.path in reserved)
                    candidate = File(dir, "$base (${index++})$extension")
                reserved += candidate.path
                return candidate
            }
            suspend fun visit(source: RemoteEntry, remoteParent: String, dir: File, depth: Int) {
                currentCoroutineContext().ensureActive()
                if (depth > 128 || entries.size >= MAX_ENTRIES) throw IOException("La carpeta remota supera el límite de recorrido")
                if (!visited.add(source.path)) throw IOException("La carpeta remota contiene rutas repetidas o un ciclo")
                if (source.path.isBlank() || source.path.contains('\u0000') || source.size < -1)
                    throw IOException("Entrada remota no válida")
                val target = reserve(dir, source.name)
                entries += Entry(remoteParent, source, target.path)
                if (source.directory) fs.list(source.path).forEach { visit(it, source.path, target, depth + 1) }
            }
            val listed = fs.list(parent).associateBy { it.path }
            for (source in sources.distinctBy { it.path }) {
                if (listed[source.path] != source) throw IOException("El original remoto cambió; actualiza la carpeta")
                visit(source, parent, root, 0)
            }
            val id = UUID.randomUUID().toString()
            return DurableDownload(id, root.path, connection.id, stamp(connection), entries,
                File(directory, "$id.download-job"), opener).also { it.save() }
        }

        fun load(file: File, opener: (String, String) -> RemoteFs = ::storedConnection): DurableDownload {
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Registro de transferencia no válido")
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readUTF() != "OI-DOWNLOAD-1") throw IOException("Registro de descarga no compatible")
                val id = input.readUTF()
                UUID.fromString(id)
                if (file.name != "$id.download-job") throw IOException("Identificador de descarga no válido")
                val destination = input.readUTF()
                val connectionId = input.readUTF()
                val stamp = input.readUTF()
                val count = input.readInt()
                if (count !in 1..MAX_ENTRIES) throw IOException("Registro de descarga no válido")
                val entries = List(count) {
                    Entry(input.readUTF(), RemoteEntry(input.readUTF(), input.readUTF(), input.readBoolean(),
                        input.readLong(), input.readUTF()), input.readUTF(), input.readInt(), input.readLong(), input.readUTF())
                }
                if (entries.any { it.phase !in 0..2 || it.length < 0 || it.source.size < -1 ||
                    it.source.path.isBlank() || it.source.path.contains('\u0000') ||
                    !SafeFiles.validName(it.source.name) ||
                    (it.phase > 0 && !it.source.directory && !it.digest.matches(Regex("[a-f0-9]{64}"))) } ||
                    entries.map { it.target }.distinct().size != entries.size || input.read() != -1)
                    throw IOException("Registro de descarga no válido")
                return DurableDownload(id, destination, connectionId, stamp, entries, file, opener)
            }
        }

        fun pending(directory: File): List<DurableDownload> = directory.listFiles().orEmpty()
            .filter { it.extension == "download-job" }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
    }
}
