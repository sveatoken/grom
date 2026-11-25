// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.AiAnswer
import app.grom.server.superserver.AiPort
import app.grom.server.superserver.StreamControl
import app.grom.server.superserver.auth.RateLimiter
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/** A validated call, ready to be sent. It holds the plaintext key for one call. */
internal class AiPlan(
    val provider: AiProviderConfig,
    val model: AiModel,
    val apiKey: String,
    val wire: AiWire,
    val request: AiChatRequest,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

internal sealed class AiRun {
    data class Ok(val completion: AiCompletion, val latencyMs: Long) : AiRun()
    data class Failure(val problem: AiProblem) : AiRun()
}

internal sealed class AiPrepared {
    data class Ready(val plan: AiPlan) : AiPrepared()
    data class Failed(val problem: AiProblem) : AiPrepared()
}

/**
 * The universal AI gateway (D94).
 *
 * It is a *router of protocols*, not a second server: the request arrives on the
 * existing data plane, the operator's stored configuration selects a provider,
 * and one of three wire adapters speaks the provider's own dialect. Everything
 * that can be decided without a network — model resolution, scope of what may be
 * asked, parameter support, key presence — is decided before a socket is opened,
 * which is what makes the failure messages actionable.
 *
 * Honesty rules:
 *   - a model that is not in the catalogue is never forwarded, because the
 *     catalogue is the operator's spending limit;
 *   - a provider without a stored key answers `AI_NOT_CONFIGURED`, never an
 *     empty completion;
 *   - an upstream error keeps the provider's own explanation as `detail` but the
 *     message stays constant, so no URL, header or key can leak;
 *   - `available` in the catalogue is configuration, and the docs say so.
 */
class AiGateway(
    private val store: AiStore,
    private val transport: AiTransport = UnavailableAiTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { defaultId() },
    /** Per-client budget; a chat call is the most expensive thing Grom can do. */
    private val limiter: RateLimiter = RateLimiter(
        capacity = CALLS_PER_MINUTE,
        refillPerSecond = CALLS_PER_MINUTE / 60.0,
        maxClients = 256
    )
) : AiPort {

    /** Last measured probe per provider; keyed by provider id, bounded below. */
    private val probes = LinkedHashMap<String, Map<String, Any?>>()

    /** Built once so the registry and the discovery document share one service. */
    private val service: AiPlugin by lazy { AiPlugin(this) }

    // ------------------------------------------------------------- AiPort

    override fun summary(): Map<String, Any?> = AiDocuments.publicSummary(load())

    override fun service(): AiPlugin = service

    override fun models(): AiAnswer {
        val config = load()
        if (!config.enabled) {
            return AiAnswer.Json(
                503,
                AiPort.error(AiCodes.DISABLED, "the AI gateway is switched off on this device")
            )
        }
        return AiAnswer.Json(200, AiDocuments.modelsDocument(config))
    }

    override fun openAiChat(body: String, client: String): AiAnswer {
        val decision = limiter.check(client)
        if (!decision.allowed) {
            return AiAnswer.Json(
                429,
                AiPort.error(AiCodes.RATE_LIMITED, "too many AI requests; wait a moment")
            )
        }
        val root = JsonParser.parseObjectOrNull(body)
            ?: return AiAnswer.Json(
                400,
                AiPort.error(AiCodes.BAD_REQUEST, "the body must be a JSON object")
            )
        return when (val parsed = AiBodies.parseOpenAi(root)) {
            is AiParsed.Failure -> AiAnswer.Json(parsed.problem.status, problemPayload(parsed.problem))
            is AiParsed.Ok -> dispatch(parsed.request, openAi = true)
        }
    }

    override fun anthropicMessages(body: String, client: String): AiAnswer {
        val decision = limiter.check(client)
        if (!decision.allowed) {
            return AiAnswer.Json(
                429,
                AiPort.error(AiCodes.RATE_LIMITED, "too many AI requests; wait a moment")
            )
        }
        val root = JsonParser.parseObjectOrNull(body)
            ?: return AiAnswer.Json(
                400,
                AiPort.error(AiCodes.BAD_REQUEST, "the body must be a JSON object")
            )
        return when (val parsed = AiBodies.parseAnthropic(root)) {
            is AiParsed.Failure -> AiAnswer.Json(parsed.problem.status, problemPayload(parsed.problem))
            is AiParsed.Ok -> dispatch(parsed.request, openAi = false)
        }
    }

    // ------------------------------------------------------------- plugin ops

    /** The non-streaming completion the `ai` service publishes to scoped callers. */
    fun complete(argsJson: String): Pair<Int, Map<String, Any?>> {
        val root = JsonParser.parseObjectOrNull(argsJson)
            ?: return 400 to AiPort.error(AiCodes.BAD_REQUEST, "the arguments must be a JSON object")
        if (root["stream"] as? Boolean == true) {
            return 400 to AiPort.error(
                AiCodes.NOT_SUPPORTED,
                "streaming is available on POST /v1/ai/chat/completions, not on a service call"
            )
        }
        return when (val parsed = AiBodies.parseOpenAi(root)) {
            is AiParsed.Failure -> parsed.problem.status to problemPayload(parsed.problem)
            is AiParsed.Ok -> when (val prepared = prepare(parsed.request)) {
                is AiPrepared.Failed -> prepared.problem.status to problemPayload(prepared.problem)
                is AiPrepared.Ready -> when (val run = execute(prepared.plan)) {
                    is AiRun.Failure -> run.problem.status to problemPayload(run.problem)
                    is AiRun.Ok -> 200 to linkedMapOf(
                        "ok" to true,
                        "text" to run.completion.text,
                        "model" to prepared.plan.model.id,
                        "provider" to prepared.plan.provider.id,
                        "finishReason" to run.completion.finish.openAi,
                        "usage" to linkedMapOf(
                            "inputTokens" to run.completion.usage?.inputTokens,
                            "outputTokens" to run.completion.usage?.outputTokens
                        ),
                        "latencyMs" to run.latencyMs
                    )
                }
            }
        }
    }

    fun modelsPayload(): Map<String, Any?> = AiDocuments.modelsDocument(load())

    /**
     * The operator's explicit probe (D96). It sends one tiny request to the
     * configured endpoint, so the lamp it produces is measured and not derived
     * from the configuration. It costs a handful of tokens by design.
     */
    @Synchronized
    fun probe(providerId: String): Map<String, Any?> {
        val config = load()
        val provider = config.provider(providerId)
            ?: return mapOf(
                "ok" to false,
                "code" to "UNKNOWN_PROVIDER",
                "message" to "no provider with id '$providerId'"
            )
        if (!provider.enabled) {
            return mapOf("ok" to false, "code" to AiCodes.PROVIDER_DISABLED, "message" to "the provider is disabled")
        }
        if (!provider.isConfigured()) {
            return mapOf("ok" to false, "code" to AiCodes.NOT_CONFIGURED, "message" to "no API key is stored")
        }
        val model = config.models.firstOrNull { it.providerId == providerId && it.enabled }
            ?: return mapOf(
                "ok" to false,
                "code" to AiCodes.UNKNOWN_MODEL,
                "message" to "add a model for this provider first"
            )

        val request = AiChatRequest(
            modelRef = model.id,
            messages = listOf(AiMessage("user", "ping")),
            maxOutputTokens = 1
        )
        val measured = when (val prepared = prepare(request, ignoreEnabledGate = true)) {
            is AiPrepared.Failed -> measured(false, prepared.problem, 0L, model.id)
            is AiPrepared.Ready -> when (val run = execute(prepared.plan)) {
                is AiRun.Failure -> measured(false, run.problem, 0L, model.id)
                is AiRun.Ok -> measured(true, null, run.latencyMs, model.id)
            }
        }
        probes[providerId] = measured
        while (probes.size > MAX_REMEMBERED_PROBES) {
            probes.remove(probes.keys.first())
        }
        return measured
    }

    @Synchronized
    fun probeSnapshot(): Map<String, Map<String, Any?>> =
        java.util.LinkedHashMap<String, Map<String, Any?>>(probes)

    private fun measured(ok: Boolean, problem: AiProblem?, latencyMs: Long, modelId: String): Map<String, Any?> =
        linkedMapOf(
            "ok" to ok,
            "code" to problem?.code,
            "detail" to (problem?.detail ?: problem?.message),
            "latencyMs" to latencyMs,
            "atMs" to clock(),
            "model" to modelId
        )

    // ------------------------------------------------------------- dispatch

    private fun dispatch(request: AiChatRequest, openAi: Boolean): AiAnswer {
        val prepared = prepare(request)
        if (prepared is AiPrepared.Failed) {
            return AiAnswer.Json(prepared.problem.status, problemPayload(prepared.problem))
        }
        val plan = (prepared as AiPrepared.Ready).plan
        if (request.stream) {
            return if (openAi) openAiStream(plan) else anthropicStream(plan)
        }
        return when (val run = execute(plan)) {
            is AiRun.Failure -> AiAnswer.Json(run.problem.status, problemPayload(run.problem))
            is AiRun.Ok -> AiAnswer.Json(
                200,
                if (openAi) {
                    AiFrames.openAiCompletion(
                        idFactory(), plan.model.id, run.completion, plan.provider.id, run.latencyMs
                    )
                } else {
                    AiFrames.anthropicMessage(
                        idFactory(), plan.model.id, run.completion, plan.provider.id, run.latencyMs
                    )
                }
            )
        }
    }

    private fun openAiStream(plan: AiPlan): AiAnswer {
        val id = idFactory()
        val model = plan.model.id
        return AiAnswer.Stream("text/event-stream; charset=utf-8") { output, control ->
            var finished = false
            var failed = false
            try {
                transportStream(plan) { event ->
                    if (control.isCancelled() || failed) return@transportStream
                    when (event) {
                        is AiStreamEvent.Delta ->
                            write(output, AiFrames.openAiDelta(id, model, event.text))

                        is AiStreamEvent.Finish -> {
                            finished = true
                            write(output, AiFrames.openAiFinish(id, model, event.finish, event.usage))
                        }

                        is AiStreamEvent.Failure -> {
                            failed = true
                            write(output, AiFrames.openAiError(id, event.problem))
                        }
                    }
                }
            } catch (_: IOException) {
                // The client hung up; the upstream read stopped with it.
            } finally {
                if (!control.isCancelled()) {
                    // A failure already wrote an error frame; `[DONE]` still
                    // closes the stream so a simple client loop terminates
                    // instead of waiting for a connection close.
                    if (!failed && !finished) {
                        write(output, AiFrames.openAiFinish(id, model, AiFinish.STOP, null))
                    }
                    write(output, AiFrames.openAiDone())
                }
            }
        }
    }

    private fun anthropicStream(plan: AiPlan): AiAnswer {
        val id = idFactory()
        val model = plan.model.id
        return AiAnswer.Stream("text/event-stream; charset=utf-8") { output, control ->
            var failed = false
            try {
                write(output, AiFrames.anthropicStart(id, model))
                write(output, AiFrames.anthropicBlockStart())
                transportStream(plan) { event ->
                    if (control.isCancelled() || failed) return@transportStream
                    when (event) {
                        is AiStreamEvent.Delta -> write(output, AiFrames.anthropicDelta(event.text))
                        is AiStreamEvent.Finish -> write(
                            output,
                            AiFrames.anthropicStop(event.finish, event.usage)
                        )
                        is AiStreamEvent.Failure -> {
                            failed = true
                            write(output, AiFrames.anthropicError(event.problem))
                        }
                    }
                }
            } catch (_: IOException) {
                // The client hung up.
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Resolves and builds everything that needs no network. A failure here is
     * answered as JSON, before the stream head — so a client never sees a 200
     * followed by "actually, no model".
     */
    internal fun prepare(request: AiChatRequest, ignoreEnabledGate: Boolean = false): AiPrepared {
        val config = load()
        if (!config.enabled && !ignoreEnabledGate) {
            return AiPrepared.Failed(
                AiProblem(503, AiCodes.DISABLED, "the AI gateway is switched off on this device")
            )
        }
        if (config.usableModels().isEmpty()) {
            return AiPrepared.Failed(
                AiProblem(
                    503,
                    AiCodes.NOT_CONFIGURED,
                    "no AI provider is ready; add a provider, a model and an API key"
                )
            )
        }
        val model = config.resolve(request.modelRef)
            ?: return AiPrepared.Failed(
                AiProblem(
                    404,
                    AiCodes.UNKNOWN_MODEL,
                    if (request.modelRef.isNullOrBlank()) {
                        "no default model is set and none was requested"
                    } else {
                        "no model '${request.modelRef.take(64)}' in the catalogue"
                    }
                )
            )
        val provider = config.provider(model.providerId)
            ?: return AiPrepared.Failed(
                AiProblem(503, AiCodes.NOT_CONFIGURED, "the model's provider no longer exists")
            )
        if (!provider.enabled) {
            return AiPrepared.Failed(
                AiProblem(503, AiCodes.PROVIDER_DISABLED, "provider '${provider.id}' is disabled")
            )
        }
        val sealed = provider.apiKeySealed
            ?: return AiPrepared.Failed(
                AiProblem(503, AiCodes.NOT_CONFIGURED, "provider '${provider.id}' has no API key")
            )
        val apiKey = store.openKey(sealed)
            ?: return AiPrepared.Failed(
                AiProblem(
                    503,
                    AiCodes.NOT_CONFIGURED,
                    "the stored API key for '${provider.id}' cannot be opened"
                )
            )
        if (!model.enabled) {
            return AiPrepared.Failed(
                AiProblem(404, AiCodes.UNKNOWN_MODEL, "model '${model.id}' is disabled")
            )
        }

        // Bounds are refusals, not truncations: a silently shortened prompt
        // would answer a question the client did not ask.
        if (request.messages.size > AiLimits.MAX_MESSAGES) {
            return AiPrepared.Failed(
                AiProblem(
                    413,
                    AiCodes.REQUEST_TOO_LARGE,
                    "at most ${AiLimits.MAX_MESSAGES} messages are accepted"
                )
            )
        }
        if (request.messages.any { it.content.length > AiLimits.MAX_MESSAGE_CHARS }) {
            return AiPrepared.Failed(
                AiProblem(
                    413,
                    AiCodes.REQUEST_TOO_LARGE,
                    "a message is longer than ${AiLimits.MAX_MESSAGE_CHARS} characters"
                )
            )
        }
        val effective = request.copy(
            maxOutputTokens = request.maxOutputTokens ?: model.maxOutputTokens
        )
        val wire = AiWire.of(provider.effectiveProtocol())
        val url = wire.endpoint(provider, model.upstream, request.stream)
        val body = wire.body(provider, effective, model.upstream, request.stream)
        if (body.length > AiLimits.MAX_REQUEST_BYTES) {
            return AiPrepared.Failed(
                AiProblem(
                    413,
                    AiCodes.REQUEST_TOO_LARGE,
                    "the request is larger than ${AiLimits.MAX_REQUEST_BYTES / 1024} KiB"
                )
            )
        }
        return AiPrepared.Ready(
            AiPlan(
                provider = provider,
                model = model,
                apiKey = apiKey,
                wire = wire,
                request = effective,
                url = url,
                headers = wire.headers(provider, apiKey),
                body = body
            )
        )
    }

    internal fun execute(plan: AiPlan): AiRun {
        val started = clock()
        val reply = try {
            transport.post(plan.url, plan.headers, plan.body, plan.provider.timeoutMs)
        } catch (timeout: AiTimeoutException) {
            return AiRun.Failure(
                AiProblem(504, AiCodes.TIMEOUT, "the provider did not answer in time")
            )
        } catch (_: AiTransportException) {
            return AiRun.Failure(
                AiProblem(502, AiCodes.UNREACHABLE, "the provider is not reachable")
            )
        }
        if (reply.status !in 200..299) {
            return AiRun.Failure(
                AiErrors.fromUpstream(
                    reply.status,
                    reply.body,
                    reply.retryAfterSeconds,
                    plan.apiKey
                )
            )
        }
        val parsed = plan.wire.parseCompletion(reply.body)
        return when (parsed) {
            // A 2xx body that carries an error object is still a provider
            // message, and it too can quote the key back at us (D95).
            is AiWireResult.Failure -> AiRun.Failure(AiErrors.redact(parsed.problem, plan.apiKey))
            is AiWireResult.Ok -> AiRun.Ok(parsed.completion, clock() - started)
        }
    }

    /**
     * Reads the provider's stream and emits normalised events. A failure before
     * the first byte is reported as an event, because the head has already been
     * written; a cancellation simply stops reading.
     */
    internal fun transportStream(plan: AiPlan, emit: (AiStreamEvent) -> Unit) {
        val timeout = plan.provider.timeoutMs
        var finished = false
        val reply = try {
            transport.stream(plan.url, plan.headers, plan.body, timeout) { line ->
                if (!line.startsWith("data:")) {
                    true
                } else {
                    val data = line.removePrefix("data:").trim()
                    when {
                        data.isEmpty() -> true
                        data == "[DONE]" -> {
                            finished = true
                            false
                        }
                        else -> {
                            when (val event = plan.wire.parseStreamEvent(data)) {
                                is AiStreamEvent.Delta -> {
                                    emit(event)
                                    true
                                }
                                is AiStreamEvent.Finish -> {
                                    emit(event)
                                    finished = true
                                    false
                                }
                                is AiStreamEvent.Failure -> {
                                    emit(
                                        AiStreamEvent.Failure(
                                            AiErrors.redact(event.problem, plan.apiKey)
                                        )
                                    )
                                    finished = true
                                    false
                                }
                                null -> true
                            }
                        }
                    }
                }
            }
        } catch (_: AiTimeoutException) {
            emit(
                AiStreamEvent.Failure(
                    AiProblem(504, AiCodes.TIMEOUT, "the provider stopped answering")
                )
            )
            return
        } catch (_: AiTransportException) {
            emit(
                AiStreamEvent.Failure(
                    AiProblem(502, AiCodes.UNREACHABLE, "the provider is not reachable")
                )
            )
            return
        }

        if (reply.status !in 200..299) {
            emit(
                AiStreamEvent.Failure(
                    AiErrors.fromUpstream(
                        reply.status,
                        reply.body,
                        reply.retryAfterSeconds,
                        plan.apiKey
                    )
                )
            )
            return
        }
        if (!finished) {
            // The provider closed the stream without a finish event. Report a
            // clean stop: the text that arrived is what the provider sent.
            emit(AiStreamEvent.Finish(AiFinish.STOP, null))
        }
    }

    fun load(): AiGatewayConfig = store.load()

    fun lastError(): String? = store.lastError

    // ---------------------------------------------------------------- helpers

    private fun problemPayload(problem: AiProblem): Map<String, Any?> = linkedMapOf(
        "ok" to false,
        "error" to AiFrames.errorJson(problem)
    )

    private fun write(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    companion object {
        const val CALLS_PER_MINUTE: Int = 20
        private const val MAX_REMEMBERED_PROBES = 16
        private val COUNTER = AtomicLong(0)

        private fun defaultId(): String = "chatcmpl-grom-${COUNTER.incrementAndGet()}-${System.nanoTime() % 100000}"

        /** Used by the tests that assert a stable id. */
        fun encodePayload(payload: Map<String, Any?>): String = Json.encode(payload)
    }
}
