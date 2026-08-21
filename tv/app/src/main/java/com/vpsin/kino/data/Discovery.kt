package com.vpsin.kino.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the laptop over UDP so nobody has to type an IP.
 *
 * Two paths, because routers differ:
 *  - we broadcast a probe and the server replies directly to us
 *  - the server broadcasts every 5s and we simply listen
 * Binding the discovery port catches both. If the router isolates clients neither
 * works, and the user falls back to entering an address by hand.
 */
object Discovery {

    private const val PORT = 48200
    private const val MAGIC = "KINO_DISCOVER"
    private const val PROBE_INTERVAL_MS = 1500L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Emits each distinct server as it is found, and keeps listening until the
     * collector is cancelled. Wrap in `withTimeoutOrNull` or collect inside a
     * cancellable scope to bound the search.
     */
    fun discover(): Flow<ServerRef> = flow {
        val socket = openSocket() ?: return@flow
        val seen = mutableSetOf<String>()

        // Re-probe on a timer: the laptop may not be running when we start.
        val prober = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
            val payload = MAGIC.toByteArray()
            while (isActive) {
                for (target in broadcastTargets()) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target, PORT)) }
                }
                delay(PROBE_INTERVAL_MS)
            }
        }

        try {
            val buffer = ByteArray(2048)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue // soTimeout exists purely so cancellation is checked
                } catch (_: Exception) {
                    break
                }

                val server = parse(packet) ?: continue
                if (seen.add(server.label)) emit(server)
            }
        } finally {
            prober.cancel()
            runCatching { socket.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun openSocket(): DatagramSocket? {
        // Binding the well-known port also picks up the server's periodic
        // announcements, not just replies to our own probes.
        runCatching {
            return DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                broadcast = true
                soTimeout = 800
            }
        }
        // Port taken: an ephemeral port still receives direct replies.
        runCatching {
            return DatagramSocket().apply {
                broadcast = true
                soTimeout = 800
            }
        }
        return null
    }

    private fun parse(packet: DatagramPacket): ServerRef? {
        val text = String(packet.data, packet.offset, packet.length).trim()
        // Our own probe echoing back off the broadcast address.
        if (text.startsWith(MAGIC)) return null

        val announcement = runCatching { json.decodeFromString<Announcement>(text) }.getOrNull()
            ?: return null
        if (announcement.magic != "KINO" || announcement.port <= 0) return null

        // Trust the packet's source address over the self-reported host: a laptop
        // with several NICs can easily advertise the wrong one.
        val host = packet.address?.hostAddress?.takeIf { it.isNotBlank() }
            ?: announcement.host.takeIf { it.isNotBlank() }
            ?: return null

        return ServerRef(
            name = announcement.name.ifBlank { host },
            host = host,
            port = announcement.port,
        )
    }

    /**
     * 255.255.255.255 is dropped by some Android builds and routers, so also target
     * each interface's directed broadcast address.
     */
    private suspend fun broadcastTargets(): List<InetAddress> = withContext(Dispatchers.IO) {
        val targets = mutableListOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        }
        targets.distinctBy { it.hostAddress }
    }
}
