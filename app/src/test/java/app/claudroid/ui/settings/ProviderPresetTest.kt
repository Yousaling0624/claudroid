package app.claudroid.ui.settings

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPresetTest {

    @Test
    fun `default settings resolve to the anthropic preset`() {
        assertEquals("Anthropic 官方", activeProviderPreset(AppSettings())?.label)
    }

    @Test
    fun `trailing slashes do not break the match`() {
        val settings = AppSettings(baseUrl = "https://api.anthropic.com/")
        assertEquals("Anthropic 官方", activeProviderPreset(settings)?.label)
    }

    @Test
    fun `a hand-edited endpoint is custom`() {
        assertNull(activeProviderPreset(AppSettings(baseUrl = "https://my-gateway.example")))
    }

    @Test
    fun `the protocol must match as well`() {
        assertNull(activeProviderPreset(AppSettings(apiFlavor = ApiFlavor.OPENAI)))
    }
}
