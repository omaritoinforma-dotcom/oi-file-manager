package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun obexNamesUseUtf16WithTerminator() {
        val name = "álbum 🎵.txt"
        val header = ObexCodec.name(name)
        assertEquals(1, header[0].toInt())
        assertEquals(header.size, ObexCodec.ushort(header, 1))
        val packet = ObexCodec.packet(0xa0, header)
        val value = ObexCodec.headers(packet, 3).single().second
        assertEquals(name + "\u0000", value.toString(Charsets.UTF_16BE))
    }

    @Test
    fun obexVariableByteAndIntegerHeadersDecodeTogether() {
        val body =
            byteArrayOf(0x94.toByte(), 1, 0xcb.toByte(), 0, 0, 1, 2) +
                ObexCodec.bytes(0x49, byteArrayOf(7, 8, 9))
        val headers = ObexCodec.headers(ObexCodec.packet(0xa0, body), 3)
        assertEquals(listOf(0x94, 0xcb, 0x49), headers.map { it.first })
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), headers[1].second)
        assertArrayEquals(byteArrayOf(7, 8, 9), headers[2].second)
    }

    @Test
    fun malformedObexLengthsAreRejectedBeforeReadingBody() {
        for (body in
            listOf(
                byteArrayOf(0x48, 0, 2),
                byteArrayOf(0x48, 0, 9, 1),
                byteArrayOf(0xcb.toByte(), 1))) {
            try {
                ObexCodec.headers(ObexCodec.packet(0xa0, body), 3)
                fail("Accepted truncated header")
            } catch (_: IOException) {}
        }
        try {
            ObexCodec.packet(0x83, ByteArray(65533))
            fail("Accepted oversize packet")
        } catch (_: IOException) {}
    }

    @Test
    fun nativeArchiveListingRejectsTraversalLinksAndAmbiguousNames() {
        for (listing in
            listOf(
                "Path = ../escape\nSize = 1\n",
                "Path = x\nSize = 1\nSymbolic Link = /etc/passwd\n",
                "Path = x\nPath = y\nSize = 1\n",
                "Path = a\nSize = 1\n\nPath = a\nSize = 1\n")) {
            try {
                NativeArchives.parseListing(listing)
                fail("Accepted unsafe listing")
            } catch (_: IOException) {}
        }
        val parsed =
            NativeArchives.parseListing(
                "Path = carpeta\nSize = 0\nAttributes = D drwxr-xr-x\n\nPath = carpeta/é.txt\nSize = 3\nAttributes = A -rw-r--r--\n")
        assertTrue(parsed[0].directory)
        assertEquals(ArchiveEntry("carpeta/é.txt", 3, false), parsed[1])
    }
}
