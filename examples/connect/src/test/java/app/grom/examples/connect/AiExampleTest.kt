// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.connect

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromCredential
import app.grom.sdk.connect.GromHttpRequest
import app.grom.sdk.connect.GromHttpResponse
import app.grom.sdk.connect.GromTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the documented AI flow runs (D94, D97).
 *
 * The transport is a fake, but the flow is the real one: discovery, scope check,
 * catalogue, one completion and one streamed completion. If the SDK's AI request
 * shape or parsing changes, this example fails with it.
 */
class AiExampleTest {

    private val connectPath = "/grom/v1/connect"
    private val modelsPath = "/v1/ai/models"
    private val chatPath = "/v1/ai/chat/completions"

    private val connectDocument = """
        {"ok":true,"schema":"grom-connect-v1","api":1,
         "principal":{"kind":"peer","nodeId":"0123456789abcdef"},
         "capabilities":["ai.chat","ai.read"],
         "ai":{"enabled":true,"configured":true,"listed":true,"items":[]},
         "services":{"listed":false,"items":[]}}
    """.trimIndent()

    private val catalogDocument = """
        {"ok":true,"defaultModel":"gpt-4o-mini","models":[
          {"id":"gpt-4o-mini","provider":"openai","providerTitle":"OpenAI",
           "title":"GPT-4o mini","available":true,"capabilities":["chat","stream"]}
        ],"providers":[]}
    """.trimIndent()

    private val chatDocument = """
        {"id":"chatcmpl-grom-1","object":"chat.completion","model":"gpt-4o-mini",
         "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4},
         "grom":{"provider":"openai","latencyMs":9}}
    """.trimIndent()

    private class FakeTransport(
        private val table: Map<String, String>,
        private val streamLines: List<String> = emptyList()
    ) : GromTransport {
        val requests = mutableListOf<GromHttpRequest>()

        override fun execute(request: GromHttpRequest): GromHttpResponse {
            requests.add(request)
            val path = request.url.substringAfter("http://127.0.0.1:47002")
            val body = table[path] ?: return GromHttpResponse(
                404,
                """{"ok":false,"error":{"code":"NOT_FOUND","message":"no such endpoint"}}"""
            )
            return GromHttpResponse(200, body)
        }

        override fun stream(
            request: GromHttpRequest,
            onLine: (String) -> Boolean
        ): GromHttpResponse {
            requests.add(request)
            for (line in streamLines) if (!onLine(line)) break
            return GromHttpResponse(200, "")
        }
    }

    private fun client(transport: GromTransport) = GromConnectClient(
        baseUrl = "http://127.0.0.1:47002",
        transport = transport,
        clock = { 1_760_000_000L },
        nonceFactory = { "abcdefgh12345678" }
    )

    private val credential = GromCredential.Session("test-token")

    @Test
    fun `the catalogue is read only with a working gateway`() {
        val transport = FakeTransport(
            mapOf(connectPath to connectDocument, modelsPath to catalogDocument)
        )
        val models = AiExample.catalog(client(transport), credential)
        assertEquals(1, models.size)
        assertEquals("gpt-4o-mini", models.single().id)
        assertTrue(models.single().supports("stream"))
    }

    @Test
    fun `an app without ai read sees an empty catalogue rather than a failure`() {
        val withoutScope = """
            {"ok":true,"schema":"grom-connect-v1","api":1,
             "principal":{"kind":"peer","nodeId":"0123456789abcdef"},
             "capabilities":["status.read"],
             "ai":{"enabled":true,"configured":true,"listed":false,"items":[]},
             "services":{"listed":false,"items":[]}}
        """.trimIndent()
        val transport = FakeTransport(mapOf(connectPath to withoutScope))
        assertTrue(AiExample.catalog(client(transport), credential).isEmpty())
    }

    @Test
    fun `one completion is returned as a plain answer`() {
        val transport = FakeTransport(
            mapOf(connectPath to connectDocument, chatPath to chatDocument)
        )
        val answer = AiExample.ask(
            client(transport),
            credential,
            "hello",
            model = "gpt-4o-mini",
            maxTokens = 32
        )
        assertEquals("hello", answer.text)
        assertEquals("gpt-4o-mini", answer.model)
        assertEquals("openai", answer.provider)
        assertEquals("stop", answer.finishReason)
        assertTrue(transport.requests.last().body.contains("\"max_tokens\":32"))
    }

    @Test
    fun `a streamed completion reports each delta and joins them`() {
        val transport = FakeTransport(
            mapOf(connectPath to connectDocument),
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val seen = StringBuilder()
        val answer = AiExample.stream(
            client(transport),
            credential,
            "say hello",
            onDelta = { seen.append(it) }
        )
        assertEquals("Hello", seen.toString())
        assertEquals("Hello", answer.text)
        assertEquals("stop", answer.finishReason)
        assertTrue(transport.requests.last().body.contains("\"stream\":true"))
    }
}
