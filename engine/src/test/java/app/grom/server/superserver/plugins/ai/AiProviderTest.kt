// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider/model document (D94, D95).
 *
 * These are the rules a request never sees directly but every request depends
 * on: which protocol a provider speaks, which base URLs are allowed, and how a
 * model name resolves against the catalogue the operator approved.
 */
class AiProviderTest {

    private fun provider(
        id: String = "openai",
        kind: AiProviderKind = AiProviderKind.OPENAI,
        baseUrl: String = kind.defaultBaseUrl,
        protocol: AiProtocol? = null,
        enabled: Boolean = true
    ) = AiProviderConfig(
        id = id,
        kind = kind,
        title = id,
        baseUrl = baseUrl,
        apiKeySealed = "sealed",
        enabled = enabled,
        protocol = protocol
    )

    private fun model(id: String, upstream: String = id, provider: String = "openai") =
        AiModel(id = id, providerId = provider, upstream = upstream, title = id)

    // ------------------------------------------------------------ protocols

    @Test
    fun `every known vendor maps to a protocol and a default endpoint`() {
        assertEquals(AiProtocol.OPENAI, AiProviderKind.OPENAI.protocol)
        assertEquals(AiProtocol.ANTHROPIC, AiProviderKind.ANTHROPIC.protocol)
        assertEquals(AiProtocol.GEMINI, AiProviderKind.GEMINI.protocol)
        // The OpenAI-compatible vendors share one adapter on purpose: it is the
        // whole point of not writing a class per vendor.
        for (kind in listOf(
            AiProviderKind.DEEPSEEK,
            AiProviderKind.QWEN,
            AiProviderKind.KIMI,
            AiProviderKind.OPENROUTER,
            AiProviderKind.CUSTOM
        )) {
            assertEquals(kind.wireName, AiProtocol.OPENAI, kind.protocol)
        }
        assertTrue(AiProviderKind.ANTHROPIC.defaultBaseUrl.startsWith("https://"))
        assertTrue(AiProviderKind.CUSTOM.defaultBaseUrl.isEmpty())
    }

