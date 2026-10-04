package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentTransactionsTest {
    @Test
    fun successfulSaveUsesReturnedIdsAndRetainsVerifiedOriginal() = runBlocking {
        val store = FakeStore()
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val saved = DocumentTransactions.save(store, store.rootId, store.originalId, expected,
            "nuevo texto".toByteArray())
        assertNotEquals(store.originalId, saved.id)
        assertNotEquals(store.originalId, saved.backupId)
        assertEquals("nuevo texto", store.text(saved.id))
        assertEquals("original", store.text(saved.backupId))
        assertEquals("nota.txt", store.stat(saved.id).name)
        assertEquals(DocumentTransactions.fingerprint(store, saved.id), saved.fingerprint)
        assertEquals(expected.sha256, DocumentTransactions.fingerprint(store, saved.backupId).sha256)
        assertFalse(store.exists(store.originalId))
        assertEquals(1, store.openedForWrite.size)
        assertTrue(store.openedForWrite.all { it in store.createdIds })
        assertFalse(store.deletedIds.contains(saved.backupId))
    }

    @Test
    fun failedStagingWriteDoesNotRenameOrWriteOriginal() = runBlocking {
        val store = FakeStore().apply { failWrite = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<IOException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo texto".toByteArray())
        }
        assertEquals("original", store.text(store.originalId))
        assertTrue(store.renamedIds.isEmpty())
        assertEquals(listOf(store.originalId), store.children(store.rootId).map { it.id })
        assertFalse(store.openedForWrite.contains(store.originalId))
    }

    @Test
    fun fullHashDetectsChangeWithSameSizeAndTimestampBeforeCreatingAnything() = runBlocking {
        val store = FakeStore()
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        store.original.bytes = "cambiado".toByteArray()
        expectFailure<DocumentConflictException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertEquals("cambiado", store.text(store.originalId))
        assertTrue(store.createdIds.isEmpty())
        assertTrue(store.openedForWrite.isEmpty())
    }

    @Test
    fun checksOriginalAgainAfterWritingStaging() = runBlocking {
        val store = FakeStore()
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        store.afterWrite = { store.original.bytes = "cambiado".toByteArray() }
        expectFailure<DocumentConflictException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertEquals("cambiado", store.text(store.originalId))
        assertTrue(store.renamedIds.isEmpty())
        assertEquals(1, store.children(store.rootId).size)
    }

    @Test
    fun providerWithoutOriginalRenameIsRejectedBeforeAnyWrite() = runBlocking {
        val store = FakeStore().apply { original.renameAllowed = false }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<IOException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertTrue(store.createdIds.isEmpty())
        assertTrue(store.openedForWrite.isEmpty())
        assertEquals("original", store.text(store.originalId))
    }

    @Test
    fun providerWithoutStagingRenameIsRejectedBeforeOpeningWriter() = runBlocking {
        val store = FakeStore().apply { createdRenameAllowed = false }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<IOException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertTrue(store.openedForWrite.isEmpty())
        assertTrue(store.renamedIds.isEmpty())
        assertEquals("original", store.text(store.originalId))
    }

    @Test
    fun providerWithoutParentCreationIsRejectedBeforeAnyWrite() = runBlocking {
        val store = FakeStore().apply { root.createAllowed = false }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<IOException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertTrue(store.createdIds.isEmpty())
        assertTrue(store.openedForWrite.isEmpty())
    }

    @Test
    fun publicationRenameFailureRestoresOriginalUsingUpdatedId() = runBlocking {
        val store = FakeStore().apply { failPublish = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertNotNull(error.restoredId)
        assertNotEquals(store.originalId, error.restoredId)
        assertEquals("original", store.text(error.restoredId!!))
        assertEquals("nota.txt", store.stat(error.restoredId!!).name)
        assertNull(error.backupId)
        assertEquals(1, store.children(store.rootId).size)
        assertTrue(store.openedForWrite.all { it in store.createdIds })
    }

    @Test
    fun failedRestorationReportsRecoverableBackupId() = runBlocking {
        val store = FakeStore().apply { failPublish = true; failRestore = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertNotNull(error.backupId)
        assertTrue(error.message!!.contains(error.backupId!!))
        assertEquals("original", store.text(error.backupId!!))
        assertEquals(expected.sha256, DocumentTransactions.fingerprint(store, error.backupId!!).sha256)
        assertFalse(store.deletedIds.contains(error.backupId))
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun tamperedPublishedContentIsRejectedAndKeepsOriginalBackup() = runBlocking {
        val store = FakeStore().apply { corruptPublished = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertEquals("original", store.text(error.backupId!!))
        assertEquals(expected.sha256, DocumentTransactions.fingerprint(store, error.backupId!!).sha256)
        assertEquals("alterado", store.text(store.children(store.rootId).single { it.name == "nota.txt" }.id))
        assertFalse(store.deletedIds.contains(error.backupId))
    }

    @Test
    fun providerReturningWrongPublicationNameKeepsBackup() = runBlocking {
        val store = FakeStore().apply { wrongPublishedName = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertEquals("original", store.text(error.backupId!!))
        assertEquals(2, store.children(store.rootId).size)
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun renameMayChangeModificationTimeWithoutChangingOriginalContent() = runBlocking {
        val store = FakeStore().apply { renameChangesTime = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val saved = DocumentTransactions.save(store, store.rootId, store.originalId, expected,
            "nuevo".toByteArray())
        assertEquals("nuevo", store.text(saved.id))
        assertEquals("original", store.text(saved.backupId))
        assertNotEquals(expected.modified, store.stat(saved.backupId).modified)
    }

    @Test
    fun renameThatSucceedsThenThrowsStillRestoresOriginal() = runBlocking {
        val store = FakeStore().apply { throwAfterBackupRename = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertEquals("original", store.text(error.restoredId!!))
        assertEquals("nota.txt", store.stat(error.restoredId!!).name)
        assertEquals(1, store.children(store.rootId).size)
    }

    @Test
    fun restorationThatChangesIdThenThrowsReportsValidRestoredId() = runBlocking {
        val store = FakeStore().apply { failPublish = true; throwAfterRestoreRename = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val error = expectFailure<DocumentSaveException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        assertNotNull(error.restoredId)
        assertEquals("original", store.text(error.restoredId!!))
        assertEquals("nota.txt", store.stat(error.restoredId!!).name)
        assertNull(error.backupId)
    }

    @Test
    fun cancellationDuringStagingLeavesOriginalUntouched() = runBlocking {
        val store = FakeStore().apply { cancelWrite = true }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<CancellationException> {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                ByteArray(600000) { 65 })
        }
        assertEquals("original", store.text(store.originalId))
        assertTrue(store.renamedIds.isEmpty())
        assertEquals(1, store.children(store.rootId).size)
    }

    @Test
    fun realJobCancellationAfterBackupStillCompletesVerifiedPublication() = runBlocking {
        val store = FakeStore()
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        val worker = launch(start = CoroutineStart.LAZY) {
            DocumentTransactions.save(store, store.rootId, store.originalId, expected,
                "nuevo".toByteArray())
        }
        store.onBackupRename = { worker.cancel() }
        worker.start()
        worker.join()
        assertTrue(worker.isCancelled)
        val documents = store.children(store.rootId)
        assertEquals("nuevo", store.text(documents.single { it.name == "nota.txt" }.id))
        assertEquals("original", store.text(documents.single { it.name.startsWith(".oi-respaldo-") }.id))
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun fingerprintUsesReadBytesWhenProviderSizeIsUnknown() = runBlocking {
        val store = FakeStore().apply { original.reportedSize = -1; original.modified = 0 }
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        assertEquals(8L, expected.size)
        assertEquals(0L, expected.modified)
        val saved = DocumentTransactions.save(store, store.rootId, store.originalId, expected,
            "nuevo".toByteArray())
        assertEquals("nuevo", store.text(saved.id))
        assertEquals("original", store.text(saved.backupId))
    }

    @Test
    fun knownOversizedDocumentIsRejectedBeforeOpeningInput() = runBlocking {
        val store = FakeStore()
        expectFailure<IOException> {
            DocumentTransactions.fingerprint(store, store.originalId, maxBytes = 4)
        }
        assertTrue(store.openedForRead.isEmpty())
        assertEquals("original", store.text(store.originalId))
    }

    @Test
    fun unknownDocumentSizeCannotBypassStreamingReadLimit() = runBlocking {
        val store = FakeStore().apply { original.reportedSize = -1 }
        expectFailure<IOException> {
            DocumentTransactions.fingerprint(store, store.originalId, maxBytes = 4)
        }
        assertEquals(5L, store.readBytes)
        assertEquals("original", store.text(store.originalId))
        val atLimit = DocumentTransactions.fingerprint(store, store.originalId, maxBytes = 8)
        assertEquals(8L, atLimit.size)
    }

    @Test
    fun savesTextUsingRequestedCharsetAndRejectsUnrepresentableCharacters() = runBlocking {
        val store = FakeStore()
        val expected = DocumentTransactions.fingerprint(store, store.originalId)
        expectFailure<IOException> {
            DocumentTransactions.saveText(store, store.rootId, store.originalId, expected,
                "emoji \uD83D\uDE00", charset = Charsets.ISO_8859_1)
        }
        assertTrue(store.createdIds.isEmpty())
        val saved = DocumentTransactions.saveText(store, store.rootId, store.originalId, expected,
            "caf\u00e9", charset = Charsets.ISO_8859_1)
        assertArrayEquals(byteArrayOf(99, 97, 102, -23), store.node(saved.id).bytes)
        assertEquals("original", store.text(saved.backupId))
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Se esperaba ${T::class.java.simpleName}, llegó $error", error)
        }
        throw AssertionError("Se esperaba ${T::class.java.simpleName}")
    }

    private class FakeStore : DocumentStore {
        data class Node(
            var id: String,
            var name: String,
            val parent: String?,
            val directory: Boolean = false,
            var bytes: ByteArray = byteArrayOf(),
            var modified: Long = 100,
            var reportedSize: Long? = null,
            var renameAllowed: Boolean = true,
            var createAllowed: Boolean = true
        )

        val rootId = "root"
        val originalId = "original"
        val root = Node(rootId, "carpeta", null, directory = true)
        val original = Node(originalId, "nota.txt", rootId, bytes = "original".toByteArray())
        private val nodes = linkedMapOf(rootId to root, originalId to original)
        val createdIds = hashSetOf<String>()
        val openedForRead = arrayListOf<String>()
        var readBytes = 0L
        val openedForWrite = arrayListOf<String>()
        val renamedIds = arrayListOf<String>()
        val deletedIds = arrayListOf<String>()
        var failWrite = false
        var cancelWrite = false
        var failPublish = false
        var failRestore = false
        var corruptPublished = false
        var wrongPublishedName = false
        var renameChangesTime = false
        var throwAfterBackupRename = false
        var throwAfterRestoreRename = false
        var createdRenameAllowed = true
        var afterWrite: (() -> Unit)? = null
        var onBackupRename: (() -> Unit)? = null
        private var nextId = 0

        fun node(id: String): Node = nodes[id] ?: throw IOException("Documento inexistente: $id")
        fun text(id: String) = String(node(id).bytes, Charsets.UTF_8)
        fun exists(id: String) = id in nodes

        override fun stat(id: String): DocumentInfo {
            val node = node(id)
            return DocumentInfo(id, node.name, node.directory,
                node.reportedSize ?: node.bytes.size.toLong(), node.modified,
                readable = true, writable = true, canRename = node.renameAllowed,
                canDelete = true, canCreate = node.directory && node.createAllowed)
        }

        override fun children(id: String) = nodes.values.filter { it.parent == id }.map { stat(it.id) }
        override fun openRead(id: String): InputStream {
            openedForRead += id
            return object : ByteArrayInputStream(node(id).bytes.copyOf()) {
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    val result = super.read(buffer, offset, count)
                    if (result > 0) readBytes += result
                    return result
                }
            }
        }

        override fun createFile(parentId: String, name: String, mimeType: String): String =
            create(parentId, name, false)

        override fun createDirectory(parentId: String, name: String): String = create(parentId, name, true)

        private fun create(parentId: String, name: String, directory: Boolean): String {
            if (!node(parentId).directory || !node(parentId).createAllowed)
                throw IOException("No se puede crear")
            if (children(parentId).any { it.name == name }) throw IOException("Nombre ocupado")
            val id = "nuevo-${++nextId}"
            nodes[id] = Node(id, name, parentId, directory, renameAllowed = createdRenameAllowed)
            createdIds += id
            return id
        }

        override fun openWriteNew(id: String): OutputStream {
            if (id !in createdIds) throw AssertionError("Se intentó escribir un original: $id")
            openedForWrite += id
            val target = node(id)
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    target.bytes += bytes.copyOfRange(offset, offset + count)
                    target.modified++
                    if (failWrite) throw IOException("Falló la copia al documento temporal")
                    if (cancelWrite) throw CancellationException("Cancelado durante la escritura")
                }
                override fun close() { afterWrite?.invoke() }
            }
        }

        override fun rename(id: String, name: String): String {
            val target = node(id)
            if (!target.renameAllowed) throw IOException("No se puede renombrar")
            val publishing = target.name.startsWith(".oi-guardando-") && name == "nota.txt"
            val restoring = target.name.startsWith(".oi-respaldo-") && name == "nota.txt"
            if (publishing && failPublish || restoring && failRestore)
                throw IOException("Falló el renombrado")
            if (children(target.parent!!).any { it.id != id && it.name == name })
                throw IOException("Nombre ocupado")
            renamedIds += id
            nodes.remove(id)
            target.id = "renombrado-${++nextId}"
            target.name = if (publishing && wrongPublishedName) "$name.cambiado" else name
            if (renameChangesTime) target.modified++
            if (publishing && corruptPublished) target.bytes = "alterado".toByteArray()
            nodes[target.id] = target
            if (name.startsWith(".oi-respaldo-")) onBackupRename?.invoke()
            if (name.startsWith(".oi-respaldo-") && throwAfterBackupRename) {
                throwAfterBackupRename = false
                throw IOException("Renombrado completado pero respuesta fallida")
            }
            if (restoring && throwAfterRestoreRename) {
                throwAfterRestoreRename = false
                throw IOException("Restauración completada pero respuesta fallida")
            }
            return target.id
        }

        override fun delete(id: String): Boolean {
            deletedIds += id
            return nodes.remove(id) != null
        }
    }
}
