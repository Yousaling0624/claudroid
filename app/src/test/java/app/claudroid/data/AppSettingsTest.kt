package app.claudroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `normalizes base urls that already contain the messages endpoint`() {
        assertEquals(
            "https://api.example.com",
            AppSettings(baseUrl = "https://api.example.com/v1/messages").normalizedBaseUrl(),
        )
        assertEquals(
            "https://api.example.com",
            AppSettings(baseUrl = "https://api.example.com/v1/").normalizedBaseUrl(),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic",
            AppSettings(baseUrl = " https://api.deepseek.com/anthropic/ ").normalizedBaseUrl(),
        )
        assertEquals(
            "https://api.anthropic.com",
            AppSettings(baseUrl = "   ").normalizedBaseUrl(),
        )
    }

    @Test
    fun `local endpoints do not require an api key`() {
        val ollama = AppSettings(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "")
        assertTrue(ollama.isLocalEndpoint())
        assertTrue(ollama.isConfigured)

        val lan = AppSettings(baseUrl = "http://192.168.1.9:8080", apiKey = "")
        assertTrue("LAN models usually run without a key", lan.isConfigured)

        val remote = AppSettings(baseUrl = "https://example.com", apiKey = "")
        assertFalse(remote.isLocalEndpoint())
        assertFalse(remote.isConfigured)

        val official = AppSettings(baseUrl = "https://api.anthropic.com", apiKey = "")
        assertFalse(official.isConfigured)
    }

    @Test
    fun `provider presets change transport fields but keep the key`() {
        val current = AppSettings(apiKey = "sk-secret", model = "old")
        val deepseek = Providers.all.first { it.id == "deepseek" }
        val next = Providers.apply(current, deepseek)
        assertEquals(ApiFlavor.OPENAI, next.apiFlavor)
        assertEquals("https://api.deepseek.com/v1", next.baseUrl)
        assertEquals(AuthStyle.BEARER, next.authStyle)
        assertEquals("deepseek-chat", next.model)
        assertEquals("sk-secret", next.apiKey)
    }

    @Test
    fun `switching providers clears tier routing and the small model`() {
        val current = AppSettings(
            apiKey = "sk-secret",
            model = "old-default",
            activeTier = ModelTier.OPUS.name,
            opusModel = "old-opus",
            fableModel = "old-fable",
            sonnetModel = "old-sonnet",
            haikuModel = "old-haiku",
            smallModel = "old-small",
        )
        val next = Providers.apply(current, Providers.all.first { it.id == "deepseek" })

        assertEquals("", next.activeTier)
        assertEquals("", next.opusModel)
        assertEquals("", next.fableModel)
        assertEquals("", next.sonnetModel)
        assertEquals("", next.haikuModel)
        assertEquals("", next.smallModel)
        assertEquals("sk-secret", next.apiKey)
        assertEquals("deepseek-chat", next.model)
    }

    @Test
    fun `an active tier is passed as an alias but keeps mapped models in env`() {
        val settings = AppSettings(
            model = "deepseek-chat",
            activeTier = ModelTier.OPUS.name,
            opusModel = "deepseek-reasoner",
            fableModel = "fable-pro",
            sonnetModel = "",
            haikuModel = "deepseek-flash",
        )

        assertEquals("opus", settings.effectiveModelArg())
        assertEquals("deepseek-reasoner", settings.modelForTier(ModelTier.OPUS))
        assertEquals(
            mapOf(
                "ANTHROPIC_DEFAULT_OPUS_MODEL" to "deepseek-reasoner",
                "ANTHROPIC_DEFAULT_FABLE_MODEL" to "fable-pro",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL" to "deepseek-flash",
                "ANTHROPIC_SMALL_FAST_MODEL" to "deepseek-flash",
            ),
            settings.modelEnv(),
        )
    }

    @Test
    fun `no active tier keeps the raw model and explicit small model wins`() {
        val settings = AppSettings(
            model = "deepseek-chat",
            smallModel = "small-explicit",
            haikuModel = "haiku-mapped",
        )

        assertEquals("deepseek-chat", settings.effectiveModelArg())
        assertEquals("deepseek-chat", settings.modelForTier(ModelTier.SONNET))
        assertEquals(
            mapOf(
                "ANTHROPIC_DEFAULT_HAIKU_MODEL" to "haiku-mapped",
                "ANTHROPIC_SMALL_FAST_MODEL" to "small-explicit",
            ),
            settings.modelEnv(),
        )
    }

    @Test
    fun `tier mapping updates only the selected tier and trims input`() {
        val updated = AppSettings()
            .withTierModel(ModelTier.FABLE, "  fable-pro  ")
            .withTierModel(ModelTier.HAIKU, "fast")

        assertEquals("fable-pro", updated.fableModel)
        assertEquals("fast", updated.haikuModel)
        assertEquals("", updated.opusModel)
        assertEquals("", updated.sonnetModel)
    }

    @Test
    fun `tier aliases match bare names and official model ids`() {
        assertEquals(ModelTier.OPUS, ModelTier.fromAliasOrId("opus"))
        assertEquals(ModelTier.OPUS, ModelTier.fromAliasOrId("claude-opus-5-5"))
        assertEquals(ModelTier.SONNET, ModelTier.fromAliasOrId("anthropic/claude-sonnet-4.5"))
        assertEquals(ModelTier.HAIKU, ModelTier.fromAliasOrId("claude-3-5-haiku-20241022"))
        assertEquals(ModelTier.FABLE, ModelTier.fromStored("fable"))
        assertEquals(null, ModelTier.fromAliasOrId("gpt-4o"))
        assertEquals(null, ModelTier.fromAliasOrId("deepseek-chat"))
    }
}
