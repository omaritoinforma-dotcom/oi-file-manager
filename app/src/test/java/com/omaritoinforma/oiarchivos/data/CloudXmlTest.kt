package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CloudXmlTest {
    @Test
    fun xmlTextRoundTripEscapesNamesWithoutLosingUnicode() {
        val name = "vacaciones & <fotos> é 🎵"
        val xml = CloudXml.body("file", mapOf("displayName" to name, "mediaType" to "image/jpeg"))
        val document = CloudXml.parse(xml.byteInputStream())
        assertEquals(name, CloudXml.text(document.documentElement, "displayName"))
        assertEquals(2, document.documentElement.childNodes.length)
    }

    @Test
    fun externalEntitiesAndOversizedResponsesAreRejected() {
        try {
            CloudXml.parse(
                "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><file>&e;</file>"
                    .byteInputStream())
            fail("DTD must be rejected")
        } catch (_: org.xml.sax.SAXException) {}
        try {
            CloudXml.bounded(ByteArray(100).inputStream(), 32)
            fail("Bounded input")
        } catch (_: IOException) {}
    }

    @Test
    fun sugarSyncCredentialsCannotBeSentToAnotherHostOrPort() {
        for (url in
            listOf(
                "http://api.sugarsync.com/file/1",
                "https://evil.example/file/1",
                "https://api.sugarsync.com.evil.example/file/1",
                "https://evil@api.sugarsync.com/file/1",
                "https://api.sugarsync.com:444/file/1")) {
            try {
                sugarEndpoint(url)
                fail(url)
            } catch (_: IOException) {}
        }
        assertEquals(
            "https://api.sugarsync.com/file/:sc:1:2",
            sugarEndpoint("https://api.sugarsync.com/file/:sc:1:2"))
    }
}
