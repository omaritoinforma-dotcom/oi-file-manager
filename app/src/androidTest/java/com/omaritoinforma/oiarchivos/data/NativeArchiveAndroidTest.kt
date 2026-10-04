package com.omaritoinforma.oiarchivos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual bundled Android executable, rather than the host 7-Zip binary. */
@RunWith(AndroidJUnit4::class)
class NativeArchiveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var work: File

    @Before fun setup() {
        work = File(instrumentation.targetContext.cacheDir, "native-test-" + UUID.randomUUID())
        assertTrue(work.mkdir())
        assertTrue("Bundled Android archive engine must be executable", NativeArchives.available)
        assertEquals("lib7zz.so", NativeArchives.executable?.name)
    }

    @After fun cleanup() { work.deleteRecursively() }

    private fun fixture(name: String) = File(work, name + ".rar").apply {
        instrumentation.context.assets.open("archives/" + name + ".rar").use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }

    @Test(timeout = 90000) fun encryptedRar5ReadsHeadersAndExtractsOnAndroid() = runBlocking {
        val source = fixture("test_read_format_rar5_encrypted_filenames")
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"),
            NativeArchives.list(source, "password").map { it.name })
        val output = File(work, "rar5")
        assertEquals(4, ArchiveTools.extract(source, output, "password") {})
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt"))
            assertEquals("This is from " + name, File(output, name).readText().trim())
    }

    @Test(timeout = 90000) fun incorrectRarPasswordPreservesSourceOnAndroid() = runBlocking {
        val source = fixture("test_read_format_rar5_encrypted_filenames")
        val output = File(work, "wrong-password")
        try { ArchiveTools.extract(source, output, "wrong") {}; fail("Accepted wrong password") }
        catch (_: IOException) {}
        assertTrue(source.isFile)
        assertFalse(output.exists())
    }

    @Test(timeout = 90000) fun encryptedClassicRarReadsOnAndroid() = runBlocking {
        val source = fixture("test_read_format_rar4_encrypted")
        val body = NativeArchives.withEntry(source, "password", "b.txt") {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals("This is from b.txt", body.trim())
    }

    @Test(timeout = 90000) fun encryptedSevenZRoundTripOnAndroid() = runBlocking {
        val folder = File(work, "álbum").apply { assertTrue(mkdir()) }
        File(folder, "secreto.txt").writeText("contenido privado")
        val archive = File(work, "protected.7z")
        ArchiveTools.compress(listOf(folder), archive, "contraseña") {}
        try { NativeArchives.list(archive, "incorrecta"); fail("Read encrypted headers") }
        catch (_: IOException) {}
        val output = File(work, "seven-z")
        ArchiveTools.extract(archive, output, "contraseña") {}
        assertEquals("contenido privado", File(output, "álbum/secreto.txt").readText())
    }
}