    @Test
    fun `an explicit protocol overrides the vendor default`() {
        val custom = provider(kind = AiProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1")
        assertEquals(AiProtocol.OPENAI, custom.effectiveProtocol())

        val anthropicOnCustom = custom.copy(protocol = AiProtocol.ANTHROPIC)
        assertEquals(AiProtocol.ANTHROPIC, anthropicOnCustom.effectiveProtocol())

        // A vendor kind can be re-pointed too: the operator may front OpenAI
        // with a gateway that speaks Anthropic.
        val rePointed = provider().copy(protocol = AiProtocol.GEMINI)
        assertEquals(AiProtocol.GEMINI, rePointed.effectiveProtocol())
    }

    // --------------------------------------------------------------- base URL

    @Test
    fun `https is accepted anywhere and http only on a loopback host`() {
        assertNull(AiProviderConfig.validateBaseUrl("https://api.example.com/v1"))
        assertNull(AiProviderConfig.validateBaseUrl("http://127.0.0.1:11434/v1"))
        assertNull(AiProviderConfig.validateBaseUrl("http://localhost:8080"))
        assertNull(AiProviderConfig.validateBaseUrl("http://[::1]:8080/v1"))
        // A bracketed literal without a port, and the fully expanded form.
        assertNull(AiProviderConfig.validateBaseUrl("http://[::1]/v1"))
        assertNull(AiProviderConfig.validateBaseUrl("http://[0:0:0:0:0:0:0:1]:8080"))
        // A remote IPv6 literal over plain http is still refused.
        assertEquals(
            "INSECURE_PROVIDER_URL",
            AiProviderConfig.validateBaseUrl("http://[2001:db8::1]:8080")!!.code
        )

        val plain = AiProviderConfig.validateBaseUrl("http://api.example.com/v1")
        assertNotNull(plain)
        assertEquals("INSECURE_PROVIDER_URL", plain!!.code)
    }

    @Test
    fun `a base URL with credentials a query or a fragment is refused`() {
        assertEquals(
            "BAD_PROVIDER_URL",
            AiProviderConfig.validateBaseUrl("https://user:pass@api.example.com/v1")!!.code
        )
        assertEquals(
            "BAD_PROVIDER_URL",
            AiProviderConfig.validateBaseUrl("https://api.example.com/v1?key=abc")!!.code
        )
        assertEquals(
            "BAD_PROVIDER_URL",
            AiProviderConfig.validateBaseUrl("https://api.example.com/v1#x")!!.code
        )
        assertEquals("BAD_PROVIDER_URL", AiProviderConfig.validateBaseUrl("ftp://example.com")!!.code)
        assertEquals("BAD_PROVIDER_URL", AiProviderConfig.validateBaseUrl("")!!.code)
        assertEquals(
            "BAD_PROVIDER_URL",
            AiProviderConfig.validateBaseUrl("https://exa mple.com")!!.code
        )
    }

    @Test
    fun `a provider id is a wire name and a title is required`() {
        assertNull(provider(id = "my-gw_1").validate())
        assertEquals("BAD_PROVIDER_ID", provider(id = "My Gw").validate()!!.code)
        assertEquals("BAD_PROVIDER_ID", provider(id = "_leading").validate()!!.code)
        assertEquals("BAD_PROVIDER_TITLE", provider().copy(title = " ").validate()!!.code)
        assertEquals(
            "BAD_PROVIDER_TIMEOUT",
            provider().copy(timeoutMs = 500).validate()!!.code
        )
    }

    // ---------------------------------------------------------------- resolve

    private fun catalog() = AiGatewayConfig(
        enabled = true,
        defaultModel = "gpt-4o-mini",
        providers = listOf(
            provider(id = "openai"),
            provider(id = "anthropic", kind = AiProviderKind.ANTHROPIC)
        ),
        models = listOf(
            model("gpt-4o-mini"),
            model("gpt-4o", upstream = "gpt-4o-2024-08-06"),
            model(
                "claude",
                upstream = "claude-3-5-sonnet-20241022",
                provider = "anthropic"
            )
        )
    )

    @Test
    fun `a request resolves by id by qualified name and by upstream alias`() {
        val config = catalog()
        assertEquals("gpt-4o-mini", config.resolve(null)!!.id)
        assertEquals("gpt-4o-mini", config.resolve("gpt-4o-mini")!!.id)
        assertEquals("gpt-4o", config.resolve("openai/gpt-4o")!!.id)
        // The upstream name of an OpenRouter-style model is accepted too.
        assertEquals("gpt-4o", config.resolve("openai/gpt-4o-2024-08-06")!!.id)
        assertEquals("claude", config.resolve("anthropic/claude-3-5-sonnet-20241022")!!.id)
    }

    @Test
    fun `a model outside the catalogue never resolves`() {
        val config = catalog()
        assertNull(config.resolve("gpt-5-ultra"))
        assertNull(config.resolve("openai/not-registered"))
        assertNull(config.resolve("unknown-vendor/gpt-4o"))
        assertNull(config.resolve("openai/"))
        assertNull(config.resolve("/gpt-4o"))
    }

    @Test
    fun `no default and no request means no model`() {
        val config = catalog().copy(defaultModel = null)
        assertNull(config.resolve(null))
        assertNull(config.resolve("  "))
    }

    // --------------------------------------------------------------- validate

    @Test
    fun `the document refuses duplicate ids dangling models and a bad default`() {
        val base = catalog()
        assertEquals(
            "DUPLICATE_PROVIDER",
            base.copy(providers = base.providers + base.providers.first()).validate()!!.code
        )
        assertEquals(
            "DUPLICATE_MODEL",
            base.copy(models = base.models + base.models.first()).validate()!!.code
        )
        assertEquals(
            "UNKNOWN_PROVIDER",
            base.copy(
                models = listOf(model("x", provider = "ghost"))
            ).validate()!!.code
        )
        assertEquals(
            "BAD_DEFAULT_MODEL",
            base.copy(defaultModel = "ghost").validate()!!.code
        )
        assertEquals(
            "BAD_MODEL_ID",
            base.copy(models = base.models + model("vendor/name")).validate()!!.code
        )
    }

    @Test
    fun `a disabled gateway accepts an empty document and enables nothing`() {
        val empty = AiGatewayConfig()
        assertNull(empty.validate())
        assertTrue(empty.usableModels().isEmpty())
        assertTrue(empty.resolve(null) == null)
    }

    @Test
    fun `usable models exclude disabled providers models and missing keys`() {
        val config = catalog().copy(
            providers = listOf(
                provider(id = "openai"),
                provider(id = "anthropic", kind = AiProviderKind.ANTHROPIC).copy(apiKeySealed = null)
            )
        )
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), config.usableModels().map { it.id })
        // A disabled model is not offered even when its provider is healthy.
        val withDisabled = config.copy(
            models = config.models.map { if (it.id == "gpt-4o") it.copy(enabled = false) else it }
        )
        assertEquals(listOf("gpt-4o-mini"), withDisabled.usableModels().map { it.id })
    }

    // ------------------------------------------------------------------- JSON

    @Test
    fun `a saved document round-trips and an empty endpoint takes the vendor default`() {
        val config = catalog().copy(enabled = true)
        val restored = AiGatewayConfig.fromJson(config.encode())!!
        assertEquals(config.providers.map { it.id }, restored.providers.map { it.id })
        assertEquals(config.models.map { it.id }, restored.models.map { it.id })
        assertEquals(config.defaultModel, restored.defaultModel)
        assertEquals("openai", restored.providers.first().kind.wireName)

        val storedWithoutUrl = AiGatewayConfig.fromJson(
            """{"version":1,"enabled":true,"providers":[{"id":"deepseek","kind":"deepseek"}],"models":[]}"""
        )!!
        assertEquals(
            "https://api.deepseek.com/v1",
            storedWithoutUrl.providers.single().baseUrl
        )
    }

    @Test
    fun `an unknown protocol in a stored document falls back to the kind`() {
        val restored = AiGatewayConfig.fromJson(
            """{"providers":[{"id":"x","kind":"custom","baseUrl":"http://127.0.0.1:9","protocol":"smoke-signals"}],"models":[]}"""
        )!!
        assertEquals(AiProtocol.OPENAI, restored.providers.single().effectiveProtocol())
    }
}
