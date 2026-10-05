package com.omaritoinforma.oiarchivos.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Durable remote -> remote relay.
 *
 * The relay deliberately reuses DurableRemoteTransfer for both halves. Staging lives under the
 * app's private files directory, never cacheDir, so Android/process death cannot silently erase a
 * transfer that the UI still promises can be resumed.
 */
class DurableRemoteRelay
private constructor(
    private val appContext: Context,
    override val id: String,
    private val sourceConnectionId: String,
    private val targetConnectionId: String,
    private val roots: List<RemoteEntry>,
    override val destination: String,
    private val move: Boolean,
    private var phase: Int,
    private var downloadId: String,
    private var uploadId: String,
    private val deletedRoots: MutableSet<String>,
    private val journal: File
) : DurableTransfer {
    override val title: String
        get() = if (move) "Moviendo entre conexiones" else "Copiando entre conexiones"

    override val count: Int
        get() = roots.size

    override val completed: Int
        get() = if (phase >= PHASE_UPLOADED) roots.size else 0

    private val relayDirectory: File
        get() = File(journal.parentFile, "relay-$id")

    private val childDirectory: File
        get() = File(relayDirectory, "jobs")

    private val stageDirectory: File
        get() = File(relayDirectory, "stage")

    private fun sourceConnection(): Connection =
        ConnectionStore(appContext).load().firstOrNull { it.id == sourceConnectionId }
            ?: throw IOException("La conexión de origen ya no existe")

    private fun targetConnection(): Connection =
        ConnectionStore(appContext).load().firstOrNull { it.id == targetConnectionId }
            ?: throw IOException("La conexión de destino ya no existe")

    private fun save() =
        SafeFiles.writeAtomic(journal) { temp ->
            FileOutputStream(temp).use { file ->
                val out = DataOutputStream(BufferedOutputStream(file))
                out.writeUTF(MAGIC)
                out.writeUTF(id)
                out.writeUTF(sourceConnectionId)
                out.writeUTF(targetConnectionId)
                out.writeUTF(destination)
                out.writeBoolean(move)
                out.writeInt(phase)
                out.writeUTF(downloadId)
                out.writeUTF(uploadId)
                out.writeInt(roots.size)
                roots.forEach {
                    out.writeUTF(it.path)
                    out.writeUTF(it.name)
                    out.writeBoolean(it.directory)
                    out.writeLong(it.size)
                }
                out.writeInt(deletedRoots.size)
                deletedRoots.forEach(out::writeUTF)
                out.flush()
                file.fd.sync()
            }
        }

    private fun child(id: String): DurableRemoteTransfer? {
        if (id.isBlank()) return null
        val file = File(childDirectory, "$id.rjob")
        return if (file.isFile) DurableRemoteTransfer.load(appContext, file) else null
    }

    private fun orphanChild(): DurableRemoteTransfer? =
        childDirectory
            .listFiles()
            .orEmpty()
            .firstOrNull { it.extension == "rjob" }
            ?.let { DurableRemoteTransfer.load(appContext, it) }

    private fun missing(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "404" in message ||
            "no existe" in message ||
            "not found" in message ||
            "no such file" in message ||
            "does not exist" in message ||
            "file not found" in message
    }

    private fun deleteSource(fs: RemoteFs, root: RemoteEntry) {
        try {
            fs.delete(root)
        } catch (error: Exception) {
            if (!missing(error)) throw error
        }
    }

    override suspend fun run(report: (OpProgress) -> Unit): OperationResult {
        if (!relayDirectory.isDirectory && !relayDirectory.mkdirs())
            throw IOException("No se pudo preparar la transferencia entre conexiones")
        if (!childDirectory.isDirectory && !childDirectory.mkdirs())
            throw IOException("No se pudo preparar el registro de transferencia")
        if (!stageDirectory.isDirectory && !stageDirectory.mkdirs())
            throw IOException("No se pudo preparar la carpeta temporal")

        if (phase == PHASE_NEW) {
            val existing = orphanChild()
            val job =
                existing
                    ?: DurableRemoteTransfer.createDownload(
                        appContext,
                        childDirectory,
                        sourceConnection(),
                        roots,
                        stageDirectory,
                        move = false)
            downloadId = job.id
            phase = PHASE_DOWNLOADING
            save()
        }

        if (phase == PHASE_DOWNLOADING) {
            val job = child(downloadId)
            if (job != null) {
                job.run(report)
            } else if (stageDirectory.listFiles() == null) {
                throw IOException("Falta la descarga temporal de la transferencia")
            }
            phase = PHASE_DOWNLOADED
            save()
        }

        if (phase == PHASE_DOWNLOADED) {
            val staged = stageDirectory.listFiles().orEmpty().toList()
            if (staged.isEmpty() && roots.isNotEmpty())
                throw IOException("La descarga temporal quedó vacía")
            val existing = orphanChild()
            val job =
                existing
                    ?: DurableRemoteTransfer.createUpload(
                        appContext,
                        childDirectory,
                        targetConnection(),
                        staged,
                        destination,
                        move = false)
            uploadId = job.id
            phase = PHASE_UPLOADING
            save()
        }

        if (phase == PHASE_UPLOADING) {
            val job = child(uploadId)
            if (job != null) {
                job.run(report)
            } else if (stageDirectory.listFiles() == null) {
                throw IOException("Falta el estado de la subida temporal")
            }
            phase = PHASE_UPLOADED
            save()
        }

        if (phase == PHASE_UPLOADED && move) {
            RemoteFiles.connect(sourceConnection()).use { fs ->
                for (root in roots) {
                    currentCoroutineContext().ensureActive()
                    if (root.path in deletedRoots) continue
                    deleteSource(fs, root)
                    deletedRoots += root.path
                    save()
                }
            }
        }

        phase = PHASE_FINISHED
        save()
        stageDirectory.deleteRecursively()
        childDirectory.deleteRecursively()
        relayDirectory.deleteRecursively()
        journal.delete()
        return OperationResult(
            if (move) "Elementos movidos entre conexiones" else "Copia entre conexiones completada")
    }

    override suspend fun discard() {
        runCatching { child(downloadId)?.discard() }
        runCatching { child(uploadId)?.discard() }
        childDirectory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "rjob" }
            .forEach { file ->
                runCatching { DurableRemoteTransfer.load(appContext, file).discard() }
            }
        relayDirectory.deleteRecursively()
        journal.delete()
    }

    companion object {
        private const val MAGIC = "OI-RELAY-1"
        private const val MAX_ROOTS = 10000
        private const val MAX_JOURNAL = 8L * 1024 * 1024

        private const val PHASE_NEW = 0
        private const val PHASE_DOWNLOADING = 1
        private const val PHASE_DOWNLOADED = 2
        private const val PHASE_UPLOADING = 3
        private const val PHASE_UPLOADED = 4
        private const val PHASE_FINISHED = 5

        fun create(
            ctx: Context,
            directory: File,
            source: Connection,
            target: Connection,
            roots: List<RemoteEntry>,
            destination: String,
            move: Boolean
        ): DurableRemoteRelay {
            if (roots.isEmpty()) throw IOException("Selecciona al menos un elemento remoto")
            if (roots.size > MAX_ROOTS)
                throw IOException("Selecciona menos de 10.000 elementos raíz")
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("No se pudo guardar la transferencia")
            roots.forEach { SafeFiles.requireName(it.name) }
            val id = UUID.randomUUID().toString()
            return DurableRemoteRelay(
                    ctx.applicationContext,
                    id,
                    source.id,
                    target.id,
                    roots.toList(),
                    destination,
                    move,
                    PHASE_NEW,
                    "",
                    "",
                    mutableSetOf(),
                    File(directory, "$id.relayjob"))
                .apply { save() }
        }

        fun load(ctx: Context, file: File): DurableRemoteRelay {
            if (file.length() > MAX_JOURNAL)
                throw IOException("Registro de transferencia entre conexiones demasiado grande")
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readUTF() != MAGIC)
                    throw IOException("Registro de transferencia entre conexiones no válido")
                val id = input.readUTF()
                if (UUID.fromString(id).toString() + ".relayjob" != file.name)
                    throw IOException("Identificador de transferencia no válido")
                val sourceConnectionId = input.readUTF()
                val targetConnectionId = input.readUTF()
                val destination = input.readUTF()
                val move = input.readBoolean()
                val phase = input.readInt()
                if (phase !in PHASE_NEW..PHASE_FINISHED)
                    throw IOException("Fase de transferencia no válida")
                val downloadId = input.readUTF()
                val uploadId = input.readUTF()
                val rootCount = input.readInt()
                if (rootCount !in 1..MAX_ROOTS)
                    throw IOException("Registro de transferencia no válido")
                val roots =
                    List(rootCount) {
                        RemoteEntry(
                            input.readUTF(),
                            input.readUTF(),
                            input.readBoolean(),
                            input.readLong())
                    }
                val deletedCount = input.readInt()
                if (deletedCount !in 0..rootCount)
                    throw IOException("Registro de transferencia no válido")
                val deleted = MutableList(deletedCount) { input.readUTF() }.toMutableSet()
                return DurableRemoteRelay(
                    ctx.applicationContext,
                    id,
                    sourceConnectionId,
                    targetConnectionId,
                    roots,
                    destination,
                    move,
                    phase,
                    downloadId,
                    uploadId,
                    deleted,
                    file)
            }
        }

        fun pending(ctx: Context, directory: File): List<DurableRemoteRelay> =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "relayjob" }
                .mapNotNull { runCatching { load(ctx, it) }.getOrNull() }
    }
}
