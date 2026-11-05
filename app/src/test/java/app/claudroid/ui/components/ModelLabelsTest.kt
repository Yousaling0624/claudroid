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
    fun activeTierSummaryExplainsDefaultRouteGroup() {
        assertEquals("默认 / 路由组 · auto/hy4free", activeTierSummary(base))
    }

    @Test
    fun activeTierSummaryExplainsSelectedTier() {
        val settings = base.copy(activeTier = ModelTier.OPUS.name, opusModel = "auto/hy4free")
        assertEquals("Opus · auto/hy4free", activeTierSummary(settings))
    }

    @Test
    fun autoMeansNoModelFlagRatherThanARawModelId() {
        val auto = base.copy(activeTier = AppSettings.AUTO_MODEL)
        assertEquals("CLI 默认", composerModelLabel(auto))
        assertEquals("CLI 默认 · 不指定模型", chatModelLabel(auto))
        assertEquals("CLI 默认 · 不指定模型", activeTierSummary(auto))
    }

    @Test
    fun emptyModelFieldIsReportedAsCliDefault() {
        val empty = AppSettings(model = "")
        assertEquals("默认", composerModelLabel(empty))
        assertEquals("CLI 默认 · 不指定模型", chatModelLabel(empty))
    }

    @Test
    fun shortLabelKeepsOnlyTheTierName() {
        val settings = base.copy(activeTier = ModelTier.OPUS.name, opusModel = "auto/hy4free")
        assertEquals("Opus", shortModelLabel(settings))
    }

    @Test
    fun shortLabelFallsBackToTheRawModel() {
        assertEquals("auto/hy4free", shortModelLabel(base))
        assertEquals("CLI 默认", shortModelLabel(base.copy(activeTier = AppSettings.AUTO_MODEL)))
        assertEquals("默认", shortModelLabel(AppSettings(model = "")))
    }

    @Test
    fun shortLabelTruncatesVeryLongModelIds() {
        val long = AppSettings(model = "vendor/some-very-long-routing-group")
        assertEquals("vendor/some-ver…", shortModelLabel(long))
    }
}
