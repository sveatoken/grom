// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json
import app.grom.server.http.JsonParser

/** One message, already reduced to text. */
data class AiMessage(val role: String, val content: String)

enum class AiFinish(val openAi: String, val anthropic: String) {
    STOP("stop", "end_turn"),
    LENGTH("length", "max_tokens"),
    CONTENT_FILTER("content_filter", "end_turn"),
    OTHER("stop", "end_turn")
}

/** A chat request after the gateway validated it; nothing here is untrusted. */
data class AiChatRequest(
    val modelRef: String?,
    val messages: List<AiMessage>,
    val system: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val user: String? = null,
    val stream: Boolean = false
)

data class AiUsage(val inputTokens: Long?, val outputTokens: Long?) {
    fun total(): Long? =
        if (inputTokens == null && outputTokens == null) null
        else (inputTokens ?: 0L) + (outputTokens ?: 0L)
}

data class AiCompletion(
    val text: String,
    val finish: AiFinish,
    val usage: AiUsage?,
    /** The upstream model name the provider reported, when it reported one. */
    val upstreamModel: String?
)

sealed class AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent()
    data class Finish(val finish: AiFinish, val usage: AiUsage?) : AiStreamEvent()
    data class Failure(val problem: AiProblem) : AiStreamEvent()
}

sealed class AiParsed {
    data class Ok(val request: AiChatRequest) : AiParsed()
    data class Failure(val problem: AiProblem) : AiParsed()
}

/**
 * Body parsing for the two compatibility shapes (D94).
 *
 * The allow-lists are deliberate. A field this gateway does not implement is a
 * `400 AI_UNSUPPORTED_PARAM` that names it — never a silently dropped
 * `tools` array or `response_format`, because a client that receives a plain
 * answer while asking for tools would conclude the model ignored it.
 */
object AiBodies {

    private val OPENAI_KEYS = setOf(
        "model", "messages", "stream", "temperature", "top_p", "max_tokens",
        "max_completion_tokens", "stop", "presence_penalty", "frequency_penalty",
        "seed", "n", "user"
    )

    private val ANTHROPIC_KEYS = setOf(
        "model", "messages", "max_tokens", "system", "temperature", "top_p",
        "top_k", "stop_sequences", "stream", "metadata"
    )

