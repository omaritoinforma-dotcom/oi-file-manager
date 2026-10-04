package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the app's real FTP, SFTP and WebDAV clients against real servers started by
 * `scripts/remote_servers.py`. The servers share one folder, so results are checked on disk.
 */
class RemoteServerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun env(name: String) =
        System.getenv("OI_REMOTE_TEST_$name")
            ?: throw AssertionError(
                "Falta OI_REMOTE_TEST_$name: inicia scripts/remote_servers.py y exporta su salida")

    private val disk by lazy { File(env("ROOT")) }

    private fun connections(): List<Connection> {
        val password = env("PASSWORD")
        return listOf(
            Connection(
                "sftp", "SFTP", Protocol.SFTP, "127.0.0.1", env("SFTP_PORT").toInt(), "oi",
                password, fingerprint = env("SFTP_FINGERPRINT")),
            Connection(
                "ftp", "FTP", Protocol.FTP, "127.0.0.1", env("FTP_PORT").toInt(), "oi", password),
            Connection(
                "webdav", "WebDAV", Protocol.WEBDAV, "http://127.0.0.1:${env("WEBDAV_PORT")}", 0,
                "oi", password))
    }

    /** Runs [block] on every server and names the protocol in any failure. */
    private suspend fun eachServer(block: suspend (Connection) -> Unit) {
        for (c in connections()) {
            try {
                block(c)
            } catch (e: Throwable) {
                throw AssertionError("${c.label}: ${e.message}", e)
            }
        }
    }

    private fun payload(seed: Int) = ByteArray(3_000_000) { ((it * 31 + seed) % 251).toByte() }

    /** Records every resumed read so the tests can prove the byte offset path was used. */
    private class Recording(val fs: RemoteFs, val offsets: MutableList<Long>) : RemoteFs by fs {
        override fun readFrom(path: String, offset: Long): InputStream? =
            fs.readFrom(path, offset)?.also { offsets += offset }
    }

    private suspend fun interrupt(job: DurableJob) {
        try {
            job.run { if (it.doneBytes > 0) throw CancellationException("process interrupted") }
            fail("Expected interruption")
        } catch (_: CancellationException) {}
    }

    private fun folder(c: Connection): String {
        val name = "${c.id}-${System.nanoTime()}"
        RemoteFiles.connect(c).use { it.mkdir("/", name) }
        assertTrue(File(disk, name).isDirectory)
        return "/$name"
    }

    @Test
    fun clientsListReadWriteRenameAndDeleteOnRealServers() = runBlocking {
        eachServer { c ->
            val base = folder(c)
            val data = payload(1)
            RemoteFiles.connect(c).use { fs ->
                val sub = fs.mkdir(base, "sub carpeta")
                fs.write(sub, "año.bin", data.inputStream(), data.size.toLong())
                assertArrayEquals(c.label, data, File(disk, "$base/sub carpeta/año.bin").readBytes())
                val entry = fs.list(sub).single()
                assertEquals(c.label, "año.bin", entry.name)
                assertEquals(c.label, data.size.toLong(), entry.size)
                assertArrayEquals(c.label, data, fs.read(entry.path).use { it.readBytes() })
                val tail = fs.readFrom(entry.path, 1_000_000)
                assertNotNull("${c.label} debe reanudar por bytes", tail)
                assertArrayEquals(
                    c.label, data.copyOfRange(1_000_000, data.size), tail!!.use { it.readBytes() })
                fs.rename(entry, "renombrado.bin")
                assertEquals(listOf("renombrado.bin"), File(disk, "$base/sub carpeta").list()!!.toList())
                fs.delete(fs.list(sub).single())
                fs.delete(fs.list(base).single())
                assertTrue(c.label, File(disk, base).list()!!.isEmpty())
            }
        }
    }

    @Test
    fun interruptedMovedDownloadResumesAtOffsetOnRealServers() = runBlocking {
        eachServer { c ->
            val base = folder(c)
            val expected = payload(2)
            File(disk, "$base/Fotos").mkdir()
            File(disk, "$base/Fotos/grande.bin").writeBytes(expected)
            File(disk, "$base/Fotos/pequeño.txt").writeText("hola")
            val journals = temp.newFolder()
            val dest = temp.newFolder()
            val offsets = ArrayList<Long>()
            val connector = { _: String -> Recording(RemoteFiles.connect(c), offsets) }
            val entries = RemoteFiles.connect(c).use { it.list(base) }
            RemoteFiles.connect(c).use { fs ->
                interrupt(
                    DurableRemote.createDownload(
                        journals, fs, c, entries, base, dest, true, connector))
            }
            assertTrue(c.label, File(disk, "$base/Fotos/grande.bin").exists())
            DurableRemote.pending(journals, connector).single().run {}
            assertTrue("${c.label}: offsets $offsets", offsets.single() > 0)
            assertArrayEquals(c.label, expected, File(dest, "Fotos/grande.bin").readBytes())
            assertEquals(c.label, "hola", File(dest, "Fotos/pequeño.txt").readText())
            assertEquals(c.label, setOf("grande.bin", "pequeño.txt"), File(dest, "Fotos").list()!!.toSet())
            // Moved: originals removed only after the copies were stored, empty folder included.
            assertTrue(c.label, File(disk, base).list()!!.isEmpty())
            assertTrue(c.label, journals.list()!!.isEmpty())
        }
    }

    @Test
    fun interruptedMovedUploadResumesWithoutLeftoversOnRealServers() = runBlocking {
        eachServer { c ->
            val base = folder(c)
            File(disk, "$base/Proyecto").mkdir()
            val source = File(temp.newFolder(), "Proyecto").apply { mkdir() }
            File(source, "a.bin").writeBytes(payload(3))
            File(source, "sub").mkdir()
            File(source, "sub/b.bin").writeBytes(payload(4))
            val journals = temp.newFolder()
            val connector = { _: String -> RemoteFiles.connect(c) }
            interrupt(DurableRemote.createUpload(journals, c, listOf(source), base, true, connector))
            assertTrue(c.label, File(source, "sub/b.bin").exists() && File(source, "a.bin").exists())
            DurableRemote.pending(journals, connector).single().run {}
            // The existing folder kept its name and content; the upload got a unique name.
            val uploaded = File(disk, "$base/Proyecto (1)")
            assertArrayEquals(c.label, payload(3), File(uploaded, "a.bin").readBytes())
            assertArrayEquals(c.label, payload(4), File(uploaded, "sub/b.bin").readBytes())
            assertEquals(c.label, setOf("a.bin", "sub"), uploaded.list()!!.toSet())
            assertEquals(c.label, listOf("b.bin"), File(uploaded, "sub").list()!!.toList())
            assertEquals(c.label, setOf("Proyecto", "Proyecto (1)"), File(disk, base).list()!!.toSet())
            assertFalse(c.label, source.exists())
        }
    }
}
