// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.AiAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The gateway against a fake network (D94).
 *
 * Everything here is deterministic: the transport records exactly what would
 * have gone on the wire, so the URL, the headers and the body of each protocol
 * are asserted byte for byte. The real socket is covered by
 * `AiUpstreamIntegrationTest`.
 */
class AiGatewayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "v1:$plaintext"
        override fun open(sealed: String): String? =
            if (sealed.startsWith("v1:")) sealed.removePrefix("v1:") else null
    }

    private class FakeTransport(
        private val answer: (url: String, body: String) -> AiHttpReply = {
                _,
                _ ->
            AiHttpReply(200, OPENAI_OK)
        },
        var streamLines: List<String> = emptyList(),
        private val streamFailure: RuntimeException? = null
    ) : AiTransport {

        val calls = ArrayList<Triple<String, Map<String, String>, String>>()
        var streamUrl: String? = null
        var streamHeaders: Map<String, String> = emptyMap()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int
        ): AiHttpReply {
            calls.add(Triple(url, headers, body))
            return answer(url, body)
        }

        override fun stream(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
            onLine: (String) -> Boolean
        ): AiHttpReply {
            streamUrl = url
            streamHeaders = headers
            streamFailure?.let { throw it }
            for (line in streamLines) {
                if (!onLine(line)) break
            }
            return AiHttpReply(200, "")
        }
    }

    private val key = "sk-test-key"

    private fun gateway(
        transport: AiTransport,
        kind: AiProviderKind = AiProviderKind.OPENAI,
        baseUrl: String? = null,
        protocol: AiProtocol? = null,
        enabled: Boolean = true,
        key: String? = this.key,
        limiter: app.grom.server.superserver.auth.RateLimiter? = null
    ): AiGateway {
        val dir = folder.newFolder()
        val store = AiStore(dir, Box())
        val provider = AiProviderConfig(
            id = "p",
            kind = kind,
            title = "P",
            baseUrl = baseUrl ?: kind.defaultBaseUrl,
            apiKeySealed = key?.let { store.sealKey(it) },
            enabled = true,
            protocol = protocol
        )
        val config = AiGatewayConfig(
            enabled = enabled,
            defaultModel = "m",
            providers = listOf(provider),
            models = listOf(AiModel("m", "p", "upstream-model", "M"))
        )
        assertEquals(null, store.save(config))
        return AiGateway(
            store = store,
            transport = transport,
            idFactory = { "grom-id" },
            limiter = limiter ?: app.grom.server.superserver.auth.RateLimiter(
                capacity = AiGateway.CALLS_PER_MINUTE,
                refillPerSecond = AiGateway.CALLS_PER_MINUTE / 60.0,
                maxClients = 64
            )
        )
    }

    private fun body(json: String): Map<String, Any?> = JsonParser.parseObjectOrNull(json)!!

    private fun payload(result: AiAnswer): Map<String, Any?> =
        (result as AiAnswer.Json).payload

    private fun call(gateway: AiGateway, json: String): AiAnswer =
        gateway.openAiChat(json, "client")

    // --------------------------------------------------------------- openai

    @Test
    fun `an openai request carries a bearer key and an openai body`() {
        val transport = FakeTransport()
        val gateway = gateway(transport)

        val answer = call(
            gateway,
            """{"messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}],"temperature":0.25}"""
        )
        assertEquals(200, (answer as AiAnswer.Json).status)

        val (url, headers, sent) = transport.calls.single()
        assertEquals("https://api.openai.com/v1/chat/completions", url)
        assertEquals("Bearer $key", headers["Authorization"])
        val root = body(sent)
        assertEquals("upstream-model", root["model"])
        assertEquals(false, root["stream"])
        assertEquals(0.25, root["temperature"])
        val messages = root["messages"] as List<*>
        // system + user: the system prompt is prepended, not merged.
        assertEquals(2, messages.size)
        assertEquals("system", (messages[0] as Map<*, *>)["role"])
        assertEquals("hi", (messages[1] as Map<*, *>)["content"])
    }

    @Test
    fun `an openai answer becomes the openai shape with a grom block`() {
        val transport = FakeTransport()
        val payload = payload(
            call(gateway(transport), """{"messages":[{"role":"user","content":"hi"}]}""")
        )
        assertEquals("chat.completion", payload["object"])
        assertEquals("grom-id", payload["id"])
        assertEquals("m", payload["model"])
        val choice = (payload["choices"] as List<*>).single() as Map<*, *>
        assertEquals("stop", choice["finish_reason"])
        assertEquals("hello", (choice["message"] as Map<*, *>)["content"])
        val usage = payload["usage"] as Map<*, *>
        assertEquals(11L, usage["prompt_tokens"])
        assertEquals("p", (payload["grom"] as Map<*, *>)["provider"])
    }

    // ------------------------------------------------------------ anthropic

    @Test
    fun `an anthropic request uses its own headers body and endpoint`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(200, ANTHROPIC_OK) })
        val gateway = gateway(transport, AiProviderKind.ANTHROPIC)

        val answer = gateway.anthropicMessages(
            """{"model":"m","max_tokens":64,"system":"be brief","messages":[{"role":"user","content":"hi"}]}""",
            "client"
        )
        assertEquals(200, (answer as AiAnswer.Json).status)

        val (url, headers, sent) = transport.calls.single()
        assertEquals("https://api.anthropic.com/v1/messages", url)
        assertEquals(key, headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertFalse(headers.containsKey("Authorization"))

        val root = body(sent)
        assertEquals(64L, root["max_tokens"])
        assertEquals("be brief", root["system"])
        assertEquals(1, (root["messages"] as List<*>).size)
    }

    @Test
    fun `anthropic text blocks are concatenated and the stop reason mapped`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(200, ANTHROPIC_OK) })
        val payload = payload(
            gateway(transport, AiProviderKind.ANTHROPIC).anthropicMessages(
                """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}""",
                "client"
            )
        )
        assertEquals("message", payload["type"])
        val content = (payload["content"] as List<*>).single() as Map<*, *>
        assertEquals("part one part two", content["text"])
        assertEquals("max_tokens", payload["stop_reason"])
        assertEquals(5L, (payload["usage"] as Map<*, *>)["input_tokens"])
    }

    @Test
    fun `a base url that already ends in v1 is not doubled`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(200, ANTHROPIC_OK) })
        gateway(transport, AiProviderKind.ANTHROPIC, baseUrl = "https://gw.example.com/v1")
            .anthropicMessages(
                """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}""",
                "client"
            )
        assertEquals(
            "https://gw.example.com/v1/messages",
            transport.calls.single().first
        )
    }

    // --------------------------------------------------------------- gemini

    @Test
    fun `a gemini request maps roles system and generation config`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(200, GEMINI_OK) })
        val gateway = gateway(transport, AiProviderKind.GEMINI)

        val payload = payload(
            call(
                gateway,
                """{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"yo"},{"role":"user","content":"again"}],"max_tokens":32,"temperature":0.4}"""
            )
        )
        assertEquals("hello from gemini", ((payload["choices"] as List<*>).single() as Map<*, *>)
            .let { (it["message"] as Map<*, *>)["content"] })

        val (url, headers, sent) = transport.calls.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/upstream-model:generateContent",
            url
        )
        assertEquals(key, headers["x-goog-api-key"])
        val root = body(sent)
        val contents = root["contents"] as List<*>
        assertEquals("user", (contents[0] as Map<*, *>)["role"])
        assertEquals("model", (contents[1] as Map<*, *>)["role"])
        val generation = root["generationConfig"] as Map<*, *>
        assertEquals(32L, generation["maxOutputTokens"])
        assertEquals(0.4, generation["temperature"])
    }

    @Test
    fun `a custom endpoint can speak anthropic and a gemini stream asks for sse`() {
        val anthropicCall = FakeTransport(answer = { _, _ -> AiHttpReply(200, ANTHROPIC_OK) })
        val custom = gateway(
            anthropicCall,
            AiProviderKind.CUSTOM,
            baseUrl = "http://127.0.0.1:9999",
            protocol = AiProtocol.ANTHROPIC
        )
        custom.anthropicMessages(
            """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}""",
            "client"
        )
        assertEquals("http://127.0.0.1:9999/v1/messages", anthropicCall.calls.single().first)

        val streamTransport = FakeTransport(streamLines = listOf("data: $GEMINI_STREAM"))
        val answer = call(
            gateway(streamTransport, AiProviderKind.GEMINI),
            """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
        )
        assertTrue(answer is AiAnswer.Stream)
        drain(answer as AiAnswer.Stream)
        assertTrue(streamTransport.streamUrl!!.endsWith(":streamGenerateContent?alt=sse"))
    }

    // --------------------------------------------------------------- errors

    @Test
    fun `upstream failures map to stable codes and keep no key`() {
        // upstream status, upstream body, the code Grom reports, Grom's status
        val cases = listOf(
            listOf(401, """{"error":{"message":"bad key sk-test-key"}}""", AiCodes.UPSTREAM_UNAUTHORIZED, 502),
            listOf(404, """{"error":{"message":"no such model"}}""", AiCodes.MODEL_NOT_FOUND, 404),
            listOf(400, """{"error":{"message":"bad request"}}""", AiCodes.UPSTREAM_BAD_REQUEST, 400),
            listOf(429, """{"error":{"message":"slow down"}}""", AiCodes.UPSTREAM_RATE_LIMITED, 429),
            listOf(500, """{"error":{"message":"boom"}}""", AiCodes.UPSTREAM_ERROR, 502)
        )
        for (case in cases) {
            val status = case[0] as Int
            val upstream = case[1] as String
            val code = case[2] as String
            val expected = case[3] as Int
            val transport = FakeTransport(answer = { _, _ -> AiHttpReply(status, upstream) })
            val answer = call(
                gateway(transport),
                """{"messages":[{"role":"user","content":"hi"}]}"""
            ) as AiAnswer.Json
            val error = answer.payload["error"] as Map<*, *>
            assertEquals("upstream $status", expected, answer.status)
            assertEquals(code, error["code"])
            val rendered = Json.encode(answer.payload)
            // The provider echoed the key in its message; Grom must not repeat
            // it, and no endpoint may appear either.
            assertTrue("a message leaked the key: $rendered", !rendered.contains(key))
            assertTrue(!rendered.contains("api.openai.com"))
        }
    }

    @Test
    fun `a refused key stays a 502 because Grom itself is healthy`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(401, """{"error":"no"}""") })
        val answer = call(gateway(transport), """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(502, (answer as AiAnswer.Json).status)
    }

    @Test
    fun `a timeout and an unreachable host get different codes`() {
        val timeout = FakeTransport(answer = { _, _ -> throw AiTimeoutException() })
        val t = call(gateway(timeout), """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(504, (t as AiAnswer.Json).status)
        assertEquals(AiCodes.TIMEOUT, (t.payload["error"] as Map<*, *>)["code"])

        val unreachable = FakeTransport(answer = { _, _ -> throw AiTransportException("x") })
        val u = call(gateway(unreachable), """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(502, (u as AiAnswer.Json).status)
        assertEquals(AiCodes.UNREACHABLE, (u.payload["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `a non json answer is a bad response not an empty completion`() {
        val transport = FakeTransport(answer = { _, _ -> AiHttpReply(200, "<html>") })
        val answer = call(gateway(transport), """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(AiCodes.BAD_RESPONSE, ((answer as AiAnswer.Json).payload["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `an unsupported parameter is named instead of ignored`() {
        val transport = FakeTransport()
        val answer = call(
            gateway(transport),
            """{"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function"}]}"""
        ) as AiAnswer.Json
        assertEquals(400, answer.status)
        assertEquals(AiCodes.UNSUPPORTED_PARAM, (answer.payload["error"] as Map<*, *>)["code"])
        assertTrue((answer.payload["error"] as Map<*, *>)["message"].toString().contains("tools"))
        assertTrue(transport.calls.isEmpty())
    }

    // ------------------------------------------------------------ pre-flight

    @Test
    fun `a disabled gateway a missing key and a disabled model never dial`() {
        val transport = FakeTransport()

        val disabled = gateway(transport, enabled = false)
        assertEquals(
            AiCodes.DISABLED,
            ((payload(call(disabled, HAI)) ["error"]) as Map<*, *>)["code"]
        )

        val noKey = gateway(transport, key = null)
        assertEquals(
            AiCodes.NOT_CONFIGURED,
            ((payload(call(noKey, HAI))["error"]) as Map<*, *>)["code"]
        )

        // A provider whose ciphertext this box cannot open is "not configured",
        // not "empty answer".
        val dir = folder.newFolder()
        val store = AiStore(dir, Box())
        assertEquals(
            null,
            store.save(
                AiGatewayConfig(
                    enabled = true,
                    defaultModel = "m",
                    providers = listOf(
                        AiProviderConfig("p", AiProviderKind.OPENAI, "P", "https://api.openai.com/v1", "vX:bad")
                    ),
                    models = listOf(AiModel("m", "p", "up", "M"))
                )
            )
        )
        val broken = AiGateway(store, transport)
        assertEquals(
            AiCodes.NOT_CONFIGURED,
            ((payload(call(broken, HAI))["error"]) as Map<*, *>)["code"]
        )

        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `an unknown model is refused without a dial`() {
        val transport = FakeTransport()
        val answer = call(
            gateway(transport),
            """{"model":"gpt-9","messages":[{"role":"user","content":"hi"}]}"""
        ) as AiAnswer.Json
        assertEquals(404, answer.status)
        assertEquals(AiCodes.UNKNOWN_MODEL, (answer.payload["error"] as Map<*, *>)["code"])
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `a message above the bound is a refusal not a truncation`() {
        val transport = FakeTransport()
        val huge = "x".repeat(AiLimits.MAX_MESSAGE_CHARS + 1)
        val answer = call(
            gateway(transport),
            """{"messages":[{"role":"user","content":"$huge"}]}"""
        ) as AiAnswer.Json
        assertEquals(413, answer.status)
        assertEquals(AiCodes.REQUEST_TOO_LARGE, (answer.payload["error"] as Map<*, *>)["code"])
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `the per client budget refuses the twenty first call`() {
        val transport = FakeTransport()
        val gateway = gateway(
            transport,
            limiter = app.grom.server.superserver.auth.RateLimiter(
                capacity = AiGateway.CALLS_PER_MINUTE,
                refillPerSecond = 0.0,
                clock = { 1_000L }
            )
        )
        repeat(AiGateway.CALLS_PER_MINUTE) {
            assertEquals(200, (call(gateway, HAI) as AiAnswer.Json).status)
        }
        val refused = call(gateway, HAI) as AiAnswer.Json
        assertEquals(429, refused.status)
        assertEquals(AiCodes.RATE_LIMITED, (refused.payload["error"] as Map<*, *>)["code"])
    }

    // -------------------------------------------------------------- streaming

    @Test
    fun `an openai stream emits deltas a finish and done`() {
        val transport = FakeTransport(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val answer = call(
            gateway(transport),
            """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
        )
        assertTrue(answer is AiAnswer.Stream)
        val text = drain(answer as AiAnswer.Stream)

        assertTrue(text.contains("\"content\":\"Hel\""))
        assertTrue(text.contains("\"content\":\"lo\""))
        assertTrue(text.contains("\"finish_reason\":\"stop\""))
        assertTrue(text.endsWith("data: [DONE]\n\n"))
        // Frames are SSE and flush one event per blank line.
        assertTrue(text.contains("\n\n"))
    }

    @Test
    fun `an anthropic stream is wrapped in anthropic events`() {
        val transport = FakeTransport(
            answer = { _, _ -> AiHttpReply(200, ANTHROPIC_OK) },
            streamLines = listOf(
                """event: content_block_delta""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
                """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
            )
        )
        val gateway = gateway(transport, AiProviderKind.ANTHROPIC)
        val answer = gateway.anthropicMessages(
            """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}],"stream":true}""",
            "client"
        )
        val text = drain(answer as AiAnswer.Stream)
        assertTrue(text.startsWith("event: message_start"))
        assertTrue(text.contains("event: content_block_delta"))
        assertTrue(text.contains("\"text\":\"Hi\""))
        assertTrue(text.contains("event: message_stop"))
    }

    @Test
    fun `a failure before the first byte becomes an error event not a broken body`() {
        val transport = FakeTransport(streamFailure = AiTransportException("down"))
        val answer = call(
            gateway(transport),
            """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
        )
        val text = drain(answer as AiAnswer.Stream)
        assertTrue(text.contains(AiCodes.UNREACHABLE))
        assertTrue(text.endsWith("data: [DONE]\n\n"))
    }

    @Test
    fun `a stream that closes early still ends with a finish and done`() {
        val transport = FakeTransport(
            streamLines = listOf("""data: {"choices":[{"delta":{"content":"partial"}}]}""")
        )
        val text = drain(
            call(
                gateway(transport),
                """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            ) as AiAnswer.Stream
        )
        assertTrue(text.contains("\"finish_reason\":\"stop\""))
        assertTrue(text.endsWith("data: [DONE]\n\n"))
    }

    // ---------------------------------------------------------- projections

    @Test
    fun `the public summary never carries an endpoint or a key`() {
        val gateway = gateway(FakeTransport())
        val public = Json.encode(gateway.summary())
        assertFalse(public.contains("api.openai.com"))
        assertFalse(public.contains(key))
        assertFalse(public.contains("apiKeySealed"))
        assertTrue(public.contains("\"enabled\":true"))
        assertTrue(public.contains("\"configured\":true"))
    }

    @Test
    fun `the models document says what is available and why`() {
        val gateway = gateway(FakeTransport())
        val document = gateway.modelsPayload()
        val model = (document["models"] as List<*>).single() as Map<*, *>
        assertEquals("m", model["id"])
        assertEquals(true, model["available"])
        assertEquals(listOf("chat", "stream"), model["capabilities"])
        assertFalse(Json.encode(document).contains("upstream-model"))
    }

    @Test
    fun `the operator document carries the endpoint and a truthful available flag`() {
        val gateway = gateway(FakeTransport())
        val document = AiDocuments.operatorStatus(gateway.load(), null, emptyMap())
        val provider = (document["providers"] as List<*>).single() as Map<*, *>
        assertEquals("https://api.openai.com/v1", provider["baseUrl"])
        assertEquals(true, provider["configured"])
        assertEquals(true, provider["keySet"])
        val model = (document["models"] as List<*>).single() as Map<*, *>
        assertEquals(true, model["available"])
        // The endpoint is for the operator; the key is not, in any form.
        assertFalse(Json.encode(document).contains(key))
        assertFalse(Json.encode(document).contains("apiKeySealed"))
    }

    @Test
    fun `a probe measures the provider and remembers the result`() {
        val transport = FakeTransport()
        val gateway = gateway(transport)
        val probe = gateway.probe("p")
        assertEquals(true, probe["ok"])
        assertEquals(1, transport.calls.size)
        val sent = body(transport.calls.single().third)
        assertEquals(1L, sent["max_tokens"])
        assertEquals("ping", (((sent["messages"] as List<*>).single()) as Map<*, *>)["content"])

        val unknown = gateway.probe("ghost")
        assertEquals(false, unknown["ok"])
        assertEquals("UNKNOWN_PROVIDER", unknown["code"])
        assertEquals(1, transport.calls.size)
    }

    // ---------------------------------------------------------------- plugin

    @Test
    fun `the ai service publishes no scoped ops and gates each op by its scope`() {
        val gateway = gateway(FakeTransport())
        val plugin = AiPlugin(gateway)

        assertTrue(plugin.scopedOps().isEmpty())
        assertEquals(
            setOf(app.grom.server.cluster.PeerScope.AI_READ),
            plugin.requiredScopes("models")
        )
        assertEquals(
            setOf(app.grom.server.cluster.PeerScope.AI_CHAT),
            plugin.requiredScopes("chat")
        )
        // `status` is the same public summary `connect` carries, so it is the
        // same grant as the catalogue (S7-R019).
        assertEquals(
            setOf(app.grom.server.cluster.PeerScope.AI_READ),
            plugin.requiredScopes("status")
        )
        assertTrue(plugin.requiredScopes("anything-else").isEmpty())
    }

    @Test
    fun `the chat op answers text and refuses streaming through a service call`() {
        val gateway = gateway(FakeTransport())
        val plugin = AiPlugin(gateway)

        val ok = plugin.rpc("chat", mapOf("messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
        val payload = (ok as app.grom.server.superserver.PluginResult.Ok).payload
        assertEquals("hello", payload["text"])
        assertEquals("m", payload["model"])
        assertEquals("p", payload["provider"])

        val streamed = plugin.rpc(
            "chat",
            mapOf(
                "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                "stream" to true
            )
        )
        val failure = streamed as app.grom.server.superserver.PluginResult.Failure
        assertEquals(400, failure.status)
        assertEquals(AiCodes.NOT_SUPPORTED, failure.code)
    }

    // --------------------------------------------------------------- helpers

    private fun drain(stream: AiAnswer.Stream): String {
        val out = ByteArrayOutputStream()
        stream.write(
            out,
            object : app.grom.server.superserver.StreamControl {
                override fun isCancelled(): Boolean = false
            }
        )
        return out.toString("UTF-8")
    }

    private companion object {
        const val HAI = """{"messages":[{"role":"user","content":"hi"}]}"""

        const val OPENAI_OK = """{
          "id":"chatcmpl-1","object":"chat.completion","model":"upstream-model",
          "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
          "usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}
        }"""

        const val ANTHROPIC_OK = """{
          "id":"msg_1","type":"message","role":"assistant","model":"upstream-model",
          "content":[{"type":"text","text":"part one "},{"type":"text","text":"part two"}],
          "stop_reason":"max_tokens",
          "usage":{"input_tokens":5,"output_tokens":7}
        }"""

        const val GEMINI_OK = """{
          "candidates":[{"content":{"parts":[{"text":"hello from gemini"}]},"finishReason":"MAX_TOKENS"}],
          "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4}
        }"""

        const val GEMINI_STREAM = """{"candidates":[{"content":{"parts":[{"text":"G"}]}}]}"""
    }
}
