package app.claudroid.engine

import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EngineEnvTest {

    @Test
    fun `an active tier sends the alias to both the model env and the mappings`() {
        val settings = AppSettings(
            model = "deepseek-chat",
            activeTier = ModelTier.OPUS.name,
            opusModel = "deepseek-reasoner",
            haikuModel = "deepseek-flash",
        )
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, settings)

        // `--model` and ANTHROPIC_MODEL must agree, otherwise the alias is overridden.
        assertEquals("opus", env["ANTHROPIC_MODEL"])
        assertEquals("deepseek-reasoner", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("deepseek-flash", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("deepseek-flash", env["ANTHROPIC_SMALL_FAST_MODEL"])
    }

    @Test
    fun `without an active tier the raw model is kept`() {
        val settings = AppSettings(model = "deepseek-chat", smallModel = "small-explicit")
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, settings)

        assertEquals("deepseek-chat", env["ANTHROPIC_MODEL"])
        assertEquals("small-explicit", env["ANTHROPIC_SMALL_FAST_MODEL"])
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_OPUS_MODEL"))
    }

    @Test
    fun `a blank model with no tier leaves model env unset`() {
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, AppSettings(model = "", smallModel = ""))

        assertFalse(env.containsKey("ANTHROPIC_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_SMALL_FAST_MODEL"))
    }
}
