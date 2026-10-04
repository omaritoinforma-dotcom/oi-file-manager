package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's SFTP client against the owner's real Internet server. Runs only with
 * `-PrealSftp` (CI passes it when the password secret exists). Every test works inside its own
 * new folder, which is deleted afterwards; nothing else on the server is touched.
 */
class RealSftpServerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun env(name: String) =
        System.getenv("OI_REAL_SFTP_$name")?.takeIf { it.isNotBlank() }
            ?: throw AssertionError("Falta OI_REAL_SFTP_$name")

    private val connection by lazy {
        Connection(
            "real-sftp",
            "SFTP real",
            Protocol.SFTP,
            env("HOST"),
            env("PORT").toInt(),
            env("USER"),
            env("PASSWORD"),
            fingerprint = env("FINGERPRINT"))
    }

    private lateinit var folder: String

    private fun payload(seed: Int) = ByteArray(3_000_000) { ((it * 17 + seed) % 251).toByte() }

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

    @Before
    fun createFolder() {
        val base = System.getenv("OI_REAL_SFTP_DIR")?.takeIf { it.isNotBlank() } ?: "."
        RemoteFiles.connect(connection).use {
            folder = it.mkdir(base, "oi-prueba-${System.currentTimeMillis()}")
        }
    }

    @After
    fun removeFolder() {
        RemoteFiles.connect(connection).use { fs ->
            fs.delete(RemoteEntry(folder, folder.substringAfterLast('/'), true, 0))
        }
    }

    @Test
    fun listWriteReadResumeRenameAndDelete() {
        val data = payload(1)
        RemoteFiles.connect(connection).use { fs ->
            fs.write(folder, "año prueba.bin", data.inputStream(), data.size.toLong())
            val entry = fs.list(folder).single()
            assertEquals("año prueba.bin", entry.name)
            assertEquals(data.size.toLong(), entry.size)
            assertArrayEquals(data, fs.read(entry.path).use { it.readBytes() })
            val tail = fs.readFrom(entry.path, 1_234_567)
            assertNotNull("El servidor debe permitir reanudar por bytes", tail)
            assertArrayEquals(data.copyOfRange(1_234_567, data.size), tail!!.use { it.readBytes() })
            fs.rename(entry, "renombrado.bin")
            assertEquals(listOf("renombrado.bin"), fs.list(folder).map { it.name })
            fs.delete(fs.list(folder).single())
            assertTrue(fs.list(folder).isEmpty())
        }
    }

    @Test
    fun interruptedUploadAndDownloadResume() = runBlocking {
        val source = File(temp.newFolder(), "Proyecto").apply { mkdir() }
        File(source, "a.bin").writeBytes(payload(2))
        File(source, "sub").mkdir()
        File(source, "sub/b.bin").writeBytes(payload(3))
        val journals = temp.newFolder()
        val connector = { _: String -> RemoteFiles.connect(connection) }
        interrupt(DurableRemote.createUpload(journals, connection, listOf(source), folder, false, connector))
        DurableRemote.pending(journals, connector).single().run {}
        val offsets = ArrayList<Long>()
        val recording = { _: String -> Recording(RemoteFiles.connect(connection), offsets) }
        val dest = temp.newFolder()
        RemoteFiles.connect(connection).use { fs ->
            val uploaded = fs.list(folder).single()
            assertEquals("Proyecto", uploaded.name)
            // No hidden temporary files may remain after the resumed upload.
            assertEquals(setOf("a.bin", "sub"), fs.list(uploaded.path).map { it.name }.toSet())
            interrupt(
                DurableRemote.createDownload(
                    journals, fs, connection, listOf(uploaded), folder, dest, false, recording))
        }
        DurableRemote.pending(journals, recording).single().run {}
        assertTrue("offsets $offsets", offsets.isNotEmpty() && offsets.all { it > 0 })
        assertArrayEquals(payload(2), File(dest, "Proyecto/a.bin").readBytes())
        assertArrayEquals(payload(3), File(dest, "Proyecto/sub/b.bin").readBytes())
    }
}
