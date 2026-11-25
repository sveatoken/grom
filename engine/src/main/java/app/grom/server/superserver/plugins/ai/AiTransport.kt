// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

/** A raw upstream answer; the wire adapter decides what it means. */
data class AiHttpReply(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Int = 0
)

/** The transport could not complete the request. Never carries a URL or key. */
open class AiTransportException(message: String) : RuntimeException(message)

/**
 * The provider did not answer inside the configured timeout. It is a separate
 * type because "slow" and "unreachable" deserve different codes and different
 * advice: one is retried, the other is usually a wrong endpoint.
 */
class AiTimeoutException(message: String = "the provider did not answer in time") :
    AiTransportException(message)

/**
 * The seam between the AI gateway and the network (D94).
 *
 * Same shape as [app.grom.server.superserver.net.JsonTransport] for the reason
 * that made that one testable: the gateway is JVM-tested against a fake
 * transport, so every parser, header rule and error mapping is covered without
 * a socket. Only `superserver/net/AiHttpTransport` implements this with the
 * platform HTTP client, and policy #63 pins that.
 */
interface AiTransport {

    /**
     * One request/response exchange.
     *
     * [timeoutMs] bounds both connect and read. Implementations MUST NOT follow
     * redirects: a `Location` would carry the API key to another host.
     */
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int
    ): AiHttpReply

    /**
     * A streaming exchange. [onLine] receives each line of the body (without the
     * terminator) while the status is 2xx; returning false stops the read.
     *
     * On a non-2xx status the body is returned instead and [onLine] is never
     * called, so the caller can map the provider's error before any byte of the
     * client's stream has been written.
     */
    fun stream(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
        onLine: (String) -> Boolean
    ): AiHttpReply
}

/**
 * Transport used when the operator has not enabled the gateway. It fails every
 * call loudly instead of pretending an answer exists.
 */
object UnavailableAiTransport : AiTransport {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int
    ): AiHttpReply = throw AiTransportException("the AI transport is not installed")

    override fun stream(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
        onLine: (String) -> Boolean
    ): AiHttpReply = throw AiTransportException("the AI transport is not installed")
}
