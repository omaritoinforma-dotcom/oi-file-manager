package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableRemoteTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val sourceAccount = Connection(id = "source", label = "Source", protocol = Protocol.WEBDAV,
        host = "source.invalid", port = 443, user = "test", secret = "source-secret")
    private val targetAccount = sourceAccount.copy(id = "target", label = "Target", host = "target.invalid",
        secret = "target-secret")
    private val payload = ByteArray(500000) { (it % 251).toByte() }

    /** Path-based server with publication that atomically rejects an occupied name. */
    private class MemoryFs : RemoteFs {
        private data class Node(val directory: Boolean, val bytes: ByteArray)
        private val nodes = linkedMapOf<String, Node>()
        override val supportsDurableUploads = true
        var writes = 0
        var publications = 0
        val deletions = mutableListOf<String>()
        var onList: (String) -> Unit = {}
        var beforePublish: (String) -> Unit = {}
        var afterPublish: (String) -> Unit = {}
        var afterDelete: (String) -> Unit = {}

        fun put(path: String, bytes: ByteArray) { nodes[path] = Node(false, bytes.copyOf()) }
        fun folder(path: String) { nodes[path] = Node(true, byteArrayOf()) }
        fun exists(path: String) = nodes.containsKey(path)
        fun bytes(path: String) = nodes.getValue(path).bytes
        private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
        private fun join(parent: String, name: String) = parent.trimEnd('/') + "/" + name
        private fun entry(path: String, node: Node) = RemoteEntry(path, path.substringAfterLast('/'),
            node.directory, if (node.directory) 0 else node.bytes.size.toLong(), "")

        override fun list(path: String): List<RemoteEntry> {
            onList(path)
            return nodes.filterKeys { parent(it) == path }.map { (name, node) -> entry(name, node) }
        }
        override fun read(path: String): InputStream = ByteArrayInputStream(bytes(path))
        override fun write(parent: String, name: String, input: InputStream, size: Long): String {
            val path = join(parent, name)
            if (exists(path)) throw IOException("Would overwrite")
            val bytes = input.readBytes()
            if (bytes.size.toLong() != size) throw IOException("Incomplete upload")
            put(path, bytes)
            writes++
            return path
        }
        override fun mkdir(parent: String, name: String): String = join(parent, name).also {
            if (exists(it)) throw IOException("Would replace folder")
            folder(it)
        }
        override fun rename(entry: RemoteEntry, name: String) { publishUpload(entry, parent(entry.path), name) }
        override fun publishUpload(stage: RemoteEntry, parent: String, name: String): String {
            val target = join(parent, name)
            beforePublish(target)
            if (exists(target)) throw IOException("Concurrent destination")
            val moved = nodes.filterKeys { it == stage.path || it.startsWith(stage.path + "/") }
            if (moved.isEmpty()) throw IOException("Missing stage")
            moved.keys.forEach(nodes::remove)
            moved.forEach { (path, node) -> nodes[target + path.removePrefix(stage.path)] = node }
            publications++
            afterPublish(target)
            return target
        }
        override fun delete(entry: RemoteEntry) {
            if (entry.directory && nodes.keys.any { it.startsWith(entry.path + "/") })
                throw IOException("Folder is not empty")
            if (nodes.remove(entry.path) == null) throw IOException("Missing source")
            deletions += entry.path
            afterDelete(entry.path)
        }
    }

    private fun source() = MemoryFs().apply { put("/file.bin", payload) }
    private fun opener(source: MemoryFs, target: MemoryFs): (String, String) -> RemoteFs = { id, stamp ->
        val account = if (id == sourceAccount.id) sourceAccount else targetAccount
        assertEquals(DurableDownload.stamp(account), stamp)
        if (id == sourceAccount.id) source else target
    }
    private val metadata: (String, String) -> Connection = { id, stamp ->
        (if (id == sourceAccount.id) sourceAccount else targetAccount).also {
            assertEquals(DurableDownload.stamp(it), stamp)
        }
    }
    private suspend fun create(journals: File, local: File, source: MemoryFs, target: MemoryFs,
        move: Boolean, remote: Boolean = true): DurableRemoteTransfer = DurableRemoteTransfer.create(
        journals, sourceAccount, source, "/", source.list("/"), local, move,
        if (remote) targetAccount else null, if (remote) "/" else "", opener(source, target), metadata)
    private fun restore(journals: File, source: MemoryFs, target: MemoryFs) =
        DurableRemoteTransfer.load(journals.listFiles()!!.single { it.extension == "remote-job" },
            opener(source, target), metadata)
    private suspend fun rejected(job: DurableRemoteTransfer) {
        try { job.run {}; fail("Expected IOException") } catch (_: IOException) {}
    }
    private suspend fun interrupted(job: DurableRemoteTransfer) {
        try { job.run {}; fail("Expected interruption") } catch (_: CancellationException) {}
    }

    @Test fun remoteCopyPublishesExactContentsAndPreservesOriginal() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        val job = create(journals, temp.newFolder("local"), source, target, move = false)
        job.run {}
        assertArrayEquals(payload, target.bytes("/file.bin"))
        assertArrayEquals(payload, source.bytes("/file.bin"))
        assertTrue(source.deletions.isEmpty())
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun localMoveDeletesOnlyAfterDestinationHasCommitted() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val local = temp.newFolder("local")
        source.afterDelete = { assertArrayEquals(payload, File(local, "file.bin").readBytes()) }
        create(temp.newFolder("journals"), local, source, target, move = true, remote = false).run {}
        assertArrayEquals(payload, File(local, "file.bin").readBytes())
        assertEquals(listOf("/file.bin"), source.deletions)
        assertEquals(0, target.writes)
    }

    @Test fun deletionIntentRecoversDeathAfterSourceDeleteWithoutSecondDelete() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        val local = temp.newFolder("local")
        source.afterDelete = { throw CancellationException("Death before deleted journal record") }
        interrupted(create(journals, local, source, target, move = true, remote = false))
        assertFalse(source.exists("/file.bin"))
        assertArrayEquals(payload, File(local, "file.bin").readBytes())
        source.afterDelete = {}
        restore(journals, source, target).run {}
        assertEquals(listOf("/file.bin"), source.deletions)
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun publishedUploadRecoversDeathBeforeChildCompletionWithoutDuplicateWrites() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        target.afterPublish = { throw CancellationException("Death after remote publication") }
        interrupted(create(journals, temp.newFolder("local"), source, target, move = true))
        assertArrayEquals(payload, target.bytes("/file.bin"))
        assertTrue(source.exists("/file.bin"))
        assertEquals(1, target.writes)
        target.afterPublish = {}
        restore(journals, source, target).run {}
        assertEquals(1, target.writes)
        assertEquals(1, target.publications)
        assertEquals(listOf("/file.bin"), source.deletions)
    }

    @Test fun completedChildrenSurviveParentFailureAndAreVerifiedInsteadOfResent() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        source.onList = { if (target.exists("/file.bin")) throw IOException("Listing unavailable") }
        rejected(create(journals, temp.newFolder("local"), source, target, move = true))
        assertTrue(source.exists("/file.bin"))
        assertEquals(1, target.writes)
        source.onList = {}
        restore(journals, source, target).run {}
        assertEquals(1, target.writes)
        assertEquals(1, target.publications)
        assertFalse(source.exists("/file.bin"))
    }

    @Test fun sameSizeSourceChangeWithoutRevisionPreventsDeletionAfterUpload() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val changed = payload.copyOf().apply { this[0] = 99 }
        target.afterPublish = { source.put("/file.bin", changed) }
        rejected(create(temp.newFolder("journals"), temp.newFolder("local"), source, target, move = true))
        assertArrayEquals(payload, target.bytes("/file.bin"))
        assertArrayEquals(changed, source.bytes("/file.bin"))
        assertTrue(source.deletions.isEmpty())
    }

    @Test fun newSourceChildIsPreservedAndItsFolderIsNeverRecursivelyDeleted() = runBlocking {
        val source = MemoryFs().apply { folder("/folder"); put("/folder/file.bin", payload) }
        val target = MemoryFs()
        target.afterPublish = { if (it == "/folder/file.bin") source.put("/folder/new.txt", byteArrayOf(7)) }
        rejected(create(temp.newFolder("journals"), temp.newFolder("local"), source, target, move = true))
        assertArrayEquals(payload, target.bytes("/folder/file.bin"))
        assertTrue(source.exists("/folder"))
        assertArrayEquals(byteArrayOf(7), source.bytes("/folder/new.txt"))
        assertFalse(source.deletions.contains("/folder"))
    }

    @Test fun interruptedFolderEnumerationCreatesNoTransferAndDeletesNothing() = runBlocking {
        val source = MemoryFs().apply { folder("/folder"); put("/folder/file.bin", payload) }
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        source.onList = { if (it == "/folder") throw IOException("Second listing page failed") }
        try {
            create(journals, temp.newFolder("local"), source, target, move = true)
            fail("Expected listing failure")
        } catch (_: IOException) {}
        assertTrue(source.exists("/folder/file.bin"))
        assertTrue(source.deletions.isEmpty())
        assertEquals(0, target.writes)
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun concurrentTargetConflictPreservesOriginalsAndUnrelatedDestination() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val unrelated = byteArrayOf(4, 5, 6)
        target.beforePublish = { target.put(it, unrelated) }
        rejected(create(temp.newFolder("journals"), temp.newFolder("local"), source, target, move = true))
        assertArrayEquals(payload, source.bytes("/file.bin"))
        assertArrayEquals(unrelated, target.bytes("/file.bin"))
        assertTrue(source.deletions.isEmpty())
    }

    @Test fun changedCompletedTargetOnRecoveryNeverPermitsOriginalDeletion() = runBlocking {
        val source = source()
        val target = MemoryFs()
        val journals = temp.newFolder("journals")
        source.onList = { if (target.exists("/file.bin")) throw IOException("Source offline") }
        rejected(create(journals, temp.newFolder("local"), source, target, move = true))
        source.onList = {}
        val changed = payload.copyOf().apply { this[0] = 99 }
        target.put("/file.bin", changed)
        rejected(restore(journals, source, target))
        assertArrayEquals(payload, source.bytes("/file.bin"))
        assertArrayEquals(changed, target.bytes("/file.bin"))
        assertTrue(source.deletions.isEmpty())
        assertEquals(1, target.writes)
    }
}
