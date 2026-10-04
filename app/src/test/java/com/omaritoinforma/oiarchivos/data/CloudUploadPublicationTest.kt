package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudUploadPublicationTest {
    private fun account(protocol: Protocol) = Connection(
        label = "Test", protocol = protocol, host = "https://s3.example.invalid",
        port = 443, user = "test-user", secret = "test-secret")

    @Test fun drivePublishesItsStableIdAndNeverWritesAnotherFilesContent() {
        var patches = 0
        val fs = CloudFs(account(Protocol.DRIVE)) { url, method, body ->
            if (method == "GET") JSONObject().put("files", JSONArray())
            else {
                assertEquals("PATCH", method)
                assertTrue(url.contains("/files/staged-id?fields=id"))
                assertEquals("finished.bin", body!!.getString("name"))
                assertEquals(1, body.length())
                patches++
                JSONObject().put("id", "staged-id")
            }
        }
        assertTrue(fs.supportsDurableUploads)
        assertEquals("staged-id", fs.publishUpload(RemoteEntry("staged-id", ".part", false, 1), "parent-id", "finished.bin"))
        assertEquals(1, patches)
    }

    @Test fun dropboxPublishesByMoveWithAutorenameDisabledAndReturnsTheFinalPath() {
        var moves = 0
        val fs = CloudFs(account(Protocol.DROPBOX)) { url, method, body ->
            assertEquals("POST", method)
            if (url.endsWith("files/list_folder"))
                JSONObject().put("entries", JSONArray()).put("has_more", false)
            else {
                assertTrue(url.endsWith("files/move_v2"))
                assertEquals("/parent/.part", body!!.getString("from_path"))
                assertEquals("/parent/finished.bin", body.getString("to_path"))
                assertFalse(body.getBoolean("autorename"))
                moves++
                JSONObject().put("metadata", JSONObject().put("path_display", "/parent/finished.bin"))
            }
        }
        assertTrue(fs.supportsDurableUploads)
        assertEquals("/parent/finished.bin", fs.publishUpload(RemoteEntry("/parent/.part", ".part", false, 1), "/parent", "finished.bin"))
        assertEquals(1, moves)
    }

    @Test fun oneDrivePublishesTheSameIdWithExplicitConflictFailure() {
        var patches = 0
        val fs = CloudFs(account(Protocol.ONEDRIVE)) { url, method, body ->
            if (method == "GET") JSONObject().put("value", JSONArray())
            else {
                assertEquals("PATCH", method)
                assertTrue(url.endsWith("/items/staged-id?@microsoft.graph.conflictBehavior=fail"))
                assertEquals("finished.bin", body!!.getString("name"))
                assertEquals("fail", body.getString("@microsoft.graph.conflictBehavior"))
                patches++
                JSONObject().put("id", "staged-id")
            }
        }
        assertTrue(fs.supportsDurableUploads)
        assertEquals("staged-id", fs.publishUpload(RemoteEntry("staged-id", ".part", false, 1), "parent-id", "finished.bin"))
        assertEquals(1, patches)
    }

    @Test fun existingDriveFileBlocksPublicationWithoutChangingEitherId() {
        var requests = 0
        val fs = CloudFs(account(Protocol.DRIVE)) { _, method, _ ->
            requests++
            assertEquals("Only a complete conflict check is allowed", "GET", method)
            JSONObject().put("files", JSONArray().put(JSONObject().put("id", "existing-id")
                .put("name", "finished.bin").put("mimeType", "application/octet-stream")))
        }
        try {
            fs.publishUpload(RemoteEntry("staged-id", ".part", false, 1), "parent-id", "finished.bin")
            fail("Existing destination must be preserved")
        } catch (e: IOException) {
            assertEquals("El destino ya existe", e.message)
        }
        assertEquals(1, requests)
    }

    @Test fun boxRenamesTheStagedFileIdWithoutRequestingOverwrite() {
        var requests = 0
        val fs = BoxFs(account(Protocol.BOX)) { url, method, body ->
            requests++
            assertEquals("https://api.box.com/2.0/files/staged-id", url)
            assertEquals("PUT", method)
            assertEquals("finished.bin", body!!.getString("name"))
            assertEquals(1, body.length())
            JSONObject().put("id", "staged-id")
        }
        assertTrue(fs.supportsDurableUploads)
        assertEquals("file:staged-id", fs.publishUpload(RemoteEntry("file:staged-id", ".part", false, 1), "folder:parent-id", "finished.bin"))
        assertEquals(1, requests)
    }

    @Test fun providersWithoutVerifiedPublicationGuaranteesDoNotClaimDurableUploadSupport() {
        val providers = listOf(
            YandexFs(account(Protocol.YANDEX)), S3Fs(account(Protocol.S3)),
            BaiduFs(account(Protocol.BAIDU)), SugarSyncFs(account(Protocol.SUGARSYNC)))
        providers.forEach { assertFalse(it.supportsDurableUploads) }
    }

    @Test fun everyEnabledCloudProviderCanPublishAStagedDirectoryBeforeUploadingChildren() {
        for (protocol in listOf(Protocol.DRIVE, Protocol.DROPBOX, Protocol.ONEDRIVE, Protocol.BOX)) {
            var publications = 0
            val json: CloudJsonRequest = { url, method, body ->
                when {
                    url.endsWith("files/list_folder") ->
                        JSONObject().put("entries", JSONArray()).put("has_more", false)
                    method == "GET" -> JSONObject().put(
                        if (protocol == Protocol.DRIVE) "files" else "value", JSONArray())
                    protocol == Protocol.DROPBOX -> {
                        assertTrue(url.endsWith("files/move_v2"))
                        assertFalse(body!!.getBoolean("autorename"))
                        publications++
                        JSONObject().put("metadata", JSONObject().put("path_display", "/parent/finished"))
                    }
                    protocol == Protocol.BOX -> {
                        assertEquals("https://api.box.com/2.0/folders/staged-id", url)
                        assertEquals("PUT", method)
                        publications++
                        JSONObject().put("id", "staged-id")
                    }
                    else -> {
                        assertEquals("PATCH", method)
                        assertEquals("finished", body!!.getString("name"))
                        publications++
                        JSONObject().put("id", "staged-id")
                    }
                }
            }
            val fs: RemoteFs = if (protocol == Protocol.BOX) BoxFs(account(protocol), json)
                else CloudFs(account(protocol), json)
            val stage = when (protocol) {
                Protocol.DROPBOX -> "/parent/.part"
                Protocol.BOX -> "folder:staged-id"
                else -> "staged-id"
            }
            val parent = if (protocol == Protocol.DROPBOX) "/parent" else "parent-id"
            val published = fs.publishUpload(RemoteEntry(stage, ".part", true, 0), parent, "finished")
            assertEquals("$protocol", if (protocol == Protocol.DROPBOX) "/parent/finished" else stage, published)
            assertEquals("$protocol", 1, publications)
        }
    }
}
