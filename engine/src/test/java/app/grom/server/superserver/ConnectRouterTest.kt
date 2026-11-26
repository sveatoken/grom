// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope
import app.grom.server.http.HttpRequest
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.NonceCache
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import app.grom.sdk.connect.GromConnectClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Client connect discovery and scoped service access (D91, D92).
 *
 * The router is a pure function over an [HttpRequest], so every rule is
 * asserted directly: no socket, no daemon and no flakiness.
 */
class ConnectRouterTest {

    private val now = 1_700_000_000L
    private val operatorSeed = Ed25519.generatePrivateSeed()
    private val operatorKey = Ed25519.derivePublicKey(operatorSeed)
    private val operatorId = Ed25519.keyId(operatorKey)

    private val servicesReadSeed = Ed25519.generatePrivateSeed()
    private val servicesReadKey = Ed25519.derivePublicKey(servicesReadSeed)
    private val servicesReadId = Ed25519.keyId(servicesReadKey)

    private val callerSeed = Ed25519.generatePrivateSeed()
    private val callerKey = Ed25519.derivePublicKey(callerSeed)
    private val callerId = Ed25519.keyId(callerKey)

    private val statusOnlySeed = Ed25519.generatePrivateSeed()
    private val statusOnlyKey = Ed25519.derivePublicKey(statusOnlySeed)
    private val statusOnlyId = Ed25519.keyId(statusOnlyKey)

    /** A service with a callable surface; `status` is deliberately not in it. */
    private class FakePlugin : Plugin {
        override val id: String = "agent"
        override val title: String = "Agent"
        override val description: String = "test double"
        override fun capabilities(): Set<String> =
            setOf("health", "status", "ping", "kv.get", "kv.put")
        override fun scopedOps(): Set<String> = setOf("ping", "kv.get", "kv.put")
        override fun status(): Map<String, Any?> = mapOf("state" to "READY")
        override fun rpc(op: String, args: Map<String, Any?>): PluginResult =
            if (op in capabilities()) {
                PluginResult.Ok(mapOf("op" to op))
            } else {
                PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
            }
    }

