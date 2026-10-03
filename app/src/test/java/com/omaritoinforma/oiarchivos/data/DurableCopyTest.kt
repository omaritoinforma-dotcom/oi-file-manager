package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableCopyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun input(parent: File, name: String = "large.bin") =
        File(parent, name).apply { writeBytes(ByteArray(900000) { (it % 251).toByte() }) }

    private suspend fun interrupt(job: DurableCopy) {
        try {
            job.run { if (it.doneBytes > 0) throw CancellationException("process interrupted") }
            fail("Expected interruption")
        } catch (_: CancellationException) {}
    }

    @Test
    fun processRestartResumesPartialAndOnlyThenDeletesMovedOriginal() = runBlocking {
        val journals = temp.newFolder("jobs")
        val source = input(temp.newFolder("source"))
        val destination = temp.newFolder("dest")
        val expected = source.readBytes()
        interrupt(DurableCopy.create(journals, listOf(source), destination, true, Conflict.RENAME))
        val partial = destination.listFiles()!!.single()
        assertTrue(partial.length() in 1 until source.length())
        assertTrue(source.exists())
        assertFalse(File(destination, source.name).exists())
        val restored = DurableCopy.pending(journals).single()
        restored.run {}
        assertArrayEquals(expected, File(destination, source.name).readBytes())
        assertFalse(source.exists())
        assertFalse(partial.exists())
        assertTrue(DurableCopy.pending(journals).isEmpty())
    }

    @Test
    fun changedOriginalWithSameSizeAndTimeIsDetectedByPersistedPrefix() = runBlocking {
        val journals = temp.newFolder("jobs")
        val source = input(temp.newFolder("source"))
        val destination = temp.newFolder("dest")
        interrupt(DurableCopy.create(journals, listOf(source), destination, true, Conflict.RENAME))
        val modified = source.lastModified()
        source.writeBytes(source.readBytes().apply { this[0] = 99 })
        assertTrue(source.setLastModified(modified))
        try {
            DurableCopy.pending(journals).single().run {}
            fail("Changed original")
        } catch (_: IOException) {}
        assertTrue(source.exists())
        assertFalse(File(destination, source.name).exists())
    }

    @Test
    fun corruptedPartialDoesNotReplaceExistingDestination() = runBlocking {
        val journals = temp.newFolder("jobs")
        val source = input(temp.newFolder("source"))
        val destination = temp.newFolder("dest")
        val target = File(destination, source.name).apply { writeText("previous destination") }
        interrupt(
            DurableCopy.create(journals, listOf(source), destination, true, Conflict.OVERWRITE))
        val part = destination.listFiles()!!.single { it.name.endsWith(".part") }
        part.writeBytes(part.readBytes().apply { this[0] = 100 })
        try {
            DurableCopy.pending(journals).single().run {}
            fail("Corrupted partial")
        } catch (_: IOException) {}
        assertEquals("previous destination", target.readText())
        assertTrue(source.exists())
    }

    @Test
    fun destinationChangedWhileInterruptedIsPreserved() = runBlocking {
        val journals = temp.newFolder("jobs")
        val source = input(temp.newFolder("source"))
        val destination = temp.newFolder("dest")
        val target = File(destination, source.name).apply { writeText("before") }
        interrupt(
            DurableCopy.create(journals, listOf(source), destination, true, Conflict.OVERWRITE))
        target.writeText("user's newer file")
        try {
            DurableCopy.pending(journals).single().run {}
            fail("Changed destination")
        } catch (_: IOException) {}
        assertEquals("user's newer file", target.readText())
        assertTrue(source.exists())
    }

    @Test
    fun resumedMoveKeepsSkippedChildrenAndFinishesOtherChildren() = runBlocking {
        val journals = temp.newFolder("jobs")
        val album = File(temp.newFolder("source"), "album").apply { mkdir() }
        val newFile = input(album)
        File(album, "same.txt").writeText("original")
        val destination = temp.newFolder("dest")
        val target = File(destination, "album").apply { mkdir() }
        File(target, "same.txt").writeText("existing")
        val expected = newFile.readBytes()
        interrupt(DurableCopy.create(journals, listOf(album), destination, true, Conflict.SKIP))
        DurableCopy.pending(journals).single().run {}
        assertEquals("original", File(album, "same.txt").readText())
        assertEquals("existing", File(target, "same.txt").readText())
        assertFalse(newFile.exists())
        assertArrayEquals(expected, File(target, newFile.name).readBytes())
    }

    @Test
    fun discardRemovesOnlyPartialsAndPreservesBothUserFiles() = runBlocking {
        val journals = temp.newFolder("jobs")
        val source = input(temp.newFolder("source"))
        val destination = temp.newFolder("dest")
        val target = File(destination, source.name).apply { writeText("existing") }
        interrupt(
            DurableCopy.create(journals, listOf(source), destination, true, Conflict.OVERWRITE))
        DurableCopy.pending(journals).single().discard()
        assertTrue(source.exists())
        assertEquals("existing", target.readText())
        assertEquals(listOf(target), destination.listFiles()!!.toList())
        assertTrue(DurableCopy.pending(journals).isEmpty())
    }

    @Test
    fun cannotCreateDestinationInsideOriginalOrFollowDestinationLink() {
        val jobs = temp.newFolder("jobs")
        val source = temp.newFolder("source")
        val inner = File(source, "inner")
        try {
            DurableCopy.create(jobs, listOf(source), inner, false, Conflict.RENAME)
            fail()
        } catch (_: IOException) {}
        assertFalse(inner.exists())
        val file = input(source)
        val dest = temp.newFolder("dest")
        val outside = temp.newFile("outside")
        Files.createSymbolicLink(File(dest, file.name).toPath(), outside.toPath())
        try {
            DurableCopy.create(jobs, listOf(file), dest, false, Conflict.OVERWRITE)
            fail()
        } catch (_: IOException) {}
        assertEquals(0L, outside.length())
    }
}
