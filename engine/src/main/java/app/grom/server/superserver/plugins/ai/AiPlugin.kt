// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.cluster.PeerScope
import app.grom.server.http.Json
import app.grom.server.superserver.Plugin
import app.grom.server.superserver.PluginResult

/**
 * The public `ai` service (D94).
 *
 * It exists so that an application which already speaks the Grom service
 * protocol (`/v1/plugins/ai/rpc`) can discover and use the gateway without a new
 * transport. The HTTP compatibility routes serve clients that speak OpenAI or
 * Anthropic; both surfaces call the same [AiGateway], so there is one
 * implementation and one set of rules.
 *
 * It deliberately publishes **no** `scopedOps`. A `services.call` credential is
 * a permission to use services, not a permission to spend the operator's AI
 * credit; the gateway's operations are gated by their own scopes instead
 * ([requiredScopes]).
 */
class AiPlugin(
    private val gateway: AiGateway
) : Plugin {

    override val id: String = "ai"

    override val title: String = "AI"

    override val description: String =
        "Chat completions through the AI providers this operator configured; keys never leave the device."

    override fun capabilities(): Set<String> = CAPABILITIES

    override fun scopedOps(): Set<String> = emptySet()

    /**
     * `models` and `status` are reading what the gateway offers, `chat` is
     * spending money and sending data to a third party. The first two are the
     * same grant; the third is separate on purpose (S7-R019).
     */
    override fun requiredScopes(op: String): Set<PeerScope> = when (op) {
        "models", "status" -> setOf(PeerScope.AI_READ)
        "chat" -> setOf(PeerScope.AI_CHAT)
        else -> emptySet()
    }

    override fun status(): Map<String, Any?> = gateway.summary()

    override fun rpc(op: String, args: Map<String, Any?>): PluginResult = when (op) {
        "models" -> PluginResult.Ok(gateway.modelsPayload())
        "status" -> PluginResult.Ok(gateway.summary())
        "chat" -> {
            val (status, payload) = gateway.complete(Json.encode(args))
            if (status in 200..299) {
                PluginResult.Ok(payload)
            } else {
                val error = payload["error"] as? Map<*, *>
                PluginResult.Failure(
                    status,
                    error?.get("code") as? String ?: "AI_ERROR",
                    error?.get("message") as? String ?: "the AI request failed"
                )
            }
        }

        else -> PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
    }

    companion object {
        val CAPABILITIES: Set<String> = setOf("models", "chat", "status")
    }
}
