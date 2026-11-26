// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope
import app.grom.server.http.HttpRequest
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.NonceCache
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The mesh routes and the per-peer service filter on the data plane (D99-D101).
 *
 * The router is a pure function over an [HttpRequest]; no socket is opened.
 * The tests pin the two properties that make the relay safe: it needs its own
 * scopes (never `services.call` or an operator key), and a peer's explicit
 * service allow-list hides the service as well as refusing it.
 */
class MeshRouterTest {

    private val now = 1_700_000_000L
    private val peerSeed = Ed25519.generatePrivateSeed()
    private val peerKey = Ed25519.derivePublicKey(peerSeed)
    private val peerId = Ed25519.keyId(peerKey)
    private val operatorSeed = Ed25519.generatePrivateSeed()
    private val operatorKey = Ed25519.derivePublicKey(operatorSeed)
    private val operatorId = Ed25519.keyId(operatorKey)

    private class EchoPlugin : Plugin {
        override val id: String = "echo"
        override val title: String = "Echo"
        override val description: String = "Echoes"
        override fun capabilities(): Set<String> = setOf("ping")
        override fun scopedOps(): Set<String> = setOf("ping")
        override fun status(): Map<String, Any?> = emptyMap()
        override fun rpc(op: String, args: Map<String, Any?>): PluginResult =
            PluginResult.Ok(mapOf("op" to op))
    }

    private class FakeMesh : MeshPort {
        var calls = 0
        override fun document(): Map<String, Any?> =
            mapOf("enabled" to true, "servers" to emptyList<Any?>())
        override fun refresh(): Map<String, Any?> = mapOf("ok" to true)
        override fun call(body: String, client: String): MeshAnswer {
            calls += 1
            return MeshAnswer(200, mapOf("ok" to true, "server" to "b"))
        }
        override fun summary(): Map<String, Any?> =
            mapOf("enabled" to true, "servers" to 1, "nearby" to 0)
    }

    private val mesh = FakeMesh()

    private fun router(
        scopes: Set<PeerScope>,
        services: Set<String> = emptySet()
    ): SuperRouter {
        val config = SuperServerConfig.defaults().copy(auth = AuthMode.KEY)
        val keys = mapOf(peerId to peerKey, operatorId to operatorKey)
        val signer = RequestSigner(
            keyLookup = { keys[it] },
            clock = { now },
            nonceCache = NonceCache(clock = { now * 1_000 })
        )
        val auth = SuperAuth(
            config = config,
            passwords = null,
            sessions = SessionStore(),
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer,
            principalOf = { id ->
                if (id == peerId) SuperAuth.Principal.Peer(peerId, scopes, services)
                else SuperAuth.Principal.Operator
            }
        )
        return SuperRouter(
            config = config,
            auth = auth,
            plugins = PluginRegistry(listOf(EchoPlugin())),
            events = EventBus(),
            uptimeMs = { 1L },
            serverStatus = { emptyMap() },
            mesh = mesh
        )
    }

    private fun signed(
        seed: ByteArray,
        path: String,
        nonce: String,
        method: String = "GET",
        body: String = ""
    ): HttpRequest {
        val key = Ed25519.derivePublicKey(seed)
        val canonical = RequestSigner.canonicalString(method, path, body, now.toString(), nonce)
        val headers = mapOf(
            RequestSigner.HEADER_KEY to Ed25519.keyId(key),
            RequestSigner.HEADER_TIMESTAMP to now.toString(),
            RequestSigner.HEADER_NONCE to nonce,
            RequestSigner.HEADER_SIGNATURE to Base64.getEncoder()
                .encodeToString(Ed25519.sign(seed, canonical.toByteArray()))
        )
        return HttpRequest(method, path, emptyMap(), headers, "HTTP/1.1", body)
    }

    private fun body(response: SuperResponse): Map<String, Any?> {
        val json = response as SuperResponse.Json
        return JsonParser.parseObjectOrNull(json.body) ?: emptyMap()
    }

