package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class NativeArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private var previous: File? = null

    @Before
    fun setup() {
        val exe = System.getenv("OI_ARCHIVE_TEST_EXECUTABLE")?.let(::File)
        assertTrue(
            "Build host 7-Zip with scripts/build_archives.py --host", exe?.canExecute() == true)
        previous = NativeArchives.executable
        NativeArchives.executable = exe
    }

    @After
    fun restore() {
        NativeArchives.executable = previous
    }

    private fun fixture(name: String): File =
        temp.newFile("$name.rar").apply {
            writeBytes(
                requireNotNull(
                        NativeArchiveTest::class.java.getResourceAsStream("/archives/$name.rar")) {
                            "Missing RAR test fixture: $name.rar"
                        }
                    .use { it.readBytes() })
        }

    @Test
    fun encryptedRar5HeadersAndContentsAreRead() = runBlocking {
        val rar = fixture("test_read_format_rar5_encrypted_filenames")
        assertEquals(
            listOf("a.txt", "b.txt", "c.txt", "d.txt"),
            NativeArchives.list(rar, "password").map { it.name })
        val destination = File(temp.root, "extracted")
        assertEquals(4, ArchiveTools.extract(rar, destination, "password") {})
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt")) assertEquals(
            "This is from $name", File(destination, name).readText().trim())
    }

    @Test
    fun wrongRarPasswordRemovesOnlyIncompleteNewFolder() = runBlocking {
        val rar = fixture("test_read_format_rar5_encrypted_filenames")
        val destination = File(temp.root, "wrong")
        try {
            ArchiveTools.extract(rar, destination, "wrong") {}
            fail("Accepted wrong password")
        } catch (_: IOException) {}
        assertFalse(destination.exists())
        assertTrue(rar.isFile)
    }

    @Test
    fun encryptedClassicRarEntryIsRead() = runBlocking {
        val rar = fixture("test_read_format_rar4_encrypted")
        assertTrue(NativeArchives.list(rar, "password").any { it.name == "b.txt" })
        val body =
            NativeArchives.withEntry(rar, "password", "b.txt") {
                it.readBytes().toString(Charsets.UTF_8)
            }
        assertEquals("This is from b.txt", body.trim())
    }

    @Test
    fun encryptedSevenZRoundTripAndHeaderPrivacy() = runBlocking {
        val input = temp.newFolder("álbum")
        File(input, "secreto.txt").writeText("contenido privado")
        val archive = File(temp.root, "protected.7z")
        ArchiveTools.compress(listOf(input), archive, "contraseña") {}
        try {
            NativeArchives.list(archive, "incorrecta")
            fail("Read encrypted headers")
        } catch (_: IOException) {}
        val destination = File(temp.root, "out")
        ArchiveTools.extract(archive, destination, "contraseña") {}
        assertEquals("contenido privado", File(destination, "álbum/secreto.txt").readText())
        assertTrue(File(input, "secreto.txt").exists())
    }
}
