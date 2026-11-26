// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import java.security.SecureRandom
import java.util.Base64

/**
 * One server calling another (D100).
 *
 * There is no second protocol here. The caller fetches the peer's own
 * `/grom/v1/connect` document and then calls exactly the routes an ordinary
 * client would: `/v1/plugins/{id}/rpc` for a service, or the AI gateway's
 * `/v1/ai/…` routes. Every request is signed with this device's Ed25519
 * identity, so the peer verifies it through the same [RequestSigner] path and
 * applies the same scopes it would apply to any peer (D73, D92, D94).
 *
 * Two limits are enforced *before* a socket is opened, so a misconfiguration is
 * a stable code instead of a remote 403 storm:
 *   - the operation must be one the peer advertised as callable;
 *   - this device must hold the scope the peer requires (`services.call`, or
 *     `ai.read`/`ai.chat` for the AI routes).
 *
 * The address and the key never appear in an error: both end up in a UI.
 */
class MeshCaller(
    private val transport: JsonTransport = app.grom.server.superserver.net.HttpJsonClient(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
    private val timeoutMs: Int = 10_000,
    private val maxBodyBytes: Int = MAX_BODY_BYTES
) {

    sealed class Reply {
        data class Json(val status: Int, val payload: Any?) : Reply()

        data class Failed(val status: Int, val code: String, val message: String) : Reply()
    }

    /**
     * Reads what the peer serves. A peer that did not grant `services.read`
     * answers `listed:false`, which is preserved rather than turned into "no
     * services".
     */
    fun fetchCatalog(peer: PeerRecord, seed: ByteArray): RemoteCatalog {
        val now = clockSeconds() * 1000
        if (peer.isInboundOnly()) {
            return RemoteCatalog.failed(peer.nodeId, now, "MESH_NO_ADDRESS")
        }
        if (!peer.grantedByPeer.contains(PeerScope.SERVICES_READ)) {
            return RemoteCatalog.failed(peer.nodeId, now, "MESH_SCOPE_NOT_GRANTED")
        }
        val response = try {
            signed("GET", peer.address, CONNECT_PATH, "", seed)
        } catch (_: TransportException) {
            return RemoteCatalog.failed(peer.nodeId, now, "MESH_UNREACHABLE")
        } catch (_: Exception) {
            return RemoteCatalog.failed(peer.nodeId, now, "MESH_UNREACHABLE")
        }
        val code = when (response.status) {
            200 -> null
            401 -> "MESH_UNAUTHORIZED"
            403 -> "MESH_SCOPE_NOT_GRANTED"
            in 300..399 -> "MESH_REDIRECTED"
            else -> "MESH_BAD_RESPONSE"
        }
        if (code != null) return RemoteCatalog.failed(peer.nodeId, now, code)
        return MeshCatalog.parse(peer.nodeId, response.body, now)
    }

    /**
     * Calls one operation on [peer].
     *
     * [service] is a plugin id, or the reserved `ai` for the AI gateway routes.
     * [op] is the operation the peer advertised. [args] is used by a plugin
     * operation; [aiBody] by an AI one. Exactly one of them applies and the
     * caller passes an empty map for the other.
     */
    fun call(
        peer: PeerRecord,
        catalog: RemoteCatalog?,
        seed: ByteArray,
        service: String,
        op: String,
        args: Map<String, Any?> = emptyMap(),
        aiBody: Map<String, Any?>? = null
    ): Reply {
        if (peer.isInboundOnly()) return fail(409, "MESH_NO_ADDRESS", "this server has no endpoint to call")
        if (!SERVICE.matches(service)) {
            return fail(400, "MESH_UNKNOWN_SERVICE", "not a service id")
        }
        if (!OP.matches(op)) return fail(400, "MESH_BAD_OP", "not an operation name")

        if (service == AI_SERVICE) {
            return callAi(peer, catalog, seed, op, aiBody)
        }
        return callPlugin(peer, catalog, seed, service, op, args)
    }

    // ------------------------------------------------------------------- AI

    private fun callAi(
        peer: PeerRecord,
        catalog: RemoteCatalog?,
        seed: ByteArray,
        op: String,
        body: Map<String, Any?>?
    ): Reply {
        if (catalog != null && !catalog.aiEnabled) {
            return fail(409, "MESH_UNKNOWN_SERVICE", "the peer has no AI gateway")
        }
        val (path, scope) = when (op) {
            "models" -> AI_MODELS to PeerScope.AI_READ
            "chat" -> AI_CHAT to PeerScope.AI_CHAT
            "messages" -> AI_MESSAGES to PeerScope.AI_CHAT
            else -> return fail(400, "MESH_OP_NOT_ALLOWED", "the AI gateway does not expose that op")
        }
        if (!peer.grantedByPeer.contains(scope)) {
            return fail(403, "MESH_SCOPE_NOT_GRANTED", "the peer did not grant ${scope.wireName}")
        }
        val encoded = if (op == "models") "" else Json.encode(body ?: emptyMap<String, Any?>())
        if (encoded.toByteArray(Charsets.UTF_8).size > maxBodyBytes) {
            return fail(413, "MESH_PAYLOAD_TOO_LARGE", "the request is larger than this server relays")
        }
        val method = if (op == "models") "GET" else "POST"
        return execute(peer, method, path, encoded, seed)
    }

    // --------------------------------------------------------------- plugin

    private fun callPlugin(
        peer: PeerRecord,
        catalog: RemoteCatalog?,
        seed: ByteArray,
        service: String,
        op: String,
        args: Map<String, Any?>
    ): Reply {
        if (!peer.grantedByPeer.contains(PeerScope.SERVICES_CALL)) {
            return fail(403, "MESH_SCOPE_NOT_GRANTED", "the peer did not grant services.call")
        }
        val described = catalog?.service(service)
        if (described == null) {
            // Without a catalogue there is nothing to check the op against; the
            // peer still enforces its own list, so the call is allowed and its
            // refusal is reported verbatim. With a catalogue, an unknown service
            // is a local, cheaper refusal.
            if (catalog != null && catalog.listed) {
                return fail(404, "MESH_UNKNOWN_SERVICE", "the peer does not list that service")
            }
        } else if (!described.callable(op)) {
            return fail(403, "MESH_OP_NOT_ALLOWED", "the peer does not expose that operation")
        }
        val encoded = Json.encode(mapOf("op" to op, "args" to args))
        if (encoded.toByteArray(Charsets.UTF_8).size > maxBodyBytes) {
            return fail(413, "MESH_PAYLOAD_TOO_LARGE", "the request is larger than this server relays")
        }
        return execute(peer, "POST", "$PLUGINS/$service/rpc", encoded, seed)
    }

    // --------------------------------------------------------------- request

    private fun execute(
        peer: PeerRecord,
        method: String,
        path: String,
        body: String,
        seed: ByteArray
    ): Reply {
        val response = try {
            signed(method, peer.address, path, body, seed)
        } catch (_: TransportException) {
            return fail(502, "MESH_UNREACHABLE", "the peer did not answer")
        } catch (_: Exception) {
            return fail(502, "MESH_UNREACHABLE", "the peer did not answer")
        }
        when (response.status) {
            401 -> return fail(401, "MESH_UNAUTHORIZED", "the peer rejected this server's key")
            403 -> {
                // The peer's own refusals are more specific than "scope
                // missing": `SERVICE_NOT_SHARED` says the operator closed this
                // service, which is a different action from granting a scope
                // (D101). Keep a well-formed code instead of flattening it.
                val code = remoteErrorCode(response.body) ?: "MESH_SCOPE_NOT_GRANTED"
                return fail(403, code, "the peer refused that operation")
            }
            in 300..399 -> return fail(502, "MESH_REDIRECTED", "the peer tried to redirect the request")
        }
        val payload = runCatching { JsonParser.parse(response.body) }.getOrNull()
            ?: response.body
        return Reply.Json(response.status, payload)
    }

    private fun signed(
        method: String,
        baseUrl: String,
        path: String,
        body: String,
        seed: ByteArray
    ): HttpJsonResponse {
        val publicKey = Ed25519.derivePublicKey(seed)
        val keyId = Ed25519.keyId(publicKey)
        val timestamp = clockSeconds().toString()
        val nonce = newNonce()
        val canonical = RequestSigner.canonicalString(method, path, body, timestamp, nonce)
        val signature = Ed25519.sign(seed, canonical.toByteArray(Charsets.UTF_8))
        return transport.request(
            method,
            "$baseUrl$path",
            mapOf(
                "Content-Type" to "application/json",
                RequestSigner.HEADER_KEY to keyId,
                RequestSigner.HEADER_TIMESTAMP to timestamp,
                RequestSigner.HEADER_NONCE to nonce,
                RequestSigner.HEADER_SIGNATURE to Base64.getEncoder().encodeToString(signature)
            ),
            body.takeIf { method != "GET" },
            timeoutMs
        )
    }

    /** The code inside a `{ok:false,error:{code}}` body, or null. */
    private fun remoteErrorCode(body: String): String? {
        val root = JsonParser.parseObjectOrNull(body) ?: return null
        val error = root["error"] as? Map<*, *> ?: return null
        val code = error["code"] as? String ?: return null
        return code.takeIf { ERROR_CODE.matches(it) }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun fail(status: Int, code: String, message: String): Reply =
        Reply.Failed(status, code, message)

    companion object {
        const val CONNECT_PATH: String = "/grom/v1/connect"
        const val PLUGINS: String = "/v1/plugins"
        const val AI_SERVICE: String = "ai"
        const val AI_MODELS: String = "/v1/ai/models"
        const val AI_CHAT: String = "/v1/ai/chat/completions"
        const val AI_MESSAGES: String = "/v1/ai/messages"

        /** The same 64 KiB bound the data plane applies to a body. */
        const val MAX_BODY_BYTES: Int = 64 * 1024

        private val SERVICE = Regex("^[a-z][a-z0-9-]{1,31}$")
        private val OP = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{2,63}$")
    }
}
