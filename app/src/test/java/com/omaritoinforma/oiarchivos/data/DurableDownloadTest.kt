package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = Connection(label = "Test", protocol = Protocol.WEBDAV, host = "example.invalid",
        port = 443, user = "account", secret = "must-never-be-in-the-journal")
    private val payload = ByteArray(900000) { (it % 251).toByte() }

    private class MemoryFs : RemoteFs {
        val listing = mutableMapOf<String, List<RemoteEntry>>()
        val contents = mutableMapOf<String, ByteArray>()
        var reads = 0
        var closes = 0
        var onList: (String) -> Unit = {}
        var onRead: (String) -> Unit = {}
        override fun list(path: String): List<RemoteEntry> { onList(path); return listing[path].orEmpty() }
        override fun read(path: String): InputStream {
            onRead(path)
            reads++
            return ByteArrayInputStream(contents.getValue(path))
        }
        override fun close() { closes++ }
        override fun write(parent: String, name: String, input: InputStream, size: Long): String = error("No uploads")
        override fun mkdir(parent: String, name: String): String = error("No remote changes")
        override fun rename(entry: RemoteEntry, name: String) = error("No remote changes")
        override fun delete(entry: RemoteEntry) = error("No remote changes")
    }

    private fun remote(size: Long = payload.size.toLong(), revision: String = "v1"): MemoryFs = MemoryFs().apply {
        listing["/"] = listOf(RemoteEntry("/large.bin", "large.bin", false, size, revision))
        contents["/large.bin"] = payload.copyOf()
    }

    private suspend fun job(fs: MemoryFs, journals: File, destination: File) =
        DurableDownload.create(journals, account, fs, "/", fs.list("/"), destination) { _, _ -> fs }

    private fun restore(journals: File, fs: MemoryFs): DurableDownload =
        DurableDownload.load(journals.listFiles()!!.single()) { _, _ -> fs }

    private suspend fun interrupt(job: DurableDownload) {
        try {
            job.run { if (it.doneBytes > 0) throw CancellationException("process interrupted") }
            fail("Expected interruption")
        } catch (_: CancellationException) {}
    }

    private suspend fun rejected(job: DurableDownload) {
        try { job.run {}; fail("Expected IOException") } catch (_: IOException) {}
    }

    @Test fun reopeningAfterProcessDeathValidatesAndAppendsThePersistedPrefix() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        val partial = destination.listFiles()!!.single()
        assertTrue(partial.length() in 1 until payload.size.toLong())
        assertFalse(File(destination, "large.bin").exists())
        assertEquals(1, fs.closes)
        val saved = journals.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(saved.contains(account.secret))
        assertFalse(saved.contains(account.user))
        restore(journals, fs).run {}
        assertArrayEquals(payload, File(destination, "large.bin").readBytes())
        assertFalse(partial.exists())
        assertTrue(journals.listFiles()!!.isEmpty())
        assertArrayEquals(payload, fs.contents.getValue("/large.bin"))
    }

    @Test fun corruptedPartialIsPreservedWithoutPublishingMixedContents() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        val partial = destination.listFiles()!!.single()
        partial.writeBytes(partial.readBytes().apply { this[0] = 99 })
        rejected(restore(journals, fs))
        assertTrue(partial.exists())
        assertFalse(File(destination, "large.bin").exists())
    }

    @Test fun changedRemoteWithSameSizeAndNoVersionIsDetectedByItsPrefix() = runBlocking {
        val fs = remote(revision = "")
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        fs.contents.getValue("/large.bin")[0] = 99
        rejected(restore(journals, fs))
        assertFalse(File(destination, "large.bin").exists())
    }

    @Test fun aChangedProviderRevisionRejectsAnOtherwiseIdenticalPrefix() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        fs.listing["/"] = fs.list("/").map { it.copy(revision = "v2") }
        val reads = fs.reads
        rejected(restore(journals, fs))
        assertEquals(reads, fs.reads)
        assertFalse(File(destination, "large.bin").exists())
    }

    @Test fun anUnrelatedNewDestinationIsNeverReplaced() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        val target = File(destination, "large.bin").apply { writeText("user's new file") }
        rejected(restore(journals, fs))
        assertEquals("user's new file", target.readText())
        assertTrue(destination.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun unknownSizeExportsCanResumeWithoutGuessingTheirFinalLength() = runBlocking {
        val fs = remote(size = -1)
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        restore(journals, fs).run {}
        assertArrayEquals(payload, File(destination, "large.bin").readBytes())
    }

    @Test fun shortReadsAreRecoverableInsteadOfBecomingCompletedFiles() = runBlocking {
        val fs = remote()
        fs.contents["/large.bin"] = payload.copyOf(300000)
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        rejected(job(fs, journals, destination))
        assertFalse(File(destination, "large.bin").exists())
        fs.contents["/large.bin"] = payload.copyOf()
        restore(journals, fs).run {}
        assertArrayEquals(payload, File(destination, "large.bin").readBytes())
    }

    @Test fun discardDoesNotFollowAReplacedPartialSymlink() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        interrupt(job(fs, journals, destination))
        val partial = destination.listFiles()!!.single()
        assertTrue(partial.delete())
        val unrelated = temp.newFile("unrelated.txt").apply { writeText("keep me") }
        Files.createSymbolicLink(partial.toPath(), unrelated.toPath())
        val restored = restore(journals, fs)
        rejected(restored)
        restored.discard()
        assertEquals("keep me", unrelated.readText())
        assertTrue(Files.isSymbolicLink(partial.toPath()))
    }

    @Test fun nestedFoldersAndDuplicateCloudNamesGetDistinctLocalTargets() = runBlocking {
        val fs = MemoryFs().apply {
            listing["/"] = listOf(RemoteEntry("folder-id", "folder", true, 0))
            listing["folder-id"] = listOf(RemoteEntry("file-a", "same.txt", false, 1), RemoteEntry("file-b", "same.txt", false, 1))
            contents["file-a"] = byteArrayOf(1)
            contents["file-b"] = byteArrayOf(2)
        }
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        job(fs, journals, destination).run {}
        assertArrayEquals(byteArrayOf(1), File(destination, "folder/same.txt").readBytes())
        assertArrayEquals(byteArrayOf(2), File(destination, "folder/same (1).txt").readBytes())
    }

    @Test fun traversalNamesCannotCreateADownloadJournal() = runBlocking {
        val fs = MemoryFs().apply { listing["/"] = listOf(RemoteEntry("id", "../outside", false, 1)) }
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        try { job(fs, journals, destination); fail("Traversal") } catch (_: IOException) {}
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun foldersWithCyclesCannotCreateADownloadJournal() = runBlocking {
        val folder = RemoteEntry("id", "folder", true, 0)
        val fs = MemoryFs().apply { listing["/"] = listOf(folder); listing["id"] = listOf(folder) }
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        try { job(fs, journals, destination); fail("Cycle") } catch (_: IOException) {}
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun aSourceChangedDuringTheReadIsNotPublished() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        val download = job(fs, journals, destination)
        var lists = 0
        fs.onList = { if (++lists == 2) fs.listing["/"] = fs.listing.getValue("/").map { it.copy(revision = "v2") } }
        rejected(download)
        assertFalse(File(destination, "large.bin").exists())
        assertTrue(destination.listFiles()!!.single().length() == payload.size.toLong())
    }

    @Test fun commitBeforeJournalCompletionIsRecoveredWithoutANetworkConnection() = runBlocking {
        val fs = remote()
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        val download = job(fs, journals, destination)
        var lists = 0
        val task = launch(start = CoroutineStart.LAZY) { download.run {} }
        fs.onList = { if (++lists == 2) task.cancel() }
        task.start()
        task.join()
        val partial = destination.listFiles()!!.single()
        assertTrue(partial.name.endsWith(".part"))
        Files.move(partial.toPath(), File(destination, "large.bin").toPath())
        val restored = DurableDownload.load(journals.listFiles()!!.single()) { _, _ -> error("Must finish offline") }
        restored.run {}
        assertArrayEquals(payload, File(destination, "large.bin").readBytes())
        assertTrue(journals.listFiles()!!.isEmpty())
    }

    @Test fun discardingABatchPreservesItsAlreadyCompletedFiles() = runBlocking {
        val fs = MemoryFs().apply {
            listing["/"] = listOf(RemoteEntry("a", "a.txt", false, 1), RemoteEntry("b", "b.txt", false, 1))
            contents["a"] = byteArrayOf(1)
            contents["b"] = byteArrayOf(2)
            onRead = { if (it == "b") throw CancellationException("interrupted second file") }
        }
        val journals = temp.newFolder("jobs")
        val destination = temp.newFolder("dest")
        try { job(fs, journals, destination).run {}; fail("Interrupted") } catch (_: CancellationException) {}
        val restored = restore(journals, fs)
        assertEquals(1, restored.completed)
        restored.discard()
        assertArrayEquals(byteArrayOf(1), File(destination, "a.txt").readBytes())
        assertFalse(File(destination, "b.txt").exists())
    }

    @Test fun authenticationRenewalDoesNotChangeTheEndpointIdentity() {
        assertEquals(DurableDownload.stamp(account), DurableDownload.stamp(account.copy(secret = "renewed")))
        assertNotEquals(DurableDownload.stamp(account), DurableDownload.stamp(account.copy(host = "other.invalid")))
        assertNotEquals(DurableDownload.stamp(account), DurableDownload.stamp(account.copy(user = "other")))
    }
}
