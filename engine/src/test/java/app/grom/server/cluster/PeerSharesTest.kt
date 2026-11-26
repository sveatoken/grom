// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.SuperAuth
import app.grom.server.superserver.auth.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Per-peer service shares (D101) and the Bluetooth endpoint spelling (D102).
 *
 * An empty share list is the compatibility default: everything the peer's
 * scopes already allow. An explicit list is a hard allow-list, persisted with
 * the record and read by the principal on the very next request.
 */
class PeerSharesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "s:" + plaintext

        override fun open(sealed: String): String? =
            sealed.removePrefix("s:").takeIf { sealed.startsWith("s:") }
    }

    private val seed = Ed25519.generatePrivateSeed()
    private val key = Ed25519.derivePublicKey(seed)
    private val nodeId = Ed25519.keyId(key)

    private fun record(services: Set<String> = emptySet()) = PeerRecord(
        nodeId = nodeId,
        publicKey = Base64.getEncoder().encodeToString(key),
        label = "Phone B",
        address = "http://192.168.1.20:47002",
        grantedByMe = setOf(PeerScope.STATUS_READ),
        grantedByPeer = setOf(PeerScope.SERVICES_READ),
        services = services,
        pairedAtMs = 1L
    )

    @Test
    fun `a share list is stored, reloaded and read by the principal`() {
        val file = File(temp.root, PeerStore.FILE_NAME)
        val store = PeerStore(file)
        store.add(record())
        store.setServices(nodeId, setOf("agent", "echo"))

        val reloaded = PeerStore(file).find(nodeId)
        assertEquals(setOf("agent", "echo"), reloaded!!.services)
        assertEquals(listOf("agent", "echo"), reloaded.toJson(2L, includeAddress = true)["services"])

        val identityStore = NodeIdentityStore(File(temp.root, NodeIdentityStore.FILE_NAME), Box())
        val fleet = FleetService(identityStore = identityStore, store = PeerStore(file))
        val principal = fleet.principalOf(nodeId)
        assertTrue(principal is SuperAuth.Principal.Peer)
        assertEquals(setOf("agent", "echo"), (principal as SuperAuth.Principal.Peer).services)
    }

    @Test
    fun `an empty list is the compatibility default, not a wildcard grant`() {
        val store = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        store.add(record(services = setOf("agent")))
        store.setServices(nodeId, emptySet())
        assertEquals(emptySet<String>(), store.find(nodeId)!!.services)
        // The router treats empty as "what the scopes allow"; the record itself
        // does not claim any service was shared.
        assertEquals(false, store.find(nodeId)!!.services.contains("agent"))
    }

    @Test
    fun `a malformed service id is refused and nothing is written`() {
        val store = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        store.add(record())
        val failure = runCatching { store.setServices(nodeId, setOf("Agent!")) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("BAD_SERVICE_LIST", (failure as ApiException).code)
        assertEquals(emptySet<String>(), store.find(nodeId)!!.services)
    }

    @Test
    fun `an unknown node is a stable not-found`() {
        val store = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        val failure = runCatching { store.setServices("ffffffffffffffff", setOf("agent")) }
            .exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("PEER_NOT_FOUND", (failure as ApiException).code)
    }

    @Test
    fun `a record survives a json round trip and a bad list is rejected`() {
        val original = record(services = setOf("agent"))
        val json = original.toJson(2L, includeAddress = true)
        assertEquals(original, PeerRecord.fromJson(json))

        val corrupted = json + ("services" to listOf("Not A Service"))
        assertNull(PeerRecord.fromJson(corrupted))
        assertTrue(JsonParser.parseObjectOrNull("{}") != null)
    }

    @Test
    fun `a bonded bluetooth address is canonical and is a peer endpoint`() {
        assertEquals("bt://AA:BB:CC:DD:EE:FF", PeerAddress.normalize("bt://aa:bb:cc:dd:ee:ff"))
        assertEquals("bt://AA:BB:CC:DD:EE:FF", PeerAddress.normalize(" BT://AA:BB:CC:DD:EE:FF "))
        assertNull(PeerAddress.normalize("bt://aa:bb:cc:dd:ee"))
        assertNull(PeerAddress.normalize("bt://AA:BB:CC:DD:EE:FF:47002"))
        assertNull(PeerAddress.normalize("bt://AA:BB:CC:DD:EE:FF/admin"))
    }
}
