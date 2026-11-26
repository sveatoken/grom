// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.Ed25519
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
 * Mutual pairing (D71, D73).
 *
 * These tests play the joining device with real Ed25519 keys: a code alone is
 * never enough, a wrong code is counted, the operator's approval is signed, and
 * nothing is stored until approval.
 */
class PairingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    private val box = ReversingBox()

    private fun identityStore() = NodeIdentityStore(
        File(temp.root, NodeIdentityStore.FILE_NAME),
        box
    )

    private fun peers() = PeerStore(File(temp.root, PeerStore.FILE_NAME))

    private fun endpoint(
        identity: NodeIdentityStore,
        peers: PeerStore,
        now: () -> Long = { 1_000L }
    ) = PairingEndpoint(
        identity = { identity.loadOrCreate("Phone A") },
        seed = { identity.seed() },
        peers = peers,
        clock = now
    )

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a joining device with the code is approved and stored with its scopes`() {
        val identity = identityStore()
        val peers = peers()
        val endpoint = endpoint(identity, peers)
        val opened = endpoint.start()
        val code = opened["code"] as String
        assertTrue(PairingProtocol.CODE_PATTERN.matches(code))

        val self = identity.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val codeHash = PairingProtocol.computeCodeHash(code, nodeB)
        val hello = PairingProtocol.helloString(self.nodeId, nodeB, codeHash)

        val answer = endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to codeHash,
                "scopes" to listOf("status.read", "config.read"),
                "signature" to b64(Ed25519.sign(seedB, hello.toByteArray()))
            ),
            "127.0.0.1:5555"
        )
        assertEquals(200, answer.status)
        val pendingId = answer.payload["pendingId"] as String

        val pending = endpoint.pendingDocument()
        assertEquals(nodeB, pending["nodeId"])
        assertEquals(listOf("config.read", "status.read"), pending["requestedScopes"])

        val decision = endpoint.decide(true, setOf(PeerScope.STATUS_READ))
        assertEquals("accepted", decision["state"])

        val stored = peers.find(nodeB)
        assertNotNull(stored)
        assertEquals("http://127.0.0.1:47002", stored?.address)
        assertEquals(setOf(PeerScope.STATUS_READ), stored?.grantedByMe)
        assertEquals(setOf(PeerScope.STATUS_READ), stored?.grantedByPeer)

        val polled = endpoint.poll(mapOf("pendingId" to pendingId))
        assertEquals(200, polled.status)
        assertEquals("accepted", polled.payload["state"])
        val accept = PairingProtocol.acceptString(
            self.nodeId,
            nodeB,
            codeHash,
            listOf("status.read")
        )
        val signature = Base64.getDecoder().decode(polled.payload["signature"] as String)
        assertTrue(Ed25519.verify(publicB, accept.toByteArray(), signature).not())
        val publicA = Base64.getDecoder().decode(self.publicKey)
        assertTrue(Ed25519.verify(publicA, accept.toByteArray(), signature))
    }

    @Test
    fun `rejecting stores nothing and the joiner is told`() {
        val identity = identityStore()
        val peers = peers()
        val endpoint = endpoint(identity, peers)
        val code = endpoint.start()["code"] as String
        val self = identity.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val codeHash = PairingProtocol.computeCodeHash(code, nodeB)
        val answer = endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to codeHash,
                "scopes" to listOf("status.read"),
                "signature" to b64(
                    Ed25519.sign(
                        seedB,
                        PairingProtocol.helloString(self.nodeId, nodeB, codeHash).toByteArray()
                    )
                )
            ),
            "127.0.0.1:5555"
        )
        val pendingId = answer.payload["pendingId"] as String
        endpoint.decide(false, null)

        assertEquals("rejected", endpoint.poll(mapOf("pendingId" to pendingId)).payload["state"])
        assertTrue(peers.list().isEmpty())
    }

    @Test
    fun `a wrong code is refused and five attempts close the session`() {
        val identity = identityStore()
        val endpoint = endpoint(identity, peers())
        endpoint.start()
        val self = identity.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val wrongHash = PairingProtocol.computeCodeHash("ABCDEFGH", nodeB)

        for (attempt in 1..4) {
            val answer = endpoint.hello(
                linkedMapOf(
                    "nodeId" to nodeB,
                    "publicKey" to b64(publicB),
                    "label" to "Phone B",
                    "port" to 47002,
                    "codeHash" to wrongHash,
                    "scopes" to listOf("status.read"),
                    "signature" to b64(
                        Ed25519.sign(
                            seedB,
                            PairingProtocol.helloString(self.nodeId, nodeB, wrongHash)
                                .toByteArray()
                        )
                    )
                ),
                "127.0.0.1:5555"
            )
            assertEquals(401, answer.status)
            assertEquals("PAIR_BAD_CODE", (answer.payload["error"] as Map<*, *>)["code"])
            assertEquals(true, endpoint.identityDocument() != null)
        }
        // The fifth wrong attempt closes the session; even the identity route
        // disappears, so nothing pair-related is left reachable.
        endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to wrongHash,
                "scopes" to listOf("status.read"),
                "signature" to b64(
                    Ed25519.sign(
                        seedB,
                        PairingProtocol.helloString(self.nodeId, nodeB, wrongHash).toByteArray()
                    )
                )
            ),
            "127.0.0.1:5555"
        )
        assertNull(endpoint.identityDocument())
    }

    @Test
    fun `a device cannot pair with itself and a forged hello is refused`() {
        val identity = identityStore()
        val endpoint = endpoint(identity, peers())
        val code = endpoint.start()["code"] as String
        val self = identity.loadOrCreate("Phone A")
        val codeHash = PairingProtocol.computeCodeHash(code, self.nodeId)
        val signature = Ed25519.sign(
            identity.seed()!!,
            PairingProtocol.helloString(self.nodeId, self.nodeId, codeHash).toByteArray()
        )
        val selfAnswer = endpoint.hello(
            linkedMapOf(
                "nodeId" to self.nodeId,
                "publicKey" to self.publicKey,
                "label" to "Phone A",
                "port" to 47002,
                "codeHash" to codeHash,
                "scopes" to listOf("status.read"),
                "signature" to b64(signature)
            ),
            "127.0.0.1:5555"
        )
        assertEquals(401, selfAnswer.status)
        assertEquals("PAIR_SELF", (selfAnswer.payload["error"] as Map<*, *>)["code"])

        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val hashB = PairingProtocol.computeCodeHash(code, nodeB)
        val forged = endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to hashB,
                "scopes" to listOf("status.read"),
                // Signed with the wrong key.
                "signature" to b64(Ed25519.sign(seedB, "not the transcript".toByteArray()))
            ),
            "127.0.0.1:5555"
        )
        assertEquals(401, forged.status)
        assertEquals("PAIR_BAD_SIGNATURE", (forged.payload["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `a closed session exposes no identity`() {
        val endpoint = endpoint(identityStore(), peers())
        assertNull(endpoint.identityDocument())
        endpoint.start()
        assertNotNull(endpoint.identityDocument())
    }

    @Test
    fun `the code hash is bound to the joining device`() {
        val first = PairingProtocol.computeCodeHash("ABCDEFGH", "0123456789abcdef")
        val second = PairingProtocol.computeCodeHash("ABCDEFGH", "fedcba9876543210")
        assertFalse(first == second)
        assertTrue(PairingProtocol.CODE_HASH_PATTERN.matches(first))
    }

    @Test
    fun `a session expires and stops accepting codes`() {
        var now = 1_000L
        val session = PairingSession(clock = { now })
        val opened = session.open()
        assertTrue(session.isOpen())
        now = opened.expiresAtMs
        assertFalse(session.isOpen())
        val verdict = session.verifyHash(
            PairingProtocol.computeCodeHash(opened.code, "0123456789abcdef"),
            "0123456789abcdef"
        )
        assertEquals(PairingSession.Verify.Expired, verdict)
    }

    @Test
    fun `approval must be a non-empty subset of what was requested`() {
        val identity = identityStore()
        val endpoint = endpoint(identity, peers())
        val code = endpoint.start()["code"] as String
        val self = identity.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val hash = PairingProtocol.computeCodeHash(code, nodeB)
        endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to hash,
                "scopes" to listOf("status.read"),
                "signature" to b64(
                    Ed25519.sign(
                        seedB,
                        PairingProtocol.helloString(self.nodeId, nodeB, hash).toByteArray()
                    )
                )
            ),
            "127.0.0.1:5555"
        )
        val empty = runCatching { endpoint.decide(true, emptySet()) }.exceptionOrNull()
        assertEquals("SCOPE_REQUIRED", (empty as app.grom.server.http.ApiException).code)
        val extra = runCatching {
            endpoint.decide(true, setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ))
        }.exceptionOrNull()
        assertEquals("BAD_SCOPE", (extra as app.grom.server.http.ApiException).code)
    }

    @Test
    fun `a hello cannot ask for a local-client scope`() {
        val identity = identityStore()
        val endpoint = endpoint(identity, peers())
        val code = endpoint.start()["code"] as String
        val self = identity.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val hash = PairingProtocol.computeCodeHash(code, nodeB)
        val answer = endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to hash,
                // `mesh.call` is a grant this device gives its own clients, not
                // something another server can hold (D104).
                "scopes" to listOf("services.read", "mesh.call"),
                "signature" to b64(
                    Ed25519.sign(
                        seedB,
                        PairingProtocol.helloString(self.nodeId, nodeB, hash).toByteArray()
                    )
                )
            ),
            "127.0.0.1:5555"
        )
        assertEquals(400, answer.status)
        val error = answer.payload["error"] as Map<*, *>
        assertEquals("BAD_SCOPE", error["code"])
        assertTrue(endpoint.pendingDocument().isEmpty())

        // The rest of the peer-grantable set is accepted, so the mesh can ask
        // for the two service scopes it actually needs.
        val accepted = endpoint.hello(
            linkedMapOf(
                "nodeId" to nodeB,
                "publicKey" to b64(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to hash,
                "scopes" to listOf("status.read", "services.read", "services.call"),
                "signature" to b64(
                    Ed25519.sign(
                        seedB,
                        PairingProtocol.helloString(self.nodeId, nodeB, hash).toByteArray()
                    )
                )
            ),
            "127.0.0.1:5555"
        )
        assertEquals(200, accepted.status)
    }
}
