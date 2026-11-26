// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.SuperAuth
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * The one-way scoped grant (D76-D78).
 *
 * A hub on the operator's VPS only ever calls in: the operator pastes its
 * public key, chooses scopes, and the engine derives the key id. The resulting
 * record has no address, is never dialled, and gives the caller a scope-limited
 * peer — never an operator.
 */
class PeerGrantTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    /** Counts every request so "never dialled" is a fact, not an intention. */
    private class CountingTransport : JsonTransport {
        var calls = 0

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            calls += 1
            return HttpJsonResponse(200, "{}")
        }
    }

    private fun service(transport: JsonTransport = CountingTransport()): Triple<FleetService, PeerStore, NodeIdentityStore> {
        val identityStore = NodeIdentityStore(
            File(temp.root, NodeIdentityStore.FILE_NAME),
            ReversingBox()
        )
        val peerStore = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        val fleet = FleetService(
            identityStore = identityStore,
            store = peerStore,
            client = PeerClient(transport = transport),
            defaultLabel = { "This phone" }
        )
        return Triple(fleet, peerStore, identityStore)
    }

    private fun readerKey(): Pair<String, String> {
        val publicKey = Ed25519.derivePublicKey(Ed25519.generatePrivateSeed())
        return Base64.getEncoder().encodeToString(publicKey) to Ed25519.keyId(publicKey)
    }

    @Test
    fun `a granted key keeps only the scopes it was given`() {
        val (fleet, store, _) = service()
        val (publicKey, nodeId) = readerKey()

        val granted = JsonParser.parseObjectOrNull(
            fleet.grantJson(
                """{"publicKey":"$publicKey","label":"home-vps hub","scopes":["status.read"]}"""
            )
        )
        assertEquals(true, granted?.get("ok"))
        val peer = granted?.get("peer") as Map<*, *>
        assertEquals(nodeId, peer["nodeId"])
        assertEquals("home-vps hub", peer["label"])
        assertFalse("a one-way grant has no address", peer.containsKey("address"))

        val stored = store.find(nodeId)
        assertNotNull(stored)
        assertEquals("", stored?.address)
        assertTrue(stored?.isInboundOnly() == true)
        assertEquals(setOf(PeerScope.STATUS_READ), stored?.grantedByMe)
        assertEquals(emptySet<PeerScope>(), stored?.grantedByPeer)

        // The caller is a peer with exactly that scope, never an operator.
        val principal = fleet.principalOf(nodeId)
        assertTrue(principal is SuperAuth.Principal.Peer)
        assertEquals(
            setOf(PeerScope.STATUS_READ),
            (principal as SuperAuth.Principal.Peer).scopes
        )
        assertEquals(SuperAuth.Principal.Operator, fleet.principalOf("operator-key"))
    }

    @Test
    fun `the node id is derived from the key and a false claim is refused`() {
        val (fleet, store, _) = service()
        val (publicKey, nodeId) = readerKey()

        val lie = runCatching {
            fleet.grantJson(
                """{"publicKey":"$publicKey","nodeId":"0000000000000000","scopes":["status.read"]}"""
            )
        }.exceptionOrNull()
        assertEquals("BAD_PUBLIC_KEY", (lie as ApiException).code)
        assertNull(store.find(nodeId))

        // The honest id is accepted and identical to the derived one.
        fleet.grantJson("""{"publicKey":"$publicKey","nodeId":"$nodeId","scopes":["status.read"]}""")
        assertNotNull(store.find(nodeId))
    }

    @Test
    fun `an empty scope list and a malformed key are refused`() {
        val (fleet, store, _) = service()
        val (publicKey, _) = readerKey()

        val noScope = runCatching {
            fleet.grantJson("""{"publicKey":"$publicKey","scopes":[]}""")
        }.exceptionOrNull()
        assertEquals("SCOPE_REQUIRED", (noScope as ApiException).code)

        val shortKey = runCatching {
            fleet.grantJson("""{"publicKey":"AAAA","scopes":["status.read"]}""")
        }.exceptionOrNull()
        assertEquals("BAD_PUBLIC_KEY", (shortKey as ApiException).code)

        val unknownScope = runCatching {
            fleet.grantJson("""{"publicKey":"$publicKey","scopes":["root"]}""")
        }.exceptionOrNull()
        assertEquals("BAD_SCOPE", (unknownScope as ApiException).code)

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `this device cannot trust its own key`() {
        val (fleet, _, identityStore) = service()
        val self = identityStore.loadOrCreate("This phone")
        val own = runCatching {
            fleet.grantJson("""{"publicKey":"${self.publicKey}","scopes":["status.read"]}""")
        }.exceptionOrNull()
        assertEquals("PEER_SELF", (own as ApiException).code)
    }

    @Test
    fun `granting scopes to an already paired server keeps its endpoint`() {
        val (fleet, store, _) = service()
        val (publicKey, nodeId) = readerKey()
        val paired = PeerRecord(
            nodeId = nodeId,
            publicKey = publicKey,
            label = "Phone B",
            address = "http://192.168.1.20:47002",
            grantedByMe = setOf(PeerScope.STATUS_READ),
            grantedByPeer = setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ),
            pairedAtMs = 42L
        )
        store.add(paired)

        fleet.grantJson(
            """{"publicKey":"$publicKey","label":"Phone B","scopes":["status.read","services.read","services.call"]}"""
        )

        val updated = store.find(nodeId)
        assertNotNull(updated)
        // The server network needs this: a grant that forgot the address would
        // turn a paired server into an inbound-only one that can never be
        // called again, while the screen still showed it as paired (D104).
        assertEquals("http://192.168.1.20:47002", updated?.address)
        assertFalse(updated?.isInboundOnly() == true)
        assertEquals(
            setOf(PeerScope.STATUS_READ, PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
            updated?.grantedByMe
        )
        // What the other device granted is not this device's to rewrite.
        assertEquals(
            setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ),
            updated?.grantedByPeer
        )
        assertEquals(42L, updated?.pairedAtMs)
    }

    @Test
    fun `a re-grant replaces the previous scopes`() {
        val (fleet, store, _) = service()
        val (publicKey, nodeId) = readerKey()

        fleet.grantJson("""{"publicKey":"$publicKey","label":"hub","scopes":["status.read"]}""")
        fleet.grantJson(
            """{"publicKey":"$publicKey","label":"hub v2","scopes":["status.read","config.read"]}"""
        )
        assertEquals(1, store.list().size)
        assertEquals("hub v2", store.find(nodeId)?.label)
        assertEquals(
            setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ),
            store.find(nodeId)?.grantedByMe
        )
    }

    @Test
    fun `refresh never dials an inbound-only grant`() {
        val transport = CountingTransport()
        val (fleet, store, _) = service(transport)
        val (publicKey, nodeId) = readerKey()
        fleet.grantJson("""{"publicKey":"$publicKey","scopes":["status.read"]}""")

        val report = JsonParser.parseObjectOrNull(fleet.refreshJson())!!
        assertEquals(0, transport.calls)
        assertEquals(0, (report["refreshed"] as Number).toInt())
        assertEquals(1, (report["inboundOnly"] as Number).toInt())
        // Nothing was measured, so the state stays "never checked" rather than
        // becoming a red lamp.
        assertEquals("unknown", store.find(nodeId)?.state(1L))
    }

    @Test
    fun `an inbound grant is absent from the public fleet block`() {
        val (fleet, _, _) = service()
        val (publicKey, _) = readerKey()
        fleet.grantJson("""{"publicKey":"$publicKey","label":"hub","scopes":["status.read"]}""")

        // The status block names this device only; a grant never adds a peer,
        // an address or a key (D59).
        val status = FleetStatus.document(fleet.identityOrNull())
        assertEquals(setOf("nodeId", "label"), status.keys)
        assertFalse(status.containsKey("peers"))
    }
}
