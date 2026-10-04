package com.omaritoinforma.oiarchivos.data

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A file server found on the local network. */
data class LanHost(val protocol: Protocol, val address: String, val port: Int, val name: String = "")

/**
 * Finds file servers on the Wi-Fi subnet by their standard ports, like ES File Explorer's LAN scan,
 * but confirms SFTP and FTP by the greeting they send, so an unrelated open port is not listed.
 */
object LanScanner {
    val standardPorts =
        listOf(Protocol.SMB to 445, Protocol.FTP to 21, Protocol.FTPS to 990, Protocol.SFTP to 22)

    /** IPv4 address and prefix of every active, non-loopback private interface. */
    fun localNetworks(): List<Pair<Inet4Address, Int>> =
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic ->
                nic.interfaceAddresses.mapNotNull { a ->
                    (a.address as? Inet4Address)
                        ?.takeIf { it.isSiteLocalAddress }
                        ?.let { it to a.networkPrefixLength.toInt() }
                }
            }

    /**
     * Hosts of the subnet, without the device itself. Networks larger than /24 are limited to the
     * device's /24 so a scan stays at most 253 hosts, as ES does.
     */
    fun subnetHosts(local: Inet4Address, prefix: Int): List<Inet4Address> {
        val bits = prefix.coerceIn(24, 30)
        val own = local.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        val network = own and mask
        val broadcast = network or (mask.inv() and 0xffffffffL)
        return (network + 1 until broadcast)
            .filter { it != own }
            .map { ip ->
                Inet4Address.getByAddress(
                    byteArrayOf(
                        (ip shr 24).toByte(), (ip shr 16).toByte(), (ip shr 8).toByte(), ip.toByte()))
                    as Inet4Address
            }
    }

    /** True when [address]:[port] answers like [protocol]. */
    fun probe(address: String, port: Int, protocol: Protocol, timeoutMs: Int = 400): Boolean =
        runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    socket.soTimeout = timeoutMs * 3
                    when (protocol) {
                        Protocol.SFTP -> greeting(socket).startsWith("SSH-")
                        Protocol.FTP -> greeting(socket).startsWith("220")
                        // SMB and implicit FTPS do not greet first; an open port is the evidence.
                        else -> true
                    }
                }
            }
            .getOrDefault(false)

    private fun greeting(socket: Socket): String {
        val bytes = ByteArray(64)
        val n = socket.getInputStream().read(bytes)
        return if (n > 0) String(bytes, 0, n, Charsets.ISO_8859_1) else ""
    }

    /**
     * Probes every host and port in parallel. [onFound] and [onProgress] may be called from
     * several threads.
     */
    suspend fun scan(
        hosts: List<Inet4Address>,
        ports: List<Pair<Protocol, Int>> = standardPorts,
        parallel: Int = 64,
        timeoutMs: Int = 400,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (LanHost) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(parallel)
        val total = hosts.size * ports.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        for (host in hosts) for ((protocol, port) in ports) launch(Dispatchers.IO) {
            gate.withPermit {
                val address = host.hostAddress ?: return@withPermit
                if (probe(address, port, protocol, timeoutMs))
                    onFound(LanHost(protocol, address, port))
                onProgress(done.incrementAndGet(), total)
            }
        }
    }
}
