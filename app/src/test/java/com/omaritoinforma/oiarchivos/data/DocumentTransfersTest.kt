package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentTransfersTest {
    @Test
    fun corruptDestinationReadBackNeverDeletesSource() = runBlocking {
        val source = FakeStore("origen")
        val original = source.file(source.rootId, "a.txt", "original")
        val destination = FakeStore("destino").apply { corruptReads = true }
        expectFailure {
            DocumentTransfers.transfer(source, listOf(original), destination, destination.rootId, move = true)
        }
        assertEquals("original", source.text(original))
        assertTrue(source.deletedIds.isEmpty())
    }

    @Test
    fun changedSourceWithSameSizeAndTimeIsPreserved() = runBlocking {
        val source = FakeStore("origen")
        val original = source.file(source.rootId, "a.txt", "original")
        val destination = FakeStore("destino")
        destination.afterWrite = { source.node(original).bytes = "cambiado".toByteArray() }
        expectFailure {
            DocumentTransfers.transfer(source, listOf(original), destination, destination.rootId, move = true)
        }
        assertEquals("cambiado", source.text(original))
        assertTrue(source.deletedIds.isEmpty())
    }

    @Test
    fun newDirectoryMemberBeforeDeletionPreservesEverySource() = runBlocking {
        val source = FakeStore("origen")
        val album = source.directory(source.rootId, "album")
        val original = source.file(album, "a.txt", "original")
        val destination = FakeStore("destino")
        var added: String? = null
        destination.afterWrite = { added = source.file(album, "nuevo.txt", "externo") }
        expectFailure {
            DocumentTransfers.transfer(source, listOf(album), destination, destination.rootId, move = true)
        }
        assertEquals("original", source.text(original))
        assertEquals("externo", source.text(added!!))
        assertTrue(source.exists(album))
        assertTrue(source.deletedIds.isEmpty())
    }

    @Test
    fun secondCopyFailureKeepsAllOriginalsIncludingAlreadyCopiedFile() = runBlocking {
        val source = FakeStore("origen")
        val first = source.file(source.rootId, "a.txt", "primero")
        val second = source.file(source.rootId, "b.txt", "segundo")
        val destination = FakeStore("destino").apply { failWritesForName = "b.txt" }
        expectFailure {
            DocumentTransfers.transfer(source, listOf(first, second), destination, destination.rootId, move = true)
        }
        assertEquals("primero", source.text(first))
        assertEquals("segundo", source.text(second))
        assertTrue(source.deletedIds.isEmpty())
        assertEquals("primero", destination.text(destination.children(destination.rootId).single().id))
    }

    @Test
    fun successfulMoveDeletesOnlyAfterCompleteHashesForEverySourceAndTarget() = runBlocking {
        val source = FakeStore("origen")
        val album = source.directory(source.rootId, "album")
        val first = source.file(album, "a.txt", "primero")
        val second = source.file(album, "b.txt", "segundo")
        val destination = FakeStore("destino")
        var checkedBeforeFirstDelete = false
        source.beforeDelete = {
            if (!checkedBeforeFirstDelete) {
                val copiedAlbum = destination.children(destination.rootId).single().id
                val files = destination.children(copiedAlbum)
                assertEquals(2, files.size)
                assertEquals(setOf("primero", "segundo"), files.map { destination.text(it.id) }.toSet())
                assertTrue(source.completedReads.count { it == first } >= 5)
                assertTrue(source.completedReads.count { it == second } >= 5)
                files.forEach { file -> assertTrue(destination.completedReads.count { it == file.id } >= 2) }
                checkedBeforeFirstDelete = true
            }
        }
        val result = DocumentTransfers.transfer(source, listOf(album), destination,
            destination.rootId, move = true)
        assertTrue(result.moved)
        assertTrue(checkedBeforeFirstDelete)
        assertEquals(listOf(first, second, album), source.deletedIds)
        assertFalse(source.exists(album))
        assertEquals(1, result.destinationIds.size)
        val files = destination.children(result.destinationIds.single())
        assertEquals(setOf("primero", "segundo"), files.map { destination.text(it.id) }.toSet())
    }

    @Test
    fun destinationInsideSourceIsRejectedBeforeCreatingDocuments() = runBlocking {
        val store = FakeStore("origen")
        val album = store.directory(store.rootId, "album")
        val nested = store.directory(album, "destino")
        val original = store.file(album, "a.txt", "original")
        expectFailure {
            DocumentTransfers.transfer(store, listOf(album), store, nested, move = true)
        }
        assertEquals("original", store.text(original))
        assertTrue(store.createdIds.isEmpty())
        assertTrue(store.openedForWrite.isEmpty())
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun missingSourceDeleteCapabilityRejectsMoveBeforeCopyingAnything() = runBlocking {
        val source = FakeStore("origen")
        val first = source.file(source.rootId, "a.txt", "primero")
        val second = source.file(source.rootId, "b.txt", "segundo")
        source.node(second).deleteAllowed = false
        val destination = FakeStore("destino")
        expectFailure {
            DocumentTransfers.transfer(source, listOf(first, second), destination, destination.rootId, move = true)
        }
        assertTrue(destination.createdIds.isEmpty())
        assertTrue(destination.openedForWrite.isEmpty())
        assertEquals("primero", source.text(first))
        assertEquals("segundo", source.text(second))
        assertTrue(source.deletedIds.isEmpty())
    }

    @Test
    fun oversizedImportIsRejectedBeforeCreatingDestination() = runBlocking {
        val source = FakeStore("origen")
        val original = source.file(source.rootId, "a.txt", "original")
        val destination = FakeStore("destino")
        expectFailure {
            DocumentTransfers.transfer(source, listOf(original), destination,
                destination.rootId, move = false, maxFileSize = 4)
        }
        assertTrue(destination.createdIds.isEmpty())
        assertTrue(destination.openedForWrite.isEmpty())
        assertEquals("original", source.text(original))
    }

    @Test
    fun providerWithoutDurableWriteKeepsOriginalAfterVerifiedCopy() = runBlocking {
        val source = FakeStore("origen")
        val original = source.file(source.rootId, "a.txt", "original")
        val destination = FakeStore("destino").apply { durableAllowed = false }
        expectFailure {
            DocumentTransfers.transfer(source, listOf(original), destination,
                destination.rootId, move = true)
        }
        assertEquals("original", source.text(original))
        assertTrue(source.deletedIds.isEmpty())
        assertEquals("original", destination.text(destination.children(destination.rootId).single().id))
    }

    @Test
    fun unknownProviderSizeStillEnforcesImportLimitBeforeCopying() = runBlocking {
        val source = FakeStore("origen")
        val original = source.file(source.rootId, "a.txt", "original")
        source.node(original).reportedSize = -1
        val destination = FakeStore("destino")
        expectFailure {
            DocumentTransfers.transfer(source, listOf(original), destination,
                destination.rootId, move = false, maxFileSize = 4)
        }
        assertTrue(destination.createdIds.isEmpty())
        assertTrue(destination.openedForWrite.isEmpty())
        assertEquals("original", source.text(original))
    }

    private suspend fun expectFailure(block: suspend () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Se esperaba una transferencia rechazada")
    }

    private class FakeStore(private val prefix: String) : DocumentStore {
        var durableAllowed = true
        override fun requireDurable(id: String) {
            if (!durableAllowed) throw IOException("El proveedor no confirma la escritura durable")
        }
        data class Node(
            val id: String,
            val name: String,
            val parent: String?,
            val directory: Boolean,
            var bytes: ByteArray = byteArrayOf(),
            var deleteAllowed: Boolean = true,
            var reportedSize: Long? = null
        )

        val rootId = "$prefix-raiz"
        private val nodes = linkedMapOf(rootId to Node(rootId, "raiz", null, true))
        private var nextId = 0
        val createdIds = hashSetOf<String>()
        val openedForWrite = arrayListOf<String>()
        val deletedIds = arrayListOf<String>()
        val completedReads = arrayListOf<String>()
        var corruptReads = false
        var failWritesForName: String? = null
        var afterWrite: (() -> Unit)? = null
        var beforeDelete: (() -> Unit)? = null

        fun node(id: String): Node = nodes[id] ?: throw IOException("Documento inexistente: $id")
        fun exists(id: String) = id in nodes
        fun text(id: String) = String(node(id).bytes, Charsets.UTF_8)
        fun file(parent: String, name: String, text: String): String =
            add(parent, name, false).also { node(it).bytes = text.toByteArray() }
        fun directory(parent: String, name: String): String = add(parent, name, true)

        private fun add(parent: String, name: String, directory: Boolean): String {
            if (!node(parent).directory || children(parent).any { it.name == name })
                throw IOException("Destino inválido o nombre ocupado")
            val id = "$prefix-${++nextId}"
            nodes[id] = Node(id, name, parent, directory)
            return id
        }

        override fun stat(id: String): DocumentInfo {
            val node = node(id)
            return DocumentInfo(node.id, node.name, node.directory,
                node.reportedSize ?: node.bytes.size.toLong(), 100,
                readable = true, writable = true, canRename = true,
                canDelete = node.deleteAllowed, canCreate = node.directory)
        }

        override fun children(id: String) = nodes.values.filter { it.parent == id }.map { stat(it.id) }

        override fun openRead(id: String): InputStream {
            val bytes = node(id).bytes.copyOf()
            if (corruptReads && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 1).toByte()
            return object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    val read = super.read(buffer, offset, count)
                    if (read < 0) completedReads += id
                    return read
                }
            }
        }

        override fun createFile(parentId: String, name: String, mimeType: String): String =
            add(parentId, name, false).also { createdIds += it }

        override fun createDirectory(parentId: String, name: String): String =
            add(parentId, name, true).also { createdIds += it }

        override fun openWriteNew(id: String): OutputStream {
            if (id !in createdIds) throw AssertionError("Se intentó escribir un documento original: $id")
            openedForWrite += id
            val target = node(id)
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    target.bytes += bytes.copyOfRange(offset, offset + count)
                    if (target.name == failWritesForName) throw IOException("Falló la escritura")
                }
                override fun close() { afterWrite?.invoke() }
            }
        }

        override fun rename(id: String, name: String): String = throw IOException("No se usa renombrado en transferencias")

        override fun delete(id: String): Boolean {
            beforeDelete?.invoke()
            if (!node(id).deleteAllowed || children(id).isNotEmpty()) throw IOException("No se puede borrar")
            deletedIds += id
            return nodes.remove(id) != null
        }
    }
}
