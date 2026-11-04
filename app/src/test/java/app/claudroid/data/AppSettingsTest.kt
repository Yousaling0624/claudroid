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
}
