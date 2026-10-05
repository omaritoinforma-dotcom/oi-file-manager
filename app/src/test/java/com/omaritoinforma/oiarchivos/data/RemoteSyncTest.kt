package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Servidor en memoria: una sola carpeta, con fallos que se pueden provocar. */
private class FakeFs(val files: MutableMap<String, ByteArray> = mutableMapOf()) : RemoteFs {
    var failWrite = false
    var failRename = false
    val log = mutableListOf<String>()

    override fun list(path: String) = files.map { (n, b) -> RemoteEntry("$path/$n", n, false, b.size.toLong()) }

    override fun read(path: String): InputStream = files.getValue(path.substringAfterLast('/')).inputStream()

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        log += "write $name"
        if (failWrite) throw IOException("sin conexión")
        files[name] = input.readBytes()
        return "$parent/$name"
    }

    override fun mkdir(parent: String, name: String) = "$parent/$name"

    override fun rename(entry: RemoteEntry, name: String) {
        log += "rename ${entry.name} -> $name"
        if (failRename) throw IOException("no permitido")
        files[name] = files.remove(entry.name)!!
    }

    override fun delete(entry: RemoteEntry) {
        log += "delete ${entry.name}"
        files.remove(entry.name)
    }
}

class RemoteSyncTest {
    @get:Rule val temp = TemporaryFolder()

    private fun edit(content: String, remoteSize: Long, name: String = "nota.txt"): RemoteSync.Edit {
        val local = File(temp.root, "copia-$name").apply { writeText(content) }
        return RemoteSync.Edit("c1", "/dir", name, local.path, remoteSize, 0, 0)
    }

    private fun text(fs: FakeFs, name: String) = fs.files[name]?.decodeToString()

    @Test
    fun replacesTheRemoteFileWhenItDidNotChange() {
        val fs = FakeFs(mutableMapOf("nota.txt" to "viejo".toByteArray()))
        val outcome = RemoteSync.upload(fs, edit("nuevo contenido", remoteSize = 5)) as RemoteSync.Outcome.Updated
        assertEquals("nuevo contenido", text(fs, "nota.txt"))
        assertEquals(listOf("nota.txt"), fs.files.keys.toList())
        // Primero se sube aparte, luego se borra el viejo y se renombra: nunca se pierde el original.
        assertEquals(listOf("write nota (subiendo).txt", "delete nota.txt", "rename nota (subiendo).txt -> nota.txt"), fs.log)
        assertEquals("nuevo contenido".length.toLong(), outcome.edit.remoteSize)
    }

    @Test
    fun aChangeOnTheServerIsAConflictAndNothingIsTouched() {
        val fs = FakeFs(mutableMapOf("nota.txt" to "alguien lo cambió".toByteArray()))
        assertEquals(RemoteSync.Outcome.Conflict, RemoteSync.upload(fs, edit("mi versión", remoteSize = 5)))
        assertEquals("alguien lo cambió", text(fs, "nota.txt"))
        assertTrue(fs.log.isEmpty())
    }

    @Test
    fun overwriteAndCopyResolveAConflict() {
        val fs = FakeFs(mutableMapOf("nota.txt" to "alguien lo cambió".toByteArray()))
        val mine = edit("mi versión", remoteSize = 5)
        val copy = RemoteSync.upload(fs, mine, RemoteSync.Mode.COPY) as RemoteSync.Outcome.Updated
        assertEquals("nota (editado).txt", copy.edit.name)
        assertEquals("alguien lo cambió", text(fs, "nota.txt"))
        assertEquals("mi versión", text(fs, "nota (editado).txt"))
        RemoteSync.upload(fs, mine, RemoteSync.Mode.COPY)
        assertEquals("mi versión", text(fs, "nota (editado 2).txt"))
        RemoteSync.upload(fs, mine, RemoteSync.Mode.OVERWRITE)
        assertEquals("mi versión", text(fs, "nota.txt"))
    }

    @Test
    fun aFailedUploadLeavesTheOldFileIntact() {
        val fs = FakeFs(mutableMapOf("nota.txt" to "viejo".toByteArray())).apply { failWrite = true }
        assertThrows(IOException::class.java) { RemoteSync.upload(fs, edit("nuevo", remoteSize = 5)) }
        assertEquals("viejo", text(fs, "nota.txt"))
    }

    @Test
    fun aFailedRenameTellsWhereTheNewVersionIs() {
        val fs = FakeFs(mutableMapOf("nota.txt" to "viejo".toByteArray())).apply { failRename = true }
        val e = assertThrows(IOException::class.java) { RemoteSync.upload(fs, edit("nuevo", remoteSize = 5)) }
        assertTrue(e.message, "nota (subiendo).txt" in e.message.orEmpty())
        assertEquals("nuevo", text(fs, "nota (subiendo).txt"))
    }

    @Test
    fun aRemoteFileDeletedMeanwhileIsUploadedAgainAndAMissingLocalCopyIsGone() {
        val fs = FakeFs()
        RemoteSync.upload(fs, edit("contenido", remoteSize = 5))
        assertEquals("contenido", text(fs, "nota.txt"))
        val gone = RemoteSync.Edit("c1", "/dir", "x.txt", File(temp.root, "no-existe").path, 1, 1, 1)
        assertEquals(RemoteSync.Outcome.Gone, RemoteSync.upload(fs, gone))
    }

    @Test
    fun changedNoticesTheEditAndStoreKeepsThePendingList() {
        val e = edit("a", remoteSize = 1)
        val synced = e.copy(syncedSize = File(e.local).length(), syncedModified = File(e.local).lastModified())
        assertFalse(RemoteSync.changed(synced))
        File(e.local).writeText("a más largo")
        assertTrue(RemoteSync.changed(synced))

        val store = RemoteSync.Store(File(temp.root, "lista.json"))
        assertTrue(store.all().isEmpty())
        store.put(synced)
        store.put(synced.copy(remoteSize = 99))
        assertEquals(listOf(99L), RemoteSync.Store(File(temp.root, "lista.json")).all().map { it.remoteSize })
        store.remove(synced.local)
        assertTrue(store.all().isEmpty())
        repeat(RemoteSync.MAX_EDITS + 5) { store.put(synced.copy(local = File(temp.root, "c$it").also { f -> f.writeText("x") }.path)) }
        assertEquals(RemoteSync.MAX_EDITS, store.all().size)
        File(store.all().first().local).delete()
        store.prune()
        assertEquals(RemoteSync.MAX_EDITS - 1, store.all().size)
    }
}
