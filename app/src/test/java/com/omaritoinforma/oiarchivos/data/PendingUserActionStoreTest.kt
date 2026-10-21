package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingUserActionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root() = File(temp.root, "queue")
    private fun source(text: String = "snapshot") = temp.newFile().apply { writeText(text) }

    @Test fun installKeepsSnapshotWhenSharedSourceChanges() {
        val input = source(); val store = PendingUserActionStore(root())
        val request = store.stageInstall(input, "com.example.app")
        input.writeText("different shared APK")
        val restored = store.load(request.id)
        assertEquals("snapshot", restored.apk!!.readText())
        assertEquals("different shared APK", input.readText())
        assertEquals(MessageDigest.getInstance("SHA-256").digest("snapshot".toByteArray())
            .joinToString("") { "%02x".format(it) }, restored.sha256)
    }
    @Test fun metadataSurvivesNewStoreInstance() {
        val request = PendingUserActionStore(root()).stageUninstall("com.example.app", remote = false)
        val restored = PendingUserActionStore(root()).load(request.id)
        assertEquals("uninstall", restored.action); assertEquals("com.example.app", restored.packageName)
        assertFalse(restored.remote); assertNull(restored.apk)
    }
    @Test fun rejectsTooLargeInputAndLeavesOriginal() {
        val input = source("123456")
        assertThrows(IOException::class.java) { PendingUserActionStore(root(), maxBytes = 5).stageInstall(input) }
        assertEquals("123456", input.readText()); assertTrue(root().listFiles().orEmpty().isEmpty())
    }
    @Test fun capsQueueWithoutEvictingEarlierRequests() {
        val store = PendingUserActionStore(root(), maxPending = 1)
        val first = store.stageUninstall("com.example.app")
        assertThrows(IOException::class.java) { store.stageUninstall("com.example.other") }
        assertEquals(listOf(first.id), store.pendingIds())
    }
    @Test fun expiryRemovesMetadataAndSnapshot() {
        var time = 100L; val store = PendingUserActionStore(root(), now = { time })
        val pending = store.stageInstall(source())
        time += PendingUserActionStore.TTL_MS
        assertTrue(store.pendingIds().isEmpty()); assertFalse(pending.apk!!.exists())
        assertThrows(IOException::class.java) { store.load(pending.id) }
    }
    @Test fun rejectsPathTraversalAndUnknownRequestIds() {
        val store = PendingUserActionStore(root())
        assertThrows(IOException::class.java) { store.load("../anything") }
        assertThrows(IOException::class.java) { store.discard("../anything") }
        assertThrows(IOException::class.java) { store.load("a".repeat(32)) }
    }
    @Test fun rejectsInvalidPackageBeforeWritingAnything() {
        val store = PendingUserActionStore(root())
        assertThrows(IOException::class.java) { store.stageInstall(source(), "com.example.app;reboot") }
        assertThrows(IOException::class.java) { store.stageUninstall("com.example.app/../other") }
        assertFalse(root().exists())
    }
    @Test fun modifiedPrivateSnapshotCannotBeReused() {
        val store = PendingUserActionStore(root()); val pending = store.stageInstall(source())
        pending.apk!!.setWritable(true); pending.apk.writeText("tampered")
        assertThrows(IOException::class.java) { store.load(pending.id) }
    }
    @Test fun discardRemovesOnlyTheSelectedRequest() {
        val store = PendingUserActionStore(root())
        val first = store.stageInstall(source()); val second = store.stageUninstall("com.example.app")
        store.discard(first.id)
        assertFalse(first.apk!!.exists()); assertEquals(listOf(second.id), store.pendingIds())
    }
    @Test fun directoryCannotBeStagedAsApk() {
        val store = PendingUserActionStore(root())
        assertThrows(IOException::class.java) { store.stageInstall(temp.newFolder()) }
    }
    @Test fun interruptedStagingIsCleanedAfterExpiry() {
        val dir = root().apply { mkdirs() }
        val orphan = File(dir, "${"a".repeat(32)}.part").apply { writeText("interrupted copy") }
        val clock = orphan.lastModified() + PendingUserActionStore.TTL_MS
        assertTrue(PendingUserActionStore(dir, now = { clock }).pendingIds().isEmpty())
        assertFalse(orphan.exists())
    }
}
