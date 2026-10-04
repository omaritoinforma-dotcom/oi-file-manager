package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the provider list methods and their real pagination/parsing, without cloud accounts. */
class CloudPaginationTest {
    @get:Rule val temp = TemporaryFolder()
    private val providers = listOf(
        Protocol.DRIVE, Protocol.DROPBOX, Protocol.ONEDRIVE, Protocol.BOX,
        Protocol.YANDEX, Protocol.BAIDU, Protocol.S3, Protocol.SUGARSYNC)

    private fun account(protocol: Protocol) = Connection(
        label = "Test", protocol = protocol, host = "https://s3.example.invalid",
        port = 443, user = "test-user", secret = "test-secret")

    private fun parameter(url: String, name: String): String =
        URL(url).query.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

    private fun rejected(block: () -> Any?): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("A partial listing must never be returned as a complete listing")
    }

    private data class Fixture(val fs: RemoteFs, val path: String, val requests: MutableList<String>)

    private fun fixture(protocol: Protocol, total: Int, pageSize: Int = 1000): Fixture {
        val requests = mutableListOf<String>()
        fun entries(offset: Int, max: Int): JSONArray = JSONArray().also { array ->
            for (index in offset until minOf(offset + minOf(pageSize, max), total)) {
                val name = "file-$index.bin"
                array.put(when (protocol) {
                    Protocol.DRIVE -> JSONObject().put("id", "id-$index").put("name", name)
                        .put("mimeType", "application/octet-stream").put("size", "0")
                    Protocol.DROPBOX -> JSONObject().put("path_display", "/folder/$name")
                        .put("name", name).put(".tag", "file").put("size", 0)
                    Protocol.ONEDRIVE -> JSONObject().put("id", "id-$index").put("name", name)
                        .put("size", 0)
                    Protocol.BOX -> JSONObject().put("id", "id-$index").put("name", name)
                        .put("type", "file").put("size", 0)
                    Protocol.YANDEX -> JSONObject().put("path", "/folder/$name").put("name", name)
                        .put("type", "file").put("size", 0)
                    Protocol.BAIDU -> JSONObject().put("path", "/folder/$name").put("fs_id", index)
                        .put("server_filename", name).put("isdir", 0).put("size", 0)
                    else -> error("XML provider")
                })
            }
        }
        val json: CloudJsonRequest = { url, _, body ->
            requests += url
            val offset = when (protocol) {
                Protocol.DRIVE -> parameter(url, "pageToken").ifBlank { "0" }.toInt()
                Protocol.DROPBOX -> body!!.optString("cursor", "0").toInt()
                Protocol.ONEDRIVE -> parameter(url, "offset").ifBlank { "0" }.toInt()
                Protocol.BOX -> {
                    assertEquals("true", parameter(url, "usemarker"))
                    assertFalse("Box offset pagination cannot list beyond 10,000", url.contains("offset="))
                    parameter(url, "marker").ifBlank { "0" }.toInt()
                }
                Protocol.YANDEX -> parameter(url, "offset").toInt()
                else -> error("Unexpected JSON provider")
            }
            val page = entries(offset, 1000)
            val next = offset + page.length()
            val more = next < total
            when (protocol) {
                Protocol.DRIVE -> JSONObject().put("files", page)
                    .put("nextPageToken", if (more) "$next" else JSONObject.NULL)
                Protocol.DROPBOX -> JSONObject().put("entries", page).put("has_more", more)
                    .put("cursor", "$next")
                Protocol.ONEDRIVE -> JSONObject().put("value", page).also {
                    if (more) it.put("@odata.nextLink",
                        "https://graph.microsoft.com/v1.0/me/drive/items/folder/children?offset=$next")
                }
                Protocol.BOX -> JSONObject().put("entries", page)
                    .put("next_marker", if (more) "$next" else JSONObject.NULL)
                Protocol.YANDEX -> JSONObject().put("_embedded",
                    JSONObject().put("items", page).put("total", total))
                else -> error("Unexpected JSON provider")
            }
        }
        val fs: RemoteFs = when (protocol) {
            Protocol.DRIVE, Protocol.DROPBOX, Protocol.ONEDRIVE -> CloudFs(account(protocol), json)
            Protocol.BOX -> BoxFs(account(protocol), json)
            Protocol.YANDEX -> YandexFs(account(protocol), json)
            Protocol.BAIDU -> BaiduFs(account(protocol)) { _, params, _ ->
                requests += params.getValue("start")
                JSONObject().put("errno", 0)
                    .put("list", entries(params.getValue("start").toInt(), 1000))
            }
            Protocol.S3 -> S3Fs(account(protocol)) { _, query ->
                val offset = query["continuation-token"]?.toInt() ?: 0
                requests += "$offset"
                val next = minOf(offset + pageSize, total)
                val more = next < total
                buildString {
                    append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                    append("<IsTruncated>$more</IsTruncated>")
                    for (index in offset until next)
                        append("<Contents><Key>folder/file-$index.bin</Key><Size>0</Size></Contents>")
                    if (more) append("<NextContinuationToken>$next</NextContinuationToken>")
                    append("</ListBucketResult>")
                }.byteInputStream()
            }
            Protocol.SUGARSYNC -> SugarSyncFs(account(protocol)) { url ->
                requests += url
                val offset = parameter(url, "start").toInt()
                val next = minOf(offset + minOf(pageSize, 500), total)
                CloudXml.parse(buildString {
                    append("<collectionContents hasMore=\"${next < total}\" start=\"$offset\" end=\"$next\">")
                    for (index in offset until next)
                        append("<file><ref>https://api.sugarsync.com/file/$index</ref>" +
                            "<displayName>file-$index.bin</displayName><size>0</size></file>")
                    append("</collectionContents>")
                }.byteInputStream())
            }
            else -> error("Unexpected provider")
        }
        val path = when (protocol) {
            Protocol.DRIVE, Protocol.ONEDRIVE -> "folder"
            Protocol.BOX -> "folder:123"
            Protocol.S3 -> "/bucket/folder"
            Protocol.SUGARSYNC -> "https://api.sugarsync.com/folder/123"
            else -> "/folder"
        }
        return Fixture(fs, path, requests)
    }

    @Test fun everyProviderRejectsAnEntryBeyond50000InsteadOfReturningTheFirst50000() {
        for (provider in providers) {
            val fixture = fixture(provider, 50_001)
            val failure = rejected { fixture.fs.list(fixture.path) }
            assertTrue("$provider: ${failure.message}", failure.message!!.contains("50.000"))
            assertTrue("$provider must fetch the page beyond the old cutoff", fixture.requests.size > 50)
        }
    }

    @Test fun everyProviderStillAcceptsExactly50000Entries() {
        for (provider in providers) {
            val fixture = fixture(provider, 50_000)
            val entries = fixture.fs.list(fixture.path)
            assertEquals("$provider", 50_000, entries.size)
            assertEquals("$provider", 50_000, entries.map { it.path }.toSet().size)
            assertEquals("$provider", "file-49999.bin", entries.last().name)
            if (provider == Protocol.BAIDU)
                assertEquals("An empty probe distinguishes exactly-full from truncated", "50000", fixture.requests.last())
        }
    }

    @Test fun recursiveDownloadCannotTreatAPartialCloudListingAsSuccess() = runBlocking {
        val fixture = fixture(Protocol.DRIVE, 50_001)
        var reads = 0
        var deletes = 0
        val observed = object : RemoteFs by fixture.fs {
            override fun read(path: String): InputStream {
                reads++
                return ByteArray(0).inputStream()
            }
            override fun delete(entry: RemoteEntry) { deletes++ }
        }
        val error = rejected {
            runBlocking {
                RemoteFiles.download(observed, RemoteEntry(fixture.path, "folder", true, 0),
                    temp.newFolder("download"), {})
            }
        }
        assertTrue(error.message!!.contains("no se completó"))
        assertEquals("A directory listing must complete before its children are copied", 0, reads)
        assertEquals(0, deletes)
    }

    @Test fun tokenCyclesIncludingEmptyPagesAreRejectedBeforeRequestingTheSamePageAgain() {
        for (provider in listOf(Protocol.DRIVE, Protocol.DROPBOX, Protocol.ONEDRIVE, Protocol.BOX)) {
            var requests = 0
            val json: CloudJsonRequest = { _, _, _ ->
                requests++
                when (provider) {
                    Protocol.DRIVE -> JSONObject().put("files", JSONArray()).put("nextPageToken", "same")
                    Protocol.DROPBOX -> JSONObject().put("entries", JSONArray()).put("has_more", true).put("cursor", "same")
                    Protocol.ONEDRIVE -> JSONObject().put("value", JSONArray())
                        .put("@odata.nextLink", "https://graph.microsoft.com/v1.0/me/drive/root/children")
                    Protocol.BOX -> JSONObject().put("entries", JSONArray()).put("next_marker", "same")
                    else -> error("Unexpected provider")
                }
            }
            val fs = if (provider == Protocol.BOX) BoxFs(account(provider), json)
                else CloudFs(account(provider), json)
            val failure = rejected { fs.list("") }
            assertTrue("$provider", failure.message!!.contains("repitió una página"))
            assertTrue("$provider", requests in 1..2)
        }
    }

    @Test fun duplicateIdsAcrossDistinctPagesAreRejectedRatherThanHidingAChangedListing() {
        var requests = 0
        val fs = BoxFs(account(Protocol.BOX)) { _, _, _ ->
            requests++
            JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("id", "same-file").put("type", "file").put("name", "file.bin")))
                .put("next_marker", if (requests == 1) "second" else JSONObject.NULL)
        }
        assertTrue(rejected { fs.list("folder:123") }.message!!.contains("repitió un archivo"))
        assertEquals(2, requests)
    }

    @Test fun boxCannotTreatAnOffsetResponseWithoutTheRequestedMarkerAsComplete() {
        val fs = BoxFs(account(Protocol.BOX)) { _, _, _ ->
            JSONObject().put("entries", JSONArray()).put("total_count", 60_000)
        }
        assertTrue(rejected { fs.list("folder:123") }.message!!.contains("no confirmó"))
    }

    @Test fun googleIncompleteSearchCannotBeReturnedAsAnEmptyOrPartialFolder() {
        val fs = CloudFs(account(Protocol.DRIVE)) { _, _, _ ->
            JSONObject().put("files", JSONArray()).put("incompleteSearch", true)
        }
        assertTrue(rejected { fs.list("folder") }.message!!.contains("no completó"))
    }

    @Test fun missingDropboxCursorIsAnErrorWhenTheProviderClaimsMoreEntries() {
        val fs = CloudFs(account(Protocol.DROPBOX)) { _, _, _ ->
            JSONObject().put("entries", JSONArray()).put("has_more", true)
        }
        assertTrue(rejected { fs.list("/folder") }.message!!.contains("paginación"))
    }

    @Test fun oneDriveNeverFollowsAContinuationLinkToAnotherHost() {
        var requests = 0
        val fs = CloudFs(account(Protocol.ONEDRIVE)) { _, _, _ ->
            requests++
            JSONObject().put("value", JSONArray())
                .put("@odata.nextLink", "https://evil.example/steal-token")
        }
        assertTrue(rejected { fs.list("folder") }.message!!.contains("inesperada"))
        assertEquals(1, requests)
    }

    @Test fun yandexUsesTheTotalInsteadOfAssumingAShortPageIsTheLastPage() {
        val fixture = fixture(Protocol.YANDEX, total = 5, pageSize = 2)
        assertEquals(5, fixture.fs.list(fixture.path).size)
        assertEquals(3, fixture.requests.size)
        val emptyPage = YandexFs(account(Protocol.YANDEX)) { _, _, _ ->
            JSONObject().put("_embedded", JSONObject().put("items", JSONArray()).put("total", 1))
        }
        assertTrue(rejected { emptyPage.list("/folder") }.message!!.contains("paginación"))
    }

    @Test fun s3RequiresBothTheTruncationFlagAndAValidContinuationToken() {
        for (xml in listOf(
            "<IsTruncated>true</IsTruncated>",
            "<NextContinuationToken>next</NextContinuationToken>",
            "<IsTruncated>false</IsTruncated><NextContinuationToken>next</NextContinuationToken>")) {
            val fs = S3Fs(account(Protocol.S3)) { _, _ ->
                "<ListBucketResult>$xml</ListBucketResult>".byteInputStream()
            }
            assertTrue(rejected { fs.list("/bucket/folder") }.message!!.contains("no confirmó"))
        }
    }

    @Test fun s3DirectoryRenameAbortsBeforeCopiesOrDeletesIfTheFullPrefixSnapshotIsTruncated() {
        var requests = 0
        val fs = S3Fs(account(Protocol.S3)) { _, query ->
            requests++
            if (query.containsKey("delimiter"))
                "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>".byteInputStream()
            else ("<ListBucketResult><IsTruncated>true</IsTruncated>" +
                "<Contents><Key>folder/file.bin</Key><Size>0</Size></Contents></ListBucketResult>")
                    .byteInputStream()
        }
        val error = rejected { fs.rename(RemoteEntry("/bucket/folder", "folder", true, 0), "renamed") }
        assertTrue(error.message!!.contains("no confirmó"))
        assertEquals(2, requests)
    }

    @Test fun sugarSyncDoesNotSilentlySkipAnInvalidFileOrAnIncompleteContinuation() {
        for (xml in listOf(
            "<collectionContents hasMore=\"false\"><file><ref>https://api.sugarsync.com/file/1</ref></file></collectionContents>",
            "<collectionContents hasMore=\"true\"/>",
            "<collectionContents/>")) {
            val fs = SugarSyncFs(account(Protocol.SUGARSYNC)) { CloudXml.parse(xml.byteInputStream()) }
            rejected { fs.list("https://api.sugarsync.com/folder/123") }
        }
    }
}
