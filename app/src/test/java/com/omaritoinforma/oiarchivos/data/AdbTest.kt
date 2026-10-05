package com.omaritoinforma.oiarchivos.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.Collections
import javax.crypto.Cipher
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun headersFollowTheAdbFormat() {
        val bytes = Adb.encode(Adb.Message(Adb.OPEN, 1, 0, "shell:ls\u0000".toByteArray()))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Adb.OPEN, b.int)
        assertEquals(1, b.int)
        assertEquals(0, b.int)
        assertEquals(9, b.int)
        assertEquals("shell:ls\u0000".toByteArray().sumOf { it.toInt() }, b.int)
        assertEquals(Adb.OPEN xor -1, b.int)
        assertEquals("OPEN", String(bytes, 0, 4)) // las órdenes se leen como texto al revés del entero
        val back = Adb.read(DataInputStream(bytes.inputStream()))
        assertEquals("shell:ls\u0000", String(back.data))
        val broken = bytes.copyOf().also { it[20] = 0 }
        assertThrows(IOException::class.java) { Adb.read(DataInputStream(broken.inputStream())) }
    }

    @Test
    fun thePublicKeyUsesTheAndroidFormatAndSignaturesVerify() {
        val text = String(Adb.publicKey(keys, "oi@telefono")).trimEnd('\u0000')
        assertTrue(text.endsWith(" oi@telefono"))
        val raw = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size) // 2048 bits: 4 + 4 + 256 + 256 + 4
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int
        val n = keys.public as java.security.interfaces.RSAPublicKey
        // n0inv · n[0] ≡ -1 (mod 2^32)
        assertEquals(-1, n0inv * n.modulus.toInt())
        assertEquals(n.modulus, Adb.parsePublicKey(text).modulus)
        assertEquals(n.publicExponent, Adb.parsePublicKey(text).publicExponent)
        // La firma descifrada con la clave pública es DigestInfo(SHA-1) + el número, como comprueba adbd.
        val token = ByteArray(20) { it.toByte() }
        val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, Adb.parsePublicKey(text))
            doFinal(Adb.sign(keys, token))
        }
        assertEquals(35, plain.size)
        assertArrayEquals(token, plain.copyOfRange(15, 35))
    }

    @Test
    fun keysAreCreatedOnceAndReused() {
        val file = File(tmp.root, "adb/adbkey")
        val first = Adb.loadOrCreateKeys(file)
        val second = Adb.loadOrCreateKeys(file)
        assertEquals(first.public, second.public)
    }

    @Test
    fun addressesAndBannersAreParsed() {
        assertEquals("192.168.1.40" to 5555, Adb.parseAddress(" 192.168.1.40 "))
        assertEquals("192.168.1.40" to 5556, Adb.parseAddress("192.168.1.40:5556"))
        assertEquals("fe80::1" to 5555, Adb.parseAddress("[fe80::1]"))
        assertThrows(IOException::class.java) { Adb.parseAddress("tv:abc") }
        val d = Adb.parseBanner("device::ro.product.name=sabrina;ro.product.model=Chromecast;features=cmd,shell_v2", "x:5555")
        assertEquals("Chromecast", d.name)
        assertEquals(setOf("cmd", "shell_v2"), d.features)
        assertEquals("x:5555", Adb.parseBanner("device::", "x:5555").name)
        assertThrows(IOException::class.java) { Adb.requirePackageName("com.x; reboot") }
        Adb.requirePackageName("com.google.android.youtube.tv")
    }

    /**
     * adbd falso: comprueba la firma con las claves que ya conoce, acepta la clave nueva tras la
     * «pregunta en pantalla» y responde a shell, exec (instalar por streaming) y sync.
     */
    private class FakeAdbd(
        val authorized: MutableList<java.security.interfaces.RSAPublicKey> = Collections.synchronizedList(ArrayList()),
        val features: String = "cmd,shell_v2",
        val acceptNewKeys: Boolean = true
    ) : AutoCloseable {
        val server = ServerSocket(0)
        val commands: MutableList<String> = Collections.synchronizedList(ArrayList())
        val files = Collections.synchronizedMap(HashMap<String, ByteArray>())
        val installed = Collections.synchronizedList(arrayListOf("com.ejemplo.juego", "org.ejemplo.radio"))
        @Volatile var askedUser = false
        @Volatile var removed: ByteArray? = null
        private val worker = thread(isDaemon = true) { runCatching { serve() } }

        private fun serve() {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                fun send(m: Adb.Message) = synchronized(out) {
                    out.write(Adb.encode(m))
                    out.flush()
                }
                val token = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
                val hello = Adb.read(input)
                assertEquals(Adb.CNXN, hello.command)
                send(Adb.Message(Adb.AUTH, Adb.AUTH_TOKEN, 0, token))
                while (true) {
                    val m = Adb.read(input)
                    if (m.command != Adb.AUTH) error("se esperaba AUTH")
                    if (m.arg0 == Adb.AUTH_SIGNATURE) {
                        val ok = authorized.any { key ->
                            runCatching {
                                Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                                    init(Cipher.DECRYPT_MODE, key)
                                    doFinal(m.data)
                                }.copyOfRange(15, 35).contentEquals(token)
                            }.getOrDefault(false)
                        }
                        if (ok) break
                        send(Adb.Message(Adb.AUTH, Adb.AUTH_TOKEN, 0, token))
                    } else if (m.arg0 == Adb.AUTH_RSAPUBLICKEY) {
                        askedUser = true
                        if (!acceptNewKeys) return
                        Thread.sleep(300) // alguien pulsa «Permitir» en la TV
                        authorized += Adb.parsePublicKey(String(m.data).trimEnd('\u0000'))
                        break
                    }
                }
                send(Adb.Message(Adb.CNXN, Adb.VERSION, 4096, "device::ro.product.model=TV de prueba;features=$features".toByteArray()))
                var remoteId = 100
                while (true) {
                    val open = Adb.read(input)
                    if (open.command != Adb.OPEN) continue
                    val local = open.arg0
                    val me = remoteId++
                    val service = String(open.data).trimEnd('\u0000')
                    commands += service
                    send(Adb.Message(Adb.OKAY, me, local))
                    fun reply(text: String) {
                        if (text.isNotEmpty()) {
                            send(Adb.Message(Adb.WRTE, me, local, text.toByteArray()))
                            val ack = Adb.read(input)
                            assertEquals(Adb.OKAY, ack.command)
                        }
                        send(Adb.Message(Adb.CLSE, me, local))
                    }
                    fun receive(n: Int): ByteArray {
                        val buf = ByteArrayOutputStream()
                        while (buf.size() < n) {
                            val w = Adb.read(input)
                            assertEquals(Adb.WRTE, w.command)
                            assertTrue("trozo de ${w.data.size} mayor que el máximo", w.data.size <= 4096)
                            buf.write(w.data)
                            send(Adb.Message(Adb.OKAY, me, local))
                        }
                        return buf.toByteArray()
                    }
                    when {
                        service == "shell:pm list packages -3" -> reply(installed.joinToString("") { "package:$it\n" })
                        service.startsWith("shell:monkey -p ") ->
                            reply(if ("LEANBACK" in service) "  bash arg: -p\nNo activities found to run, monkey aborted.\n" else "Events injected: 1\n")
                        service.startsWith("shell:pm uninstall ") -> {
                            installed.remove(service.substringAfterLast(' '))
                            reply("Success\n")
                        }
                        service.startsWith("shell:input keyevent ") -> reply("")
                        service.startsWith("exec:cmd package install -r -S ") -> {
                            val size = service.substringAfterLast(' ').toInt()
                            val apk = receive(size)
                            files["stream"] = apk
                            reply(if (apk.copyOfRange(0, 2).contentEquals("PK".toByteArray())) "Success\n" else "Failure [INSTALL_PARSE_FAILED_NOT_APK]\n")
                        }
                        service.startsWith("shell:pm install -r '") -> {
                            val path = service.substringAfter("'").substringBefore("'")
                            reply(if (files[path]?.size ?: 0 > 0) "Success\n" else "Failure [missing]\n")
                        }
                        service.startsWith("shell:rm -f '") -> {
                            removed = files.remove(service.substringAfter("'").substringBefore("'"))
                            reply("")
                        }
                        service == "sync:" -> {
                            // SEND, DATA… y DONE pueden llegar partidos en varios WRTE.
                            val buf = ByteArrayOutputStream()
                            var path = ""
                            val content = ByteArrayOutputStream()
                            loop@ while (true) {
                                val w = Adb.read(input)
                                assertEquals(Adb.WRTE, w.command)
                                send(Adb.Message(Adb.OKAY, me, local))
                                buf.write(w.data)
                                while (true) {
                                    val all = buf.toByteArray()
                                    if (all.size < 8) break
                                    val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
                                    val id = String(all, 0, 4)
                                    b.position(4)
                                    val len = b.int
                                    val bodyLen = if (id == "DONE" || id == "QUIT") 0 else len
                                    if (all.size < 8 + bodyLen) break
                                    val body = all.copyOfRange(8, 8 + bodyLen)
                                    buf.reset()
                                    buf.write(all, 8 + bodyLen, all.size - 8 - bodyLen)
                                    when (id) {
                                        "SEND" -> path = String(body).substringBefore(',')
                                        "DATA" -> {
                                            assertTrue(body.size <= 64 * 1024)
                                            content.write(body)
                                        }
                                        "DONE" -> {
                                            files[path] = content.toByteArray()
                                            send(Adb.Message(Adb.WRTE, me, local, "OKAY".toByteArray() + ByteArray(4)))
                                            assertEquals(Adb.OKAY, Adb.read(input).command)
                                        }
                                        "QUIT" -> break@loop
                                    }
                                }
                            }
                            send(Adb.Message(Adb.CLSE, me, local))
                        }
                        else -> reply("desconocido\n")
                    }
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(2000)
        }
    }

    private fun apk(size: Int) = File(tmp.root, "app.apk").apply { writeBytes(ByteArray(size) { (it * 7).toByte() }.also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }) }

    @Test
    fun aNewPhoneIsApprovedOnTheTvAndThenRemembered() {
        FakeAdbd().use { tv ->
            var asked = false
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba", onApprovalNeeded = { asked = true }).use {
                assertEquals("TV de prueba", it.device.name)
            }
            assertTrue(asked)
            assertTrue(tv.askedUser)
            assertEquals(1, tv.authorized.size)
        }
        // La segunda vez basta la firma: no se vuelve a preguntar.
        val known = Collections.synchronizedList(arrayListOf(keys.public as java.security.interfaces.RSAPublicKey))
        FakeAdbd(known).use { tv ->
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba").close()
            assertFalse(tv.askedUser)
        }
    }

    @Test
    fun aRejectedPhoneGetsAClearError() {
        FakeAdbd(acceptNewKeys = false).use { tv ->
            val e = assertThrows(IOException::class.java) {
                Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba", timeoutMs = 2000, approvalMs = 2000)
            }
            assertNotNull(e.message)
        }
    }

    @Test
    fun appsAreListedOpenedRemovedAndKeysSent() {
        val known = Collections.synchronizedList(arrayListOf(keys.public as java.security.interfaces.RSAPublicKey))
        FakeAdbd(known).use { tv ->
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba").use { adb ->
                assertEquals(listOf("com.ejemplo.juego", "org.ejemplo.radio"), adb.packages())
                adb.launch("com.ejemplo.juego")
                adb.uninstall("org.ejemplo.radio")
                assertEquals(listOf("com.ejemplo.juego"), adb.packages())
                adb.key(Adb.Key.RIGHT)
                assertThrows(IOException::class.java) { adb.launch("x; reboot") }
            }
            assertEquals(
                listOf(
                    "shell:pm list packages -3",
                    "shell:monkey -p com.ejemplo.juego -c android.intent.category.LEANBACK_LAUNCHER 1",
                    "shell:monkey -p com.ejemplo.juego -c android.intent.category.LAUNCHER 1",
                    "shell:pm uninstall org.ejemplo.radio",
                    "shell:pm list packages -3",
                    "shell:input keyevent 22"),
                tv.commands)
        }
    }

    @Test
    fun apksAreStreamedOnNewTvsAndCopiedOnOldOnes() {
        val file = apk(10_000 + 123)
        val known = Collections.synchronizedList(arrayListOf(keys.public as java.security.interfaces.RSAPublicKey))
        FakeAdbd(known).use { tv ->
            var last = 0L
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba").use { it.install(file) { done, _ -> last = done } }
            assertEquals(file.length(), last)
            assertArrayEquals(file.readBytes(), tv.files["stream"])
        }
        // Android 6 o anterior: sin «cmd», se copia con sync y se instala con pm.
        val old = Collections.synchronizedList(arrayListOf(keys.public as java.security.interfaces.RSAPublicKey))
        val big = apk(150_000)
        FakeAdbd(old, features = "shell_v2").use { tv ->
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba").use { adb ->
                adb.install(big)
            }
            assertEquals(listOf("sync:", "shell:pm install -r '", "shell:rm -f '"), tv.commands.map { it.substringBefore("/data") })
            assertTrue(tv.files.isEmpty()) // se borró la copia temporal
            assertArrayEquals(big.readBytes(), tv.removed)
        }
    }

    @Test
    fun anInvalidApkReportsTheTvReason() {
        val bad = File(tmp.root, "malo.apk").apply { writeBytes(ByteArray(500)) }
        val known = Collections.synchronizedList(arrayListOf(keys.public as java.security.interfaces.RSAPublicKey))
        FakeAdbd(known).use { tv ->
            Adb.Client.connect("127.0.0.1", tv.server.localPort, keys, "oi@prueba").use { adb ->
                val e = assertThrows(IOException::class.java) { adb.install(bad) }
                assertTrue(e.message, e.message!!.contains("INSTALL_PARSE_FAILED_NOT_APK"))
            }
        }
    }
}
