package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Provider metadata. A negative size or zero modification time means unknown. */
data class DocumentInfo(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val readable: Boolean,
    val writable: Boolean,
    val canRename: Boolean,
    val canDelete: Boolean,
    val canCreate: Boolean
)

/**
 * A provider-neutral document API. Creation must return a new document, never an existing one.
 * openWriteNew is exclusively for documents created by this operation. rename must reject an
 * occupied name rather than replacing another document, and callers must use its returned ID.
 */
interface DocumentStore {
    fun stat(id: String): DocumentInfo
    fun children(id: String): List<DocumentInfo>
    fun openRead(id: String): InputStream
    fun createFile(parentId: String, name: String, mimeType: String): String
    fun createDirectory(parentId: String, name: String): String
    fun openWriteNew(id: String): OutputStream
    fun rename(id: String, name: String): String
    fun delete(id: String): Boolean
    /** Local adapters sync the new file before a move may remove its source. */
    fun requireDurable(id: String) {}
}

data class DocumentFingerprint(val size: Long, val sha256: String, val modified: Long)

data class SavedDocument(
    val id: String,
    val fingerprint: DocumentFingerprint,
    /** Kept after a successful save so the previous contents remain recoverable. */
    val backupId: String
)

class DocumentConflictException(message: String) : IOException(message)

class DocumentSaveException(
    message: String,
    cause: Throwable? = null,
    val backupId: String? = null,
    /** A failed publication may restore the original under a different provider ID. */
    val restoredId: String? = null
) : IOException(message, cause)

/**
 * All byte reads and writes are streamed; an existing document is never opened for writing.
 * Conflict checks cannot provide atomic compare-and-swap on a provider without that primitive.
 * A changed backup is checked before publication and kept recoverable on any verification error.
 */
object DocumentTransactions {
    private const val BUFFER_SIZE = 262144

