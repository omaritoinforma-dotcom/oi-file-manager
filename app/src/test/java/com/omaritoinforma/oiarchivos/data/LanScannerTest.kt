package com.omaritoinforma.oiarchivos.data

import java.net.Inet4Address
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LanScannerTest {
    private val servers = ArrayList<ServerSocket>()

    /** A local server that sends [greeting] (or nothing) to every client. */
    private fun server(greeting: String?): Int {
        val socket = ServerSocket(0)
        servers += socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        if (greeting != null) it.getOutputStream().write(greeting.toByteArray())
                        Thread.sleep(300)
                    }
                }
            }
        }
        return socket.localPort
    }

    @After fun close() = servers.forEach { it.close() }

    @Test
    fun protocolIsConfirmedByItsGreeting() {
        val ssh = server("SSH-2.0-OpenSSH_9.6\r\n")
        val ftp = server("220 Servidor listo\r\n")
        val silent = server(null)
        assertTrue(LanScanner.probe("127.0.0.1", ssh, Protocol.SFTP))
        assertFalse("Un FTP no es SFTP", LanScanner.probe("127.0.0.1", ftp, Protocol.SFTP))
        assertTrue(LanScanner.probe("127.0.0.1", ftp, Protocol.FTP))
        assertFalse("Un SSH no es FTP", LanScanner.probe("127.0.0.1", ssh, Protocol.FTP))
        assertTrue("SMB no saluda primero", LanScanner.probe("127.0.0.1", silent, Protocol.SMB))
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(LanScanner.probe("127.0.0.1", closed, Protocol.SMB))
    }

    @Test
    fun subnetIsLimitedToTheDevicesSlash24AndSkipsItself() {
        val own = Inet4Address.getByName("10.0.2.16") as Inet4Address
        val hosts = LanScanner.subnetHosts(own, 24).map { it.hostAddress }
        assertEquals(253, hosts.size)
        assertEquals("10.0.2.1", hosts.first())
        assertEquals("10.0.2.254", hosts.last())
        assertFalse(own.hostAddress in hosts)
        assertEquals(hosts, LanScanner.subnetHosts(own, 16).map { it.hostAddress })
        assertEquals(
            listOf("192.168.1.9", "192.168.1.10"),
            LanScanner.subnetHosts(Inet4Address.getByName("192.168.1.11") as Inet4Address, 30)
                .map { it.hostAddress })
    }

    @Test
    fun scanReportsOnlyAnsweringServersAndAllProgress() = runBlocking {
        val ssh = server("SSH-2.0-test\r\n")
        val ftp = server("220 ok\r\n")
        val found = Collections.synchronizedList(ArrayList<LanHost>())
        var last = 0 to 0
        val local = Inet4Address.getByName("127.0.0.1") as Inet4Address
        LanScanner.scan(
            listOf(local),
            listOf(Protocol.SFTP to ssh, Protocol.FTP to ftp, Protocol.SFTP to ftp),
            onProgress = { done, total -> synchronized(this) { if (done > last.first) last = done to total } }) {
                found += it
            }
        assertEquals(
            setOf(LanHost(Protocol.SFTP, "127.0.0.1", ssh), LanHost(Protocol.FTP, "127.0.0.1", ftp)),
            found.toSet())
        assertEquals(3 to 3, last)
    }
}
