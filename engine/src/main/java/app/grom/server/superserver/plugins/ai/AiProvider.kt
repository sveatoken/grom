// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json
import app.grom.server.http.JsonParser

/**
 * Wire protocols the gateway speaks *upstream* (D94).
 *
 * Three shapes cover every provider that matters today, and they are the whole
 * extension mechanism: a new vendor is a `custom` provider with one of these
 * protocols, not a new class. That is deliberate — a provider list compiled into
 * the APK would need an app update before an operator could reach a new vendor.
 */
enum class AiProtocol(val wireName: String) {
    /** `POST {base}/chat/completions`, `Authorization: Bearer`, OpenAI body. */
    OPENAI("openai"),

    /** `POST {base}/v1/messages`, `x-api-key`, Anthropic body. */
    ANTHROPIC("anthropic"),

    /** `POST {base}/v1beta/models/{model}:generateContent`, `x-goog-api-key`. */
    GEMINI("gemini");

    companion object {
        fun fromWire(value: String?): AiProtocol? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/**
 * Known vendors and their defaults (D94).
 *
 * [defaultBaseUrl] is only a default the operator accepts or replaces; the
 * gateway never reaches a host that is not in the saved configuration, and it
 * never falls back to a vendor URL when a provider's own base URL is missing.
 * The kind selects a protocol and a default endpoint — nothing else.
 */
enum class AiProviderKind(
    val wireName: String,
    val defaultBaseUrl: String,
    val protocol: AiProtocol
) {
    OPENAI("openai", "https://api.openai.com/v1", AiProtocol.OPENAI),
    ANTHROPIC("anthropic", "https://api.anthropic.com", AiProtocol.ANTHROPIC),
    GEMINI("gemini", "https://generativelanguage.googleapis.com", AiProtocol.GEMINI),
    DEEPSEEK("deepseek", "https://api.deepseek.com/v1", AiProtocol.OPENAI),
    QWEN("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", AiProtocol.OPENAI),
    KIMI("kimi", "https://api.moonshot.cn/v1", AiProtocol.OPENAI),
    OPENROUTER("openrouter", "https://openrouter.ai/api/v1", AiProtocol.OPENAI),

    /**
     * Anything else: llama.cpp, Ollama, LM Studio, vLLM, a company gateway. The
     * operator supplies the base URL and picks the protocol; `http` is accepted
     * only for a loopback host, because an API key must not travel in clear text
     * off the device.
     */
    CUSTOM("custom", "", AiProtocol.OPENAI);

    companion object {
        fun fromWire(value: String?): AiProviderKind? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/** Stable error envelope shared by the routes, the plugin and the UI. */
data class AiProblem(
    val status: Int,
    val code: String,
    val message: String,
    val retryAfterSeconds: Int = 0,
    /** Upstream's own explanation, already truncated and never a credential. */
    val detail: String? = null
)

object AiCodes {
    const val UNKNOWN_MODEL = "AI_UNKNOWN_MODEL"
    const val PROVIDER_DISABLED = "AI_PROVIDER_DISABLED"
    const val NOT_CONFIGURED = "AI_NOT_CONFIGURED"
    const val DISABLED = "AI_DISABLED"
    const val BAD_REQUEST = "AI_BAD_REQUEST"
    const val UNSUPPORTED_PARAM = "AI_UNSUPPORTED_PARAM"
    const val REQUEST_TOO_LARGE = "AI_REQUEST_TOO_LARGE"
    const val UPSTREAM_UNAUTHORIZED = "AI_UPSTREAM_UNAUTHORIZED"
    const val UPSTREAM_RATE_LIMITED = "AI_UPSTREAM_RATE_LIMITED"
    const val UPSTREAM_BAD_REQUEST = "AI_UPSTREAM_BAD_REQUEST"
    const val UPSTREAM_ERROR = "AI_UPSTREAM_ERROR"
    const val MODEL_NOT_FOUND = "AI_MODEL_NOT_FOUND"
    const val UNREACHABLE = "AI_UNREACHABLE"
    const val TIMEOUT = "AI_TIMEOUT"
    const val BAD_RESPONSE = "AI_BAD_RESPONSE"
    const val RATE_LIMITED = "AI_RATE_LIMITED"
    const val NOT_SUPPORTED = "AI_NOT_SUPPORTED"
}

/** The hard bounds; every one of them is enforced before a socket is opened. */
object AiLimits {
    const val MAX_PROVIDERS: Int = 16
    const val MAX_MODELS: Int = 64
    const val MAX_ID_CHARS: Int = 32
    const val MAX_MODEL_ID_CHARS: Int = 64
    const val MAX_TITLE_CHARS: Int = 64
    const val MAX_BASE_URL_CHARS: Int = 512
    const val MAX_MESSAGES: Int = 64
    const val MAX_MESSAGE_CHARS: Int = 32_000
    const val MAX_REQUEST_BYTES: Int = 64 * 1024
    const val MAX_RESPONSE_BYTES: Int = 512 * 1024
    const val MAX_STREAM_LINE_BYTES: Int = 64 * 1024
    const val MIN_TIMEOUT_MS: Int = 1_000
    const val MAX_TIMEOUT_MS: Int = 120_000
    const val DEFAULT_TIMEOUT_MS: Int = 60_000
    const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 1_024
    const val MAX_MAX_OUTPUT_TOKENS: Int = 65_536
    const val MAX_PARAM_DEPTH: Int = 4
}

/**
 * One upstream provider.
 *
 * [apiKeySealed] is a [app.grom.server.store.SecretBox] ciphertext and is never
 * plaintext, never logged and never projected into a status document. The
 * plaintext exists for the duration of one upstream request and is not retained.
 */
data class AiProviderConfig(
    val id: String,
    val kind: AiProviderKind,
    val title: String,
    val baseUrl: String,
    val apiKeySealed: String? = null,
    val enabled: Boolean = true,
    val timeoutMs: Int = AiLimits.DEFAULT_TIMEOUT_MS,
    /** Overrides the kind's protocol when set; this is how `custom` works. */
    val protocol: AiProtocol? = null
) {

    fun effectiveProtocol(): AiProtocol = protocol ?: kind.protocol

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && !apiKeySealed.isNullOrBlank()

    /** Null when the record is usable; otherwise the code the operator must fix. */
    fun validate(): AiProblem? {
        if (!ID_RULE.matches(id)) {
            return AiProblem(
                400,
                "BAD_PROVIDER_ID",
                "a provider id is 1-${AiLimits.MAX_ID_CHARS} characters of a-z, 0-9, '.', '_' or '-'"
            )
        }
        if (title.isBlank() || title.length > AiLimits.MAX_TITLE_CHARS) {
            return AiProblem(400, "BAD_PROVIDER_TITLE", "a provider title is 1-${AiLimits.MAX_TITLE_CHARS} characters")
        }
        if (timeoutMs !in AiLimits.MIN_TIMEOUT_MS..AiLimits.MAX_TIMEOUT_MS) {
            return AiProblem(
                400,
                "BAD_PROVIDER_TIMEOUT",
                "the timeout must be ${AiLimits.MIN_TIMEOUT_MS / 1000}..${AiLimits.MAX_TIMEOUT_MS / 1000} seconds"
            )
        }
        validateBaseUrl(baseUrl)?.let { return it }
        return null
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "kind" to kind.wireName,
        "title" to title,
        "baseUrl" to baseUrl,
        "apiKeySealed" to apiKeySealed,
        "enabled" to enabled,
        "timeoutMs" to timeoutMs,
        "protocol" to protocol?.wireName
    )

    /** The operator-facing projection. Includes the endpoint, never the key. */
    fun toOperatorJson(modelCount: Int): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "kind" to kind.wireName,
        "protocol" to effectiveProtocol().wireName,
        "title" to title,
        "baseUrl" to baseUrl,
        "enabled" to enabled,
        "timeoutMs" to timeoutMs,
        "keySet" to !apiKeySealed.isNullOrBlank(),
        "configured" to isConfigured(),
        "modelCount" to modelCount
    )

    /**
     * The data-plane projection (D95). No endpoint and no key flag detail: a
     * connected application learns that a provider exists and whether it can be
     * used, not where the operator's credential is sent.
     */
    fun toPublicJson(modelCount: Int): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "kind" to kind.wireName,
        "protocol" to effectiveProtocol().wireName,
        "title" to title,
        "enabled" to enabled,
        "configured" to isConfigured(),
        "modelCount" to modelCount
    )

    companion object {
        private val ID_RULE = Regex("^[a-z0-9][a-z0-9._-]*$")

        /**
         * Base-URL policy (D95): `https` anywhere, `http` only for a loopback
         * host. No userinfo, query or fragment — the path is allowed because
         * gateways commonly live under one (`/v1`, `/openai/v1`).
         */
        fun validateBaseUrl(raw: String): AiProblem? {
            val value = raw.trim()
            if (value.isEmpty()) {
                return AiProblem(400, "BAD_PROVIDER_URL", "a base URL is required")
            }
            if (value.length > AiLimits.MAX_BASE_URL_CHARS) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL is too long")
            }
            if (value.any { it.isWhitespace() || it.code < 0x20 }) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL contains a space or control character")
            }
            val lower = value.lowercase()
            val secure = lower.startsWith("https://")
            val plain = lower.startsWith("http://")
            if (!secure && !plain) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL must start with https://")
            }
            val rest = value.substringAfter("://", "")
            if (rest.isEmpty()) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL has no host")
            }
            if (rest.contains('@')) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL must not contain a user name or password")
            }
            if (rest.contains('?') || rest.contains('#')) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL must not contain a query or fragment")
            }
            // The authority is everything before the first path segment. A
            // bracketed IPv6 literal keeps its colons, so it is unwrapped before
            // any port split — `[::1]:8080` is a loopback host with a port, and
            // a naive `substringBefore(':')` reads it as "[".
            val authority = rest.substringBefore('/')
            val host = (
                if (authority.startsWith("[")) {
                    authority.substringAfter('[').substringBefore(']')
                } else {
                    authority.substringBefore(':')
                }
                ).trim()
            if (host.isEmpty()) {
                return AiProblem(400, "BAD_PROVIDER_URL", "the base URL has no host")
            }
            if (plain && host.lowercase() !in LOOPBACK_HOSTS) {
                return AiProblem(
                    400,
                    "INSECURE_PROVIDER_URL",
                    "plain http is allowed only for a local endpoint (127.0.0.1 or localhost); " +
                        "an API key must not travel in clear text"
                )
            }
            return null
        }

        private val LOOPBACK_HOSTS =
            setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

        fun fromJson(raw: Map<*, *>): AiProviderConfig? {
            val id = (raw["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val kind = AiProviderKind.fromWire(raw["kind"] as? String)
                ?: AiProviderKind.fromWire(id)
                ?: AiProviderKind.CUSTOM
            val protocol = AiProtocol.fromWire(raw["protocol"] as? String)
            val storedBase = (raw["baseUrl"] as? String)?.trim().orEmpty()
            return AiProviderConfig(
                id = id,
                kind = kind,
                title = (raw["title"] as? String)?.trim()?.take(AiLimits.MAX_TITLE_CHARS)
                    ?.takeIf { it.isNotEmpty() } ?: id,
                // An empty base URL falls back to the vendor default exactly once,
                // at load time, so a status document always names what is used.
                baseUrl = storedBase.ifEmpty { kind.defaultBaseUrl },
                apiKeySealed = (raw["apiKeySealed"] as? String)?.takeIf { it.isNotBlank() },
                enabled = raw["enabled"] as? Boolean ?: true,
                timeoutMs = (raw["timeoutMs"] as? Number)?.toInt() ?: AiLimits.DEFAULT_TIMEOUT_MS,
                protocol = protocol
            )
        }
    }
}

