package com.omaritoinforma.oiarchivos.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class NearbyLinkTest {
    @Test
    fun buildAndParseRoundTripWithAccentsAndSpaces() {
        val link = NearbyLink.build("192.168.1.20", 42137, "Teléfono de Ana & Co")
        assertTrue(link, link.startsWith("oiarchivos://enviar?"))
        assertEquals(Nearby.Peer("192.168.1.20", 42137, "Teléfono de Ana & Co"), NearbyLink.parse(link))
    }

    @Test
    fun onlyLocalNetworkAddressesAreAccepted() {
        for (ok in listOf("10.0.2.2", "172.16.0.5", "172.31.255.1", "192.168.0.1", "169.254.3.4"))
            assertTrue(ok, NearbyLink.isLocalAddress(ok))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "example.com", "300.1.1.1", "10.0.0", "10.0.0.1.5", "", "10.0.0.-1", "0x0a.0.0.1", "[::1]"))
            assertFalse(bad, NearbyLink.isLocalAddress(bad))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=8.8.8.8&port=42137&nombre=x"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=evil.example.com&port=42137&nombre=x"))
    }

    @Test
    fun malformedOrOutOfRangeLinksAreRejected() {
        assertNull(NearbyLink.parse("http://enviar?host=192.168.1.2&port=42137"))
        assertNull(NearbyLink.parse("oiarchivos://otra?host=192.168.1.2&port=42137"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=80"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=abc"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=70000"))
        assertNull(NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=42137&nombre=" + "a".repeat(500)))
    }

    @Test
    fun theNameIsCleanedAndDefaultsToTheAddress() {
        val peer = NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=42137&nombre=Ana%0A%00Pixel")!!
        assertEquals("AnaPixel", peer.name)
        assertEquals("192.168.1.2", NearbyLink.parse("oiarchivos://enviar?host=192.168.1.2&port=42137")!!.name)
    }

    @Test
    fun theQrCodeDecodesBackToTheLink() {
        val link = NearbyLink.build("192.168.1.20", 42137, "Teléfono de Ana")
        val grid = NearbyLink.qr(link)
        val size = grid.size
        assertTrue(size > 20)
        val scale = 6
        val pixels = IntArray(size * scale * size * scale) { i ->
            val x = (i % (size * scale)) / scale
            val y = (i / (size * scale)) / scale
            if (grid[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size * scale, size * scale, pixels)))
        assertEquals(link, QRCodeReader().decode(bitmap).text)
    }
}
