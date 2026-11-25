// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope

/**
 * The plugin contract of the public data plane (D39, S5-R020…S5-R026).
 *
 * A plugin answers a small, fixed vocabulary. It is deliberately *not* a
 * dynamic loader: the registry is built in code, so nothing that arrives over
 * the network can introduce a new implementation.
 *
 * Honesty rules (S1-R017, S5-R022):
 *   - an operation the backend cannot perform returns `501 NOT_SUPPORTED` with
 *     the exact capability name — never a plausible-looking empty value;
 *   - a backend that is configured but unreachable returns `503`;
 *   - a backend that was never configured returns `503 <PLUGIN>_UNCONFIGURED`;
 *   - a measured value is either present or the call fails. Grom never invents
 *     a number to fill a hole.
 */
sealed class PluginResult {

    data class Ok(val payload: Map<String, Any?>) : PluginResult()

    data class Failure(
        val status: Int,
        val code: String,
        val message: String
    ) : PluginResult()
}

interface Plugin {

    /** Stable wire identifier; used in `/v1/plugins/{id}/rpc`. */
    val id: String

    /** Human title for the operator surface. */
    val title: String

    val description: String

    /** Capabilities the plugin can currently serve, e.g. `status`. */
    fun capabilities(): Set<String>

    /**
     * The operations a client holding `services.call` may invoke (D92).
     *
     * Empty means the service is operator-only, which is the safe default: a
     * plugin that forgets to declare this exposes nothing to a connected app.
     * Sensitive operations — reading the full status, changing configuration,
     * moving funds — MUST stay out of this set, because the route-level scope
     * only says "this caller may use services", not "this caller may do
     * anything a service can do".
     */
    fun scopedOps(): Set<String> = emptySet()

    /**
     * The scopes an operation requires *instead of* `services.call` (D94).
     *
     * Empty is the established meaning: the op is either operator-only or one of
     * [scopedOps]. A non-empty set names a narrower, purpose-built grant — the
     * AI gateway uses `ai.read` and `ai.chat` so that holding "may use services"
     * never means "may spend the operator's AI credit". The gate is the scopes,
     * not `services.call`, because they say what the caller may do, not merely
     * that it may call something.
     */
    fun requiredScopes(op: String): Set<PeerScope> = emptySet()

    /** Side-effect-free snapshot for `/v1/plugins`. Contains no secrets. */
    fun status(): Map<String, Any?>

    fun rpc(op: String, args: Map<String, Any?>): PluginResult
}
