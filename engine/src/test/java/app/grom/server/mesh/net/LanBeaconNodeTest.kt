// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.mesh.MeshBeacon
import app.grom.server.superserver.auth.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The UDP carrier of the LAN beacon, over a real loopback socket (D99).
 *
 * The multicast group is replaced by loopback unicast, which is the same
 * `DatagramSocket` path minus the group membership: a datagram is sent, received
 * and handed to the verifier. What the test does **not** prove is multicast
 * delivery on a hostile network — that is a deployment fact, not a code fact.
 */
class LanBeaconNodeTest {

    private fun freePort(): Int =
        DatagramSocket(0).use { it.localPort }

    @Test
    fun `a beacon sent by one node is received and verifies`() {
        val loopback = InetAddress.getLoopbackAddress()
        val port = freePort()
        val received = AtomicReference<String?>(null)
        val host = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val receiver = LanBeaconNode(
            port = port,
            group = loopback,
            sendPort = port
        )
        assertTrue(receiver.start { from, line ->
            host.set(from)
            received.set(line)
            latch.countDown()
        })

        val sender = LanBeaconNode(port = 0, group = loopback, sendPort = port)
        assertTrue(sender.start { _, _ -> })

        val seed = Ed25519.generatePrivateSeed()
        val line = MeshBeacon.encode(seed, 47002, System.currentTimeMillis() / 1000, "n-1")
        assertNotNull(line)
        assertTrue(sender.send(line!!))

        assertTrue("no beacon received", latch.await(5, TimeUnit.SECONDS))
        assertEquals(line, received.get())
        assertEquals("127.0.0.1", host.get())
        assertNotNull(MeshBeacon.decode(received.get()))

        sender.stop()
        receiver.stop()
        assertTrue(!sender.isRunning())
        assertTrue(!receiver.isRunning())
    }

    @Test
    fun `an oversized datagram is refused before it is sent`() {
        val loopback = InetAddress.getLoopbackAddress()
        val node = LanBeaconNode(port = 0, group = loopback)
        assertTrue(node.start { _, _ -> })
        assertTrue(!node.send("x".repeat(600)))
        node.stop()
    }

    @Test
    fun `a stopped node cannot send or beacon`() {
        val loopback = InetAddress.getLoopbackAddress()
        val node = LanBeaconNode(port = 0, group = loopback)
        node.stop()
        assertTrue(!node.isRunning())
        assertNull(node.lastError)
        // startBeaconing on a stopped node is a no-op, not a thread leak.
        node.startBeaconing(5_000) { "grom-mesh-beacon-v1 x" }
        node.stop()
    }
}
