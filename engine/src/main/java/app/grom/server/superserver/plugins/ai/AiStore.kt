// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import java.io.File

/**
 * Atomic persistence of the AI gateway configuration (D40, D94).
 *
 * The provider API keys are sealed by [SecretBox] before the document is built,
 * so the file on disk holds ciphertext and nothing else. A corrupt or oversized
 * file is not fatal: the gateway reports [lastError] and behaves as "no provider
 * configured", which is the honest state and cannot spend money.
 */
class AiStore(
    private val directory: File,
    private val secretBox: SecretBox
) {

    private val file = File(directory, FILE_NAME)

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun load(): AiGatewayConfig {
        lastError = null
        if (!file.isFile) return AiGatewayConfig()
        return try {
            // Measure before reading: a rogue file must not be pulled into
            // memory only to be rejected afterwards.
            if (file.length() > MAX_FILE_BYTES) {
                lastError = "the AI configuration is larger than $MAX_FILE_BYTES bytes"
                AiGatewayConfig()
            } else {
                AiGatewayConfig.fromJson(file.readText()) ?: run {
                    lastError = "the AI configuration is not a JSON object"
                    AiGatewayConfig()
                }
            }
        } catch (failure: Exception) {
            lastError = "the AI configuration is unreadable: ${failure.javaClass.simpleName}"
            AiGatewayConfig()
        }
    }

    /**
     * Seals a plaintext key for storage, or returns null when the platform box
     * refuses. The plaintext is never written, logged or kept.
     */
    fun sealKey(plaintext: String): String? =
        runCatching { secretBox.seal(plaintext) }.getOrNull()

    fun openKey(sealed: String): String? = runCatching { secretBox.open(sealed) }.getOrNull()

    /**
     * Validates and writes the document. Returns the refusal instead of writing
     * a configuration the server would then have to refuse at call time.
     */
    @Synchronized
    fun save(config: AiGatewayConfig): AiProblem? {
        config.validate()?.let { return it }
        val document = Json.encode(config.toJson())
        if (document.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            return AiProblem(
                413,
                "AI_CONFIG_TOO_LARGE",
                "the AI configuration is larger than ${MAX_FILE_BYTES / 1024} KiB"
            )
        }
        return try {
            directory.mkdirs()
            // Write-then-rename: a crash mid-write leaves the previous document.
            val temp = File(directory, "$FILE_NAME.tmp")
            temp.writeText(document)
            harden(temp)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            harden(file)
            lastError = null
            null
        } catch (failure: Exception) {
            lastError = "the AI configuration could not be written: ${failure.javaClass.simpleName}"
            AiProblem(500, "AI_CONFIG_WRITE_FAILED", "the configuration could not be saved")
        }
    }

    /**
     * Owner-only, like every other store (D40). The keys inside are already
     * ciphertext, so this is the second lock, not the first.
     */
    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "ai.json"
        const val MAX_FILE_BYTES: Int = 128 * 1024

        /**
         * Reads the document without a [SecretBox], for a JVM test or a
         * diagnostic that only inspects shapes. Never used by the server.
         */
        fun readForInspection(text: String): AiGatewayConfig? = AiGatewayConfig.fromJson(text)
    }
}

// -------------------------------------------------------------------- inputs

