// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.http.Json
import app.grom.server.superserver.auth.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * What the mesh publishes, and to whom (D59, D99).
 *
 * The data-plane roster names servers and their state; it never names an
 * endpoint. Only the operator's Binder view may carry an address, because the
 * operator is the one who has to approve it.
 */
class MeshDocumentsTest {

    private val seed = Ed25519.generatePrivateSeed()
    private val key = Ed25519.derivePublicKey(seed)
    private val nodeId = Ed25519.keyId(key)

    private val peer = PeerRecord(
        nodeId = nodeId,
        publicKey = Base64.getEncoder().encodeToString(key),
        label = "Phone B",
        address = "http://192.168.1.20:47002",
        grantedByMe = setOf(PeerScope.SERVICES_READ),
        grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
        pairedAtMs = 0L,
        lastProbeMs = 1_000L,
        lastProbeOk = true
    )

    private val catalog = MeshCatalog.parse(
        nodeId,
        """
        {
          "api": 1,
          "principal": {"kind": "peer", "nodeId": "$nodeId"},
          "services": {"listed": true, "items": [
            {"id": "agent", "title": "Agent", "capabilities": ["ping"], "scopedOps": ["ping"]}
          ]},
          "ai": {"enabled": true, "listed": true, "items": [{"id": "m1"}]}
        }
        """.trimIndent(),
        2_000L
    )

    @Test
    fun `the roster names servers and contains no endpoint`() {
        val servers = MeshDocuments.servers(listOf(peer), mapOf(nodeId to catalog), emptyList(), 2_000L)
        assertEquals(1, servers.size)
        val encoded = Json.encode(servers.first())
        assertFalse(encoded.contains("192.168.1.20"))
        assertFalse(encoded.contains("\"address\""))
        assertEquals("alive", servers.first()["state"])
        assertEquals(true, servers.first()["servicesListed"])
        assertEquals(true, (servers.first()["ai"] as Map<*, *>)["enabled"])
    }

    @Test
    fun `nearby addresses are only published to the operator view`() {
        val candidate = MeshCandidate(
            key = "id:$nodeId",
            nodeId = nodeId,
            nodeIdPrefix = nodeId.substring(0, 8),
            publicKey = null,
            label = null,
            transport = MeshTransport.LAN,
            address = "http://192.168.1.20:47002",
            port = 47002,
            signedBeacon = true,
            firstSeenMs = 1_000L,
            lastSeenMs = 2_000L
        )
        val operatorView = Json.encode(MeshDocuments.nearby(listOf(candidate), includeAddress = true))
        assertTrue(operatorView.contains("192.168.1.20"))

        val sharedView = Json.encode(MeshDocuments.nearby(listOf(candidate), includeAddress = false))
        assertFalse(sharedView.contains("192.168.1.20"))
        assertFalse(sharedView.contains("\"address\""))
        assertTrue(sharedView.contains(nodeId))
    }

    @Test
    fun `the status summary is counts only`() {
        val summary = MeshDocuments.summary(MeshSettings(enabled = true), servers = 2, near = 3)
        assertEquals(2, summary["servers"])
        assertEquals(3, summary["nearby"])
        assertEquals(true, summary["enabled"])
        val encoded = Json.encode(summary)
        assertFalse(encoded.contains("nodeId"))
        assertFalse(encoded.contains("192.168"))
    }
}
