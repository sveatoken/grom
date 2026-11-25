// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json

/**
 * The wire documents the gateway emits (D94).
 *
 * Rendering lives here, apart from the gateway, so a test can assert the exact
 * bytes of an OpenAI chunk or an Anthropic event without a socket — and so both
 * compatibility shapes stay obviously parallel.
 */
object AiFrames {

    fun openAiCompletion(
        id: String,
        model: String,
        completion: AiCompletion,
        provider: String,
        latencyMs: Long
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "object" to "chat.completion",
        "created" to System.currentTimeMillis() / 1000,
        "model" to model,
        "choices" to listOf(
            linkedMapOf(
                "index" to 0,
                "message" to linkedMapOf("role" to "assistant", "content" to completion.text),
                "finish_reason" to completion.finish.openAi
            )
        ),
        "usage" to usageJson(completion.usage),
        "grom" to linkedMapOf(
            "provider" to provider,
            "upstreamModel" to completion.upstreamModel,
            "latencyMs" to latencyMs
        )
    )

    fun anthropicMessage(
        id: String,
        model: String,
        completion: AiCompletion,
        provider: String,
        latencyMs: Long
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "type" to "message",
        "role" to "assistant",
        "model" to model,
        "content" to listOf(
            linkedMapOf("type" to "text", "text" to completion.text)
        ),
        "stop_reason" to completion.finish.anthropic,
        "stop_sequence" to null,
        "usage" to linkedMapOf(
            "input_tokens" to (completion.usage?.inputTokens ?: 0L),
            "output_tokens" to (completion.usage?.outputTokens ?: 0L)
        ),
        "grom" to linkedMapOf(
            "provider" to provider,
            "upstreamModel" to completion.upstreamModel,
            "latencyMs" to latencyMs
        )
    )

    private fun usageJson(usage: AiUsage?): Map<String, Any?> = linkedMapOf(
        "prompt_tokens" to (usage?.inputTokens ?: 0L),
        "completion_tokens" to (usage?.outputTokens ?: 0L),
        "total_tokens" to (usage?.total() ?: 0L)
    )

    // --------------------------------------------------------------- openai SSE

    fun openAiDelta(id: String, model: String, text: String): String =
        sse("data: " + Json.encode(chunk(id, model, text, finish = null) ))

    fun openAiFinish(id: String, model: String, finish: AiFinish, usage: AiUsage?): String =
        sse(
            "data: " + Json.encode(
                linkedMapOf(
                    "id" to id,
                    "object" to "chat.completion.chunk",
                    "created" to System.currentTimeMillis() / 1000,
                    "model" to model,
                    "choices" to listOf(
                        linkedMapOf(
                            "index" to 0,
                            "delta" to emptyMap<String, Any?>(),
                            "finish_reason" to finish.openAi
                        )
                    ),
                    "usage" to usageJson(usage)
                )
            )
        )

    fun openAiDone(): String = sse("data: [DONE]")

    fun openAiError(id: String, problem: AiProblem): String = sse(
        "data: " + Json.encode(
            linkedMapOf(
                "id" to id,
                "object" to "chat.completion.chunk",
                "error" to errorJson(problem)
            )
        )
    )