/** What a host sends to add or change a provider (D96). */
data class AiProviderInput(
    val id: String,
    val kind: AiProviderKind?,
    val title: String?,
    val baseUrl: String?,
    /** Plaintext, used once and sealed; never echoed back. */
    val apiKey: String?,
    val clearKey: Boolean,
    val enabled: Boolean?,
    val timeoutMs: Int?,
    val protocol: AiProtocol?
) {

    companion object {
        fun parse(root: Map<String, Any?>): AiProviderInput? {
            val id = (root["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return AiProviderInput(
                id = id,
                kind = AiProviderKind.fromWire(root["kind"] as? String),
                title = (root["title"] as? String)?.trim(),
                baseUrl = (root["baseUrl"] as? String)?.trim(),
                apiKey = (root["apiKey"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                clearKey = root["clearKey"] as? Boolean ?: false,
                enabled = root["enabled"] as? Boolean,
                timeoutMs = (root["timeoutMs"] as? Number)?.toInt(),
                protocol = AiProtocol.fromWire(root["protocol"] as? String)
            )
        }
    }
}

/** What a host sends to add or change a model. */
data class AiModelInput(
    val id: String,
    val providerId: String?,
    val upstream: String?,
    val title: String?,
    val enabled: Boolean?,
    val maxOutputTokens: Int?
) {

    companion object {
        fun parse(root: Map<String, Any?>): AiModelInput? {
            val id = (root["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return AiModelInput(
                id = id,
                providerId = (root["provider"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                upstream = (root["upstream"] as? String)?.trim(),
                title = (root["title"] as? String)?.trim(),
                enabled = root["enabled"] as? Boolean,
                maxOutputTokens = (root["maxOutputTokens"] as? Number)?.toInt()
            )
        }
    }
}

sealed class AiEdit {
    data class Ok(val config: AiGatewayConfig) : AiEdit()
    data class Failure(val problem: AiProblem) : AiEdit()
}

/**
 * Every mutation of the AI configuration, as pure functions (D30).
 *
 * Keeping this out of the store and the runtime means the rules — a duplicate
 * id, a model that names a missing provider, a default that would dangle after a
 * removal — are JVM-tested without a file or an Android keystore.
 */
object AiConfigEditor {

    fun upsertProvider(
        config: AiGatewayConfig,
        input: AiProviderInput,
        sealedKey: String?
    ): AiEdit {
        val existing = config.provider(input.id)
        // "Set this key" and "remove my key" cannot both be true. Refusing is
        // better than picking one: the answer decides whether the operator's
        // money is reachable from an app, and a silent winner would be a guess.
        if (input.clearKey && sealedKey != null) {
            return AiEdit.Failure(
                AiProblem(
                    400,
                    "BAD_PROVIDER_INPUT",
                    "a new key and clearKey cannot be sent together"
                )
            )
        }
        if (existing == null && config.providers.size >= AiLimits.MAX_PROVIDERS) {
            return AiEdit.Failure(
                AiProblem(400, "TOO_MANY_PROVIDERS", "at most ${AiLimits.MAX_PROVIDERS} providers")
            )
        }
        val kind = input.kind ?: existing?.kind ?: AiProviderKind.CUSTOM
        val provider = AiProviderConfig(
            id = input.id,
            kind = kind,
            title = input.title?.takeIf { it.isNotEmpty() }
                ?: existing?.title
                ?: defaultTitle(kind, input.id),
            // A blank field on update keeps the stored endpoint; on create the
            // vendor default applies. The gateway never guesses at call time.
            baseUrl = input.baseUrl?.takeIf { it.isNotEmpty() }
                ?: existing?.baseUrl
                ?: kind.defaultBaseUrl,
            apiKeySealed = when {
                input.clearKey -> null
                sealedKey != null -> sealedKey
                else -> existing?.apiKeySealed
            },
            enabled = input.enabled ?: existing?.enabled ?: true,
            timeoutMs = input.timeoutMs ?: existing?.timeoutMs ?: AiLimits.DEFAULT_TIMEOUT_MS,
            protocol = input.protocol ?: existing?.protocol
        )
        provider.validate()?.let { return AiEdit.Failure(it) }

        val providers = config.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) providers[index] = provider else providers.add(provider)
        return AiEdit.Ok(config.copy(providers = providers))
    }

    /** Removes a provider *and* its models, so nothing dangles behind it. */
    fun removeProvider(config: AiGatewayConfig, id: String): AiEdit {
        if (config.provider(id) == null) {
            return AiEdit.Failure(AiProblem(404, "UNKNOWN_PROVIDER", "no provider with id '$id'"))
        }
        val providers = config.providers.filterNot { it.id == id }
        val models = config.models.filterNot { it.providerId == id }
        val default = config.defaultModel?.takeIf { ref -> models.any { it.id == ref } }
        return AiEdit.Ok(config.copy(providers = providers, models = models, defaultModel = default))
    }

    fun upsertModel(config: AiGatewayConfig, input: AiModelInput): AiEdit {
        val existing = config.model(input.id)
        if (existing == null && config.models.size >= AiLimits.MAX_MODELS) {
            return AiEdit.Failure(
                AiProblem(400, "TOO_MANY_MODELS", "at most ${AiLimits.MAX_MODELS} models")
            )
        }
        val model = AiModel(
            id = input.id,
            providerId = input.providerId ?: existing?.providerId.orEmpty(),
            upstream = input.upstream?.takeIf { it.isNotEmpty() }
                ?: existing?.upstream
                ?: input.id,
            title = input.title?.takeIf { it.isNotEmpty() } ?: existing?.title ?: input.id,
            enabled = input.enabled ?: existing?.enabled ?: true,
            maxOutputTokens = input.maxOutputTokens ?: existing?.maxOutputTokens
        )
        model.validate(config.provider(model.providerId) != null)?.let { return AiEdit.Failure(it) }

        val models = config.models.toMutableList()
        val index = models.indexOfFirst { it.id == model.id }
        if (index >= 0) models[index] = model else models.add(model)
        return AiEdit.Ok(config.copy(models = models))
    }

    fun removeModel(config: AiGatewayConfig, id: String): AiEdit {
        if (config.model(id) == null) {
            return AiEdit.Failure(AiProblem(404, "UNKNOWN_MODEL", "no model with id '$id'"))
        }
        val models = config.models.filterNot { it.id == id }
        val default = config.defaultModel?.takeIf { ref -> models.any { it.id == ref } }
        return AiEdit.Ok(config.copy(models = models, defaultModel = default))
    }

    /** Picks the model a request uses when it names none. */
    fun withDefaultModel(config: AiGatewayConfig, id: String?): AiEdit {
        val cleaned = id?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null && config.model(cleaned) == null) {
            return AiEdit.Failure(AiProblem(404, "UNKNOWN_MODEL", "no model with id '$cleaned'"))
        }
        return AiEdit.Ok(config.copy(defaultModel = cleaned))
    }

    fun withEnabled(config: AiGatewayConfig, enabled: Boolean): AiEdit =
        AiEdit.Ok(config.copy(enabled = enabled))

    private fun defaultTitle(kind: AiProviderKind, id: String): String = when (kind) {
        AiProviderKind.OPENAI -> "OpenAI"
        AiProviderKind.ANTHROPIC -> "Anthropic"
        AiProviderKind.GEMINI -> "Google Gemini"
        AiProviderKind.DEEPSEEK -> "DeepSeek"
        AiProviderKind.QWEN -> "Qwen"
        AiProviderKind.KIMI -> "Kimi"
        AiProviderKind.OPENROUTER -> "OpenRouter"
        AiProviderKind.CUSTOM -> id
    }
}

/** Parses a Binder document into the input the editor takes. */
object AiInputs {
    fun provider(json: String): Pair<AiProviderInput?, AiProblem?> {
        val root = JsonParser.parseObjectOrNull(json)
            ?: return null to AiProblem(400, "AI_BAD_INPUT", "the document must be a JSON object")
        val input = AiProviderInput.parse(root)
            ?: return null to AiProblem(400, "AI_BAD_INPUT", "a provider document needs an id")
        return input to null
    }

    fun model(json: String): Pair<AiModelInput?, AiProblem?> {
        val root = JsonParser.parseObjectOrNull(json)
            ?: return null to AiProblem(400, "AI_BAD_INPUT", "the document must be a JSON object")
        val input = AiModelInput.parse(root)
            ?: return null to AiProblem(400, "AI_BAD_INPUT", "a model document needs an id")
        return input to null
    }
}
