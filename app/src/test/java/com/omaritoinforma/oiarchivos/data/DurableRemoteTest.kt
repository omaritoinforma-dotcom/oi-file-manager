package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableRemoteTest {
    @get:Rule val temp = TemporaryFolder()

    /** Cloud-like server: paths are opaque ids and an interrupted write leaves a partial file. */
    private class FakeServer(val ranged: Boolean) {
        class Node(val id: String, val parent: String, var name: String, val directory: Boolean) {
            var data = ByteArray(0)
        }

        val nodes = linkedMapOf("root" to Node("root", "", "", true))
        val offsets = ArrayList<Long>()
        private var next = 0

        fun add(parent: String, name: String, data: ByteArray? = null): Node {
            val node = Node("id-${next++}", parent, name, data == null)
            if (data != null) node.data = data
            nodes[node.id] = node
            return node
        }

        fun child(parent: String, name: String) =
            nodes.values.single { it.parent == parent && it.name == name }

        fun entry(node: Node) =
            RemoteEntry(
                node.id,
                node.name,
                node.directory,
                if (node.directory) 0 else node.data.size.toLong(),
            )

        fun fs() =
            object : RemoteFs {
                override fun list(path: String) =
                    nodes.values.filter { it.parent == path }.map { entry(it) }

                override fun read(path: String): InputStream =
                    ByteArrayInputStream(nodes.getValue(path).data)

                override fun readFrom(path: String, offset: Long): InputStream? {
                    if (!ranged) return null
                    offsets += offset
                    val data = nodes.getValue(path).data
                    return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
                }

                override fun write(
                    parent: String,
                    name: String,
                    input: InputStream,
                    size: Long,
                ): String {
                    if (list(parent).any { it.name == name }) throw IOException("exists")
                    val node = add(parent, name, ByteArray(0))
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    try {
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    } finally {
                        node.data = out.toByteArray()
                    }
                    return node.id
                }

                override fun mkdir(parent: String, name: String) = add(parent, name).id

                override fun rename(entry: RemoteEntry, name: String) {
                    nodes.getValue(entry.path).name = name
                }

                override fun delete(entry: RemoteEntry) {
                    if (nodes.remove(entry.path) == null) throw IOException("missing")
                }
            }
    }

    private val connection =
        Connection("conn-1", "Nube", Protocol.DRIVE, "", 0, "user", "very-secret-token")

    private fun bytes(seed: Int = 0) = ByteArray(900000) { ((it + seed) % 251).toByte() }

    private suspend fun interrupt(job: DurableJob, after: Long = 0) {
        try {
            job.run { if (it.doneBytes > after) throw CancellationException("process interrupted") }
            fail("Expected interruption")
        } catch (_: CancellationException) {}
    }

    private suspend fun download(
        server: FakeServer,
        journals: File,
        dest: File,
        move: Boolean,
        vararg nodes: FakeServer.Node,
    ) =
        DurableRemote.createDownload(
            journals,
            server.fs(),
            connection,
            nodes.map { server.entry(it) },
            "root",
            dest,
            move,
        ) {
            assertEquals(connection.id, it)
            server.fs()
        }

    private fun pending(server: FakeServer, journals: File) =
        DurableRemote.pending(journals) { server.fs() }.single()

    @Test
    fun restartedDownloadResumesAtOffsetAndOnlyThenDeletesMovedOriginal() = runBlocking {
        val server = FakeServer(ranged = true)
        val folder = server.add("root", "Fotos")
        val expected = bytes()
        val file = server.add(folder.id, "large.bin", expected)
        val journals = temp.newFolder("jobs")
        val dest = temp.newFolder("dest")
        interrupt(download(server, journals, dest, true, folder))
        assertTrue(file.id in server.nodes)
        assertFalse(File(dest, "Fotos/large.bin").exists())
        assertFalse(
            journals
                .listFiles()!!
                .single()
                .readText(Charsets.ISO_8859_1)
                .contains("very-secret-token")
        )
        pending(server, journals).run {}
        assertEquals(1, server.offsets.size)
        assertTrue(server.offsets.single() > 0)
        assertArrayEquals(expected, File(dest, "Fotos/large.bin").readBytes())
        assertEquals(listOf("large.bin"), File(dest, "Fotos").list()!!.toList())
        assertEquals(setOf("root"), server.nodes.keys)
        assertTrue(DurableRemote.pending(journals) { server.fs() }.isEmpty())
    }

    @Test
    fun serverWithoutRangesRestartsTheFileFromZero() = runBlocking {
        val server = FakeServer(ranged = false)
        val expected = bytes(7)
        val file = server.add("root", "video.mp4", expected)
        val journals = temp.newFolder("jobs")
        val dest = temp.newFolder("dest")
        interrupt(download(server, journals, dest, false, file))
        pending(server, journals).run {}
        assertArrayEquals(expected, File(dest, "video.mp4").readBytes())
        assertTrue(file.id in server.nodes)
        assertEquals(listOf("video.mp4"), dest.list()!!.toList())
    }

    @Test
    fun changedRemoteSizeDiscardsThePartialPrefix() = runBlocking {
        val server = FakeServer(ranged = true)
        val file = server.add("root", "doc.bin", bytes())
        val journals = temp.newFolder("jobs")
        val dest = temp.newFolder("dest")
        interrupt(download(server, journals, dest, false, file))
        val changed = ByteArray(950000) { 3 }
        file.data = changed
        try {
            pending(server, journals).run {}
            fail("Recorded size no longer matches")
        } catch (_: IOException) {}
        // The new content was fetched from zero, never appended to the old prefix.
        assertTrue(server.offsets.isEmpty())
        assertFalse(File(dest, "doc.bin").exists())
    }

    @Test
    fun downloadNeverReplacesAFileCreatedMeanwhile() = runBlocking {
        val server = FakeServer(ranged = true)
        val expected = bytes(3)
        val file = server.add("root", "notes.bin", expected)
        val journals = temp.newFolder("jobs")
        val dest = temp.newFolder("dest")
        interrupt(download(server, journals, dest, false, file))
        File(dest, "notes.bin").writeText("created by the user")
        pending(server, journals).run {}
        assertEquals("created by the user", File(dest, "notes.bin").readText())
        assertArrayEquals(expected, File(dest, "notes (1).bin").readBytes())
    }

    @Test
    fun restartedUploadReplacesOnlyItsOwnPartialAndKeepsOriginalsUntilStored() = runBlocking {
        val server = FakeServer(ranged = true)
        server.add("root", "Proyecto", null)
        val source = temp.newFolder("Proyecto")
        val first = File(source, "a.bin").apply { writeBytes(bytes(1)) }
        val nested = File(source, "sub").apply { mkdir() }
        val second = File(nested, "b.bin").apply { writeBytes(bytes(2)) }
        val journals = temp.newFolder("jobs")
        val job =
            DurableRemote.createUpload(journals, connection, listOf(source), "root", true) {
                server.fs()
            }
        interrupt(job)
        // The folder name conflicted, so a unique name was recorded before writing.
        val uploaded = server.child("root", "Proyecto (1)")
        val partial = server.nodes.values.single { !it.directory }
        assertTrue(partial.data.size < 900000)
        assertTrue(first.exists() && second.exists())
        pending(server, journals).run {}
        val sub = server.child(uploaded.id, "sub")
        assertArrayEquals(bytes(1), server.child(uploaded.id, "a.bin").data)
        assertArrayEquals(bytes(2), server.child(sub.id, "b.bin").data)
        assertEquals(1, server.nodes.values.count { it.parent == sub.id })
        assertFalse(source.exists())
        assertTrue(DurableRemote.pending(journals) { server.fs() }.isEmpty())
    }

    @Test
    fun changedLocalOriginalStopsMovedUpload() = runBlocking {
        val server = FakeServer(ranged = true)
        val source = File(temp.newFolder("src"), "data.bin").apply { writeBytes(bytes()) }
        val journals = temp.newFolder("jobs")
        interrupt(
            DurableRemote.createUpload(journals, connection, listOf(source), "root", true) {
                server.fs()
            }
        )
        source.appendBytes(byteArrayOf(1))
        try {
            pending(server, journals).run {}
            fail("Changed original")
        } catch (_: IOException) {}
        assertEquals(900001, source.length())
    }

    @Test
    fun deletedConnectionIsReportedAndJournalIsKept() = runBlocking {
        val server = FakeServer(ranged = true)
        val file = server.add("root", "x.bin", bytes())
        val journals = temp.newFolder("jobs")
        interrupt(download(server, journals, temp.newFolder("dest"), false, file))
        val orphan = DurableRemote.pending(journals) { throw NoSuchElementException() }.single()
        try {
            orphan.run {}
            fail("Missing connection")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Nube"))
        }
        assertEquals(1, DurableRemote.pending(journals) { server.fs() }.size)
    }
}
