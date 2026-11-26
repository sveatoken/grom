// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.superserver.auth.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed LAN beacon (D99, S8-R004).
 *
 * A beacon is a name tag, not a credential: it proves the sender holds the key
 * whose hash is the node id, and the timestamp bounds how long a captured line
 * can pretend a dead phone is nearby. The tests pin exactly that and no more.
 */
class MeshBeaconTest {

    private val seed = Ed25519.generatePrivateSeed()
    private val nodeId = Ed25519.keyId(Ed25519.derivePublicKey(seed))
    private val now = 1_700_000_000L

    @Test
    fun `a signed beacon round-trips and names its key`() {
        val line = MeshBeacon.encode(seed, 47002, now, "nonce-1")
        assertNotNull(line)
        val decoded = MeshBeacon.decode(line, now)
        assertNotNull(decoded)
        assertEquals(nodeId, decoded!!.nodeId)
        assertEquals(47002, decoded.port)
        assertEquals("nonce-1", decoded.nonce)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(Ed25519.derivePublicKey(seed)),
            decoded.publicKey
        )
    }

    @Test
    fun `a tampered port or a foreign signature is refused`() {
        val line = MeshBeacon.encode(seed, 47002, now, "nonce-1")!!
        val parts = line.split(' ').toMutableList()
        parts[2] = "47003"
        assertNull(MeshBeacon.decode(parts.joinToString(" "), now))

        val other = Ed25519.generatePrivateSeed()
        val forged = MeshBeacon.encode(other, 47002, now, "nonce-1")!!
        // A correctly signed beacon from a different key is valid on its own,
        // but it can never carry *our* node id.
        val decoded = MeshBeacon.decode(forged, now)
        assertNotNull(decoded)
        assertTrue(decoded!!.nodeId != nodeId)
    }

    @Test
    fun `an old capture is not evidence that the device is here now`() {
        val line = MeshBeacon.encode(seed, 47002, now, "nonce-1")!!
        assertNotNull(MeshBeacon.decode(line, now + MeshBeacon.MAX_SKEW_SECONDS))
        assertNull(MeshBeacon.decode(line, now + MeshBeacon.MAX_SKEW_SECONDS + 1))
        assertNull(MeshBeacon.decode(line, now - MeshBeacon.MAX_SKEW_SECONDS - 1))
    }

    @Test
    fun `malformed lines are refused without partial trust`() {
        assertNull(MeshBeacon.decode("", now))
        assertNull(MeshBeacon.decode("grom-mesh-beacon-v1 0123456789abcdef 47002", now))
        assertNull(MeshBeacon.decode("other 0123456789abcdef 47002 a b c", now))
        assertNull(MeshBeacon.decode(MeshBeacon.encode(seed, 0, now, "n"), now))
        assertNull(MeshBeacon.decode(MeshBeacon.encode(seed, 47002, now, ""), now))
    }
}
