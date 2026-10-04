package com.omaritoinforma.oiarchivos.data

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 15 integration against the platform ExternalStorageProvider and real URI grants.
 * The installed target app needs MANAGE_EXTERNAL_STORAGE app-op for UUID-owned fixtures only.
 * DocumentsUI must be available and the device unlocked; no fake Context or permission bypass
 * is used. If a tree grant is missing, the public system picker grants it to MainActivity.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class AndroidDocumentIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 120000)
    fun realExternalStorageCopiesAndSafeSavePreserveOriginals() = runBlocking {
        assertTrue("Esta integración requiere Android 15 o posterior", Build.VERSION.SDK_INT >= 35)
        assertTrue(
            "Autoriza fixtures con: adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
            Environment.isExternalStorageManager())

        val name = "OI-SAF-test-${UUID.randomUUID()}"
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val fixture = File(downloads, name)
        val localRoot = File(context.cacheDir, "$name-local").canonicalFile
        val documentId = "primary:Download/$name"
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId)
        val rootId = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
        var persistedHere = false
        try {
            assertTrue("No se pudo crear la carpeta UUID en Download", fixture.mkdir())
            assertTrue("No se pudo crear la carpeta local de pruebas", localRoot.mkdir())
            val sourceBytes = ByteArray(390123) { (it % 251).toByte() }
            File(fixture, "saf-source.bin").writeBytes(sourceBytes)
            val originalBytes = "Original caf\u00e9 \u03a9\nsegunda l\u00ednea\n".toByteArray(Charsets.UTF_8)
            File(fixture, "original.txt").writeBytes(originalBytes)

            if (!hasTreeGrant(Uri.parse(rootId))) {
                selectOwnedTree(tree, documentId, name)
                context.contentResolver.takePersistableUriPermission(tree, TREE_FLAGS)
                persistedHere = true
            }
            assertTrue("DocumentsUI no concedió lectura y escritura al UID de la app", hasTreeGrant(Uri.parse(rootId)))
            val saf = AndroidDocumentStore(context)
            val local = LocalDocumentStore()
            val root = saf.stat(rootId)
            assertTrue("El árbol real debe permitir lectura, escritura y creación",
                root.directory && root.readable && root.writable && root.canCreate)
            val sourceId = saf.children(rootId).single { it.name == "saf-source.bin" }.id
            val received = File(localRoot, "recibidos").apply { assertTrue(mkdir()) }.canonicalFile

            // SAF -> phone: use both production adapters and verify the provider source remains.
            val toLocal = DocumentTransfers.transfer(saf, listOf(sourceId), local,
                received.path, move = false,
                sameDocument = { first, second -> AndroidDocumentStore.sameLocation(first, second) })
            assertFalse(toLocal.moved)
            assertDocumentBytes(local, toLocal.destinationIds.single(), sourceBytes)
            local.requireDurable(toLocal.destinationIds.single())
            assertDocumentBytes(saf, sourceId, sourceBytes)

            // Phone -> SAF: new documents receive a normal tree grant and provider flags.
            val localBytes = ByteArray(270111) { ((it * 7) % 251).toByte() }
            val localSource = File(localRoot, "local-source.bin").apply { writeBytes(localBytes) }
            val targetDirectory = saf.createDirectory(rootId, "destino")
            val toSaf = DocumentTransfers.transfer(local, listOf(localSource.path), saf,
                targetDirectory, move = false,
                sameDocument = { first, second -> AndroidDocumentStore.sameLocation(first, second) })
            assertDocumentBytes(saf, toSaf.destinationIds.single(), localBytes)
            saf.requireDurable(toSaf.destinationIds.single())
            assertArrayEquals(localBytes, localSource.readBytes())

            // SAF -> SAF: read-back uses the real ContentResolver, not a provider substitute.
            val withinSaf = DocumentTransfers.transfer(saf, listOf(sourceId), saf,
                targetDirectory, move = false,
                sameDocument = { first, second -> AndroidDocumentStore.sameLocation(first, second) })
            assertDocumentBytes(saf, withinSaf.destinationIds.single(), sourceBytes)
            assertDocumentBytes(saf, sourceId, sourceBytes)

            // A path-based provider can return the original URI after publishing the staging
            // file. Verify returned IDs and the retained backup rather than assuming ID changes.
            val editableId = saf.children(rootId).single { it.name == "original.txt" }.id
            val canonicalId = saf.requireSafeReplacement(rootId, editableId)
            val expected = DocumentTransactions.fingerprint(saf, canonicalId)
            val editedBytes = "Guardado verificado caf\u00e9 \u03a9\n".toByteArray(Charsets.UTF_8)
            val saved = DocumentTransactions.save(saf, rootId, canonicalId, expected, editedBytes)
            assertEquals("original.txt", saf.stat(saved.id).name)
            assertNotEquals(DocumentsContract.getDocumentId(Uri.parse(saved.id)),
                DocumentsContract.getDocumentId(Uri.parse(saved.backupId)))
            assertDocumentBytes(saf, saved.id, editedBytes)
            assertDocumentBytes(saf, saved.backupId, originalBytes)
            assertEquals(saved.fingerprint, DocumentTransactions.fingerprint(saf, saved.id))
            assertEquals(expected.sha256, DocumentTransactions.fingerprint(saf, saved.backupId).sha256)

            // Change bytes outside SAF while retaining both size and timestamp. Full SHA-256
            // must reject the stale editor snapshot before any new staging document is created.
            val changed = editedBytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val publishedFile = File(fixture, "original.txt")
            publishedFile.writeBytes(changed)
            assertTrue("No se pudo conservar el timestamp para la prueba de conflicto",
                publishedFile.setLastModified(saved.fingerprint.modified))
            assertEquals(saved.fingerprint.size, saf.stat(saved.id).size)
            assertEquals(saved.fingerprint.modified, saf.stat(saved.id).modified)
            val beforeConflict = saf.children(rootId).map { it.id }.toSet()
            try {
                DocumentTransactions.save(saf, rootId, saved.id, saved.fingerprint,
                    "No debe publicarse".toByteArray())
                fail("Se aceptó una huella de origen obsoleta")
            } catch (_: DocumentConflictException) {}
            assertDocumentBytes(saf, saved.id, changed)
            assertDocumentBytes(saf, saved.backupId, originalBytes)
            assertEquals(beforeConflict, saf.children(rootId).map { it.id }.toSet())
        } finally {
            try {
                if (persistedHere)
                    context.contentResolver.releasePersistableUriPermission(tree, TREE_FLAGS)
            } finally {
                try { deleteOwned(fixture, downloads, name) }
                finally { deleteOwned(localRoot, context.cacheDir.canonicalFile, "$name-local") }
            }
        }
    }

    private suspend fun assertDocumentBytes(store: DocumentStore, id: String, expected: ByteArray) {
        assertArrayEquals(expected, store.openRead(id).use { it.readBytes() })
        val fingerprint = DocumentTransactions.fingerprint(store, id)
        assertEquals(expected.size.toLong(), fingerprint.size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(expected)
            .joinToString("") { "%02x".format(it) }, fingerprint.sha256)
    }

    private fun hasTreeGrant(uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), TREE_FLAGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Public picker launched by the target Activity: the resulting URI grant belongs to its UID. */
    private fun selectOwnedTree(tree: Uri, documentId: String, name: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw AssertionError("No se encontró MainActivity para recibir el grant del selector")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: Activity? = null
        await("MainActivity no alcanzó RESUMED") {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it.packageName == context.packageName }
            }
            activity != null
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(TREE_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(AUTHORITY, documentId))
        instrumentation.runOnMainSync { activity!!.startActivityForResult(intent, PICKER_REQUEST) }
        await("DocumentsUI no permitió seleccionar la carpeta UUID $name") {
            if (clickNode { node ->
                    val text = node.text?.toString()?.lowercase(Locale.ROOT)
                    node.viewIdResourceName?.endsWith("/action_menu_select") == true ||
                        text in setOf("use this folder", "usar esta carpeta", "seleccionar carpeta")
                }) true
            else {
                // Handle a firmware that opens Download instead of respecting EXTRA_INITIAL_URI.
                clickNode { it.text?.toString() == name }
                false
            }
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        await("DocumentsUI no concedió el árbol UUID a la app tras confirmar Allow") {
            if (hasTreeGrant(root)) true
            else {
                clickNode {
                    val text = it.text?.toString()?.lowercase(Locale.ROOT)
                    text in setOf("allow", "permitir")
                }
                false
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun clickNode(matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isEnabled && matches(node)) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                var parent = node.parent
                while (parent != null) {
                    val next = parent.parent
                    val clicked = parent.isEnabled && parent.isClickable &&
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    if (clicked) { next?.recycle(); return true }
                    parent = next
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { visit(root) } finally { root.recycle() }
    }

    /** No FOLLOW_LINKS: cleanup cannot traverse outside either UUID-owned fixture directory. */
    private fun deleteOwned(directory: File, parent: File, expectedName: String) {
        check(directory.name == expectedName && directory.parentFile.canonicalFile == parent.canonicalFile)
        if (!directory.exists()) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val PICKER_REQUEST = 61031
        const val TREE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
