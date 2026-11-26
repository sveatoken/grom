// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The eight-byte BLE advertisement (D99).
 *
 * It is a hint and nothing else: the prefix is truncated, unsigned, and only
 * meaningful together with a port. Anything that does not decode exactly is
 * dropped rather than guessed at.
 */
class BleAdvertisementTest {

    private val nodeId = "0123456789abcdef"

    @Test
    fun `a payload round-trips the prefix, the port and the data-plane flag`() {
        val bytes = BleAdvertisement.encode(nodeId, 47002, dataPlaneUp = true)
        assertNotNull(bytes)
        assertEquals(BleAdvertisement.PAYLOAD_BYTES, bytes!!.size)
        val decoded = BleAdvertisement.decode(bytes)
        assertNotNull(decoded)
        assertEquals("01234567", decoded!!.nodeIdPrefix)
        assertEquals(47002, decoded.port)
        assertEquals(true, decoded.dataPlaneUp)
        assertEquals("ble:01234567", decoded.key())
    }

    @Test
    fun `a stopped data plane is visible in the flags`() {
        val decoded = BleAdvertisement.decode(
            BleAdvertisement.encode(nodeId, 47002, dataPlaneUp = false)!!
        )
        assertEquals(false, decoded!!.dataPlaneUp)
    }

    @Test
    fun `the advertisement is too small to carry a request`() {
        // The bound is the point: BLE is a discovery carrier (D99).
        assertEquals(8, BleAdvertisement.PAYLOAD_BYTES)
        assertNull(BleAdvertisement.decode(ByteArray(64) { 1 }))
    }

    @Test
    fun `a different version or a bad port is not ours`() {
        val bytes = BleAdvertisement.encode(nodeId, 47002)!!
        bytes[0] = 2
        assertNull(BleAdvertisement.decode(bytes))
        assertNull(BleAdvertisement.encode(nodeId, 0))
        assertNull(BleAdvertisement.encode("not-a-node-id", 47002))
    }
}