    private fun chunk(id: String, model: String, text: String, finish: String?): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "object" to "chat.completion.chunk",
            "created" to System.currentTimeMillis() / 1000,
            "model" to model,
            "choices" to listOf(
                linkedMapOf(
                    "index" to 0,
                    "delta" to linkedMapOf("content" to text),
                    "finish_reason" to finish
                )
            )
        )

    // ------------------------------------------------------------ anthropic SSE

    fun anthropicStart(id: String, model: String): String = anthropicEvent(
        "message_start",
        linkedMapOf(
            "type" to "message_start",
            "message" to linkedMapOf(
                "id" to id,
                "type" to "message",
                "role" to "assistant",
                "model" to model,
                "content" to emptyList<Any?>(),
                "stop_reason" to null,
                "usage" to linkedMapOf("input_tokens" to 0L, "output_tokens" to 0L)
            )
        )
    )

    fun anthropicBlockStart(): String = anthropicEvent(
        "content_block_start",
        linkedMapOf(
            "type" to "content_block_start",
            "index" to 0,
            "content_block" to linkedMapOf("type" to "text", "text" to "")
        )
    )

    fun anthropicDelta(text: String): String = anthropicEvent(
        "content_block_delta",
        linkedMapOf(
            "type" to "content_block_delta",
            "index" to 0,
            "delta" to linkedMapOf("type" to "text_delta", "text" to text)
        )
    )

    fun anthropicStop(finish: AiFinish, usage: AiUsage?): String = buildString {
        append(
            anthropicEvent(
                "content_block_stop",
                linkedMapOf("type" to "content_block_stop", "index" to 0)
            )
        )
        append(
            anthropicEvent(
                "message_delta",
                linkedMapOf(
                    "type" to "message_delta",
                    "delta" to linkedMapOf("stop_reason" to finish.anthropic),
                    "usage" to linkedMapOf(
                        "output_tokens" to (usage?.outputTokens ?: 0L)
                    )
                )
            )
        )
        append(anthropicEvent("message_stop", linkedMapOf("type" to "message_stop")))
    }

    fun anthropicError(problem: AiProblem): String = anthropicEvent(
        "error",
        linkedMapOf(
            "type" to "error",
            "error" to linkedMapOf(
                "type" to problem.code.lowercase(),
                "message" to problem.message
            )
        )
    )

    private fun anthropicEvent(name: String, payload: Map<String, Any?>): String =
        "event: $name\ndata: ${Json.encode(payload)}\n\n"

    private fun sse(payload: String): String = "$payload\n\n"

    fun errorJson(problem: AiProblem): Map<String, Any?> = linkedMapOf(
        "code" to problem.code,
        "message" to problem.message,
        "detail" to problem.detail
    )
}

/** The projections: what an operator sees, and what a connected application sees. */
object AiDocuments {

    /**
     * Operator-facing (Binder, signature-gated). Endpoints and key *presence* —
     * never a key, sealed or otherwise.
     */
    fun operatorStatus(
        config: AiGatewayConfig,
        loadError: String?,
        probes: Map<String, Map<String, Any?>>
    ): Map<String, Any?> = linkedMapOf(
        "enabled" to config.enabled,
        "configured" to config.usableModels().isNotEmpty(),
        "defaultModel" to config.defaultModel,
        "error" to loadError,
        "providerCount" to config.providers.size,
        "modelCount" to config.models.size,
        "providers" to config.providers.map { provider ->
            provider.toOperatorJson(config.modelsOf(provider.id).size) +
                mapOf("probe" to probes[provider.id])
        },
        // `available` is computed the same way the data-plane catalogue computes
        // it, so the screen's lamp and a client's decision cannot disagree.
        "models" to config.models.map { model ->
            model.toOperatorJson(
                config.provider(model.providerId)?.title ?: model.providerId
            ) + linkedMapOf(
                "available" to (
                    model.enabled && config.usableProviders().any { it.id == model.providerId }
                    )
            )
        }
    )

    /**
     * Data-plane summary (D95). Shipped in `connect` and `/v1/status`: no base
     * URL, no key flag detail and no upstream model name.
     */
    fun publicSummary(config: AiGatewayConfig): Map<String, Any?> = linkedMapOf(
        "enabled" to config.enabled,
        "configured" to config.usableModels().isNotEmpty(),
        "defaultModel" to config.defaultModel,
        "providerCount" to config.providers.size,
        "modelCount" to config.models.size,
        "providers" to config.providers.map { it.toPublicJson(config.modelsOf(it.id).size) }
    )

    /**
     * The callable catalog. `available` is derived from configuration (enabled
     * provider, stored key, enabled model) and is not a promise that the
     * provider will answer — that is only known when a call is made.
     */
    fun modelsDocument(config: AiGatewayConfig): Map<String, Any?> {
        val usableProviders = config.usableProviders().associateBy { it.id }
        return linkedMapOf(
            "ok" to true,
            "defaultModel" to config.defaultModel,
            "models" to config.models.map { model ->
                val provider = config.provider(model.providerId)
                model.toPublicJson(provider?.title ?: model.providerId) + linkedMapOf(
                    "available" to (
                        model.enabled && usableProviders.containsKey(model.providerId)
                        )
                )
            },
            "providers" to config.providers.map { it.toPublicJson(config.modelsOf(it.id).size) }
        )
    }
}
