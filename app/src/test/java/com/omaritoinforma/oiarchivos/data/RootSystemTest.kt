package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class RootSystemTest {
    private val mounts =
        """
        /dev/block/dm-0 / erofs ro,seclabel,relatime 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid 0 0
        /dev/block/dm-3 /vendor erofs ro,seclabel 0 0
        /dev/block/dm-50 /data f2fs rw,lazytime,seclabel,nosuid,nodev 0 0
        /dev/block/dm-50 /system/etc/hosts f2fs rw,lazytime,seclabel,nosuid,nodev 0 0
        /dev/block/dm-9 /mnt/media\040rw ext4 rw 0 0
        """.trimIndent()

    @Test
    fun theMountOfAPathIsTheLongestAndLatest() {
        // Sistema como raíz (system-as-root): /system está dentro de «/».
        assertEquals("/" to true, RootSystem.mountOf("/system", mounts))
        assertEquals("/vendor" to true, RootSystem.mountOf("/vendor/lib", mounts))
        assertEquals("/data" to false, RootSystem.mountOf("/data/local/tmp", mounts))
        // El hosts con una copia montada encima.
        assertEquals("/system/etc/hosts" to false, RootSystem.mountOf("/system/etc/hosts", mounts))
        assertEquals("/mnt/media rw" to false, RootSystem.mountOf("/mnt/media rw/x", mounts))
        // /data2 no está dentro de /data.
        assertEquals("/" to true, RootSystem.mountOf("/data2", mounts))
        val remounted = mounts + "\n/dev/block/dm-0 / erofs rw,seclabel 0 0"
        assertEquals("/" to false, RootSystem.mountOf("/system", remounted))
    }

    @Test
    fun hostsLinesAreValidated() {
        val good =
            """
            # comentario
            127.0.0.1       localhost
            ::1             ip6-localhost ip6-loopback
            0.0.0.0 anuncios.ejemplo.com tracker.ejemplo.net # bloqueados
            fe80::1%wlan0 router.local

            """.trimIndent()
        assertEquals(emptyList<String>(), RootSystem.hostsErrors(good))
        val bad = "127.0.0.1 localhost\n300.1.1.1 malo.com\n10.0.0.1\nhola mundo\n10.0.0.2 nombre;rm"
        assertEquals(listOf(2, 3, 4, 5), RootSystem.hostsErrors(bad).map { it.substringAfter("Línea ").substringBefore(':').toInt() })
    }

    @Test
    fun aFolderIsListedWithOneCommandEvenWithOddNames() {
        val dir = kotlin.io.path.createTempDirectory("raíz con espacio").toFile()
        try {
            java.io.File(dir, "carpeta 'rara'").mkdir()
            java.io.File(dir, "línea\nnueva.txt").writeText("12345")
            java.io.File(dir, "%s y \$HOME.bin").writeBytes(ByteArray(3))
            java.nio.file.Files.createSymbolicLink(java.io.File(dir, "enlace").toPath(), java.io.File(dir, "carpeta 'rara'").toPath())
            val out = ProcessBuilder("sh", "-c", RootFs.listCommand(dir.path)).start().inputStream.readBytes().toString(Charsets.UTF_8)
            val entries = RootFs.parseListing(out).associateBy { it.name }
            assertEquals(setOf("carpeta 'rara'", "línea\nnueva.txt", "%s y \$HOME.bin"), entries.keys)
            assertTrue(entries.getValue("carpeta 'rara'").directory)
            assertEquals(5L, entries.getValue("línea\nnueva.txt").size)
            assertEquals(3L, entries.getValue("%s y \$HOME.bin").size)
            assertEquals(java.io.File(dir, "carpeta 'rara'").path, entries.getValue("carpeta 'rara'").path)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shellQuotingSurvivesQuotes() {
        assertEquals("'a b'", RootShell.q("a b"))
        assertEquals("'it'\"'\"'s'", RootShell.q("it's"))
    }
}
