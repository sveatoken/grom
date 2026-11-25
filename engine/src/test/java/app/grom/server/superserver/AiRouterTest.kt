// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope
import app.grom.server.http.HttpRequest
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.NonceCache
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import app.grom.server.superserver.plugins.ai.AiGatewayConfig
import app.grom.server.superserver.plugins.ai.AiGateway
import app.grom.server.superserver.plugins.ai.AiModel
import app.grom.server.superserver.plugins.ai.AiProviderConfig
import app.grom.server.superserver.plugins.ai.AiProviderKind
import app.grom.server.superserver.plugins.ai.AiStore
import app.grom.server.superserver.plugins.ai.AiTransport
import app.grom.server.superserver.plugins.ai.AiHttpReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The AI gateway on the data plane (D94, D97).
 *
 * The point of this test is the *gate*, not the provider: which credential may
 * read the catalogue, which may spend a completion, and that `services.call`
 * alone never does either. Both compatibility routes are exercised through the
 * router, including the streamed one, so the head/socket handoff is covered too.
 */
class AiRouterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_700_000_000L
    private val key = "sk-router-test"

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "v1:$plaintext"
        override fun open(sealed: String): String? =
            if (sealed.startsWith("v1:")) sealed.removePrefix("v1:") else null
    }

    private class FakeTransport(
        private val status: Int = 200,
        private val body: String = OPENAI_OK,
        private val streamLines: List<String> = emptyList()
    ) : AiTransport {
        var calls = 0
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int
        ): AiHttpReply {
            calls++
            return AiHttpReply(status, this.body)
        }

        override fun stream(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
            onLine: (String) -> Boolean
        ): AiHttpReply {
            calls++
            for (line in streamLines) if (!onLine(line)) break
            return AiHttpReply(200, "")
        }
    }

    private val readSeed = Ed25519.generatePrivateSeed()
    private val readKey = Ed25519.derivePublicKey(readSeed)
    private val readId = Ed25519.keyId(readKey)

    private val chatSeed = Ed25519.generatePrivateSeed()
    private val chatKey = Ed25519.derivePublicKey(chatSeed)
    private val chatId = Ed25519.keyId(chatKey)

    private val servicesSeed = Ed25519.generatePrivateSeed()
    private val servicesKey = Ed25519.derivePublicKey(servicesSeed)
    private val servicesId = Ed25519.keyId(servicesKey)

    private val operatorSeed = Ed25519.generatePrivateSeed()
    private val operatorKey = Ed25519.derivePublicKey(operatorSeed)
    private val operatorId = Ed25519.keyId(operatorKey)

    /**
     * A gateway over one provider. [kind] selects the *upstream* protocol; the
     * shape a caller uses is decided by the route, not by this, and the two are
     * deliberately independent (D94).
     */
    private fun gateway(
        transport: AiTransport,
        kind: AiProviderKind = AiProviderKind.OPENAI,
        baseUrl: String = "https://api.openai.com/v1"
    ): AiGateway {
        val store = AiStore(folder.newFolder(), Box())
        val config = AiGatewayConfig(
            enabled = true,
            defaultModel = "gpt-4o-mini",
            providers = listOf(
                AiProviderConfig(
                    id = "openai",
                    kind = kind,
                    title = kind.wireName,
                    baseUrl = baseUrl,
                    apiKeySealed = store.sealKey(key)
                )
            ),
            models = listOf(AiModel("gpt-4o-mini", "openai", "gpt-4o-mini", "GPT-4o mini"))
        )
        assertEquals(null, store.save(config))
        return AiGateway(store = store, transport = transport, idFactory = { "grom-id" })
    }

    private fun router(ai: AiPort): SuperRouter {
        val config = SuperServerConfig.defaults().copy(auth = AuthMode.KEY)
        val keys = mapOf(
            operatorId to operatorKey,
            readId to readKey,
            chatId to chatKey,
            servicesId to servicesKey
        )
        val auth = SuperAuth(
            config = config,
            passwords = null,
            sessions = SessionStore(),
            requestLimiter = RateLimiter(capacity = 10_000, refillPerSecond = 0.0),
            loginLimiter = LoginLimiter(),
            signer = RequestSigner(
                keyLookup = { keys[it] },
                clock = { now },
                nonceCache = NonceCache(clock = { now * 1_000 })
            ),
            principalOf = { id ->
                when (id) {
                    readId -> SuperAuth.Principal.Peer(readId, setOf(PeerScope.AI_READ))
                    chatId -> SuperAuth.Principal.Peer(
                        chatId,
                        setOf(PeerScope.AI_READ, PeerScope.AI_CHAT)
                    )
                    servicesId -> SuperAuth.Principal.Peer(
                        servicesId,
                        setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
                    )
                    else -> SuperAuth.Principal.Operator
                }
            }
        )
        return SuperRouter(
            config = config,
            auth = auth,
            plugins = PluginRegistry(listOfNotNull(ai.service())),
            events = EventBus(),
            uptimeMs = { 1_000L },
            ai = ai,
            serverStatus = { mapOf("fleet" to mapOf("nodeId" to "0123456789abcdef")) },
            gromVersion = { "0.17.0-test" }
        )
    }

    private var nonce = 0

    private fun signed(
        seed: ByteArray,
        path: String,
        method: String = "GET",
        body: String = ""
    ): HttpRequest {
        nonce += 1
        // The server accepts 8-128 characters of [A-Za-z0-9_-] (S5-R018); a
        // short nonce is refused before the signature is even checked.
        val value = "nonce%05d".format(nonce)
        val publicKey = Ed25519.derivePublicKey(seed)
        val canonical = RequestSigner.canonicalString(method, path, body, now.toString(), value)
        val headers = mapOf(
            RequestSigner.HEADER_KEY to Ed25519.keyId(publicKey),
            RequestSigner.HEADER_TIMESTAMP to now.toString(),
            RequestSigner.HEADER_NONCE to value,
            RequestSigner.HEADER_SIGNATURE to Base64.getEncoder()
                .encodeToString(Ed25519.sign(seed, canonical.toByteArray()))
        )
        return HttpRequest(method, path, emptyMap(), headers, "HTTP/1.1", body)
    }

    private fun anonymous(path: String, method: String = "GET", body: String = ""): HttpRequest =
        HttpRequest(method, path, emptyMap(), emptyMap(), "HTTP/1.1", body)

    private fun payload(response: SuperResponse): Map<String, Any?> =
        JsonParser.parseObjectOrNull((response as SuperResponse.Json).body)!!

    private fun errorCode(response: SuperResponse): String? =
        ((payload(response)["error"] as? Map<*, *>)?.get("code")) as? String

    private val chatBody =
        """{"messages":[{"role":"user","content":"hi"}]}"""

    // ------------------------------------------------------------- the gate

    @Test
    fun `the catalogue needs ai read and nothing else will do`() {
        val ai = gateway(FakeTransport())
        val router = router(ai)

        assertEquals(401, (router.handle(anonymous(SuperRouter.AI_MODELS), "1.1.1.1") as SuperResponse.Json).status)

        val reader = router.handle(signed(readSeed, SuperRouter.AI_MODELS), "1.1.1.1")
        assertEquals(200, (reader as SuperResponse.Json).status)
        val models = payload(reader)["models"] as List<*>
        assertEquals("gpt-4o-mini", (models.single() as Map<*, *>)["id"])

        // `services.call` is a different permission and does not read the catalog.
        val services = router.handle(
            signed(servicesSeed, SuperRouter.AI_MODELS),
            "1.1.1.1"
        )
        assertEquals(403, (services as SuperResponse.Json).status)
        assertEquals("SCOPE_REQUIRED", errorCode(services))
    }

    @Test
    fun `a completion needs ai chat and services call is not enough`() {
        val transport = FakeTransport()
        val ai = gateway(transport)
        val router = router(ai)

        val reader = router.handle(
            signed(readSeed, SuperRouter.AI_CHAT, "POST", chatBody),
            "1.1.1.1"
        )
        assertEquals(403, (reader as SuperResponse.Json).status)
        assertEquals(0, transport.calls)

        val services = router.handle(
            signed(servicesSeed, SuperRouter.AI_CHAT, "POST", chatBody),
            "1.1.1.1"
        )
        assertEquals(403, (services as SuperResponse.Json).status)
        assertEquals(0, transport.calls)

        val chatter = router.handle(
            signed(chatSeed, SuperRouter.AI_CHAT, "POST", chatBody),
            "1.1.1.1"
        )
        assertEquals(200, (chatter as SuperResponse.Json).status)
        assertEquals(1, transport.calls)
        val choice = (payload(chatter)["choices"] as List<*>).single() as Map<*, *>
        assertEquals("hello", (choice["message"] as Map<*, *>)["content"])
    }

    @Test
    fun `the anthropic alias is served under the same scope`() {
        // The caller asks in the Anthropic shape and the provider speaks
        // Anthropic too; the alias exists because Anthropic SDKs append
        // `/v1/messages` to whatever base URL they are given.
        val transport = FakeTransport(body = ANTHROPIC_OK)
        val ai = gateway(transport, AiProviderKind.ANTHROPIC, "https://api.anthropic.com")
        val router = router(ai)

        val body = """{"model":"gpt-4o-mini","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
        val answer = router.handle(
            signed(chatSeed, SuperRouter.AI_MESSAGES_ALIAS, "POST", body),
            "1.1.1.1"
        )
        assertEquals(200, (answer as SuperResponse.Json).status)
        assertEquals("message", payload(answer)["type"])

        val refused = router.handle(
            signed(readSeed, SuperRouter.AI_MESSAGES, "POST", body),
            "1.1.1.1"
        )
        assertEquals(403, (refused as SuperResponse.Json).status)
    }

    @Test
    fun `an operator key reaches both routes`() {
        val transport = FakeTransport()
        val ai = gateway(transport)
        val router = router(ai)

        assertEquals(
            200,
            (router.handle(signed(operatorSeed, SuperRouter.AI_MODELS), "1.1.1.1") as SuperResponse.Json)
                .status
        )
        assertEquals(
            200,
            (router.handle(
                signed(operatorSeed, SuperRouter.AI_CHAT, "POST", chatBody),
                "1.1.1.1"
            ) as SuperResponse.Json).status
        )
    }

    @Test
    fun `the wrong verb is 405 and a bad body is 400`() {
        val transport = FakeTransport()
        val ai = gateway(transport)
        val router = router(ai)

        val wrongVerb = router.handle(
            signed(chatSeed, SuperRouter.AI_MODELS, "POST", ""),
            "1.1.1.1"
        ) as SuperResponse.Json
        assertEquals(405, wrongVerb.status)
        assertEquals("GET", wrongVerb.headers["Allow"])

        val badBody = router.handle(
            signed(chatSeed, SuperRouter.AI_CHAT, "POST", "{ not json"),
            "1.1.1.1"
        ) as SuperResponse.Json
        assertEquals(400, badBody.status)
        assertEquals("AI_BAD_REQUEST", errorCode(badBody))
    }

    // --------------------------------------------------------------- stream

    @Test
    fun `a streamed completion crosses the router as a stream`() {
        val transport = FakeTransport(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val ai = gateway(transport)
        val router = router(ai)

        val response = router.handle(
            signed(
                chatSeed,
                SuperRouter.AI_CHAT,
                "POST",
                """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            ),
            "1.1.1.1"
        )
        assertTrue(response is SuperResponse.Stream)
        val stream = response as SuperResponse.Stream
        assertEquals("text/event-stream; charset=utf-8", stream.headers["Content-Type"])

        val out = ByteArrayOutputStream()
        stream.write(
            out,
            object : StreamControl {
                override fun isCancelled(): Boolean = false
            }
        )
        val text = out.toString("UTF-8")
        assertTrue(text.contains("\"content\":\"Hel\""))
        assertTrue(text.contains("\"content\":\"lo\""))
        assertTrue(text.endsWith("data: [DONE]\n\n"))
    }

    // -------------------------------------------------------------- connect

    @Test
    fun `connect publishes the gateway without listing anything anonymously`() {
        val ai = gateway(FakeTransport())
        val router = router(ai)

        val anonymous = payload(router.handle(anonymous(SuperRouter.CONNECT), "1.1.1.1"))
        val block = anonymous["ai"] as Map<*, *>
        assertEquals(true, block["enabled"])
        assertEquals(false, block["listed"])
        assertTrue((block["items"] as List<*>).isEmpty())
        assertEquals("ai.read", (block["requires"] as Map<*, *>)["models"])
        assertEquals("ai.chat", (block["requires"] as Map<*, *>)["chat"])
        // The anonymous document never names an endpoint or a provider model.
        val raw = Json.encode(anonymous)
        assertFalse(raw.contains("api.openai.com"))
        assertFalse(raw.contains("gpt-4o-mini"))
        assertFalse(raw.contains(key))
    }

    @Test
    fun `connect lists the catalogue for ai read`() {
        val ai = gateway(FakeTransport())
        val router = router(ai)

        val document = payload(router.handle(signed(readSeed, SuperRouter.CONNECT), "1.1.1.1"))
        val block = document["ai"] as Map<*, *>
        assertEquals(true, block["listed"])
        val items = block["items"] as List<*>
        assertEquals("gpt-4o-mini", (items.single() as Map<*, *>)["id"])
        // Even here, no endpoint and no key.
        val raw = Json.encode(document)
        assertFalse(raw.contains("api.openai.com"))
        assertFalse(raw.contains(key))
    }

    @Test
    fun `status reports the gateway summary but no endpoint`() {
        val ai = gateway(FakeTransport())
        val router = router(ai)

        // `/v1/status` itself needs `status.read`; the operator credential holds
        // every scope, so this test is about the block, not about the gate.
        val status = payload(router.handle(signed(operatorSeed, SuperRouter.STATUS), "1.1.1.1"))
        val block = status["ai"] as Map<*, *>
        assertEquals(true, block["enabled"])
        assertEquals(1, (block["modelCount"] as Number).toInt())
        val raw = Json.encode(status)
        assertFalse(raw.contains("api.openai.com"))
        assertFalse(raw.contains(key))
    }

    // ------------------------------------------------------- plugin routing

    @Test
    fun `the ai service is callable by its own scopes and not by services call`() {
        val transport = FakeTransport()
        val ai = gateway(transport)
        val router = router(ai)

        val modelsPath = "/v1/plugins/ai/rpc"
        val modelsBody = """{"op":"models"}"""
        val listed = router.handle(
            signed(readSeed, modelsPath, "POST", modelsBody),
            "1.1.1.1"
        )
        assertEquals(200, (listed as SuperResponse.Json).status)

        // `ai.read` also reads the public status of the service (S7-R019); it
        // is the same summary `connect` carries, with no endpoint in it.
        val statusOp = router.handle(
            signed(readSeed, modelsPath, "POST", """{"op":"status"}"""),
            "1.1.1.1"
        )
        assertEquals(200, (statusOp as SuperResponse.Json).status)
        assertEquals(
            1,
            ((payload(statusOp)["result"] as Map<*, *>)["modelCount"] as Number).toInt()
        )

        val chatThroughService = """{"op":"chat","args":{"messages":[{"role":"user","content":"hi"}]}}"""
        val refused = router.handle(
            signed(readSeed, modelsPath, "POST", chatThroughService),
            "1.1.1.1"
        )
        assertEquals(403, (refused as SuperResponse.Json).status)

        val allowed = router.handle(
            signed(chatSeed, modelsPath, "POST", chatThroughService),
            "1.1.1.1"
        )
        assertEquals(200, (allowed as SuperResponse.Json).status)
        assertEquals("hello", (payload(allowed)["result"] as Map<*, *>)["text"])

        // A client that may use arbitrary services still cannot spend AI credit:
        // `ai` publishes no scopedOps at all.
        val services = router.handle(
            signed(servicesSeed, modelsPath, "POST", chatThroughService),
            "1.1.1.1"
        )
        assertEquals(403, (services as SuperResponse.Json).status)
    }

    @Test
    fun `the service listing marks the ai service as operator only`() {
        val ai = gateway(FakeTransport())
        val router = router(ai)
        val listing = payload(router.handle(signed(operatorSeed, SuperRouter.PLUGINS), "1.1.1.1"))
        val service = (listing["plugins"] as List<*>).single() as Map<*, *>
        assertEquals("ai", service["id"])
        assertTrue((service["scopedOps"] as List<*>).isEmpty())
        @Suppress("UNCHECKED_CAST")
        val capabilities = service["capabilities"] as List<String>
        assertTrue(capabilities.contains("models"))
        assertTrue(capabilities.contains("chat"))
        assertNotNull(service["description"])
    }

    private companion object {
        const val OPENAI_OK = """{
          "id":"chatcmpl-1","object":"chat.completion","model":"gpt-4o-mini",
          "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
          "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}
        }"""

        const val ANTHROPIC_OK = """{
          "id":"msg_1","type":"message","role":"assistant","model":"gpt-4o-mini",
          "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
          "usage":{"input_tokens":3,"output_tokens":1}
        }"""
    }
}
