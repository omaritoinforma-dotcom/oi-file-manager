package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudConditionalDeleteTest {
    private fun account(protocol: Protocol) = Connection(
        label = "Test", protocol = protocol, host = "https://s3.example.invalid",
        port = 443, user = "test-user", secret = "test-secret")

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Changed sources must be preserved") } catch (_: IOException) {}
    }

    @Test fun dropboxRevisionMismatchPreservesTheNewFileWithoutAnUnconditionalRetry() {
        var serverRevision = "v2"
        var deleted = false
        var requests = 0
        val fs = CloudFs(account(Protocol.DROPBOX)) { url, method, body ->
            requests++
            assertEquals("https://api.dropboxapi.com/2/files/delete_v2", url)
            assertEquals("POST", method)
            assertEquals("/file.bin", body!!.getString("path"))
            assertTrue("An unconditional delete would destroy the new revision", body.has("parent_rev"))
            if (body.getString("parent_rev") != serverRevision) throw IOException("revision_mismatch")
            deleted = true
            JSONObject().put("metadata", JSONObject().put("rev", serverRevision))
        }
        val copied = RemoteEntry("/file.bin", "file.bin", false, 7, "v1")
        rejected { fs.deleteIfUnchanged(copied) }
        assertFalse(deleted)
        assertEquals(1, requests)
        serverRevision = "v1"
        assertTrue(fs.deleteIfUnchanged(copied))
        assertTrue(deleted)
        assertEquals(2, requests)
    }

    @Test fun oneDriveAndBoxUseTheListedEntityTagAsAServerSidePrecondition() {
        for (protocol in listOf(Protocol.ONEDRIVE, Protocol.BOX)) {
            val json: CloudJsonRequest = { url, method, _ ->
                assertEquals("GET", method)
                val file = JSONObject().put("id", "source-id").put("name", "file.bin").put("size", 7)
                if (protocol == Protocol.BOX) {
                    assertTrue("Box must request the tag used for deletion", url.contains("size,etag"))
                    file.put("type", "file").put("etag", "entity-v1")
                    JSONObject().put("entries", JSONArray().put(file)).put("next_marker", JSONObject.NULL)
                } else {
                    file.put("eTag", "entity-v1").put("cTag", "content-v1")
                    JSONObject().put("value", JSONArray().put(file))
                }
            }
            val fs: RemoteFs = if (protocol == Protocol.BOX) BoxFs(account(protocol), json)
                else CloudFs(account(protocol), json)
            val copied = fs.list("parent-id").single()
            assertEquals("$protocol", "entity-v1", copied.revision)
            var serverRevision = "entity-v2"
            var deleted = false
            var requests = 0
            val delete: CloudDeleteRequest = { url, headers ->
                requests++
                val expected = if (protocol == Protocol.BOX) "https://api.box.com/2.0/files/source-id"
                    else "https://graph.microsoft.com/v1.0/me/drive/items/source-id"
                assertEquals(expected, url)
                assertEquals("$protocol", "entity-v1", headers["If-Match"])
                if (headers["If-Match"] != serverRevision) throw IOException("412 Precondition Failed")
                deleted = true
            }
            if (fs is BoxFs) fs.deleteRequest = delete else (fs as CloudFs).deleteRequest = delete
            rejected { fs.deleteIfUnchanged(copied) }
            assertFalse("$protocol new revision must survive", deleted)
            assertEquals("$protocol must not retry without If-Match", 1, requests)
            serverRevision = "entity-v1"
            assertTrue("$protocol", fs.deleteIfUnchanged(copied))
            assertTrue("$protocol", deleted)
            assertEquals(2, requests)
        }
    }

    @Test fun boxEmptyDirectoryDeletePreservesAChildArrivingAfterTheClientsEmptyCheck() {
        val fs = BoxFs(account(Protocol.BOX))
        val children = mutableListOf("arrived-after-scan.bin")
        var folderDeleted = false
        var requests = 0
        fs.deleteRequest = { url, headers ->
            requests++
            assertEquals("https://api.box.com/2.0/folders/source-id?recursive=false", url)
            assertEquals("folder-v1", headers["If-Match"])
            if (children.isNotEmpty()) throw IOException("409 folder_not_empty")
            folderDeleted = true
        }
        val copied = RemoteEntry("folder:source-id", "folder", true, 0, "folder-v1")
        rejected { fs.deleteEmptyDirectory(copied) }
        assertEquals(listOf("arrived-after-scan.bin"), children)
        assertFalse(folderDeleted)
        assertEquals(1, requests)
        children.clear()
        assertTrue(fs.deleteEmptyDirectory(copied))
        assertTrue(folderDeleted)
        assertEquals(2, requests)
    }

    @Test fun unsupportedRevisionsAndFolderDeletesReturnFalseWithoutAnyMutation() {
        for (protocol in listOf(Protocol.DRIVE, Protocol.DROPBOX, Protocol.ONEDRIVE)) {
            val fs = CloudFs(account(protocol)) { _, _, _ -> error("No API mutation is safe") }
            fs.deleteRequest = { _, _ -> error("No HTTP mutation is safe") }
            assertFalse(fs.deleteIfUnchanged(RemoteEntry("id", "file.bin", false, 7)))
            assertFalse(fs.deleteIfUnchanged(RemoteEntry("id", "folder", true, 0, "revision")))
            assertFalse(fs.deleteEmptyDirectory(RemoteEntry("id", "folder", true, 0, "revision")))
            if (protocol == Protocol.DRIVE)
                assertFalse("Drive version is not an entity tag",
                    fs.deleteIfUnchanged(RemoteEntry("id", "file.bin", false, 7, "12345")))
        }
        val box = BoxFs(account(Protocol.BOX))
        box.deleteRequest = { _, _ -> error("No mutation is safe") }
        assertFalse(box.deleteIfUnchanged(RemoteEntry("file:id", "file.bin", false, 7)))
        assertFalse(box.deleteIfUnchanged(RemoteEntry("folder:id", "folder", true, 0, "etag")))
        assertFalse(box.deleteEmptyDirectory(RemoteEntry("file:id", "file.bin", false, 7, "etag")))
    }
}