    suspend fun fingerprint(
        store: DocumentStore,
        id: String,
        maxBytes: Long = Long.MAX_VALUE
    ): DocumentFingerprint {
        currentCoroutineContext().ensureActive()
        if (maxBytes < 0) throw IOException("El límite de lectura no puede ser negativo")
        val before = store.stat(id)
        if (before.directory || !before.readable)
            throw IOException("No se puede leer el documento: ${before.name}")
        if (before.size > maxBytes)
            throw IOException("El documento supera el límite permitido de $maxBytes bytes")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        store.openRead(id).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = maxBytes - size
                val limit = if (remaining < buffer.size) remaining.toInt() + 1 else buffer.size
                val count = input.read(buffer, 0, limit)
                if (count < 0) break
                if (count == 0) continue
                if (count > remaining)
                    throw IOException("El documento supera el límite permitido de $maxBytes bytes")
                digest.update(buffer, 0, count)
                size += count
            }
        }
        currentCoroutineContext().ensureActive()
        val after = store.stat(id)
        if (after.id != before.id || after.directory || after.name != before.name ||
            (before.size >= 0 && before.size != size) ||
            (after.size >= 0 && after.size != size) ||
            changedTime(before.modified, after.modified))
            throw DocumentConflictException("El documento cambió durante la lectura: ${before.name}")
        return DocumentFingerprint(size, hex(digest.digest()), after.modified)
    }

    suspend fun saveText(
        store: DocumentStore,
        parentId: String,
        originalId: String,
        expected: DocumentFingerprint,
        text: String,
        mimeType: String = "text/plain",
        charset: Charset = Charsets.UTF_8
    ): SavedDocument {
        currentCoroutineContext().ensureActive()
        val encoded = try {
            charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        } catch (error: Exception) {
            throw IOException("El texto no se puede guardar con la codificación ${charset.name()}", error)
        }
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return save(store, parentId, originalId, expected, bytes, mimeType)
    }

    suspend fun save(
        store: DocumentStore,
        parentId: String,
        originalId: String,
        expected: DocumentFingerprint,
        bytes: ByteArray,
        mimeType: String = "text/plain"
    ): SavedDocument {
        currentCoroutineContext().ensureActive()
        if (expected.size < 0 || !expected.sha256.matches(Regex("[0-9a-fA-F]{64}")))
            throw IOException("Falta la huella completa del documento original")
        val parent = store.stat(parentId)
        val original = store.stat(originalId)
        requireSaveCapabilities(parent, original)
        val siblings = store.children(parentId)
        requireOriginal(siblings, originalId, original.name)
        requireExpected(fingerprint(store, originalId), expected, original.name)

        val snapshot = bytes.copyOf()
        val stagingName = uniqueName(siblings, ".oi-guardando-")
        val existingIds = siblings.mapTo(hashSetOf()) { it.id }
        var stagingId: String? = null
        var ownsStaging = false
        var published = false
        var backupId: String? = null
        try {
            currentCoroutineContext().ensureActive()
            val stage = store.createFile(parentId, stagingName, mimeType)
            stagingId = stage
            if (stage == originalId || stage == parentId || stage in existingIds)
                throw IOException("El proveedor no creó un documento nuevo; se conserva el original")
            ownsStaging = true
            val staged = store.stat(stage)
            if (staged.directory || !staged.readable || !staged.writable || !staged.canRename)
                throw IOException("El proveedor no permite verificar y renombrar el documento temporal")
            val digest = MessageDigest.getInstance("SHA-256")
            store.openWriteNew(stage).use { output ->
                var offset = 0
                while (offset < snapshot.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(BUFFER_SIZE, snapshot.size - offset)
                    output.write(snapshot, offset, count)
                    digest.update(snapshot, offset, count)
                    offset += count
                }
                output.flush()
            }
            currentCoroutineContext().ensureActive()
            val intended = DocumentFingerprint(snapshot.size.toLong(), hex(digest.digest()), 0)
            requireContent(fingerprint(store, stage), intended,
                "La escritura temporal no coincide con el texto; se conserva el original")

            val currentParent = store.stat(parentId)
            val currentOriginal = store.stat(originalId)
            requireSaveCapabilities(currentParent, currentOriginal)
            if (currentOriginal.name != original.name)
                throw DocumentConflictException("El nombre del original cambió; vuelve a abrirlo")
            val currentSiblings = store.children(parentId)
            requireOriginal(currentSiblings, originalId, original.name)
            val backupName = uniqueName(currentSiblings, ".oi-respaldo-", "-${original.name}")
            // Hash the entire original again immediately before its first mutation.
            requireExpected(fingerprint(store, originalId), expected, original.name)
            currentCoroutineContext().ensureActive()

            // Once the original has been renamed, complete publication or restoration even if
            // the caller cancels. Cancellation remains responsive while staging and hashing.
            return withContext(NonCancellable) {
                backupId = try {
                    store.rename(originalId, backupName)
                } catch (error: Exception) {
                    // Some providers can complete a rename and then report an error.
                    val renamed = runCatching {
                        store.children(parentId).singleOrNull { it.name == backupName }
                    }.getOrNull()
                    if (renamed != null) {
                        backupId = renamed.id
                        throw restoreAfterFailure(store, parentId, original.name, renamed.id,
                            expected, error)
                    }
                    throw DocumentSaveException(
                        "No se pudo preparar el respaldo; no se publicó el texto nuevo", error)
                }
                val retained = backupId!!
                try {
                    if (store.stat(retained).name == original.name)
                        throw IOException("El proveedor no renombró el original")
                    requireContent(fingerprint(store, retained), expected,
                        "El contenido original cambió al preparar el respaldo")
                } catch (error: Exception) {
                    throw restoreAfterFailure(store, parentId, original.name, retained, expected, error)
                }
                val savedId = try {
                    // A second document created concurrently must never be replaced.
                    if (store.children(parentId).any { it.name == original.name })
                        throw DocumentConflictException("Se creó otro documento con el nombre del original")
                    store.rename(stage, original.name)
                } catch (error: Exception) {
                    throw restoreAfterFailure(store, parentId, original.name, retained, expected, error)
                }
                published = true
                val verified = try {
                    if (savedId == retained || store.stat(savedId).name != original.name)
                        throw IOException("El proveedor no publicó el documento con su nombre original")
                    val actual = fingerprint(store, savedId)
                    requireContent(actual, intended, "El documento publicado no coincide con el texto")
                    requireContent(fingerprint(store, retained), expected,
                        "El respaldo original cambió durante la publicación")
                    actual
                } catch (error: Exception) {
                    throw DocumentSaveException(
                        "No se pudo verificar el guardado. El original se conserva en el respaldo: $retained",
                        error, backupId = retained)
                }
                SavedDocument(savedId, verified, retained)
            }
        } finally {
            // Only a document newly created here is eligible for cleanup. Backups are retained.
            if (ownsStaging && !published && stagingId != backupId && stagingId != originalId)
                runCatching { stagingId?.let { if (store.stat(it).canDelete) store.delete(it) } }
        }
    }

    private suspend fun restoreAfterFailure(
        store: DocumentStore,
        parentId: String,
        originalName: String,
        backupId: String,
        expected: DocumentFingerprint,
        publicationError: Exception
    ): DocumentSaveException {
        var recoveryId = backupId
        return try {
            val occupied = store.children(parentId).filter { it.name == originalName }
            if (occupied.any { it.id != backupId })
                throw IOException("Otro documento ocupa el nombre original")
            if (occupied.none { it.id == backupId }) {
                recoveryId = try {
                    store.rename(backupId, originalName)
                } catch (renameError: Exception) {
                    // As with the first rename, recovery itself can mutate the ID before failing.
                    val old = runCatching { store.stat(backupId) }.getOrNull()
                    val restored = store.children(parentId).singleOrNull { it.name == originalName }
                    if (restored == null || old != null && old.name != originalName)
                        throw renameError
                    recoveryId = restored.id
                    requireContent(fingerprint(store, restored.id), expected,
                        "El contenido restaurado no coincide con el original")
                    restored.id
                }
            }
            requireContent(fingerprint(store, recoveryId), expected,
                "El contenido restaurado no coincide con el original")
            DocumentSaveException("No se pudo publicar el texto; se restauró el documento original",
                publicationError, restoredId = recoveryId)
        } catch (recoveryError: Exception) {
            DocumentSaveException(
                "No se pudo publicar ni restaurar el original. Recupera el respaldo: $recoveryId",
                publicationError, backupId = recoveryId
            ).also { it.addSuppressed(recoveryError) }
        }
    }

    private fun requireSaveCapabilities(parent: DocumentInfo, original: DocumentInfo) {
        if (!parent.directory || !parent.readable || !parent.writable || !parent.canCreate)
            throw IOException("La carpeta no permite crear y verificar documentos")
        if (original.directory || !original.readable || !original.writable || !original.canRename)
            throw IOException("El proveedor no permite un guardado seguro; falta lectura, escritura o renombrado")
    }

    private fun requireOriginal(siblings: List<DocumentInfo>, originalId: String, name: String) {
        val named = siblings.filter { it.name == name }
        if (named.size != 1 || named.single().id != originalId)
            throw DocumentConflictException("El original cambió de carpeta o su nombre ya no es único")
    }

    private fun requireExpected(actual: DocumentFingerprint, expected: DocumentFingerprint, name: String) {
        if (actual.size != expected.size || !actual.sha256.equals(expected.sha256, ignoreCase = true) ||
            changedTime(actual.modified, expected.modified))
            throw DocumentConflictException("El original cambió: $name. Vuelve a abrirlo antes de guardar")
    }

    private fun requireContent(actual: DocumentFingerprint, expected: DocumentFingerprint, message: String) {
        if (actual.size != expected.size || !actual.sha256.equals(expected.sha256, ignoreCase = true))
            throw IOException(message)
    }

    private fun changedTime(a: Long, b: Long) = a > 0 && b > 0 && a != b

    private fun uniqueName(siblings: List<DocumentInfo>, prefix: String, suffix: String = ""): String {
        val occupied = siblings.mapTo(hashSetOf()) { it.name }
        while (true) {
            val candidate = prefix + UUID.randomUUID() + suffix
            if (candidate !in occupied) return candidate
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
