package app.claudroid.engine

import app.claudroid.data.AppSettings
import app.claudroid.data.EffortLevel
import app.claudroid.data.ModelTier
import app.claudroid.data.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeArgsTest {

    private fun args(settings: AppSettings, cliVersion: String = "2.1.318"): List<String> =
        ClaudeEngine.buildArgs(prompt = "hello", resume = null, s = settings, cliVersion = cliVersion)

    /** Value of a `--flag value` pair, or null when the flag is absent. */
    private fun List<String>.flag(name: String): String? {
        val i = indexOf(name)
        return if (i >= 0 && i + 1 < size) get(i + 1) else null
    }

    @Test
    fun `default args always request a permission mode`() {
        val args = args(AppSettings())
        assertEquals(PermissionMode.BYPASS.cli, args.flag("--permission-mode"))
        assertEquals("-p", args[0])
        assertEquals("hello", args[1])
        assertTrue(args.containsAll(listOf("--output-format", "stream-json")))
    }

    @Test
    fun `auto permission mode is passed through as the official auto value`() {
        val args = args(AppSettings(permissionMode = PermissionMode.AUTO))
        assertEquals("auto", args.flag("--permission-mode"))
    }

    @Test
    fun `an active tier requests its cli alias`() {
        val args = args(AppSettings(activeTier = ModelTier.OPUS.name, model = "ignored-backend"))
        assertEquals("opus", args.flag("--model"))
    }

    @Test
    fun `auto model omits the model flag entirely`() {
        val args = args(AppSettings(activeTier = AppSettings.AUTO_MODEL, model = "ignored-backend"))
        assertFalse("--model must be absent so the CLI uses its own default", args.contains("--model"))
        // A stored "AUTO" with surrounding whitespace is still the app-level Auto choice.
        val padded = args(
            AppSettings(activeTier = "  ${AppSettings.AUTO_MODEL}  ", model = "ignored-backend"),
        )
        assertFalse(padded.contains("--model"))
    }

    @Test
    fun `effort auto omits the flag while a concrete level is passed`() {
        assertFalse(args(AppSettings(effort = EffortLevel.AUTO)).contains("--effort"))
        assertEquals("high", args(AppSettings(effort = EffortLevel.HIGH)).flag("--effort"))
        assertEquals("xhigh", args(AppSettings(effort = EffortLevel.XHIGH)).flag("--effort"))
    }

    @Test
    fun `extra system prompt appends and disables snapshot on new cli versions`() {
        val settings = AppSettings(extraSystemPrompt = "extra instructions")
        val modern = args(settings, cliVersion = "2.1.318")
        assertEquals("extra instructions", modern.flag("--append-system-prompt"))
        assertEquals("off", modern.flag("--system-prompt-snapshot"))

        // The flag only exists since 2.1.257; older binaries must not receive it.
        val old = args(settings, cliVersion = "2.1.100")
        assertEquals("extra instructions", old.flag("--append-system-prompt"))
        assertFalse(old.contains("--system-prompt-snapshot"))
    }

    @Test
    fun `without an extra system prompt there is no snapshot flag`() {
        val args = args(AppSettings())
        assertFalse(args.contains("--append-system-prompt"))
        assertFalse(args.contains("--system-prompt-snapshot"))
    }
}