    private fun code(response: SuperResponse): String {
        val error = body(response)["error"] as? Map<*, *> ?: return ""
        return error["code"] as? String ?: ""
    }

    @Test
    fun `the mesh document needs mesh read and carries no endpoint`() {
        val denied = router(setOf(PeerScope.STATUS_READ))
            .handle(signed(peerSeed, "/v1/mesh", "nonce-001"), "peer")
        assertEquals(403, (denied as SuperResponse.Json).status)
        assertEquals("SCOPE_REQUIRED", code(denied))

        val allowed = router(setOf(PeerScope.MESH_READ))
            .handle(signed(peerSeed, "/v1/mesh", "nonce-002"), "peer")
        assertEquals(200, (allowed as SuperResponse.Json).status)
    }

    @Test
    fun `the relay needs mesh call, not services call`() {
        val servicesOnly = router(setOf(PeerScope.SERVICES_CALL, PeerScope.SERVICES_READ))
            .handle(
                signed(peerSeed, "/v1/mesh/call", "nonce-003", "POST", """{"server":"x"}"""),
                "peer"
            )
        assertEquals(403, (servicesOnly as SuperResponse.Json).status)
        assertEquals(0, mesh.calls)

        val relay = router(setOf(PeerScope.MESH_CALL))
            .handle(
                signed(peerSeed, "/v1/mesh/call", "nonce-004", "POST", """{"server":"x"}"""),
                "peer"
            )
        assertEquals(200, (relay as SuperResponse.Json).status)
        assertEquals(1, mesh.calls)
    }

    @Test
    fun `an explicit service allow-list hides and refuses the other services`() {
        val denied = router(
            setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
            services = setOf("other")
        ).handle(signed(peerSeed, "/v1/plugins/echo/rpc", "nonce-005", "POST", """{"op":"ping"}"""), "peer")
        assertEquals(403, (denied as SuperResponse.Json).status)
        assertEquals("SERVICE_NOT_SHARED", code(denied))

        val allowed = router(
            setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
            services = setOf("echo")
        ).handle(signed(peerSeed, "/v1/plugins/echo/rpc", "nonce-006", "POST", """{"op":"ping"}"""), "peer")
        assertEquals(200, (allowed as SuperResponse.Json).status)

        // The listing is filtered too, so a peer cannot even see an unshared
        // service's description.
        val hidden = router(
            setOf(PeerScope.SERVICES_READ),
            services = setOf("other")
        ).handle(signed(peerSeed, "/v1/plugins", "nonce-007"), "peer")
        assertEquals(200, (hidden as SuperResponse.Json).status)
        assertEquals(0, (body(hidden)["plugins"] as List<*>).size)
    }

    @Test
    fun `the connect document publishes the mesh shape without the roster`() {
        val response = router(setOf(PeerScope.MESH_READ))
            .handle(signed(peerSeed, "/grom/v1/connect", "nonce-008"), "peer")
        assertEquals(200, (response as SuperResponse.Json).status)
        val document = body(response)
        val mesh = document["mesh"] as Map<*, *>
        assertEquals("/v1/mesh", mesh["path"])
        assertEquals("/v1/mesh/call", mesh["call"])
        assertEquals(true, mesh["enabled"])
        assertFalse(document.toString().contains("\"servers\""))
        val endpoints = document["endpoints"] as Map<*, *>
        assertEquals("/v1/mesh", endpoints["mesh"])
    }

    @Test
    fun `a status block reports counts only`() {
        val response = router(setOf(PeerScope.STATUS_READ))
            .handle(signed(peerSeed, "/v1/status", "nonce-009"), "peer")
        assertEquals(200, (response as SuperResponse.Json).status)
        val block = body(response)["mesh"] as Map<*, *>
        assertEquals(true, block["enabled"])
        assertTrue(block.containsKey("servers"))
        assertFalse(block.containsKey("nodeId"))
        assertFalse(block.containsKey("address"))
    }
}
