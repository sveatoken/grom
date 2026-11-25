// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.AiAnswer
import app.grom.server.superserver.net.AiHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * The gateway over a real socket (D94).
 *
 * A loopback server stands in for the provider: it speaks plain HTTP, records
 * the request it received, and answers with a provider-shaped document. Nothing
 * is mocked between the gateway and that server — the same `AiHttpTransport` the
 * phone uses does the writing and reading, so this test fails if a header, a URL
 * or the body framing is wrong.
 *
 * A live check runs only when the operator exports real credentials; see
 * [live upstream call]. Without them it is skipped rather than faked.
 */
class AiGatewayIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "v1:$plaintext"
        override fun open(sealed: String): String? =
            if (sealed.startsWith("v1:")) sealed.removePrefix("v1:") else null
    }

    /** One request served by [withUpstream]. */
    private class Received(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    /**
     * Serves exactly one HTTP request with [response] and returns what it read.
     */
    private fun <T> withUpstream(
        contentType: String,
        response: String,
        block: (baseUrl: String, received: () -> Received) -> T
    ): T {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = server.localPort
        var captured: Received? = null
        val worker = thread(isDaemon = true, name = "fake-provider") {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val head = ArrayList<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        head.add(line)
                    }
                    val headers = head.drop(1).mapNotNull { line ->
                        val index = line.indexOf(':')
                        if (index <= 0) null
                        else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                    }.toMap()
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val chunk = reader.read(body, read, length - read)
                        if (chunk < 0) break
                        read += chunk
                    }
                    val method = head.firstOrNull()?.substringBefore(' ') ?: "?"
                    val path = head.firstOrNull()?.substringAfter(' ')?.substringBefore(' ') ?: "?"
                    captured = Received(method, path, headers, String(body, 0, read))

                    val bytes = response.toByteArray(Charsets.UTF_8)
                    val answer = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(answer.toByteArray(Charsets.ISO_8859_1))
                        write(bytes)
                        flush()
                    }
                }
            } finally {
                runCatching { server.close() }
            }
        }

        return try {
            block("http://127.0.0.1:$port", { captured ?: error("the provider saw no request") })
        } finally {
            worker.join(5_000)
            runCatching { server.close() }
        }
    }

    private fun gatewayAt(baseUrl: String, kind: AiProviderKind = AiProviderKind.OPENAI): AiGateway {
        val store = AiStore(folder.newFolder(), Box())
        assertEquals(
            null,
            store.save(
                AiGatewayConfig(
                    enabled = true,
                    defaultModel = "m",
                    providers = listOf(
                        AiProviderConfig(
                            id = "local",
                            kind = kind,
                            title = "Local",
                            baseUrl = baseUrl,
                            apiKeySealed = store.sealKey("sk-local")
                        )
                    ),
                    models = listOf(AiModel("m", "local", "test-model", "Test"))
                )
            )
        )
        return AiGateway(store = store, transport = AiHttpTransport())
    }

    @Test
    fun `a completion travels to a real http endpoint and comes back parsed`() {
        val upstream = """{
          "id":"chatcmpl-local","object":"chat.completion","model":"test-model",
          "choices":[{"index":0,"message":{"role":"assistant","content":"from the socket"},"finish_reason":"stop"}],
          "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}
        }"""
        withUpstream("application/json", upstream) { baseUrl, received ->
            val answer = gatewayAt(baseUrl).openAiChat(
                """{"model":"m","messages":[{"role":"user","content":"over the wire"}]}""",
                "client"
            ) as AiAnswer.Json

            assertEquals(200, answer.status)
            val choice = (answer.payload["choices"] as List<*>).single() as Map<*, *>
            assertEquals("from the socket", (choice["message"] as Map<*, *>)["content"])

            val request = received()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer sk-local", request.headers["authorization"])
            val body = JsonParser.parseObjectOrNull(request.body)!!
            assertEquals("test-model", body["model"])
            assertEquals("over the wire", ((body["messages"] as List<*>).single() as Map<*, *>)["content"])
        }
    }

    @Test
    fun `a streamed completion is framed as sse while it arrives`() {
        val events = """
            data: {"choices":[{"delta":{"content":"one "}}]}

            data: {"choices":[{"delta":{"content":"two"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"length"}]}

            data: [DONE]

        """.trimIndent() + "\n"

        withUpstream("text/event-stream", events) { baseUrl, received ->
            val answer = gatewayAt(baseUrl).openAiChat(
                """{"model":"m","messages":[{"role":"user","content":"stream"}],"stream":true}""",
                "client"
            )
            assertTrue(answer is AiAnswer.Stream)

            val out = ByteArrayOutputStream()
            (answer as AiAnswer.Stream).write(
                out,
                object : app.grom.server.superserver.StreamControl {
                    override fun isCancelled(): Boolean = false
                }
            )
            val text = out.toString("UTF-8")
            assertTrue(text, text.contains("\"content\":\"one \""))
            assertTrue(text, text.contains("\"content\":\"two\""))
            assertTrue(text, text.contains("\"finish_reason\":\"length\""))
            assertTrue(text, text.endsWith("data: [DONE]\n\n"))
            assertEquals("/chat/completions", received().path)
        }
    }

    @Test
    fun `an anthropic shaped endpoint is addressed the way anthropic expects`() {
        val upstream = """{
          "id":"msg_local","type":"message","role":"assistant","model":"test-model",
          "content":[{"type":"text","text":"anthropic over the wire"}],
          "stop_reason":"end_turn","usage":{"input_tokens":2,"output_tokens":4}
        }"""
        withUpstream("application/json", upstream) { baseUrl, received ->
            val answer = gatewayAt(baseUrl, AiProviderKind.ANTHROPIC).anthropicMessages(
                """{"model":"m","max_tokens":16,"messages":[{"role":"user","content":"hello"}]}""",
                "client"
            ) as AiAnswer.Json
            assertEquals(200, answer.status)

            val request = received()
            assertEquals("/v1/messages", request.path)
            assertEquals("sk-local", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
        }
    }

    /**
     * Live upstream call (D96).
     *
     * Skipped unless the operator exports a real endpoint. This is the only test
     * that spends someone's credit and talks to the internet, so it never runs by
     * default — and when it is skipped the suite says so rather than pretending.
     *
     *   GROM_AI_LIVE_BASE_URL   e.g. https://api.deepseek.com/v1
     *   GROM_AI_LIVE_API_KEY    a real key
     *   GROM_AI_LIVE_MODEL      the upstream model name
     *   GROM_AI_LIVE_PROTOCOL   openai|anthropic|gemini (default openai)
     */
    @Test
    fun `live upstream call`() {
        val baseUrl = System.getenv("GROM_AI_LIVE_BASE_URL")
        val apiKey = System.getenv("GROM_AI_LIVE_API_KEY")
        val model = System.getenv("GROM_AI_LIVE_MODEL")
        assumeTrue(
            "no live AI credentials exported (GROM_AI_LIVE_BASE_URL / _API_KEY / _MODEL)",
            !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && !model.isNullOrBlank()
        )

        val protocol = AiProtocol.fromWire(System.getenv("GROM_AI_LIVE_PROTOCOL")) ?: AiProtocol.OPENAI
        val store = AiStore(folder.newFolder(), Box())
        assertEquals(
            null,
            store.save(
                AiGatewayConfig(
                    enabled = true,
                    defaultModel = "live",
                    providers = listOf(
                        AiProviderConfig(
                            id = "live",
                            kind = AiProviderKind.CUSTOM,
                            title = "Live",
                            baseUrl = baseUrl!!,
                            apiKeySealed = store.sealKey(apiKey!!),
                            timeoutMs = 60_000,
                            protocol = protocol
                        )
                    ),
                    models = listOf(AiModel("live", "live", model!!, "Live"))
                )
            )
        )
        val gateway = AiGateway(store = store, transport = AiHttpTransport())
        val probe = gateway.probe("live")
        assertEquals("probe failed: $probe", true, probe["ok"])

        val answer = gateway.openAiChat(
            """{"model":"live","messages":[{"role":"user","content":"Reply with the word ok."}],"max_tokens":16}""",
            "live"
        ) as AiAnswer.Json
        assertEquals(200, answer.status)
        val choice = (answer.payload["choices"] as List<*>).single() as Map<*, *>
        val text = ((choice["message"] as Map<*, *>)["content"] as String).trim()
        assertTrue("the provider answered nothing", text.isNotEmpty())
        // A live check prints nothing secret; it only proves the round trip.
        println("live AI gateway: ${JsonPreview.of(answer.payload)}")
    }

    private object JsonPreview {
        fun of(payload: Map<String, Any?>): String {
            val grom = payload["grom"] as? Map<*, *>
            return "provider=${grom?.get("provider")} model=${payload["model"]} " +
                "latencyMs=${grom?.get("latencyMs")} " +
                "chars=${
                    (payload["choices"] as? List<*>)?.size?.let { it } ?: 0
                }"
        }
    }

    @Suppress("unused")
    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