    fun parseOpenAi(root: Map<String, Any?>): AiParsed {
        rejectUnknown(root, OPENAI_KEYS, "openai")?.let { return it }

        val stream = root["stream"] as? Boolean ?: false
        if (root.containsKey("stream") && root["stream"] !is Boolean) {
            return AiParsed.Failure(bad("field stream must be a boolean"))
        }
        val n = intOrNull(root["n"])
        if (n != null && n != 1) {
            return AiParsed.Failure(
                AiProblem(400, AiCodes.UNSUPPORTED_PARAM, "only n=1 is supported")
            )
        }
        val maxTokens = intOrNull(root["max_tokens"]) ?: intOrNull(root["max_completion_tokens"])
        if (root.containsKey("max_tokens") && maxTokens == null) {
            return AiParsed.Failure(bad("field max_tokens must be a number"))
        }

        val messages = ArrayList<AiMessage>()
        val system = StringBuilder()
        for (item in (root["messages"] as? List<*>).orEmpty()) {
            val message = item as? Map<*, *>
                ?: return AiParsed.Failure(bad("every message must be an object"))
            val role = (message["role"] as? String)?.lowercase()
                ?: return AiParsed.Failure(bad("every message needs a string role"))
            val text = contentText(message["content"]) ?: return AiParsed.Failure(
                AiProblem(
                    400,
                    AiCodes.NOT_SUPPORTED,
                    "only text message content is supported"
                )
            )
            when (role) {
                "system", "developer" -> system.append(if (system.isEmpty()) text else "\n$text")
                "user", "assistant" -> messages.add(AiMessage(role, text))
                else -> return AiParsed.Failure(bad("unsupported role '$role'"))
            }
        }
        if (root["messages"] == null || messages.isEmpty()) {
            return AiParsed.Failure(bad("at least one user or assistant message is required"))
        }

        return AiParsed.Ok(
            AiChatRequest(
                modelRef = (root["model"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                messages = messages,
                system = system.toString().ifEmpty { null },
                maxOutputTokens = maxTokens,
                temperature = doubleOrNull(root["temperature"]),
                topP = doubleOrNull(root["top_p"]),
                stop = stopList(root["stop"]),
                seed = longOrNull(root["seed"]),
                presencePenalty = doubleOrNull(root["presence_penalty"]),
                frequencyPenalty = doubleOrNull(root["frequency_penalty"]),
                user = (root["user"] as? String)?.take(256),
                stream = stream
            )
        )
    }

    fun parseAnthropic(root: Map<String, Any?>): AiParsed {
        rejectUnknown(root, ANTHROPIC_KEYS, "anthropic")?.let { return it }

        val stream = root["stream"] as? Boolean ?: false
        val system = when (val raw = root["system"]) {
            null -> null
            is String -> raw
            is List<*> -> raw.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
                .joinToString("\n").ifEmpty { null }
            else -> return AiParsed.Failure(bad("field system must be a string"))
        }

        val messages = ArrayList<AiMessage>()
        for (item in (root["messages"] as? List<*>).orEmpty()) {
            val message = item as? Map<*, *>
                ?: return AiParsed.Failure(bad("every message must be an object"))
            val role = (message["role"] as? String)?.lowercase()
                ?: return AiParsed.Failure(bad("every message needs a string role"))
            if (role != "user" && role != "assistant") {
                return AiParsed.Failure(bad("unsupported role '$role'; use system for instructions"))
            }
            val text = contentText(message["content"]) ?: return AiParsed.Failure(
                AiProblem(400, AiCodes.NOT_SUPPORTED, "only text message content is supported")
            )
            messages.add(AiMessage(role, text))
        }
        if (root["messages"] == null || messages.isEmpty()) {
            return AiParsed.Failure(bad("at least one message is required"))
        }
        val maxTokens = intOrNull(root["max_tokens"])
        if (root.containsKey("max_tokens") && maxTokens == null) {
            return AiParsed.Failure(bad("field max_tokens must be a number"))
        }

        return AiParsed.Ok(
            AiChatRequest(
                modelRef = (root["model"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                messages = messages,
                system = system,
                // Anthropic requires max_tokens; a missing value is filled with
                // the gateway default rather than forwarded as an invalid body.
                maxOutputTokens = maxTokens,
                temperature = doubleOrNull(root["temperature"]),
                topP = doubleOrNull(root["top_p"]),
                topK = intOrNull(root["top_k"]),
                stop = stopList(root["stop_sequences"]),
                stream = stream
            )
        )
    }

    /**
     * Text of a `content` field: a string, or a list of `{type:"text",text:…}`
     * blocks. Anything else (images, tool results, audio) is refused by name.
     */
    private fun contentText(raw: Any?): String? = when (raw) {
        is String -> raw
        is List<*> -> {
            val parts = ArrayList<String>(raw.size)
            var textOnly = true
            for (item in raw) {
                val block = item as? Map<*, *>
                if (block == null) {
                    textOnly = false
                    break
                }
                val type = (block["type"] as? String)?.lowercase()
                when (type) {
                    "text" -> parts.add((block["text"] as? String).orEmpty())
                    "input_text" -> parts.add((block["text"] as? String).orEmpty())
                    else -> {
                        textOnly = false
                        break
                    }
                }
            }
            if (textOnly) parts.joinToString("\n") else null
        }
        else -> null
    }

    private fun rejectUnknown(
        root: Map<String, Any?>,
        allowed: Set<String>,
        shape: String
    ): AiParsed.Failure? {
        for (key in root.keys) {
            if (key !in allowed) {
                return AiParsed.Failure(
                    AiProblem(
                        400,
                        AiCodes.UNSUPPORTED_PARAM,
                        "the gateway does not implement '$key' on the $shape shape"
                    )
                )
            }
        }
        return null
    }

    private fun bad(message: String): AiProblem = AiProblem(400, AiCodes.BAD_REQUEST, message)

    private fun stopList(raw: Any?): List<String> = when (raw) {
        is String -> listOf(raw)
        is List<*> -> raw.mapNotNull { it as? String }
        else -> emptyList()
    }

    private fun intOrNull(raw: Any?): Int? = (raw as? Number)?.toInt()

    private fun longOrNull(raw: Any?): Long? = (raw as? Number)?.toLong()

    private fun doubleOrNull(raw: Any?): Double? = (raw as? Number)?.toDouble()
}

/** The result of parsing one upstream body. */
sealed class AiWireResult {
    data class Ok(val completion: AiCompletion) : AiWireResult()
    data class Failure(val problem: AiProblem) : AiWireResult()
}

/**
 * One upstream protocol (D94).
 *
 * Implementations are pure: they turn a validated request into a URL, headers
 * and a body, and turn the answer back into [AiCompletion] or [AiStreamEvent].
 * The transport is the only thing that opens a socket, which is what keeps this
 * layer JVM-testable without a network.
 */
internal interface AiWire {

    fun endpoint(provider: AiProviderConfig, upstreamModel: String, stream: Boolean): String

    fun headers(provider: AiProviderConfig, apiKey: String): Map<String, String>

    fun body(provider: AiProviderConfig, request: AiChatRequest, upstreamModel: String, stream: Boolean): String

    fun parseCompletion(body: String): AiWireResult

    /** One `data:` payload of an SSE stream; null when the line carries no event. */
    fun parseStreamEvent(data: String): AiStreamEvent?

    companion object {
        fun of(protocol: AiProtocol): AiWire = when (protocol) {
            AiProtocol.OPENAI -> OpenAiWire
            AiProtocol.ANTHROPIC -> AnthropicWire
            AiProtocol.GEMINI -> GeminiWire
        }
    }
}

// -------------------------------------------------------------------- OpenAI

internal object OpenAiWire : AiWire {

    override fun endpoint(provider: AiProviderConfig, upstreamModel: String, stream: Boolean): String =
        "${provider.baseUrl.trimEnd('/')}/chat/completions"

    override fun headers(provider: AiProviderConfig, apiKey: String): Map<String, String> =
        linkedMapOf<String, String>(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        ).apply {
            if (provider.kind == AiProviderKind.OPENROUTER) {
                // OpenRouter attributes traffic with these; they carry no secret.
                put("X-Title", "Grom")
            }
        }

    override fun body(
        provider: AiProviderConfig,
        request: AiChatRequest,
        upstreamModel: String,
        stream: Boolean
    ): String {
        val messages = ArrayList<Map<String, Any?>>(request.messages.size + 1)
        request.system?.let { messages.add(mapOf("role" to "system", "content" to it)) }
        request.messages.forEach { messages.add(mapOf("role" to it.role, "content" to it.content)) }

        val body = linkedMapOf<String, Any?>(
            "model" to upstreamModel,
            "messages" to messages,
            "stream" to stream
        )
        request.temperature?.let { body["temperature"] = it }
        request.topP?.let { body["top_p"] = it }
        request.maxOutputTokens?.let { body["max_tokens"] = it }
        if (request.stop.isNotEmpty()) body["stop"] = request.stop
        request.seed?.let { body["seed"] = it }
        request.presencePenalty?.let { body["presence_penalty"] = it }
        request.frequencyPenalty?.let { body["frequency_penalty"] = it }
        request.user?.let { body["user"] = it }
        return Json.encode(body)
    }

    override fun parseCompletion(body: String): AiWireResult {
        val root = JsonParser.parseObjectOrNull(body)
            ?: return AiWireResult.Failure(
                AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered non-JSON")
            )
        val choices = root["choices"] as? List<*> ?: return AiWireResult.Failure(
            AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered without choices")
        )
        val choice = choices.firstOrNull() as? Map<*, *> ?: return AiWireResult.Failure(
            AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered with no choice")
        )
        val message = choice["message"] as? Map<*, *>
        val text = (message?.get("content") as? String)
            ?: (choice["text"] as? String)
            ?: return AiWireResult.Failure(
                AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered without text")
            )
        val usage = (root["usage"] as? Map<*, *>)?.let {
            AiUsage(
                (it["prompt_tokens"] as? Number)?.toLong(),
                (it["completion_tokens"] as? Number)?.toLong()
            )
        }
        return AiWireResult.Ok(
            AiCompletion(
                text = text,
                finish = finishOf(choice["finish_reason"] as? String),
                usage = usage,
                upstreamModel = root["model"] as? String
            )
        )
    }

    override fun parseStreamEvent(data: String): AiStreamEvent? {
        val root = JsonParser.parseObjectOrNull(data) ?: return null
        errorOf(root)?.let { return AiStreamEvent.Failure(it) }
        val choice = ((root["choices"] as? List<*>)?.firstOrNull()) as? Map<*, *> ?: return null
        val usage = (root["usage"] as? Map<*, *>)?.let {
            AiUsage(
                (it["prompt_tokens"] as? Number)?.toLong(),
                (it["completion_tokens"] as? Number)?.toLong()
            )
        }
        val finishRaw = choice["finish_reason"] as? String
        if (finishRaw != null) {
            return AiStreamEvent.Finish(finishOf(finishRaw), usage)
        }
        val delta = choice["delta"] as? Map<*, *> ?: return null
        val text = delta["content"] as? String ?: return null
        return if (text.isEmpty()) null else AiStreamEvent.Delta(text)
    }

    private fun finishOf(raw: String?): AiFinish = when (raw) {
        "stop" -> AiFinish.STOP
        "length" -> AiFinish.LENGTH
        "content_filter" -> AiFinish.CONTENT_FILTER
        else -> AiFinish.OTHER
    }
}

// ----------------------------------------------------------------- Anthropic

internal object AnthropicWire : AiWire {

    private const val VERSION = "2023-06-01"

    override fun endpoint(provider: AiProviderConfig, upstreamModel: String, stream: Boolean): String {
        val base = provider.baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    override fun headers(provider: AiProviderConfig, apiKey: String): Map<String, String> =
        linkedMapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to VERSION,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )

    override fun body(
        provider: AiProviderConfig,
        request: AiChatRequest,
        upstreamModel: String,
        stream: Boolean
    ): String {
        val body = linkedMapOf<String, Any?>(
            "model" to upstreamModel,
            "max_tokens" to (request.maxOutputTokens ?: AiLimits.DEFAULT_MAX_OUTPUT_TOKENS),
            "messages" to request.messages.map { mapOf("role" to it.role, "content" to it.content) },
            "stream" to stream
        )
        request.system?.let { body["system"] = it }
        request.temperature?.let { body["temperature"] = it }
        request.topP?.let { body["top_p"] = it }
        request.topK?.let { body["top_k"] = it }
        if (request.stop.isNotEmpty()) body["stop_sequences"] = request.stop
        return Json.encode(body)
    }

    override fun parseCompletion(body: String): AiWireResult {
        val root = JsonParser.parseObjectOrNull(body)
            ?: return AiWireResult.Failure(
                AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered non-JSON")
            )
        errorOf(root)?.let { return AiWireResult.Failure(it) }
        val blocks = root["content"] as? List<*> ?: return AiWireResult.Failure(
            AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered without content")
        )
        val text = blocks.mapNotNull { block ->
            val map = block as? Map<*, *>
            if ((map?.get("type") as? String) == "text") map["text"] as? String else null
        }.joinToString("")
        val usage = (root["usage"] as? Map<*, *>)?.let {
            AiUsage(
                (it["input_tokens"] as? Number)?.toLong(),
                (it["output_tokens"] as? Number)?.toLong()
            )
        }
        return AiWireResult.Ok(
            AiCompletion(
                text = text,
                finish = finishOf(root["stop_reason"] as? String),
                usage = usage,
                upstreamModel = root["model"] as? String
            )
        )
    }

    override fun parseStreamEvent(data: String): AiStreamEvent? {
        val root = JsonParser.parseObjectOrNull(data) ?: return null
        if ((root["type"] as? String) == "error") {
            errorOf(root)?.let { return AiStreamEvent.Failure(it) }
        }
        when (root["type"] as? String) {
            "content_block_delta" -> {
                val delta = root["delta"] as? Map<*, *>
                val text = delta?.get("text") as? String ?: return null
                return if (text.isEmpty()) null else AiStreamEvent.Delta(text)
            }
            "message_delta" -> {
                val delta = root["delta"] as? Map<*, *>
                val usage = (root["usage"] as? Map<*, *>)?.let {
                    AiUsage(
                        (it["input_tokens"] as? Number)?.toLong(),
                        (it["output_tokens"] as? Number)?.toLong()
                    )
                }
                return AiStreamEvent.Finish(finishOf(delta?.get("stop_reason") as? String), usage)
            }
            else -> return null
        }
    }

    private fun finishOf(raw: String?): AiFinish = when (raw) {
        "end_turn", "stop_sequence" -> AiFinish.STOP
        "max_tokens" -> AiFinish.LENGTH
        else -> AiFinish.OTHER
    }
}

// -------------------------------------------------------------------- Gemini

internal object GeminiWire : AiWire {

    override fun endpoint(provider: AiProviderConfig, upstreamModel: String, stream: Boolean): String {
        val base = provider.baseUrl.trimEnd('/')
        val prefix = if (base.endsWith("/v1beta")) base else "$base/v1beta"
        val name = encodePath(upstreamModel.removePrefix("models/"))
        val verb = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$prefix/models/$name:$verb"
    }

    override fun headers(provider: AiProviderConfig, apiKey: String): Map<String, String> =
        linkedMapOf(
            "x-goog-api-key" to apiKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )

    override fun body(
        provider: AiProviderConfig,
        request: AiChatRequest,
        upstreamModel: String,
        stream: Boolean
    ): String {
        val contents = request.messages.map { message ->
            mapOf(
                "role" to if (message.role == "assistant") "model" else "user",
                "parts" to listOf(mapOf("text" to message.content))
            )
        }
        val generation = linkedMapOf<String, Any?>()
        request.temperature?.let { generation["temperature"] = it }
        request.topP?.let { generation["topP"] = it }
        request.topK?.let { generation["topK"] = it }
        (request.maxOutputTokens ?: AiLimits.DEFAULT_MAX_OUTPUT_TOKENS).let {
            generation["maxOutputTokens"] = it
        }
        if (request.stop.isNotEmpty()) generation["stopSequences"] = request.stop

        val body = linkedMapOf<String, Any?>(
            "contents" to contents,
            "generationConfig" to generation
        )
        request.system?.let {
            body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to it)))
        }
        return Json.encode(body)
    }

    override fun parseCompletion(body: String): AiWireResult {
        val root = JsonParser.parseObjectOrNull(body)
            ?: return AiWireResult.Failure(
                AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered non-JSON")
            )
        errorOf(root)?.let { return AiWireResult.Failure(it) }
        val candidate = ((root["candidates"] as? List<*>)?.firstOrNull()) as? Map<*, *>
            ?: return AiWireResult.Failure(
                AiProblem(502, AiCodes.BAD_RESPONSE, "the provider answered without candidates")
            )
        val text = textOf(candidate["content"])
        val usage = (root["usageMetadata"] as? Map<*, *>)?.let {
            AiUsage(
                (it["promptTokenCount"] as? Number)?.toLong(),
                (it["candidatesTokenCount"] as? Number)?.toLong()
            )
        }
        return AiWireResult.Ok(
            AiCompletion(
                text = text,
                finish = finishOf(candidate["finishReason"] as? String),
                usage = usage,
                upstreamModel = root["modelVersion"] as? String
            )
        )
    }

    override fun parseStreamEvent(data: String): AiStreamEvent? {
        val root = JsonParser.parseObjectOrNull(data) ?: return null
        errorOf(root)?.let { return AiStreamEvent.Failure(it) }
        val candidate = ((root["candidates"] as? List<*>)?.firstOrNull()) as? Map<*, *>
            ?: return null
        val usage = (root["usageMetadata"] as? Map<*, *>)?.let {
            AiUsage(
                (it["promptTokenCount"] as? Number)?.toLong(),
                (it["candidatesTokenCount"] as? Number)?.toLong()
            )
        }
        val finishRaw = candidate["finishReason"] as? String
        if (finishRaw != null) {
            return AiStreamEvent.Finish(finishOf(finishRaw), usage)
        }
        val text = textOf(candidate["content"])
        return if (text.isEmpty()) null else AiStreamEvent.Delta(text)
    }

    private fun textOf(raw: Any?): String {
        val parts = ((raw as? Map<*, *>)?.get("parts") as? List<*>) ?: return ""
        return parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
    }

    private fun finishOf(raw: String?): AiFinish = when (raw) {
        "STOP" -> AiFinish.STOP
        "MAX_TOKENS" -> AiFinish.LENGTH
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> AiFinish.CONTENT_FILTER
        else -> AiFinish.OTHER
    }

    /** Percent-encodes a path segment; `models/gemini-1.5-flash` keeps its dash. */
    private fun encodePath(value: String): String = buildString(value.length + 8) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            val safe = ch in 0x30..0x39 || ch in 0x41..0x5A || ch in 0x61..0x7A ||
                ch == '-'.code || ch == '.'.code || ch == '_'.code || ch == '~'.code
            if (safe) {
                append(ch.toChar())
            } else {
                append('%').append(HEX[ch shr 4]).append(HEX[ch and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

// -------------------------------------------------------------------- errors

/**
 * Maps a provider failure onto a Grom code (D94).
 *
 * The upstream explanation is kept, truncated and free of anything that looks
 * like a credential; the URL, the headers and the request body never appear in a
 * message, because those carry the API key.
 */
internal object AiErrors {

    fun fromUpstream(
        status: Int,
        body: String,
        retryAfterSeconds: Int,
        secret: String? = null
    ): AiProblem {
        val detail = detailOf(body)?.let { redactSecret(it, secret) }
        return when (status) {
            400, 404, 422 -> AiProblem(
                if (status == 404) 404 else 400,
                if (status == 404) AiCodes.MODEL_NOT_FOUND else AiCodes.UPSTREAM_BAD_REQUEST,
                if (status == 404) "the provider does not know that model"
                else "the provider rejected the request",
                detail = detail
            )
            401, 403 -> AiProblem(
                502,
                AiCodes.UPSTREAM_UNAUTHORIZED,
                "the provider rejected the stored API key",
                detail = detail
            )
            429 -> AiProblem(
                429,
                AiCodes.UPSTREAM_RATE_LIMITED,
                "the provider is rate limiting this key",
                retryAfterSeconds = retryAfterSeconds.coerceAtLeast(1),
                detail = detail
            )
            in 500..599 -> AiProblem(
                502,
                AiCodes.UPSTREAM_ERROR,
                "the provider reported a server error",
                detail = detail
            )
            else -> AiProblem(
                502,
                AiCodes.UPSTREAM_ERROR,
                "the provider answered HTTP $status",
                detail = detail
            )
        }
    }

    /**
     * The provider's own explanation, with the credential taken out (D95).
     *
     * A provider is free to echo whatever it likes in an error — including the
     * key it just rejected. That text is returned to the calling application, so
     * the stored secret is removed before it leaves the gateway, and the detail
     * is left as `null` when nothing but the key was in it.
     */
    fun redactSecret(text: String, secret: String?): String? {
        val cleaned = if (secret.isNullOrEmpty()) text else text.replace(secret, REDACTED)
        return cleaned.trim().ifEmpty { null }
    }

    /** Applies [redactSecret] to an already-built problem, e.g. a 2xx error body. */
    fun redact(problem: AiProblem, secret: String?): AiProblem {
        val detail = problem.detail?.let { redactSecret(it, secret) } ?: return problem
        return if (detail == problem.detail) problem else problem.copy(detail = detail)
    }

    /** `{"error":{"message":"…"}}`, `{"error":"…"}`, `{"message":"…"}`. */
    fun detailOf(body: String): String? {
        val root = JsonParser.parseObjectOrNull(body) ?: return null
        val raw = when (val error = root["error"]) {
            is Map<*, *> -> error["message"] as? String ?: error["type"] as? String
            is String -> error
            else -> root["message"] as? String
        } ?: return null
        return raw.trim().take(300).ifEmpty { null }
    }

    /** A provider answer that carries an error object with a 200 status. */
    fun errorOf(root: Map<*, *>): AiProblem? {
        val error = root["error"] ?: return null
        val message = when (error) {
            is Map<*, *> -> (error["message"] as? String) ?: "the provider reported an error"
            is String -> error
            else -> "the provider reported an error"
        }
        return AiProblem(502, AiCodes.UPSTREAM_ERROR, "the provider reported an error", detail = message.take(300))
    }

    /** What the stored key is replaced with; never a partial fragment of it. */
    private const val REDACTED = "[redacted]"
}

/** Shared by the wire adapters: the error object inside a 2xx body. */
internal fun errorOf(root: Map<*, *>): AiProblem? = AiErrors.errorOf(root)
