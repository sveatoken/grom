// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded, expiring discovery book (D99).
 *
 * A candidate is never a peer: the book only remembers what was heard and for
 * how long. These tests pin the bounds that make a hostile LAN unable to grow
 * memory, resurrect a switched-off phone, or sneak this device into its own
 * nearby list.
 */
class DiscoveryBookTest {

    private var now = 1_000_000L
    private val book = DiscoveryBook(
        clock = { now },
        selfNodeId = { "aaaaaaaaaaaaaaaa" },
        ttlMs = 120_000,
        maxCandidates = 3
    )

    private fun candidate(
        nodeId: String?,
        transport: MeshTransport = MeshTransport.LAN,
        address: String = "http://192.168.1.20:47002",
        prefix: String? = nodeId?.substring(0, 8),
        signed: Boolean = nodeId != null
    ) = MeshCandidate(
        key = "ignored",
        nodeId = nodeId,
        nodeIdPrefix = prefix,
        publicKey = null,
        label = null,
        transport = transport,
        address = address,
        port = 47002,
        signedBeacon = signed,
        firstSeenMs = now,
        lastSeenMs = now
    )

    @Test
    fun `a sighting is remembered and expires on its own`() {
        assertTrue(book.offer(candidate("0123456789abcdef")))
        assertEquals(1, book.size())
        now += 120_001
        assertEquals(0, book.size())
        assertNull(book.find("id:0123456789abcdef"))
    }

    @Test
    fun `a better sighting upgrades an earlier one without resetting first seen`() {
        book.offer(candidate("0123456789abcdef", address = ""))
        val firstSeen = book.list().first().firstSeenMs
        now += 10_000
        book.offer(candidate("0123456789abcdef", address = "http://192.168.1.30:47002"))
        val merged = book.find("id:0123456789abcdef")
        assertNotNull(merged)
        assertEquals("http://192.168.1.30:47002", merged!!.address)
        assertEquals(firstSeen, merged.firstSeenMs)
        assertEquals(1, book.size())
    }

    @Test
    fun `this device and malformed endpoints are not candidates`() {
        assertFalse(book.offer(candidate("aaaaaaaaaaaaaaaa")))
        assertFalse(book.offer(candidate("0123456789abcdef", address = "file:///etc/passwd")))
        assertFalse(book.offer(candidate(null, address = "", prefix = null)))
        assertEquals(0, book.size())
    }

    @Test
    fun `the book is bounded`() {
        book.offer(candidate("0000000000000001"))
        book.offer(candidate("0000000000000002"))
        book.offer(candidate("0000000000000003"))
        book.offer(candidate("0000000000000004"))
        assertEquals(3, book.size())
        assertNull(book.find("id:0000000000000001"))
    }

    @Test
    fun `retain drops exactly the sightings a switch turned off`() {
        book.offer(candidate("0000000000000001"))
        book.offer(candidate("0000000000000002", transport = MeshTransport.BLUETOOTH_LE))
        assertEquals(2, book.size())
        val dropped = book.retain { !it.transport.isBluetooth }
        assertEquals(1, dropped)
        assertEquals(1, book.size())
        assertEquals(MeshTransport.LAN, book.list().single().transport)
        // Nothing left to drop is not an error.
        assertEquals(0, book.retain { !it.transport.isBluetooth })
    }

    @Test
    fun `an unsigned BLE sighting with a prefix is a candidate with no address`() {
        assertTrue(
            book.offer(
                candidate(
                    nodeId = null,
                    transport = MeshTransport.BLUETOOTH_LE,
                    address = "",
                    prefix = "01234567",
                    signed = false
                )
            )
        )
        val found = book.list().single()
        assertFalse(found.isDataCapable())
        assertEquals(MeshTransport.BLUETOOTH_LE, found.transport)
    }
}
