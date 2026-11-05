package app.claudroid.engine

import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `auto model leaves ANTHROPIC_MODEL unset but keeps tier and small model mappings`() {
        val settings = AppSettings(
            model = "must-not-be-used",
            activeTier = AppSettings.AUTO_MODEL,
            opusModel = "opus-backend",
            haikuModel = "haiku-backend",
            smallModel = "small-explicit",
        )
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, settings)

        assertFalse("Auto must not pin the CLI to a model id", env.containsKey("ANTHROPIC_MODEL"))
        // Mappings are orthogonal to the --model choice: they still resolve tier aliases
        // when the CLI (or a subagent) asks for one, and back the small/fast model.
        assertEquals("opus-backend", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("haiku-backend", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("small-explicit", env["ANTHROPIC_SMALL_FAST_MODEL"])
    }

    @Test
    fun `subagent and auto mode classifier models are exported when non blank`() {
        val settings = AppSettings(
            subagentModel = "  haiku-agent  ",
            autoModeModel = "classifier-v2",
        )
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, settings)

        assertEquals("haiku-agent", env["CLAUDE_CODE_SUBAGENT_MODEL"])
        assertEquals("classifier-v2", env["CLAUDE_CODE_AUTO_MODE_MODEL"])
    }

    @Test
    fun `blank subagent and classifier models are omitted`() {
        val env = linkedMapOf<String, String>()

        EngineEnv.applyModel(env, AppSettings(subagentModel = "   ", autoModeModel = ""))

        assertFalse(env.containsKey("CLAUDE_CODE_SUBAGENT_MODEL"))
        assertFalse(env.containsKey("CLAUDE_CODE_AUTO_MODE_MODEL"))
    }

    @Test
    fun `agent teams switch is written as 1 when on and 0 when off`() {
        val on = linkedMapOf<String, String>()
        EngineEnv.applyFeatures(on, AppSettings(agentTeams = true))
        assertEquals("1", on["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"])

        val off = linkedMapOf<String, String>()
        EngineEnv.applyFeatures(off, AppSettings(agentTeams = false))
        assertEquals("0", off["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"])
    }

    @Test
    fun `disabled features write their disable flag`() {
        val settings = AppSettings(
            workflows = false,
            autoMemory = false,
            backgroundTasks = false,
            extendedThinking = false,
        )
        val env = linkedMapOf<String, String>()

        EngineEnv.applyFeatures(env, settings)

        assertEquals("1", env["CLAUDE_CODE_DISABLE_WORKFLOWS"])
        assertEquals("1", env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"])
        assertEquals("1", env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
        assertEquals("1", env["CLAUDE_CODE_DISABLE_THINKING"])
    }

    @Test
    fun `enabled features write no disable flag`() {
        val settings = AppSettings(
            workflows = true,
            autoMemory = true,
            backgroundTasks = true,
            extendedThinking = true,
        )
        val env = linkedMapOf<String, String>()

        EngineEnv.applyFeatures(env, settings)

        assertNull(env["CLAUDE_CODE_DISABLE_WORKFLOWS"])
        assertNull(env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"])
        assertNull(env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
        assertNull(env["CLAUDE_CODE_DISABLE_THINKING"])
    }

    @Test
    fun `defaults enable every feature except agent teams`() {
        val env = linkedMapOf<String, String>()

        EngineEnv.applyFeatures(env, AppSettings())

        assertEquals("0", env["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"])
        assertFalse(env.keys.any { it.startsWith("CLAUDE_CODE_DISABLE_") })
    }
}
