// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import java.io.OutputStream

/**
 * What the AI gateway answers on the data plane (D94).
 *
 * It mirrors [SuperResponse] so the router does not need to know anything about
 * providers, protocols or streaming: a gateway either produces a JSON document
 * or takes over the socket and writes a stream.
 */
sealed class AiAnswer {

    data class Json(val status: Int, val payload: Map<String, Any?>) : AiAnswer()

    class Stream(
        val contentType: String,
        val write: (OutputStream, StreamControl) -> Unit
    ) : AiAnswer()
}

/**
 * The seam between the data-plane router and the AI gateway.
 *
 * The router owns authentication and scope checks; the gateway never sees a
 * principal. That split is intentional: a bug in a provider adapter cannot
 * widen a permission, and a scope change cannot alter a provider request.
 */
interface AiPort {

    /** Sanitised summary for `connect` and `/v1/status`; never a key or endpoint. */
    fun summary(): Map<String, Any?>

    /** The callable catalog, for a caller that holds `ai.read`. */
    fun models(): AiAnswer

    /** `POST /v1/ai/chat/completions` — OpenAI-compatible request and answer. */
    fun openAiChat(body: String, client: String): AiAnswer

    /** `POST /v1/ai/v1/messages` — Anthropic-compatible request and answer. */
    fun anthropicMessages(body: String, client: String): AiAnswer

    /**
     * The service this gateway publishes in the plugin registry, or null when it
     * has none. Keeping it on the port means the host needs one seam, and the
     * router and the service listing can never disagree about whether an `ai`
     * service exists.
     */
    fun service(): Plugin? = null

    companion object {

        /** Used when the runtime has not installed a gateway (the peripheral role). */
        val DISABLED: AiPort = object : AiPort {
            override fun summary(): Map<String, Any?> =
                linkedMapOf("enabled" to false, "configured" to false, "models" to 0)

            override fun models(): AiAnswer = AiAnswer.Json(
                503,
                error("AI_DISABLED", "the AI gateway is not available in this build")
            )

            override fun openAiChat(body: String, client: String): AiAnswer = models()

            override fun anthropicMessages(body: String, client: String): AiAnswer = models()
        }

        fun error(code: String, message: String): Map<String, Any?> =
            mapOf("ok" to false, "error" to mapOf("code" to code, "message" to message))
    }
}
