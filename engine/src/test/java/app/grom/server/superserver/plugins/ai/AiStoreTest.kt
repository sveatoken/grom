// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.ai

import app.grom.server.store.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Key sealing, atomic persistence and the mutation rules (D40, D94, D95).
 *
 * Reversing the "ciphertext" is enough to prove the file never holds plaintext:
 * if the API key appears verbatim on disk, the test fails.
 */
class AiStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String =
            "v1:" + plaintext.reversed() + ":end"

        override fun open(sealed: String): String? {
            // Strict on purpose: a value this box did not produce must not come
            // back as a plausible-looking secret.
            if (!sealed.startsWith("v1:") || !sealed.endsWith(":end")) return null
            return sealed.removePrefix("v1:").removeSuffix(":end").reversed()
        }
    }

    private fun store(dir: File = folder.newFolder()) = AiStore(dir, ReversingBox())

    private fun configWithKey(key: String = "sk-live-0123456789"): AiGatewayConfig {
        val store = store()
        val edited = AiConfigEditor.upsertProvider(
            AiGatewayConfig(enabled = true),
            AiProviderInput(
                id = "openai",
                kind = AiProviderKind.OPENAI,
                title = "OpenAI",
                baseUrl = null,
                apiKey = null,
                clearKey = false,
                enabled = true,
                timeoutMs = null,
                protocol = null
            ),
            store.sealKey(key)
        ) as AiEdit.Ok
        return (AiConfigEditor.upsertModel(
            edited.config,
            AiModelInput("gpt-4o-mini", "openai", null, null, null, null)
        ) as AiEdit.Ok).config
    }

    // -------------------------------------------------------------- sealing

    @Test
    fun `the api key is never written in plaintext`() {
        val dir = folder.newFolder()
        val store = AiStore(dir, ReversingBox())
        val key = "sk-live-do-not-leak"
        val config = configWithKey(key)
        assertNull(store.save(config))

        val text = File(dir, AiStore.FILE_NAME).readText()
        assertFalse("the plaintext key reached the file", text.contains(key))
        assertTrue(text.contains("apiKeySealed"))

        // ...and it still opens with the same box.
        val restored = store.load()
        assertEquals(key, store.openKey(restored.providers.single().apiKeySealed!!))
    }

    @Test
    fun `a key sealed by another box is reported as unusable rather than misread`() {
        val store = store()
        assertNull(store.save(configWithKey()))
        val restored = store.load().copy(
            providers = store.load().providers.map { it.copy(apiKeySealed = "v9:garbage:end") }
        )
        // The box's own format check refuses instead of returning a wrong secret.
        assertNull(store.openKey(restored.providers.single().apiKeySealed!!))
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun `the document survives a reload and leaves no temporary file`() {
        val dir = folder.newFolder()
        val store = AiStore(dir, ReversingBox())
        assertNull(store.save(configWithKey()))

        val again = AiStore(dir, ReversingBox())
        val loaded = again.load()
        assertNull(again.lastError)
        assertEquals("gpt-4o-mini", loaded.defaultModel ?: "gpt-4o-mini")
        assertEquals("openai", loaded.providers.single().id)
        assertFalse(File(dir, "${AiStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `a corrupt document reports the reason and configures nothing`() {
        val dir = folder.newFolder()
        File(dir, AiStore.FILE_NAME).writeText("{ not json")
        val store = AiStore(dir, ReversingBox())
        val loaded = store.load()
        assertTrue(loaded.providers.isEmpty())
        assertNotNull(store.lastError)
        // The file is not deleted: the operator can inspect what happened.
        assertTrue(File(dir, AiStore.FILE_NAME).exists())
    }

    @Test
    fun `an oversized document is refused instead of parsed`() {
        val dir = folder.newFolder()
        File(dir, AiStore.FILE_NAME).writeText("x".repeat(AiStore.MAX_FILE_BYTES + 1))
        val store = AiStore(dir, ReversingBox())
        assertTrue(store.load().providers.isEmpty())
        assertTrue(store.lastError!!.contains("larger than"))
    }

    @Test
    fun `an invalid document is not written`() {
        val dir = folder.newFolder()
        val store = AiStore(dir, ReversingBox())
        val bad = AiGatewayConfig(
            enabled = true,
            providers = listOf(
                AiProviderConfig("openai", AiProviderKind.OPENAI, "x", "http://remote.example", "s")
            ),
            models = emptyList()
        )
        val problem = store.save(bad)
        assertNotNull(problem)
        assertEquals("INSECURE_PROVIDER_URL", problem!!.code)
        assertFalse(File(dir, AiStore.FILE_NAME).exists())
    }

    // ------------------------------------------------------------- editor

    @Test
    fun `adding a provider twice updates it and keeps the stored key`() {
        val base = configWithKey()
        val sealedBefore = base.providers.single().apiKeySealed

        val updated = AiConfigEditor.upsertProvider(
            base,
            AiProviderInput("openai", null, "OpenAI (mine)", null, null, false, false, null, null),
            null
        ) as AiEdit.Ok

        val provider = updated.config.providers.single()
        assertEquals("OpenAI (mine)", provider.title)
        assertEquals(sealedBefore, provider.apiKeySealed)
        assertEquals(AiProviderKind.OPENAI, provider.kind)
    }

    @Test
    fun `a new key replaces the old one and clearKey removes it`() {
        val base = configWithKey()
        val store = store()

        val replaced = AiConfigEditor.upsertProvider(
            base,
            AiProviderInput("openai", null, null, null, null, false, null, null, null),
            store.sealKey("sk-new")
        ) as AiEdit.Ok
        assertEquals(
            "sk-new",
            store.openKey(replaced.config.providers.single().apiKeySealed!!)
        )

        val cleared = AiConfigEditor.upsertProvider(
            replaced.config,
            AiProviderInput("openai", null, null, null, null, clearKey = true, null, null, null),
            null
        ) as AiEdit.Ok
        assertNull(cleared.config.providers.single().apiKeySealed)
        assertFalse(cleared.config.providers.single().isConfigured())
    }

    @Test
    fun `a new key and clearKey together are refused`() {
        // The two intents contradict each other and the answer decides whether
        // an app can spend the operator's credit, so neither silently wins.
        val store = store()
        val result = AiConfigEditor.upsertProvider(
            configWithKey(),
            AiProviderInput("openai", null, null, null, null, clearKey = true, null, null, null),
            store.sealKey("sk-new")
        )
        assertTrue(result is AiEdit.Failure)
        assertEquals("BAD_PROVIDER_INPUT", (result as AiEdit.Failure).problem.code)
    }

    @Test
    fun `removing a provider removes its models and a dangling default`() {
        val base = configWithKey()
        assertNull(base.defaultModel)
        val withDefault = (AiConfigEditor.withDefaultModel(base, "gpt-4o-mini") as AiEdit.Ok).config
        assertEquals("gpt-4o-mini", withDefault.defaultModel)

        val removed = AiConfigEditor.removeProvider(withDefault, "openai") as AiEdit.Ok
        assertTrue(removed.config.providers.isEmpty())
        assertTrue(removed.config.models.isEmpty())
        assertNull(removed.config.defaultModel)

        val missing = AiConfigEditor.removeProvider(withDefault, "ghost") as AiEdit.Failure
        assertEquals(404, missing.problem.status)
    }

    @Test
    fun `removing a model clears a default that pointed at it`() {
        val base = (AiConfigEditor.withDefaultModel(configWithKey(), "gpt-4o-mini") as AiEdit.Ok).config
        val removed = AiConfigEditor.removeModel(base, "gpt-4o-mini") as AiEdit.Ok
        assertTrue(removed.config.models.isEmpty())
        assertNull(removed.config.defaultModel)
    }

    @Test
    fun `a model cannot name a provider that does not exist`() {
        val edit = AiConfigEditor.upsertModel(
            AiGatewayConfig(enabled = true),
            AiModelInput("gpt-4o", "openai", null, null, null, null)
        )
        assertEquals("UNKNOWN_PROVIDER", (edit as AiEdit.Failure).problem.code)
    }

    @Test
    fun `a default model must be in the catalogue`() {
        val edit = AiConfigEditor.withDefaultModel(configWithKey(), "ghost")
        assertEquals("UNKNOWN_MODEL", (edit as AiEdit.Failure).problem.code)
    }

    @Test
    fun `the document bounds how many providers and models may be added`() {
        var config = AiGatewayConfig(enabled = true)
        for (index in 0 until AiLimits.MAX_PROVIDERS) {
            val edit = AiConfigEditor.upsertProvider(
                config,
                AiProviderInput(
                    "p$index", AiProviderKind.CUSTOM, null,
                    "https://example.com/$index", null, false, null, null, null
                ),
                null
            )
            assertEquals(true, edit is AiEdit.Ok)
            config = (edit as AiEdit.Ok).config
        }
        val overflow = AiConfigEditor.upsertProvider(
            config,
            AiProviderInput("extra", AiProviderKind.CUSTOM, null, "https://example.com/x", null, false, null, null, null),
            null
        )
        assertEquals("TOO_MANY_PROVIDERS", (overflow as AiEdit.Failure).problem.code)
    }
}