    private fun buildRouter(
        auth: AuthMode = AuthMode.KEY,
        passwordHash: String? = null
    ): SuperRouter {
        val config = SuperServerConfig.defaults().copy(auth = auth, passwordHash = passwordHash)
        val keys = mapOf(
            operatorId to operatorKey,
            servicesReadId to servicesReadKey,
            callerId to callerKey,
            statusOnlyId to statusOnlyKey
        )
        val signer = RequestSigner(
            keyLookup = { keys[it] },
            clock = { now },
            nonceCache = NonceCache(clock = { now * 1_000 })
        )
        val superAuth = SuperAuth(
            config = config,
            passwords = passwordHash?.let { PasswordHash.decode(it) },
            sessions = SessionStore(),
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer,
            principalOf = { id ->
                when (id) {
                    servicesReadId -> SuperAuth.Principal.Peer(
                        servicesReadId,
                        setOf(PeerScope.SERVICES_READ)
                    )
                    callerId -> SuperAuth.Principal.Peer(
                        callerId,
                        setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
                    )
                    statusOnlyId -> SuperAuth.Principal.Peer(
                        statusOnlyId,
                        setOf(PeerScope.STATUS_READ)
                    )
                    else -> SuperAuth.Principal.Operator
                }
            }
        )
        return SuperRouter(
            config = config,
            auth = superAuth,
            plugins = PluginRegistry(listOf(FakePlugin())),
            events = EventBus(),
            uptimeMs = { 1_000L },
            serverStatus = { mapOf("fleet" to mapOf("nodeId" to "0123456789abcdef")) },
            gromVersion = { "0.16.0-test" },
            dataPlane = {
                mapOf(
                    "enabled" to true,
                    "running" to true,
                    "bind" to "LOOPBACK",
                    "address" to "127.0.0.1:47002",
                    "port" to 47002,
                    "maxBodyBytes" to 65536
                )
            }
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

    private fun plain(path: String, method: String = "GET", body: String = ""): HttpRequest =
        HttpRequest(method, path, emptyMap(), emptyMap(), "HTTP/1.1", body)

    private fun body(response: SuperResponse): Map<String, Any?> {
        val json = response as SuperResponse.Json
        @Suppress("UNCHECKED_CAST")
        return JsonParser.parseObjectOrNull(json.body) ?: emptyMap()
    }

    private fun code(response: SuperResponse): String {
        @Suppress("UNCHECKED_CAST")
        val error = body(response)["error"] as? Map<String, Any?> ?: return ""
        return error["code"] as? String ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectAt(map: Map<String, Any?>, name: String): Map<String, Any?> =
        map[name] as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun listAt(map: Map<String, Any?>, name: String): List<Any?> =
        map[name] as? List<Any?> ?: emptyList()

    // ------------------------------------------------------------- anonymous

    @Test
    fun `anonymous connect describes how to authenticate but lists no service`() {
        val router = buildRouter()
        val json = router.handle(plain("/grom/v1/connect"), "10.0.0.7:5555") as SuperResponse.Json
        assertEquals(200, json.status)
        val document = body(json)

        assertEquals("grom-connect-v1", document["schema"])
        assertEquals("grom-super", document["service"])
        assertEquals(1L, document["api"])
        assertEquals("0.16.0-test", document["grom"])
        assertEquals("ready", document["state"])
        assertEquals("anonymous", objectAt(document, "principal")["kind"])
        assertEquals(emptyList<String>(), listAt(document, "capabilities"))

        val services = objectAt(document, "services")
        assertEquals(false, services["listed"])
        assertEquals("/v1/plugins", services["path"])
        assertEquals(emptyList<Any?>(), listAt(services, "items"))

        val auth = objectAt(document, "auth")
        assertEquals(listOf("key"), listAt(auth, "methods"))
        assertTrue(listAt(auth, "scopes").contains("services.read"))
        assertTrue(listAt(auth, "scopes").contains("services.call"))

        val dataPlane = objectAt(document, "dataPlane")
        assertEquals(true, dataPlane["enabled"])
        assertEquals("127.0.0.1:47002", dataPlane["address"])

        // The document is discovery, not a leak: no token, no password, no
        // peer address and no configuration block.
        val text = json.body
        assertFalse(text.contains("token"))
        assertFalse(text.contains("password"))
        assertFalse(text.contains("notifications"))
        assertFalse(text.contains("npub"))
    }

    @Test
    fun `connect advertises the auth methods that are actually live`() {
        val keyOnly = body(buildRouter(auth = AuthMode.KEY).handle(plain("/grom/v1/connect"), "c"))
        assertEquals(listOf("key"), listAt(objectAt(keyOnly, "auth"), "methods"))

        val both = buildRouter(
            auth = AuthMode.BOTH,
            passwordHash = PasswordHash.create("correct horse").value
        )
        val document = body(both.handle(plain("/grom/v1/connect"), "c"))
        assertEquals(
            listOf("password", "key"),
            listAt(objectAt(document, "auth"), "methods")
        )
    }

    @Test
    fun `a presented credential is verified and never downgraded`() {
        val router = buildRouter()
        val forged = HttpRequest(
            "GET", "/grom/v1/connect", emptyMap(),
            mapOf(RequestSigner.HEADER_KEY to operatorId),
            "HTTP/1.1", ""
        )
        val response = router.handle(forged, "10.0.0.7")
        assertEquals(401, (response as SuperResponse.Json).status)
        assertEquals("UNAUTHORIZED", code(response))
    }

    @Test
    fun `connect is read-only`() {
        val response = buildRouter().handle(plain("/grom/v1/connect", method = "POST"), "c")
        assertEquals(405, (response as SuperResponse.Json).status)
        assertEquals("METHOD_NOT_ALLOWED", code(response))
    }

    // ------------------------------------------------------------ authorised

    @Test
    fun `an operator sees services with their status block`() {
        val router = buildRouter()
        val document = body(
            router.handle(signed(operatorSeed, "/grom/v1/connect", "nonce2001"), "c")
        )
        assertEquals("operator", objectAt(document, "principal")["kind"])
        assertEquals(PeerScope.entries.size, listAt(document, "capabilities").size)

        val services = objectAt(document, "services")
        assertEquals(true, services["listed"])
        val items = listAt(services, "items")
        assertEquals(1, items.size)
        @Suppress("UNCHECKED_CAST")
        val service = items[0] as Map<String, Any?>
        assertEquals("agent", service["id"])
        assertEquals(listOf("kv.get", "kv.put", "ping"), service["scopedOps"])
        assertNotNull(service["status"])
    }

    @Test
    fun `services read lists a service without its measured status`() {
        val document = body(
            buildRouter().handle(
                signed(servicesReadSeed, "/grom/v1/connect", "nonce2002"),
                "c"
            )
        )
        assertEquals("peer", objectAt(document, "principal")["kind"])
        assertEquals(listOf("services.read"), listAt(document, "capabilities"))

        val services = objectAt(document, "services")
        assertEquals(true, services["listed"])
        @Suppress("UNCHECKED_CAST")
        val service = listAt(services, "items")[0] as Map<String, Any?>
        // Listing a service is not permission to read how it is doing; that
        // stays a `status.read` surface.
        assertFalse(service.containsKey("status"))
        assertEquals(listOf("kv.get", "kv.put", "ping"), service["scopedOps"])
    }

    @Test
    fun `status read does not grant the service list`() {
        val document = body(
            buildRouter().handle(
                signed(statusOnlySeed, "/grom/v1/connect", "nonce2003"),
                "c"
            )
        )
        assertEquals(listOf("status.read"), listAt(document, "capabilities"))
        assertEquals(false, objectAt(document, "services")["listed"])
    }

    // ---------------------------------------------------------- service routes

    @Test
    fun `services read may enumerate but not call`() {
        val router = buildRouter()
        val list = router.handle(
            signed(servicesReadSeed, "/v1/plugins", "nonce3001"),
            "c"
        ) as SuperResponse.Json
        assertEquals(200, list.status)

        val rpc = router.handle(
            signed(
                servicesReadSeed,
                "/v1/plugins/agent/rpc",
                "nonce3002",
                method = "POST",
                body = """{"op":"ping"}"""
            ),
            "c"
        ) as SuperResponse.Json
        assertEquals(403, rpc.status)
        assertEquals("SCOPE_REQUIRED", code(rpc))
    }

    @Test
    fun `services call may invoke only the ops the service exposes`() {
        val router = buildRouter()
        val allowed = router.handle(
            signed(
                callerSeed,
                "/v1/plugins/agent/rpc",
                "nonce3003",
                method = "POST",
                body = """{"op":"ping","args":{"x":1}}"""
            ),
            "c"
        ) as SuperResponse.Json
        assertEquals(200, allowed.status)
        assertEquals("ping", objectAt(body(allowed), "result")["op"])

        val refused = router.handle(
            signed(
                callerSeed,
                "/v1/plugins/agent/rpc",
                "nonce3004",
                method = "POST",
                body = """{"op":"status"}"""
            ),
            "c"
        ) as SuperResponse.Json
        assertEquals(403, refused.status)
        assertEquals("SCOPE_REQUIRED", code(refused))
    }

    @Test
    fun `a scoped caller never reaches events, logout or the config export`() {
        val router = buildRouter()
        assertEquals(
            403,
            (router.handle(signed(callerSeed, "/v1/events", "nonce3005"), "c") as SuperResponse.Json).status
        )
        assertEquals(
            403,
            (router.handle(signed(callerSeed, "/v1/config", "nonce3006"), "c") as SuperResponse.Json).status
        )
        assertEquals(
            403,
            (
                router.handle(
                    signed(callerSeed, "/v1/auth/logout", "nonce3007", method = "POST"),
                    "c"
                ) as SuperResponse.Json
                ).status
        )
    }

    @Test
    fun `an unknown service is still a 404, not a scope answer`() {
        val router = buildRouter()
        val response = router.handle(
            signed(callerSeed, "/v1/plugins/nope/rpc", "nonce3008", method = "POST", body = """{"op":"ping"}"""),
            "c"
        ) as SuperResponse.Json
        assertEquals(404, response.status)
        assertEquals("UNKNOWN_PLUGIN", code(response))
    }

    @Test
    fun `health stays unchanged and anonymous`() {
        val response = buildRouter().handle(plain("/v1/health"), "c") as SuperResponse.Json
        assertEquals(200, response.status)
        assertEquals("grom-super", body(response)["service"])
        assertEquals(1L, body(response)["api"])
        assertFalse(response.body.contains("services"))
        assertFalse(response.body.contains("capabilities"))
    }

    // ----------------------------------------------------- cross-module parity

    @Test
    fun `the sdk signs byte-for-byte the string the server verifies`() {
        val cases = listOf(
            Triple("GET", "/grom/v1/connect", ""),
            Triple("POST", "/v1/plugins/agent/rpc", """{"op":"ping","args":{}}"""),
            Triple("GET", "/v1/status", ""),
            Triple("POST", "/v1/auth/login", """{"password":"ümlaut \u0001"}""")
        )
        for ((method, path, payload) in cases) {
            assertEquals(
                RequestSigner.canonicalString(method, path, payload, "1700000000", "abcdefgh1234"),
                GromConnectClient.canonicalString(method, path, payload, "1700000000", "abcdefgh1234")
            )
        }
    }
}
