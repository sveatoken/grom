// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Server-to-server calls (D100).
 *
 * Every refusal that can be decided locally is decided before a socket is
 * opened: a missing grant, an operation the peer never published and an
 * unknown service all cost zero requests. The requests that do go out are
 * verified byte-for-byte against the server's own canonical string.
 */
class MeshCallerTest {

    private val localSeed = Ed25519.generatePrivateSeed()
    private val remoteSeed = Ed25519.generatePrivateSeed()
    private val remoteKey = Ed25519.derivePublicKey(remoteSeed)
    private val remoteId = Ed25519.keyId(remoteKey)
    private val nowSeconds = 1_700_000_000L

    private class Recorded(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?
    )

    private class FakeTransport : JsonTransport {
        val calls = ArrayList<Recorded>()
        var answer: (Recorded) -> HttpJsonResponse = { HttpJsonResponse(404, "{}") }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            val recorded = Recorded(method, url, headers, body)
            calls.add(recorded)
            return answer(recorded)
        }
    }

    private fun peer(
        address: String = "http://127.0.0.1:47002",
        byPeer: Set<PeerScope> = setOf(
            PeerScope.SERVICES_READ,
            PeerScope.SERVICES_CALL,
            PeerScope.AI_READ,
            PeerScope.AI_CHAT
        )
    ) = PeerRecord(
        nodeId = remoteId,
        publicKey = Base64.getEncoder().encodeToString(remoteKey),
        label = "Phone B",
        address = address,
        grantedByMe = setOf(PeerScope.STATUS_READ),
        grantedByPeer = byPeer,
        pairedAtMs = 0L
    )

    private fun caller(transport: FakeTransport) = MeshCaller(
        transport = transport,
        clockSeconds = { nowSeconds },
        timeoutMs = 1_000
    )

    private val connect = """
        {
          "api": 1,
          "principal": {"kind": "peer", "nodeId": "$remoteId"},
          "services": {"listed": true, "items": [
            {"id": "agent", "title": "Agent", "capabilities": ["ping"], "scopedOps": ["ping"]}
          ]},
          "ai": {"enabled": true, "listed": true, "items": [{"id": "m1"}]}
        }
    """.trimIndent()

    private fun catalog() = MeshCatalog.parse(remoteId, connect, nowSeconds * 1000)

    private fun assertSignature(recorded: Recorded, path: String) {
        val timestamp = recorded.headers[RequestSigner.HEADER_TIMESTAMP]
        val nonce = recorded.headers[RequestSigner.HEADER_NONCE]
        val signature = recorded.headers[RequestSigner.HEADER_SIGNATURE]
        assertNotNull(timestamp)
        assertNotNull(nonce)
        assertNotNull(signature)
        val canonical = RequestSigner.canonicalString(
            recorded.method,
            path,
            recorded.body.orEmpty(),
            timestamp!!,
            nonce!!
        )
        assertTrue(
            Ed25519.verify(
                Ed25519.derivePublicKey(localSeed),
                canonical.toByteArray(),
                Base64.getDecoder().decode(signature)
            )
        )
        assertEquals(Ed25519.keyId(Ed25519.derivePublicKey(localSeed)), recorded.headers[RequestSigner.HEADER_KEY])
    }

    // ---------------------------------------------------------------- catalogue

    @Test
    fun `a catalogue fetch without services read never opens a socket`() {
        val transport = FakeTransport()
        val reply = caller(transport)
            .fetchCatalog(peer(byPeer = setOf(PeerScope.STATUS_READ)), localSeed)
        assertEquals("MESH_SCOPE_NOT_GRANTED", reply.error)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `a catalogue fetch is a signed GET of the connect document`() {
        val transport = FakeTransport()
        transport.answer = { HttpJsonResponse(200, connect) }
        val reply = caller(transport).fetchCatalog(peer(), localSeed)
        assertEquals(null, reply.error)
        assertTrue(reply.listed)
        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("http://127.0.0.1:47002/grom/v1/connect", call.url)
        assertSignature(call, "/grom/v1/connect")
    }

    // ------------------------------------------------------------------- calls

    @Test
    fun `a service call is signed and carries the op and args`() {
        val transport = FakeTransport()
        transport.answer = { HttpJsonResponse(200, """{"ok":true,"result":{"pong":true}}""") }
        val reply = caller(transport).call(
            peer(), catalog(), localSeed, "agent", "ping", mapOf("x" to 1)
        )
        assertTrue(reply is MeshCaller.Reply.Json)
        val answer = reply as MeshCaller.Reply.Json
        assertEquals(200, answer.status)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("http://127.0.0.1:47002/v1/plugins/agent/rpc", call.url)
        val body = JsonParser.parseObjectOrNull(call.body.orEmpty())
        assertEquals("ping", body?.get("op"))
        assertSignature(call, "/v1/plugins/agent/rpc")
    }

    @Test
    fun `a missing services call grant costs no request`() {
        val transport = FakeTransport()
        val reply = caller(transport).call(
            peer(byPeer = setOf(PeerScope.SERVICES_READ)), catalog(), localSeed, "agent", "ping"
        )
        assertEquals("MESH_SCOPE_NOT_GRANTED", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `an operation the peer did not publish is refused locally`() {
        val transport = FakeTransport()
        val reply = caller(transport).call(peer(), catalog(), localSeed, "agent", "status")
        assertEquals("MESH_OP_NOT_ALLOWED", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `a service that is not in the peer's catalogue is refused locally`() {
        val transport = FakeTransport()
        val reply = caller(transport).call(peer(), catalog(), localSeed, "other", "ping")
        assertEquals("MESH_UNKNOWN_SERVICE", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `an inbound-only peer is never dialled`() {
        val transport = FakeTransport()
        val reply = caller(transport).call(
            peer(address = ""), catalog(), localSeed, "agent", "ping"
        )
        assertEquals("MESH_NO_ADDRESS", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    // ---------------------------------------------------------------------- AI

    @Test
    fun `the AI catalogue is a signed GET and chat is a signed POST`() {
        val transport = FakeTransport()
        transport.answer = { HttpJsonResponse(200, """{"ok":true}""") }
        caller(transport).call(peer(), catalog(), localSeed, "ai", "models")
        caller(transport).call(
            peer(), catalog(), localSeed, "ai", "chat", aiBody = mapOf("model" to "m1")
        )
        assertEquals(2, transport.calls.size)
        assertEquals("GET", transport.calls[0].method)
        assertEquals("http://127.0.0.1:47002/v1/ai/models", transport.calls[0].url)
        assertEquals("POST", transport.calls[1].method)
        assertEquals("http://127.0.0.1:47002/v1/ai/chat/completions", transport.calls[1].url)
        assertSignature(transport.calls[1], "/v1/ai/chat/completions")
    }

    @Test
    fun `an AI call needs the AI scope, not services call`() {
        val transport = FakeTransport()
        val reply = caller(transport).call(
            peer(byPeer = setOf(PeerScope.SERVICES_CALL)), catalog(), localSeed, "ai", "chat"
        )
        assertEquals("MESH_SCOPE_NOT_GRANTED", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    // --------------------------------------------------------------- failures

    @Test
    fun `the peer's own refusals are mapped to stable codes`() {
        val transport = FakeTransport()
        transport.answer = { HttpJsonResponse(401, "{}") }
        val unauthorized = caller(transport).call(peer(), catalog(), localSeed, "agent", "ping")
        assertEquals("MESH_UNAUTHORIZED", (unauthorized as MeshCaller.Reply.Failed).code)

        transport.answer = { HttpJsonResponse(403, "{}") }
        val scoped = caller(transport).call(peer(), catalog(), localSeed, "agent", "ping")
        assertEquals("MESH_SCOPE_NOT_GRANTED", (scoped as MeshCaller.Reply.Failed).code)

        // A specific refusal from the peer is preserved rather than flattened.
        transport.answer = {
            HttpJsonResponse(
                403,
                """{"ok":false,"error":{"code":"SERVICE_NOT_SHARED","message":"closed"}}"""
            )
        }
        val unshared = caller(transport).call(peer(), catalog(), localSeed, "agent", "ping")
        assertEquals("SERVICE_NOT_SHARED", (unshared as MeshCaller.Reply.Failed).code)

        transport.answer = { throw TransportException("down") }
        val down = caller(transport).call(peer(), catalog(), localSeed, "agent", "ping")
        assertEquals("MESH_UNREACHABLE", (down as MeshCaller.Reply.Failed).code)
        // The message never carries the address.
        assertTrue(!down.message.contains("127.0.0.1"))
    }

    @Test
    fun `an oversized relay is refused before the socket`() {
        val transport = FakeTransport()
        val big = MeshCaller(transport = transport, maxBodyBytes = 10)
        val reply = big.call(peer(), catalog(), localSeed, "agent", "ping", mapOf("x" to "y".repeat(64)))
        assertEquals("MESH_PAYLOAD_TOO_LARGE", (reply as MeshCaller.Reply.Failed).code)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `the signature is over the exact path and body the server verifies`() {
        val transport = FakeTransport()
        transport.answer = { HttpJsonResponse(200, Json.encode(mapOf("ok" to true))) }
        caller(transport).call(peer(), catalog(), localSeed, "agent", "ping")
        val call = transport.calls.single()
        val path = call.url.substringAfter("127.0.0.1:47002")
        assertSignature(call, path)
    }
}
