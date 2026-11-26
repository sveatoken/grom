// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PairingPort
import app.grom.server.cluster.PeerScope
import app.grom.server.core.Token
import app.grom.server.http.HttpRequest
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.RequestSigner
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

sealed class SuperResponse {

    data class Json(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    ) : SuperResponse()

    /**
     * A response whose body is produced incrementally (SSE). The listener writes
     * the head, then hands the socket to [write] until the callback returns or
     * [StreamControl.isCancelled] reports shutdown.
     */
    class Stream(
        val headers: Map<String, String>,
        val write: (OutputStream, StreamControl) -> Unit
    ) : SuperResponse()
}

interface StreamControl {
    fun isCancelled(): Boolean
}

/**
 * Routing for the public data plane (S5-R010…S5-R026).
 *
 * The control plane (`/grom/v1/`) is untouched: it stays loopback-only and
 * token-authenticated. This router is the separately-reachable data plane.
 *
 * Order matters: health is answered without credentials (it exposes no secret),
 * the rate limiter runs before any credential work, and authentication runs
 * before path resolution so an unauthenticated caller cannot enumerate routes.
 */
class SuperRouter(
    private val config: SuperServerConfig,
    private val auth: SuperAuth,
    private val plugins: PluginRegistry,
    private val events: EventBus,
    private val uptimeMs: () -> Long,
    private val serverStatus: () -> Map<String, Any?>,
    /** Pairing routes, live only while an operator has opened a session (D71). */
    private val pairing: PairingPort = PairingPort.DISABLED,
    /** The sanitised hand-off document served at `GET /v1/config` (D53). */
    private val configExport: () -> String = { "{}" },
    /** The host APK's version, reported by `GET /grom/v1/connect` (D91). */
    private val gromVersion: () -> String = { "unknown" },
    /**
     * The AI gateway (D94). Disabled by default, so a router built without one
     * answers `503 AI_DISABLED` instead of pretending a provider exists.
     */
    private val ai: AiPort = AiPort.DISABLED,
    /**
     * The server network (D99). Disabled by default, so a router built without
     * one answers `503 MESH_DISABLED` instead of inventing a roster.
     */
    private val mesh: MeshPort = MeshPort.DISABLED,
    /**
     * The measured data-plane endpoint, read at request time. A bind that has
     * not happened is reported as such; the route never invents an address.
     */
    private val dataPlane: () -> Map<String, Any?> = {
        mapOf(
            "enabled" to config.enabled,
            "running" to true,
            "bind" to config.bind.name,
            "port" to config.port
        )
    },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun handle(request: HttpRequest, client: String): SuperResponse {
        val path = normalize(request.path)

        if (path == HEALTH) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            return json(
                200,
                mapOf(
                    "ok" to true,
                    "service" to SERVICE,
                    "api" to API_VERSION,
                    "uptimeMs" to uptimeMs()
                )
            )
        }

        auth.checkRequestRate(client)?.let { return rejected(it) }

        if (path == LOGIN) {
            if (request.method != "POST") return methodNotAllowed(request.method, "POST")
            if (!isJson(request)) return unsupportedMediaType()
            if (oversized(request)) return tooLarge()

            val parsed = JsonParser.parseObjectOrNull(request.body)
                ?: return badRequest("body must be a JSON object")
            val result = auth.login(client, parsed["password"] as? String)
            result.failure?.let { return rejected(it) }
            return json(
                200,
                mapOf("ok" to true, "token" to result.token, "expiresAtMs" to result.expiresAtMs)
            )
        }

        // Connect is the one document a client needs before it owns a
        // credential (D91). It is answered without one, but it is not a bypass:
        // a presented credential is verified, and an invalid one gets the same
        // uniform 401 as every other authenticated route. The unauthenticated
        // document deliberately lists no service, so it cannot be used to
        // enumerate configuration.
        if (path == CONNECT) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            if (!hasCredential(request)) return json(200, connectDocument(null))
            return when (
                val outcome = auth.authenticate(request.method, path, request.body, request::header)
            ) {
                is SuperAuth.Outcome.Rejected -> rejected(outcome)
                is SuperAuth.Outcome.Authenticated -> json(200, connectDocument(outcome.principal))
            }
        }

        // Pairing is the only pre-authenticated surface, and only while a
        // session is open; a closed device answers every pairing route with the
        // session-closed refusal instead of exposing a hint of a surface.
        if (path == PAIR_IDENTITY || path == PAIR_HELLO || path == PAIR_POLL) {
            return pairingRoute(path, request, client)
        }

        val authenticated = when (
            val outcome = auth.authenticate(request.method, path, request.body, request::header)
        ) {
            is SuperAuth.Outcome.Rejected -> return rejected(outcome)
            is SuperAuth.Outcome.Authenticated -> outcome
        }
        val principal = authenticated.principal

        return when {
            path == STATUS -> {
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                if (!hasScope(principal, PeerScope.STATUS_READ)) return scopeRequired()
                json(200, statusPayload())
            }

            path == CONFIG -> {
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                if (!hasScope(principal, PeerScope.CONFIG_READ)) return scopeRequired()
                SuperResponse.Json(200, configExport())
            }

            path == LOGOUT -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                if (request.method != "POST") return methodNotAllowed(request.method, "POST")
                auth.revoke(Token.fromHeaders(request.header("authorization"), request.header("x-grom-token")))
                json(200, mapOf("ok" to true))
            }

            path == PLUGINS -> {
                if (!canReadServices(principal)) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                json(
                    200,
                    mapOf(
                        "ok" to true,
                        "plugins" to visiblePlugins(principal).map {
                            describe(it, includeStatus = hasStatusRead(principal))
                        }
                    )
                )
            }

            path == EVENTS -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                eventStream()
            }

            path.startsWith("$PLUGINS/") -> pluginRoute(path, request, principal)

            path == AI_MODELS -> {
                if (!serviceAllowed(principal, AI_SERVICE_ID)) return serviceNotShared()
                if (!hasScope(principal, PeerScope.AI_READ)) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                aiAnswer(ai.models())
            }

            path == AI_CHAT -> {
                if (!serviceAllowed(principal, AI_SERVICE_ID)) return serviceNotShared()
                if (!hasScope(principal, PeerScope.AI_CHAT)) return scopeRequired()
                if (request.method != "POST") return methodNotAllowed(request.method, "POST")
                if (!isJson(request)) return unsupportedMediaType()
                if (oversized(request)) return tooLarge()
                aiAnswer(ai.openAiChat(request.body, client))
            }

            path == AI_MESSAGES || path == AI_MESSAGES_ALIAS -> {
                if (!serviceAllowed(principal, AI_SERVICE_ID)) return serviceNotShared()
                if (!hasScope(principal, PeerScope.AI_CHAT)) return scopeRequired()
                if (request.method != "POST") return methodNotAllowed(request.method, "POST")
                if (!isJson(request)) return unsupportedMediaType()
                if (oversized(request)) return tooLarge()
                aiAnswer(ai.anthropicMessages(request.body, client))
            }

            path == MESH -> {
                if (!hasScope(principal, PeerScope.MESH_READ)) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                json(200, mesh.document())
            }

            path == MESH_CALL -> {
                // A relay grant, deliberately separate from services.call
                // (D100): asking this device to reach a third server is a
                // different decision from calling a local service.
                if (!hasScope(principal, PeerScope.MESH_CALL)) return scopeRequired()
                if (request.method != "POST") return methodNotAllowed(request.method, "POST")
                if (!isJson(request)) return unsupportedMediaType()
                if (oversized(request)) return tooLarge()
                val answer = mesh.call(request.body, client)
                json(answer.status, answer.payload)
            }

            else -> error(404, "NOT_FOUND", "no such endpoint")
        }
    }

    // ------------------------------------------------------------------ routes

    /** The three pairing routes: unsigned, but live only inside a session. */
    private fun pairingRoute(path: String, request: HttpRequest, client: String): SuperResponse {
        if (path == PAIR_IDENTITY) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            val document = pairing.identityDocument()
                ?: return error(404, "PAIR_SESSION_CLOSED", "no pairing session is open")
            return json(200, document)
        }
        if (request.method != "POST") return methodNotAllowed(request.method, "POST")
        if (!isJson(request)) return unsupportedMediaType()
        if (oversized(request)) return tooLarge()
        val parsed = JsonParser.parseObjectOrNull(request.body)
            ?: return badRequest("body must be a JSON object")
        val answer = if (path == PAIR_HELLO) {
            pairing.hello(parsed, client)
        } else {
            pairing.poll(parsed)
        }
        return json(answer.status, answer.payload)
    }

    private fun pluginRoute(
        path: String,
        request: HttpRequest,
        principal: SuperAuth.Principal
    ): SuperResponse {
        val parts = path.removePrefix("$PLUGINS/").split("/")
        if (parts.isEmpty() || parts[0].isEmpty()) return error(404, "NOT_FOUND", "no such endpoint")

        val plugin = plugins.find(parts[0])
            ?: return error(404, "UNKNOWN_PLUGIN", "no plugin with that id")

        // The operator's per-peer allow-list (D101) is a hard filter: a service
        // that is not shared is not even described to that peer.
        if (!serviceAllowed(principal, plugin.id)) return serviceNotShared()

        if (parts.size == 1) {
            if (!canReadServices(principal)) return scopeRequired()
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            return json(
                200,
                mapOf("ok" to true) + describe(plugin, includeStatus = hasStatusRead(principal))
            )
        }

        if (parts.size != 2 || parts[1] != "rpc") {
            return error(404, "NOT_FOUND", "no such endpoint")
        }
        if (request.method != "POST") return methodNotAllowed(request.method, "POST")
        if (!isJson(request)) return unsupportedMediaType()
        if (oversized(request)) return tooLarge()

        val parsed = JsonParser.parseObjectOrNull(request.body)
            ?: return badRequest("body must be a JSON object")
        val op = parsed["op"] as? String
            ?: return badRequest("missing field: op")
        if (op.isEmpty() || op.length > 64) return badRequest("invalid field: op")

        // Op-level, not route-level (D92). `services.call` says the client may
        // use services; it never says it may do anything a service can do. A
        // plugin declares the ops it exposes to a scoped caller, and the empty
        // default means an operator-only service.
        //
        // An operation may instead name its own scopes (D94): `ai.chat` is a
        // narrower grant than `services.call`, and demanding it here is what
        // keeps "may use services" from meaning "may spend AI credit". Such an
        // op needs exactly those scopes — not `services.call` as well, which
        // would force every AI client to hold a permission it never uses.
        if (principal !is SuperAuth.Principal.Operator) {
            val required = plugin.requiredScopes(op)
            val allowed = if (required.isEmpty()) {
                canCallServices(principal) && op in plugin.scopedOps()
            } else {
                required.all { hasScope(principal, it) }
            }
            if (!allowed) return scopeRequired()
        }

        val rawArgs = parsed["args"] ?: emptyMap<String, Any?>()
        if (rawArgs !is Map<*, *>) return badRequest("field args must be an object")
        @Suppress("UNCHECKED_CAST")
        val args = rawArgs as Map<String, Any?>

        return when (val result = plugin.rpc(op, args)) {
            is PluginResult.Ok -> json(
                200,
                mapOf(
                    "ok" to true,
                    "plugin" to plugin.id,
                    "op" to op,
                    "result" to result.payload
                )
            )

            is PluginResult.Failure -> json(
                result.status,
                mapOf(
                    "ok" to false,
                    "plugin" to plugin.id,
                    "op" to op,
                    "error" to mapOf("code" to result.code, "message" to result.message)
                )
            )
        }
    }

    private fun eventStream(): SuperResponse {
        val subscription = events.subscribe()
            ?: return error(503, "TOO_MANY_STREAMS", "too many event streams are open")

        return SuperResponse.Stream(
            headers = mapOf(
                "Content-Type" to "text/event-stream; charset=utf-8",
                "X-Accel-Buffering" to "no"
            )
        ) { output, control ->
            try {
                write(output, "retry: 5000\n\n")
                write(
                    output,
                    EventBus.frame(
                        "hello",
                        mapOf("service" to SERVICE, "api" to API_VERSION)
                    )
                )
                val deadline = clock() + MAX_STREAM_MS
                while (!control.isCancelled() && clock() < deadline) {
                    val frame = subscription.queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                    write(output, frame ?: EventBus.HEARTBEAT)
                }
                write(output, EventBus.frame("bye", mapOf("reason" to "stream closed")))
            } catch (_: IOException) {
                // The client closed the stream; nothing to report.
            } finally {
                events.unsubscribe(subscription)
            }
        }
    }

    private fun write(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    // ---------------------------------------------------------------- payloads

    private fun statusPayload(): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "ok" to true,
            "service" to SERVICE,
            "api" to API_VERSION,
            "uptimeMs" to uptimeMs(),
            "auth" to config.auth.name,
            "bind" to config.bind.name,
            "ai" to ai.summary(),
            "mesh" to mesh.summary(),
            "plugins" to plugins.all().map { describe(it, includeStatus = true) }
        )
        base.putAll(serverStatus())
        return base
    }

    /**
     * One service, as a discovery document describes it (D91, D92).
     *
     * [includeStatus] is the caller's `status.read`, not a property of the
     * listing: a principal that may enumerate services does not automatically
     * get the measured state of every one of them. `scopedOps` is always
     * present because it is the callable surface, not a secret.
     */
    private fun describe(plugin: Plugin, includeStatus: Boolean): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to plugin.id,
            "title" to plugin.title,
            "description" to plugin.description,
            "capabilities" to plugin.capabilities().sorted(),
            "scopedOps" to plugin.scopedOps().sorted()
        ).apply {
            if (includeStatus) put("status", plugin.status())
        }

    /**
     * The connect document (D91). This is the only document served before a
     * credential exists, so it is held to the same rule as `/v1/health`: it
     * publishes what a client needs in order to connect, and nothing about how
     * this device is configured. The service list is added only for a
     * principal that may read it; the anonymous answer says so explicitly with
     * `listed: false` instead of an empty list that looks like "no services".
     */
    private fun connectDocument(principal: SuperAuth.Principal?): Map<String, Any?> {
        val capabilities = when (principal) {
            null -> emptyList()
            is SuperAuth.Principal.Operator -> PeerScope.entries.map { it.wireName }.sorted()
            is SuperAuth.Principal.Peer -> PeerScope.wireNames(principal.scopes)
        }
        val listed = canReadServicesOrNull(principal)
        return linkedMapOf(
            "ok" to true,
            "schema" to CONNECT_SCHEMA,
            "service" to SERVICE,
            "grom" to gromVersion(),
            "api" to API_VERSION,
            "compat" to linkedMapOf(
                "minApi" to API_VERSION,
                "maxApi" to API_VERSION,
                "signature" to SIGNATURE_SCHEME,
                "keyHeader" to RequestSigner.HEADER_KEY,
                "timestampHeader" to RequestSigner.HEADER_TIMESTAMP,
                "nonceHeader" to RequestSigner.HEADER_NONCE,
                "signatureHeader" to RequestSigner.HEADER_SIGNATURE
            ),
            "state" to "ready",
            "timeMs" to clock(),
            "dataPlane" to dataPlane(),
            "auth" to linkedMapOf(
                "methods" to authMethods(),
                "scopes" to PeerScope.entries.map { it.wireName }.sorted(),
                "login" to LOGIN
            ),
            "principal" to when (principal) {
                null -> mapOf<String, Any?>("kind" to "anonymous")
                is SuperAuth.Principal.Operator -> mapOf<String, Any?>("kind" to "operator")
                is SuperAuth.Principal.Peer -> mapOf<String, Any?>(
                    "kind" to "peer",
                    "nodeId" to principal.nodeId
                )
            },
            "capabilities" to capabilities,
            "services" to linkedMapOf(
                "listed" to listed,
                "path" to PLUGINS,
                "requires" to PeerScope.SERVICES_READ.wireName,
                "items" to if (listed) {
                    visiblePluginsOrNull(principal).map {
                        describe(it, includeStatus = hasStatusReadOrNull(principal))
                    }
                } else {
                    emptyList<Any?>()
                }
            ),
            "ai" to aiDocument(principal),
            "mesh" to meshDocument(),
            "endpoints" to ENDPOINTS
        )
    }

    /**
     * The AI gateway inside the discovery document (D94).
     *
     * The shape is published always — a client should be able to tell that this
     * device *has* a gateway and that it needs `ai.read`. The provider and model
     * catalogue is published only to a caller that holds `ai.read`, exactly like
     * the service list, and it never contains an endpoint or a key.
     */
    private fun aiDocument(principal: SuperAuth.Principal?): Map<String, Any?> {
        val summary = ai.summary()
        val listed = principal != null && hasScope(principal, PeerScope.AI_READ)
        val models = if (listed) {
            (ai.models() as? AiAnswer.Json)?.payload?.get("models") ?: emptyList<Any?>()
        } else {
            emptyList<Any?>()
        }
        return linkedMapOf(
            "path" to AI_PATH,
            "models" to AI_MODELS,
            "chat" to AI_CHAT,
            "messages" to AI_MESSAGES,
            "requires" to linkedMapOf(
                "models" to PeerScope.AI_READ.wireName,
                "chat" to PeerScope.AI_CHAT.wireName
            ),
            "enabled" to (summary["enabled"] as? Boolean ?: false),
            "configured" to (summary["configured"] as? Boolean ?: false),
            "listed" to listed,
            "items" to models
        )
    }

    private fun aiAnswer(answer: AiAnswer): SuperResponse = when (answer) {
        is AiAnswer.Json -> json(answer.status, answer.payload)
        is AiAnswer.Stream -> SuperResponse.Stream(
            headers = mapOf("Content-Type" to answer.contentType),
            write = answer.write
        )
    }

    /** `password` only when a hash is stored, `key` only when key auth is live. */
    private fun authMethods(): List<String> = buildList {
        if (auth.passwordEnabled()) add("password")
        if (config.auth != AuthMode.PASSWORD) add("key")
    }

    private fun hasCredential(request: HttpRequest): Boolean =
        request.header("authorization") != null ||
            request.header("x-grom-token") != null ||
            request.header(RequestSigner.HEADER_KEY) != null

    // ----------------------------------------------------------------- helpers

    private fun normalize(path: String): String =
        path.trimEnd('/').ifEmpty { "/" }

    private fun isJson(request: HttpRequest): Boolean {
        if (request.body.isEmpty()) return true
        val contentType = request.header("content-type") ?: return true
        return contentType.lowercase().startsWith("application/json")
    }

    private fun oversized(request: HttpRequest): Boolean =
        request.body.toByteArray(Charsets.UTF_8).size > config.maxBodyBytes

    private fun json(status: Int, payload: Map<String, Any?>): SuperResponse.Json =
        SuperResponse.Json(status, Json.encode(payload))

    private fun error(status: Int, code: String, message: String): SuperResponse.Json =
        json(status, mapOf("ok" to false, "error" to mapOf("code" to code, "message" to message)))

    private fun rejected(outcome: SuperAuth.Outcome.Rejected): SuperResponse.Json {
        val headers = if (outcome.retryAfterSeconds > 0) {
            mapOf("Retry-After" to outcome.retryAfterSeconds.toString())
        } else {
            emptyMap()
        }
        return SuperResponse.Json(
            outcome.status,
            Json.encode(
                mapOf("ok" to false, "error" to mapOf("code" to outcome.code, "message" to outcome.message))
            ),
            headers
        )
    }

    /**
     * The services a caller may even see (D101). The operator sees every
     * service; a peer with no explicit allow-list sees every service its scopes
     * allow (the pre-D101 behaviour), and a peer with one sees only those.
     */
    private fun visiblePlugins(principal: SuperAuth.Principal): List<Plugin> =
        plugins.all().filter { serviceAllowed(principal, it.id) }

    private fun visiblePluginsOrNull(principal: SuperAuth.Principal?): List<Plugin> =
        if (principal == null) emptyList() else visiblePlugins(principal)

    private fun serviceAllowed(principal: SuperAuth.Principal, serviceId: String): Boolean =
        when (principal) {
            is SuperAuth.Principal.Operator -> true
            is SuperAuth.Principal.Peer -> principal.services.isEmpty() ||
                principal.services.contains(serviceId)
        }

    /**
     * The server network inside the discovery document (D99).
     *
     * The shape is published always so a client can tell the device *has* a
     * server network and what it needs; the roster is not duplicated here — it
     * lives at `GET /v1/mesh` behind `mesh.read`, with no address in it.
     */
    private fun meshDocument(): Map<String, Any?> {
        val summary = mesh.summary()
        return linkedMapOf(
            "path" to MESH,
            "call" to MESH_CALL,
            "enabled" to (summary["enabled"] as? Boolean ?: false),
            "requires" to linkedMapOf(
                "read" to PeerScope.MESH_READ.wireName,
                "call" to PeerScope.MESH_CALL.wireName
            )
        )
    }

    private fun serviceNotShared(): SuperResponse.Json =
        error(403, "SERVICE_NOT_SHARED", "this service is not shared with this credential")

    private fun hasScope(principal: SuperAuth.Principal, scope: PeerScope): Boolean =
        when (principal) {
            is SuperAuth.Principal.Operator -> true
            is SuperAuth.Principal.Peer -> principal.scopes.contains(scope)
        }

    private fun hasStatusRead(principal: SuperAuth.Principal): Boolean =
        hasScope(principal, PeerScope.STATUS_READ)

    private fun hasStatusReadOrNull(principal: SuperAuth.Principal?): Boolean =
        principal != null && hasScope(principal, PeerScope.STATUS_READ)

    /** `services.read`: enumerate services. Operator keys have it by definition. */
    private fun canReadServices(principal: SuperAuth.Principal): Boolean =
        hasScope(principal, PeerScope.SERVICES_READ)

    private fun canReadServicesOrNull(principal: SuperAuth.Principal?): Boolean =
        principal != null && hasScope(principal, PeerScope.SERVICES_READ)

    /** `services.call`: invoke the operations a service exposes to scoped clients. */
    private fun canCallServices(principal: SuperAuth.Principal): Boolean =
        hasScope(principal, PeerScope.SERVICES_CALL)

    private fun scopeRequired(): SuperResponse.Json =
        error(403, "SCOPE_REQUIRED", "this credential does not allow that")

    private fun badRequest(message: String): SuperResponse.Json =
        error(400, "BAD_REQUEST", message)

    private fun tooLarge(): SuperResponse.Json =
        error(413, "PAYLOAD_TOO_LARGE", "request body exceeds ${config.maxBodyBytes} bytes")

    private fun unsupportedMediaType(): SuperResponse.Json =
        error(415, "UNSUPPORTED_MEDIA_TYPE", "request body must be application/json")

    private fun methodNotAllowed(method: String, vararg allowed: String): SuperResponse.Json =
        SuperResponse.Json(
            405,
            Json.encode(
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to "METHOD_NOT_ALLOWED",
                        "message" to "$method is not allowed here"
                    )
                )
            ),
            mapOf("Allow" to allowed.joinToString(", "))
        )

    companion object {
        const val SERVICE: String = "grom-super"
        const val API_VERSION: Int = 1

        /**
         * The discovery document a client reads before it owns a credential
         * (D91). It lives under the `/grom/v1/` namespace the project already
         * uses for its own contract, while the rest of the data plane keeps
         * the `/v1/` paths every existing client already speaks.
         */
        const val CONNECT: String = "/grom/v1/connect"
        const val CONNECT_SCHEMA: String = "grom-connect-v1"
        const val SIGNATURE_SCHEME: String = "grom-ed25519-v1"

        const val HEALTH: String = "/v1/health"
        const val LOGIN: String = "/v1/auth/login"
        const val LOGOUT: String = "/v1/auth/logout"
        const val STATUS: String = "/v1/status"
        const val CONFIG: String = "/v1/config"
        const val PLUGINS: String = "/v1/plugins"
        const val EVENTS: String = "/v1/events"

        /** The server network (D99): an address-free roster and a scoped relay. */
        const val MESH: String = "/v1/mesh"
        const val MESH_CALL: String = "/v1/mesh/call"
        const val AI_SERVICE_ID: String = "ai"

        /** The AI gateway (D94): catalogue, OpenAI shape, Anthropic shape. */
        const val AI_PATH: String = "/v1/ai"
        const val AI_MODELS: String = "$AI_PATH/models"
        const val AI_CHAT: String = "$AI_PATH/chat/completions"
        const val AI_MESSAGES: String = "$AI_PATH/messages"
        /** Anthropic SDKs append `/v1/messages` to the base URL; both are served. */
        const val AI_MESSAGES_ALIAS: String = "$AI_PATH/v1/messages"
        const val PAIR_IDENTITY: String = "/v1/pair/identity"
        const val PAIR_HELLO: String = "/v1/pair/hello"
        const val PAIR_POLL: String = "/v1/pair/poll"

        /** The route map a client discovers; it never has to guess a path. */
        private val ENDPOINTS: Map<String, String> = linkedMapOf(
            "connect" to CONNECT,
            "health" to HEALTH,
            "login" to LOGIN,
            "logout" to LOGOUT,
            "status" to STATUS,
            "config" to CONFIG,
            "plugins" to PLUGINS,
            "events" to EVENTS,
            "mesh" to MESH,
            "meshCall" to MESH_CALL,
            "aiModels" to AI_MODELS,
            "aiChat" to AI_CHAT,
            "aiMessages" to AI_MESSAGES
        )

        private const val HEARTBEAT_MS: Long = 15_000
        private const val MAX_STREAM_MS: Long = 30L * 60 * 1000
    }
}
