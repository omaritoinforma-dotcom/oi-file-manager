package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A durable download/upload/delete workflow. Every child journal survives until the parent
 * commits its next phase. A remote folder is never recursively deleted after copying a listing.
 */
class DurableRemoteTransfer private constructor(
    override val id: String,
    override val destination: String,
    private val sourceId: String,
    private val sourceStamp: String,
    private val sourceParent: String,
    private val targetId: String,
    private val targetStamp: String,
    private val move: Boolean,
    private val journal: File,
    private val work: File,
    private var finished: Boolean,
    private val deleting: MutableSet<String>,
    private val deleted: MutableSet<String>,
    private val retained: MutableSet<String>,
    private val opener: (String, String) -> RemoteFs,
    private val connectionProvider: (String, String) -> Connection
) : DurableTransfer {
    override val title get() = if (move) "Moviendo archivos remotos" else "Copiando entre servidores"
    override val disconnectOnPause = true
    override val count get() = download()?.count ?: 0
    override val completed get() = if (finished) count else download()?.completed ?: 0

    private fun download(): DurableDownload? =
        child("download-job")?.let { DurableDownload.load(it, opener) }

    private fun child(extension: String): File? {
        val files = work.listFiles().orEmpty().filter { it.extension == extension }
        if (files.size > 1) throw IOException("Hay registros duplicados en la transferencia")
        return files.singleOrNull()
    }

    private fun save() = SafeFiles.writeAtomic(journal) { temp ->
        FileOutputStream(temp).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.writeUTF("OI-REMOTE-TRANSFER-2")
            listOf(id, destination, sourceId, sourceStamp, sourceParent, targetId, targetStamp)
                .forEach(out::writeUTF)
            out.writeBoolean(move)
            out.writeBoolean(finished)
            out.writeInt(deleting.size)
            deleting.forEach(out::writeUTF)
            out.writeInt(deleted.size)
            deleted.forEach(out::writeUTF)
            out.writeInt(retained.size)
            retained.forEach(out::writeUTF)
            out.flush()
            file.fd.sync()
        }
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult {
        if (finished) return finish()
        val download = download() ?: throw IOException("Falta el registro de descarga")
        download.runKeepingJournal(report)
        val sources = download.downloadedSources()
        val upload = if (targetId.isNotBlank()) {
            child("upload-job")?.let { DurableUpload.load(it, opener) }
                ?: opener(targetId, targetStamp).use { fs ->
                    val roots = sources.filter { it.parent == sourceParent }.map { it.local }
                    DurableUpload.create(work, connectionForUpload(), fs, destination, roots, opener)
                }
        } else null
        upload?.runKeepingJournal(report)
        if (move && sources.any { it.source.path !in deleted && it.source.path !in retained }) {
            opener(sourceId, sourceStamp).use { fs ->
                // Delete leaves before directories, verifying every leaf against the committed copy.
                for (item in sources.asReversed()) {
                    currentCoroutineContext().ensureActive()
                    val expected = item.source
                    if (expected.path in deleted || expected.path in retained) continue
                    // A later destination mutation must never turn this into data loss.
                    download.runKeepingJournal(report)
                    upload?.runKeepingJournal(report)
                    val matches = fs.list(item.parent).filter { it.path == expected.path }
                    if (matches.size > 1) throw IOException("El origen remoto contiene IDs repetidos")
                    val actual = matches.singleOrNull()
                    if (actual == null) {
                        if (expected.path !in deleting)
                            throw IOException("El origen desapareció antes de moverlo: " + expected.name)
                    } else if (expected.directory) {
                        if (!actual.directory || actual.name != expected.name)
                            throw IOException("La carpeta original cambió: " + expected.name)
                        if (fs.list(actual.path).isNotEmpty())
                            throw IOException("La carpeta contiene archivos nuevos; se conservan en origen")
                        deleting += expected.path
                        save()
                        currentCoroutineContext().ensureActive()
                        if (!fs.deleteEmptyDirectory(actual)) {
                            retained += expected.path
                            save()
                            continue
                        }
                    } else {
                        if (actual != expected)
                            throw IOException("El original cambió; se conserva: " + expected.name)
                        val localHash = AnalysisTools.digest(item.local)
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                        fs.read(actual.path).use { input ->
                            val buffer = ByteArray(262144)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                hash.update(buffer, 0, n)
                            }
                        }
                        val remoteHash = hash.digest().joinToString("") { "%02x".format(it) }
                        if (localHash != remoteHash ||
                            fs.list(item.parent).singleOrNull { it.path == expected.path } != actual)
                            throw IOException("El original cambió; no se borra: " + expected.name)
                        deleting += expected.path
                        save()
                        currentCoroutineContext().ensureActive()
                        if (!fs.deleteIfUnchanged(actual)) {
                            retained += expected.path
                            save()
                            continue
                        }
                    }
                    // Persisting intent first lets recovery distinguish our own completed delete.
                    currentCoroutineContext().ensureActive()
                    deleted += expected.path
                    save()
                }
            }
        }
        finished = true
        save()
        return finish()
    }

    // create() persists only the target identity; renewed credentials are resolved at run time.
    private fun connectionForUpload(): Connection {
        val connection = connectionProvider(targetId, targetStamp)
        if (DurableDownload.stamp(connection) != targetStamp)
            throw IOException("La conexión de destino cambió; se conserva el trabajo")
        return connection
    }

    private fun finish(): OperationResult {
        val changed = if (targetId.isBlank()) download()?.downloadedSources()?.map { it.local }.orEmpty()
            else emptyList()
        work.deleteRecursively()
        journal.delete()
        return OperationResult(when {
            retained.isNotEmpty() -> "Archivos copiados y verificados; originales conservados: ${retained.size} (borrado seguro no disponible)"
            move -> "Archivos movidos y verificados"
            else -> "Archivos copiados y verificados"
        }, changed)
    }

    override fun discard() {
        child("upload-job")?.let { DurableUpload.load(it, opener).discard() }
        download()?.discard()
        work.deleteRecursively()
        journal.delete()
    }

    companion object {
        private fun storedMetadata(id: String, expected: String): Connection {
            val connection = ConnectionStore(RemoteFiles.appContext).load().singleOrNull { it.id == id }
                ?: throw IOException("La conexión guardada ya no existe")
            if (DurableDownload.stamp(connection) != expected)
                throw IOException("La conexión cambió; se conserva el trabajo")
            return connection
        }

        private fun storedConnection(id: String, expected: String): RemoteFs =
            RemoteFiles.connect(storedMetadata(id, expected))

        suspend fun create(
            directory: File,
            source: Connection,
            fs: RemoteFs,
            parent: String,
            entries: List<RemoteEntry>,
            destination: File,
            move: Boolean,
            target: Connection? = null,
            targetParent: String = "",
            opener: (String, String) -> RemoteFs = ::storedConnection,
            connectionProvider: (String, String) -> Connection = ::storedMetadata
        ): DurableRemoteTransfer {
            if (!move && target == null) throw IOException("Una copia local utiliza el registro de descarga")
            if (Files.isSymbolicLink(directory.toPath())) throw IOException("Registro no válido")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo guardar el trabajo")
            val id = UUID.randomUUID().toString()
            val work = File(directory, id + ".transfer-work")
            if (!work.mkdir()) throw IOException("No se pudo crear el registro")
            try {
                val local = if (target == null) destination else File(work, "contents")
                val download = DurableDownload.create(work, source, fs, parent, entries, local, opener)
                if (target != null) {
                    opener(target.id, DurableDownload.stamp(target)).use {
                        if (!it.supportsDurableUploads)
                            throw IOException("El destino todavía no permite transferencias recuperables")
                    }
                    if (source.id == target.id && download.downloadedSources().any {
                        it.source.directory && it.source.path == targetParent
                    }) throw IOException("No puedes copiar una carpeta dentro de sí misma")
                }
                return DurableRemoteTransfer(id,
                    if (target == null) destination.canonicalPath else targetParent,
                    source.id, DurableDownload.stamp(source), parent,
                    target?.id.orEmpty(), target?.let(DurableDownload::stamp).orEmpty(), move,
                    File(directory, id + ".remote-job"), work, false, linkedSetOf(), linkedSetOf(), linkedSetOf(), opener,
                    connectionProvider)
                    .also { it.save() }
            } catch (e: Exception) {
                work.deleteRecursively()
                throw e
            }
        }

        fun load(file: File, opener: (String, String) -> RemoteFs = ::storedConnection,
            connectionProvider: (String, String) -> Connection = ::storedMetadata): DurableRemoteTransfer {
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Registro no válido")
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readUTF() != "OI-REMOTE-TRANSFER-2") throw IOException("Registro no compatible")
                val id = input.readUTF()
                UUID.fromString(id)
                if (file.name != id + ".remote-job") throw IOException("Identificador no válido")
                val fields = List(6) { input.readUTF() }
                val move = input.readBoolean()
                val finished = input.readBoolean()
                fun paths(): MutableSet<String> {
                    val count = input.readInt()
                    if (count !in 0..100000) throw IOException("Registro no válido")
                    return List(count) { input.readUTF() }.toMutableSet()
                }
                val deleting = paths()
                val deleted = paths()
                val retained = paths()
                if (input.read() != -1 || !deleting.containsAll(deleted))
                    throw IOException("Registro no válido")
                val work = File(file.parentFile, id + ".transfer-work")
                if (Files.isSymbolicLink(work.toPath())) throw IOException("Registro no válido")
                return DurableRemoteTransfer(id, fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], move, file, work, finished, deleting, deleted, retained, opener,
                    connectionProvider)
            }
        }

        fun pending(directory: File) = directory.listFiles().orEmpty()
            .filter { it.extension == "remote-job" }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
    }
}
