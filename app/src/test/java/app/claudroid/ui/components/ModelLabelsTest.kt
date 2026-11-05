package app.claudroid.ui.components

import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLabelsTest {

    private val base = AppSettings(model = "auto/hy4free")

    @Test
    fun composerShowsModelWithoutActiveTier() {
        assertEquals("auto/hy4free", composerModelLabel(base))
    }

    @Test
    fun composerShowsTierAndMappedModel() {
        val settings = base.copy(activeTier = ModelTier.OPUS.name, opusModel = "auto/hy4free")
        assertEquals("Opus · auto/hy4free", composerModelLabel(settings))
    }

    @Test
    fun unmappedTierHasShortComposerLabel() {
        val settings = base.copy(activeTier = ModelTier.SONNET.name)
        assertEquals("Sonnet", composerModelLabel(settings))
    }

    @Test
    fun chatLabelFallsBackToOfficialDefault() {
        val settings = base.copy(activeTier = ModelTier.FABLE.name)
        assertEquals("Fable · 官方默认", chatModelLabel(settings))
    }

    @Test
    fun chatLabelShowsTierMapping() {
        val settings = base.copy(activeTier = ModelTier.HAIKU.name, haikuModel = "small-model")
        assertEquals("Haiku · small-model", chatModelLabel(settings))
    }

    @Test
    fun tierMappingLabelUsesConfiguredValue() {
        val settings = base.copy(sonnetModel = "claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", tierMappingLabel(settings, ModelTier.SONNET))
        assertEquals("官方默认", tierMappingLabel(settings, ModelTier.OPUS))
    }

    @Test
    fun activeTierSummaryExplainsDefault() {
        assertEquals("默认模型 · auto/hy4free", activeTierSummary(base))
    }

    @Test
    fun activeTierSummaryExplainsSelectedTier() {
        val settings = base.copy(activeTier = ModelTier.OPUS.name, opusModel = "auto/hy4free")
        assertEquals("Opus · auto/hy4free", activeTierSummary(settings))
    }
}
