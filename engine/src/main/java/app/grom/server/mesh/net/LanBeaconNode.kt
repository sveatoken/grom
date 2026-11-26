// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.mesh.MeshLimits
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The LAN carrier of the discovery beacon (D99, D102).
 *
 * One socket receives, one thread decodes, one daemon thread sends — and all
 * three live only while the operator has discovery enabled. There is no
 * scheduler inside the engine and no timer that outlives [stop].
 *
 * The receive path is intentionally forgiving about *where* a datagram came
 * from (unicast, broadcast or multicast) and strict about *what* it says: the
 * payload is handed to [app.grom.server.mesh.MeshBeacon.decode], which checks
 * the signature and the timestamp. The sender host is used for nothing except
 * building the candidate's endpoint, and only after the beacon verified.
 *
 * This class is the only place in `mesh/` that opens a socket, so a policy check
 * can hold the rest of the package to "no socket symbols" and the transport
 * layer to "the shared [app.grom.server.superserver.net.JsonTransport] only"
 * (D102).
 */
class LanBeaconNode(
    /** Port this node listens on. */
    private val port: Int = DEFAULT_PORT,
    /** Destination group; a multicast group by default, loopback in tests. */
    private val group: InetAddress = DEFAULT_GROUP,
    /** Destination port, so a test can point two nodes at each other. */
    private val sendPort: Int = port,
    private val maxBytes: Int = MeshLimits.MAX_BEACON_BYTES,
    private val logger: (String) -> Unit = {}
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiver: Thread? = null
    private var beacons: Thread? = null

    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = running.get()

    /**
     * Binds the receive socket and starts the reader. [onBeacon] gets the
     * sender's textual address and the datagram's text.
     */
    fun start(onBeacon: (String, String) -> Unit): Boolean {
        if (running.get()) return true
        val created = try {
            val address = group
            // Capture the listen port before the receiver block: inside
            // `apply` an unqualified `port` is the socket's (remote) port, not
            // this node's, and binding to -1 is an IllegalArgumentException.
            val listenPort = port
            val bound = if (address.isMulticastAddress) {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(listenPort))
                    joinGroup(address)
                }
            } else {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(listenPort))
                }
            }
            bound.soTimeout = 1_000
            bound
        } catch (failure: Exception) {
            lastError = "BEACON_BIND_FAILED: ${failure.javaClass.simpleName}"
            logger("mesh beacon bind failed: ${failure.javaClass.simpleName}")
            return false
        }
        socket = created
        running.set(true)
        lastError = null
        receiver = Thread({ receiveLoop(created, onBeacon) }, "grom-mesh-beacon-rx").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Sends one already-signed line to the group. Returns false on any failure. */
    fun send(line: String): Boolean {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return false
        return try {
            DatagramSocket().use { sender ->
                sender.broadcast = true
                val packet = DatagramPacket(bytes, bytes.size, group, sendPort)
                sender.send(packet)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sends [payload] every [intervalMs] while running. A null payload means
     * "not now" and skips this round; it never throws.
     */
    fun startBeaconing(intervalMs: Long, payload: () -> String?) {
        if (!running.get()) return
        if (beacons != null) return
        beacons = Thread({
            while (running.get()) {
                val line = runCatching { payload() }.getOrNull()
                if (line != null) runCatching { send(line) }
                try {
                    Thread.sleep(intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "grom-mesh-beacon-tx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        receiver?.interrupt()
        receiver = null
        beacons?.interrupt()
        beacons = null
    }

    private fun receiveLoop(socket: DatagramSocket, onBeacon: (String, String) -> Unit) {
        val buffer = ByteArray(maxBytes + 1)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                // A timeout is the normal path; a closed socket ends the loop.
                if (!running.get()) return
                continue
            }
            if (packet.length <= 0 || packet.length > maxBytes) continue
            val host = packet.address?.hostAddress ?: continue
            val line = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            runCatching { onBeacon(host, line) }
        }
    }

    companion object {
        /** 47100 is the hub roster; discovery sits one block above it (D99). */
        const val DEFAULT_PORT: Int = 47_110

        /**
         * Link-local, administratively scoped: never routed off the LAN.
         *
         * It is built from bytes rather than a name, so this file never resolves
         * a host name and the tree keeps its "no name resolution in the server"
         * invariant (policy #7).
         */
        val DEFAULT_GROUP: InetAddress = InetAddress.getByAddress(
            byteArrayOf(239.toByte(), 255.toByte(), 71, 10)
        )

        const val MIN_INTERVAL_MS: Long = 5_000
    }
}
