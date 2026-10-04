package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableUploadTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = Connection(label = "Test", protocol = Protocol.WEBDAV,
        host = "https://example.invalid", port = 443, user = "private-account",
        secret = "must-never-appear-in-the-upload-journal")
    private val payload = ByteArray(700000) { (it % 251).toByte() }

    /** Tests both URL/path providers and providers whose object IDs survive a rename. */
    private class MemoryFs(val ids: Boolean = false) : RemoteFs {
        data class Node(var path: String, var parent: String, var name: String,
            val directory: Boolean, var bytes: ByteArray = byteArrayOf(), var revision: Int = 1)
        private val nodes = linkedMapOf<String, Node>()
        private var nextId = 0
        override var supportsDurableUploads = true
        var writes = 0
        var publications = 0
        var closes = 0
        var beforeWrite: (String, String) -> Unit = { _, _ -> }
        var afterWrite: (Node) -> Unit = {}
        var beforePublish: (RemoteEntry, String, String) -> Unit = { _, _, _ -> }
        var afterPublish: (Node) -> Unit = {}
        fun put(parent: String, name: String, bytes: ByteArray, directory: Boolean = false): Node {
            val path = if (ids) "id${++nextId}" else RemoteFiles.join(parent, name)
            return Node(path, parent, name, directory, bytes.copyOf()).also { nodes[path] = it }
        }
        fun get(parent: String, name: String): Node? = nodes.values.singleOrNull { it.parent == parent && it.name == name }
        fun names(parent: String): List<String> = nodes.values.filter { it.parent == parent }.map { it.name }
        fun remove(node: Node) { nodes.remove(node.path) }
        fun change(node: Node, bytes: ByteArray) { node.bytes = bytes.copyOf(); node.revision++ }
        private fun entry(node: Node) = RemoteEntry(node.path, node.name, node.directory,
            if (node.directory) 0 else node.bytes.size.toLong(), node.revision.toString())
        override fun list(path: String) = nodes.values.filter { it.parent == path }.map(::entry)
        override fun read(path: String): InputStream = ByteArrayInputStream(nodes.getValue(path).bytes)
        override fun write(parent: String, name: String, input: InputStream, size: Long): String {
            beforeWrite(parent, name)
            if (get(parent, name) != null) throw IOException("No overwrite")
            writes++
            val node = put(parent, name, byteArrayOf())
            val bytes = ByteArrayOutputStream()
            try {
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    node.bytes = bytes.toByteArray()
                }
            } finally { node.bytes = bytes.toByteArray() }
            afterWrite(node)
            return node.path
        }
        override fun mkdir(parent: String, name: String): String {
            if (get(parent, name) != null) throw IOException("No overwrite")
            return put(parent, name, byteArrayOf(), true).path
        }
        override fun publishUpload(stage: RemoteEntry, parent: String, name: String): String {
            beforePublish(stage, parent, name)
            if (get(parent, name) != null) throw IOException("Destination exists")
            val node = nodes.getValue(stage.path)
            val old = node.path
            val updated = if (ids) old else RemoteFiles.join(parent, name)
            nodes.remove(old)
            node.path = updated
            node.name = name
            node.revision++
            nodes[updated] = node
            if (!ids) {
                val descendants = nodes.values.filter { it.path.startsWith(old.trimEnd('/') + "/") }.toList()
                descendants.forEach {
                    nodes.remove(it.path)
                    it.path = updated + it.path.removePrefix(old)
                    it.parent = updated + it.parent.removePrefix(old)
                    nodes[it.path] = it
                }
            }
            publications++
            afterPublish(node)
            return updated
        }
        override fun rename(entry: RemoteEntry, name: String) = error("Use no-replace publication")
        override fun delete(entry: RemoteEntry) {
            val node = nodes.getValue(entry.path)
            if (node.directory && nodes.values.any { it.parent == node.path })
                throw IOException("Directory is not empty")
            nodes.remove(node.path)
        }
        override fun close() { closes++ }
    }

    private fun source(name: String = "large.bin", bytes: ByteArray = payload): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private suspend fun prepare(fs: MemoryFs, jobs: File, files: List<File>, connection: Connection = account): DurableUpload =
        DurableUpload.create(jobs, connection, fs, "/", files) { _, _ -> fs }

    private fun restore(fs: MemoryFs, jobs: File): DurableUpload =
        DurableUpload.load(jobs.listFiles()!!.single()) { _, _ -> fs }

    private suspend fun reject(job: DurableUpload) {
        try { job.run {}; fail("Expected IOException") } catch (_: IOException) {}
    }

    @Test fun interruptedFileRestartsWithoutReplacingTheOriginalOrAnUnrelatedDestination() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        val job = prepare(fs, jobs, listOf(original))
        try {
            job.run { if (it.doneBytes > 0) throw CancellationException("paused") }
            fail("Expected pause")
        } catch (_: CancellationException) {}
        assertNull(fs.get("/", original.name))
        assertEquals(1, fs.closes)
        val saved = jobs.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(saved.contains(account.secret))
        assertFalse(saved.contains(account.user))
        restore(fs, jobs).run {}
        assertArrayEquals(payload, fs.get("/", original.name)!!.bytes)
        assertArrayEquals(payload, original.readBytes())
        assertEquals(1, fs.publications)
        assertEquals(2, fs.writes)
        assertTrue(jobs.listFiles()!!.isEmpty())
    }

    @Test fun aConfirmedFileIsNotResentAfterALaterBatchFailure() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val a = source("a.bin", byteArrayOf(1, 2))
        val b = source("b.bin", byteArrayOf(3, 4))
        fs.beforeWrite = { _, _ -> if (fs.writes == 1) throw IOException("offline") }
        reject(prepare(fs, jobs, listOf(a, b)))
        assertArrayEquals(a.readBytes(), fs.get("/", "a.bin")!!.bytes)
        fs.beforeWrite = { _, _ -> }
        restore(fs, jobs).run {}
        assertEquals(2, fs.writes)
        assertEquals(listOf("a.bin", "b.bin"), fs.names("/"))
    }

    @Test fun aFullyWrittenStageIsRecoveredWithoutResendingItsBytes() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        fs.afterWrite = { throw IOException("lost acknowledgement") }
        reject(prepare(fs, jobs, listOf(original)))
        fs.afterWrite = {}
        restore(fs, jobs).run {}
        assertEquals(1, fs.writes)
        assertEquals(1, fs.publications)
        assertArrayEquals(payload, fs.get("/", original.name)!!.bytes)
    }

    @Test fun publicationBeforeTheCompletionRecordIsRecoveredWithoutDuplicates() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        fs.afterPublish = { throw IOException("process died after publish") }
        reject(prepare(fs, jobs, listOf(original)))
        fs.afterPublish = {}
        restore(fs, jobs).run {}
        assertEquals(1, fs.writes)
        assertEquals(1, fs.publications)
        assertEquals(listOf(original.name), fs.names("/"))
    }

    @Test fun sameNameSameContentWithAnotherStableIdIsNeverAdopted() = runBlocking {
        val fs = MemoryFs(ids = true)
        val jobs = temp.newFolder("jobs")
        val original = source()
        fs.afterPublish = { node ->
            fs.remove(node)
            fs.put("/", node.name, node.bytes)
            throw IOException("published object was replaced")
        }
        reject(prepare(fs, jobs, listOf(original), account.copy(protocol = Protocol.DRIVE)))
        fs.afterPublish = {}
        reject(restore(fs, jobs))
        assertEquals(1, fs.writes)
        assertEquals(1, fs.publications)
        assertArrayEquals(payload, fs.get("/", original.name)!!.bytes)
    }

    @Test fun aDestinationCreatedDuringPublicationIsNeverOverwritten() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        fs.beforePublish = { _, parent, name -> fs.put(parent, name, byteArrayOf(99)) }
        reject(prepare(fs, jobs, listOf(original)))
        fs.beforePublish = { _, _, _ -> }
        reject(restore(fs, jobs))
        assertArrayEquals(byteArrayOf(99), fs.get("/", original.name)!!.bytes)
        assertArrayEquals(payload, original.readBytes())
    }

    @Test fun aChangedLocalFileWithTheSameSizeAndTimestampIsRejected() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        val modified = original.lastModified()
        val job = prepare(fs, jobs, listOf(original))
        original.writeBytes(payload.copyOf().apply { this[0] = 99 })
        assertTrue(original.setLastModified(modified))
        reject(job)
        assertEquals(0, fs.writes)
        assertNull(fs.get("/", original.name))
    }

    @Test fun localMutationDuringUploadCannotPublishMixedContents() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        val modified = original.lastModified()
        fs.afterWrite = {
            original.writeBytes(payload.copyOf().apply { this[0] = 99 })
            original.setLastModified(modified)
        }
        reject(prepare(fs, jobs, listOf(original)))
        assertNull(fs.get("/", original.name))
        assertEquals(0, fs.publications)
        assertEquals(99, original.readBytes()[0].toInt())
    }

    @Test fun corruptServerContentsAreNotAcceptedJustBecauseTheirLengthMatches() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        fs.afterWrite = { fs.change(it, it.bytes.copyOf().apply { this[0] = 99 }) }
        reject(prepare(fs, jobs, listOf(original)))
        assertNull(fs.get("/", original.name))
        assertEquals(0, fs.publications)
        fs.afterWrite = {}
        val result = restore(fs, jobs).run {}
        assertArrayEquals(payload, fs.get("/", original.name)!!.bytes)
        assertTrue(result.message!!.contains("temporales conservados"))
    }

    @Test fun aPublishedFileChangedBeforeBatchRecoveryIsRejected() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val a = source("a.bin", byteArrayOf(1))
        val b = source("b.bin", byteArrayOf(2))
        fs.beforeWrite = { _, _ -> if (fs.writes == 1) throw IOException("offline") }
        reject(prepare(fs, jobs, listOf(a, b)))
        fs.change(fs.get("/", "a.bin")!!, byteArrayOf(99))
        fs.beforeWrite = { _, _ -> }
        reject(restore(fs, jobs))
        assertEquals(1, fs.writes)
        assertArrayEquals(byteArrayOf(99), fs.get("/", "a.bin")!!.bytes)
    }

    @Test fun nestedFoldersAndOverlappingSourcesAreUploadedOnce() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val folder = temp.newFolder("tree")
        val nested = File(folder, "child").apply { mkdir() }
        val file = File(nested, "a.txt").apply { writeText("hello") }
        val job = prepare(fs, jobs, listOf(folder, nested, file))
        assertEquals(1, job.count)
        job.run {}
        val root = fs.get("/", "tree")!!
        val child = fs.get(root.path, "child")!!
        assertEquals(listOf("child"), fs.names(root.path))
        assertEquals(listOf("a.txt"), fs.names(child.path))
        assertArrayEquals(file.readBytes(), fs.get(child.path, "a.txt")!!.bytes)
        assertEquals(1, fs.writes - 2) // two temporary directory identity markers
        assertTrue(file.exists())
    }

    @Test fun aFolderPublishedBeforeJournalSaveIsRecognizedByItsMarker() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val folder = temp.newFolder("tree")
        File(folder, "a.txt").writeText("hello")
        fs.afterPublish = { if (it.directory) throw IOException("process died") }
        reject(prepare(fs, jobs, listOf(folder)))
        fs.afterPublish = {}
        restore(fs, jobs).run {}
        assertEquals(listOf("tree"), fs.names("/"))
        assertEquals(listOf("a.txt"), fs.names("/tree"))
    }

    @Test fun existingDestinationNamesAreReservedWithoutReplacingAnything() = runBlocking {
        val fs = MemoryFs()
        fs.put("/", "large.bin", byteArrayOf(99))
        val jobs = temp.newFolder("jobs")
        val original = source()
        prepare(fs, jobs, listOf(original)).run {}
        assertArrayEquals(byteArrayOf(99), fs.get("/", "large.bin")!!.bytes)
        assertArrayEquals(payload, fs.get("/", "large (1).bin")!!.bytes)
    }

    @Test fun symlinksAndUnsupportedPublicationCannotPrepareAnUpload() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        val link = File(temp.root, "link.bin")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        try { prepare(fs, jobs, listOf(link)); fail("Expected symlink rejection") } catch (_: IOException) {}
        fs.supportsDurableUploads = false
        try { prepare(fs, jobs, listOf(original)); fail("Expected capability rejection") } catch (_: IOException) {}
        assertEquals(0, fs.writes)
        assertTrue(jobs.listFiles()!!.isEmpty())
    }

    @Test fun aSourceReplacedByASymlinkAfterPreparationCannotBeRead() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        prepare(fs, jobs, listOf(original))
        val other = source("other.bin")
        assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), other.toPath())
        reject(restore(fs, jobs))
        assertEquals(0, fs.writes)
        assertArrayEquals(payload, other.readBytes())
    }

    @Test fun anEmptyFileIsVerifiedAndPublishedAfterALostWriteAcknowledgement() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source("empty.bin", byteArrayOf())
        fs.afterWrite = { throw IOException("lost acknowledgement") }
        reject(prepare(fs, jobs, listOf(original)))
        fs.afterWrite = {}
        restore(fs, jobs).run {}
        assertEquals(1, fs.writes)
        assertEquals(1, fs.publications)
        assertArrayEquals(byteArrayOf(), fs.get("/", original.name)!!.bytes)
    }

    @Test fun discardPreservesCompletedAndUnrelatedFiles() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val a = source("a.bin", byteArrayOf(1))
        val b = source("b.bin", byteArrayOf(2))
        fs.beforeWrite = { _, _ -> if (fs.writes == 1) throw IOException("offline") }
        reject(prepare(fs, jobs, listOf(a, b)))
        fs.put("/", "unrelated.bin", byteArrayOf(99))
        restore(fs, jobs).discard()
        assertArrayEquals(byteArrayOf(1), fs.get("/", "a.bin")!!.bytes)
        assertArrayEquals(byteArrayOf(99), fs.get("/", "unrelated.bin")!!.bytes)
        assertTrue(a.exists())
        assertTrue(b.exists())
        assertTrue(jobs.listFiles()!!.isEmpty())
    }

    @Test fun aNestedWorkflowRetainsItsJournalAndCanVerifyAfterItsLocalSourceIsRemoved() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        val original = source()
        val job = prepare(fs, jobs, listOf(original))
        job.runKeepingJournal {}
        val published = job.publishedSources().single()
        assertEquals(original.canonicalPath, published.source.path)
        assertEquals(payload.size.toLong(), published.size)
        assertTrue(published.hash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(original.delete())
        restore(fs, jobs).runKeepingJournal {}
        assertEquals(1, fs.writes)
        assertTrue(jobs.listFiles()!!.single().exists())
        restore(fs, jobs).discard()
        assertArrayEquals(payload, fs.get("/", "large.bin")!!.bytes)
    }

    @Test fun changingTheStoredConnectionRejectsRecoveryBeforeAnyRemoteWrite() = runBlocking {
        val fs = MemoryFs()
        val jobs = temp.newFolder("jobs")
        prepare(fs, jobs, listOf(source()))
        val job = DurableUpload.load(jobs.listFiles()!!.single()) { id, stamp ->
            assertEquals(account.id, id)
            assertEquals(DurableDownload.stamp(account), stamp)
            if (DurableDownload.stamp(account.copy(user = "other-account")) != stamp)
                throw IOException("account changed")
            fs
        }
        reject(job)
        assertEquals(0, fs.writes)
        assertTrue(jobs.listFiles()!!.single().exists())
    }
}