/** One selectable model. [upstream] is what the provider itself calls it. */
data class AiModel(
    val id: String,
    val providerId: String,
    val upstream: String,
    val title: String,
    val enabled: Boolean = true,
    val maxOutputTokens: Int? = null
) {

    fun capabilities(): List<String> = listOf("chat", "stream")

    fun validate(providerExists: Boolean): AiProblem? {
        if (id.isEmpty() || id.length > AiLimits.MAX_MODEL_ID_CHARS) {
            return AiProblem(
                400,
                "BAD_MODEL_ID",
                "a model id is 1-${AiLimits.MAX_MODEL_ID_CHARS} characters"
            )
        }
        if (id.contains('/')) {
            return AiProblem(
                400,
                "BAD_MODEL_ID",
                "a model id must not contain '/'; that separator selects a provider"
            )
        }
        if (upstream.isBlank() || upstream.length > 128) {
            return AiProblem(400, "BAD_MODEL_UPSTREAM", "the upstream model name is required")
        }
        if (title.isBlank() || title.length > 128) {
            return AiProblem(400, "BAD_MODEL_TITLE", "a model title is required")
        }
        if (!providerExists) {
            return AiProblem(400, "UNKNOWN_PROVIDER", "no provider with id '$providerId'")
        }
        val limit = maxOutputTokens
        if (limit != null && limit !in 1..AiLimits.MAX_MAX_OUTPUT_TOKENS) {
            return AiProblem(
                400,
                "BAD_MODEL_LIMIT",
                "maxOutputTokens must be 1..${AiLimits.MAX_MAX_OUTPUT_TOKENS}"
            )
        }
        return null
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "provider" to providerId,
        "upstream" to upstream,
        "title" to title,
        "enabled" to enabled,
        "maxOutputTokens" to maxOutputTokens
    )

    fun toPublicJson(providerTitle: String): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "provider" to providerId,
        "providerTitle" to providerTitle,
        "title" to title,
        "enabled" to enabled,
        "capabilities" to capabilities()
    )

    fun toOperatorJson(providerTitle: String): Map<String, Any?> =
        toPublicJson(providerTitle) + linkedMapOf("upstream" to upstream)

    companion object {
        fun fromJson(raw: Map<*, *>): AiModel? {
            val id = (raw["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val provider = (raw["provider"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val upstream = (raw["upstream"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: id
            return AiModel(
                id = id,
                providerId = provider,
                upstream = upstream,
                title = (raw["title"] as? String)?.trim()?.take(128)?.takeIf { it.isNotEmpty() }
                    ?: id,
                enabled = raw["enabled"] as? Boolean ?: true,
                maxOutputTokens = (raw["maxOutputTokens"] as? Number)?.toInt()
            )
        }
    }
}

/** Everything the gateway needs, as one immutable document. */
data class AiGatewayConfig(
    val enabled: Boolean = false,
    val defaultModel: String? = null,
    val providers: List<AiProviderConfig> = emptyList(),
    val models: List<AiModel> = emptyList()
) {

    fun provider(id: String): AiProviderConfig? = providers.firstOrNull { it.id == id }

    fun model(id: String): AiModel? = models.firstOrNull { it.id == id }

    fun modelsOf(providerId: String): List<AiModel> = models.filter { it.providerId == providerId }

    /** Providers a caller may actually use: enabled, and with a key and endpoint. */
    fun usableProviders(): List<AiProviderConfig> = providers.filter { it.enabled && it.isConfigured() }

    fun usableModels(): List<AiModel> {
        val providers = usableProviders().associateBy { it.id }
        return models.filter { it.enabled && providers.containsKey(it.providerId) }
    }

    /**
     * Resolves the `model` field of a request (D94).
     *
     * Accepts a catalog id (`gpt-4o-mini`) or a qualified name
     * (`openai/gpt-4o-mini`). A name that is not in the catalog is *not* passed
     * upstream: the catalog is what the operator allowed an application to
     * spend money on, so "not registered" and "unknown" are the same answer.
     */
    fun resolve(requested: String?): AiModel? {
        val wanted = requested?.trim().orEmpty()
        if (wanted.isEmpty()) {
            return defaultModel?.let { model(it) }
        }
        model(wanted)?.let { return it }
        if (!wanted.contains('/')) return null
        val providerId = wanted.substringBefore('/')
        val name = wanted.substringAfter('/')
        if (providerId.isEmpty() || name.isEmpty()) return null
        return models.firstOrNull {
            it.providerId == providerId && (it.id == name || it.upstream == name)
        }
    }

    /** The same check the server runs before it admits a document (S5-R008). */
    fun validate(): AiProblem? {
        if (!enabled) return null
        if (providers.size > AiLimits.MAX_PROVIDERS) {
            return AiProblem(400, "TOO_MANY_PROVIDERS", "at most ${AiLimits.MAX_PROVIDERS} providers")
        }
        if (models.size > AiLimits.MAX_MODELS) {
            return AiProblem(400, "TOO_MANY_MODELS", "at most ${AiLimits.MAX_MODELS} models")
        }
        val seenProviders = HashSet<String>()
        for (provider in providers) {
            provider.validate()?.let { return it }
            if (!seenProviders.add(provider.id)) {
                return AiProblem(400, "DUPLICATE_PROVIDER", "duplicate provider id '${provider.id}'")
            }
        }
        val seenModels = HashSet<String>()
        for (model in models) {
            model.validate(seenProviders.contains(model.providerId))?.let { return it }
            if (!seenModels.add(model.id)) {
                return AiProblem(400, "DUPLICATE_MODEL", "duplicate model id '${model.id}'")
            }
        }
        val default = defaultModel?.trim().orEmpty()
        if (default.isNotEmpty() && resolve(default) == null) {
            return AiProblem(400, "BAD_DEFAULT_MODEL", "the default model '$default' is not in the catalog")
        }
        return null
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "version" to VERSION,
        "enabled" to enabled,
        "defaultModel" to defaultModel,
        "providers" to providers.map { it.toJson() },
        "models" to models.map { it.toJson() }
    )

    fun encode(): String = Json.encode(toJson())

    companion object {
        const val VERSION: Int = 1

        fun fromJson(text: String): AiGatewayConfig? {
            val root = JsonParser.parseObjectOrNull(text) ?: return null
            return fromMap(root)
        }

        fun fromMap(root: Map<*, *>): AiGatewayConfig {
            val providers = ArrayList<AiProviderConfig>()
            (root["providers"] as? List<*>)?.forEach { item ->
                (item as? Map<*, *>)?.let { map -> AiProviderConfig.fromJson(map)?.let(providers::add) }
            }
            val models = ArrayList<AiModel>()
            (root["models"] as? List<*>)?.forEach { item ->
                (item as? Map<*, *>)?.let { map -> AiModel.fromJson(map)?.let(models::add) }
            }
            return AiGatewayConfig(
                enabled = root["enabled"] as? Boolean ?: false,
                defaultModel = (root["defaultModel"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                providers = providers,
                models = models
            )
        }
    }
}
