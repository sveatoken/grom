// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.connect

import app.grom.sdk.connect.GromAiModel
import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromCredential
import app.grom.sdk.connect.GromConnectException

/**
 * The reference AI flow for S7 (D94).
 *
 * This is what "use the phone's AI gateway" looks like in a host application:
 * discover, read the catalogue, pick a model, ask, and — if the user is waiting —
 * stream. It uses only `:sdk`, which speaks the existing HTTP/JSON API.
 *
 * The four steps map to the specification:
 *
 *   1. discovery   `connect()` — is there a gateway, and may I read it?
 *   2. catalogue   `aiCatalog()` — what the operator allows me to spend on
 *   3. one answer  `aiChat()`
 *   4. streaming   `aiChatStream()` — text as the provider produces it
 *
 * A host should render [Answer] (or its own strings) and show any
 * `GromConnectException.code` as a message, never a stack trace. An empty
 * catalogue is a normal state: the operator has not configured a provider.
 */
object AiExample {

    /** What a host shows after one completion. */
    data class Answer(
        val text: String,
        val model: String?,
        val provider: String?,
        val finishReason: String?
    )

    /**
     * The models this credential may call. Empty means "the operator offers
     * none" — either the gateway is off or the key was not granted `ai.read`.
     * `available` is configuration, so a host must not paint it as health.
     */
    fun catalog(client: GromConnectClient, credential: GromCredential): List<GromAiModel> {
        val info = client.connect(credential)
        // Asking without the scope is a 403; checking first lets a host show
        // "AI is not shared with this app" instead of an error dialog.
        if (!info.has("ai.read") && info.principalKind != "operator") return emptyList()
        return try {
            client.aiCatalog(credential).models
        } catch (_: GromConnectException) {
            emptyList()
        }
    }

    /**
     * One completion. The model defaults to the operator's choice, which is
     * why the catalogue is read first: a host that lets the user pick should
     * offer exactly these ids.
     */
    fun ask(
        client: GromConnectClient,
        credential: GromCredential,
        prompt: String,
        model: String? = null,
        maxTokens: Int? = null
    ): Answer {
        val reply = client.aiChat(credential, prompt, model = model, maxTokens = maxTokens)
        return Answer(
            text = reply.text,
            model = reply.model,
            provider = reply.provider,
            finishReason = reply.finishReason
        )
    }

    /**
     * The same request, reported while it arrives. [onDelta] runs on the
     * calling thread, so a UI host must marshal it to its own dispatcher.
     */
    fun stream(
        client: GromConnectClient,
        credential: GromCredential,
        prompt: String,
        model: String? = null,
        onDelta: (String) -> Unit
    ): Answer {
        val reply = client.aiChatStream(credential, prompt, model = model, onDelta = onDelta)
        return Answer(
            text = reply.text,
            model = reply.model,
            provider = reply.provider,
            finishReason = reply.finishReason
        )
    }
}
