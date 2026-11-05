package app.claudroid.ui.workspace

import app.claudroid.engine.TerminalRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure shaping/trimming logic used by the workspace terminal. */
class WorkspaceTerminalLogicTest {

    @Test
    fun `clear is a UI builtin, blank input is ignored`() {
        assertEquals(CommandIntent.Run, classifyCommand("ls -la"))
        assertEquals(CommandIntent.Run, classifyCommand("  pwd  "))
        assertEquals(CommandIntent.Clear, classifyCommand("clear"))
        assertEquals(CommandIntent.Clear, classifyCommand(" clear "))
        assertEquals(CommandIntent.Ignore, classifyCommand(""))
        assertEquals(CommandIntent.Ignore, classifyCommand("   "))
    }

    @Test
    fun `subtitle shows the launch mode and current work directory`() {
        assertEquals("PRoot（免 root） · /work", terminalSubtitle("PRoot（免 root）", ""))
        assertEquals("root + chroot · /work/src/app", terminalSubtitle("root + chroot", "src/app"))
        assertEquals("/work/src/app", terminalSubtitle(null, "src/app/"))
        assertEquals("/work", terminalSubtitle("", ""))
    }

    @Test
    fun `live output grows until the cap then keeps only the tail`() {
        val first = appendLiveOutput("", "hello")
        assertEquals("hello", first.text)
        assertFalse(first.truncated)

        val over = appendLiveOutput("x".repeat(199_999), "abcdef", maxChars = 200_000)
        assertEquals(200_000, over.text.length)
        assertTrue(over.truncated)
        // Tail is preserved: the last six characters of the combined string.
        assertEquals("abcdef", over.text.takeLast(6))
        assertTrue(over.text.startsWith("x"))
    }

    @Test
    fun `live output stays within cap when already truncated`() {
        val current = "y".repeat(MAX_LIVE_CHARS)
        val next = appendLiveOutput(current, "z")
        assertEquals(MAX_LIVE_CHARS, next.text.length)
        assertEquals('z', next.text.last())
        assertTrue(next.truncated)
    }

    @Test
    fun `trimLines keeps the newest lines`() {
        val lines = (0 until 305).map {
            TerminalLine("line $it", TerminalLine.Kind.Output)
        }
        val trimmed = trimLines(lines, max = 300)
        assertEquals(300, trimmed.size)
        assertEquals("line 5", trimmed.first().text)
        assertEquals("line 304", trimmed.last().text)
        assertEquals(lines, trimLines(lines, max = 400))
    }

    @Test
    fun `completion lines append output, truncation notice and error`() {
        val ok = completionLines("hello\n", false, TerminalRunner.Result("hello", 0, ""))
        assertEquals(listOf(TerminalLine("hello", TerminalLine.Kind.Output)), ok)

        val withError = completionLines(
            "partial",
            false,
            TerminalRunner.Result("partial", -1, "", error = "超时"),
        )
        assertEquals(
            listOf(
                TerminalLine("partial", TerminalLine.Kind.Output),
                TerminalLine("超时", TerminalLine.Kind.Error),
            ),
            withError,
        )

        val nonZero = completionLines("", false, TerminalRunner.Result("", 42, "src"))
        assertEquals(
            listOf(TerminalLine("退出码 42", TerminalLine.Kind.Info)),
            nonZero,
        )
    }

    @Test
    fun `completion lines report a locally truncated stream`() {
        val local = completionLines("abc", true, TerminalRunner.Result("abc", 0, ""))
        assertTrue(local.any { it.text == TRUNCATION_NOTICE && it.kind == TerminalLine.Kind.Info })
        assertTrue(completionLines("abc", false, TerminalRunner.Result("abc", 0, ""))
            .none { it.text == TRUNCATION_NOTICE })
    }

    @Test
    fun `stopped lines keep partial output and mark the stop`() {
        val lines = stoppedLines("partial\n", false)
        assertEquals(
            listOf(
                TerminalLine("partial", TerminalLine.Kind.Output),
                TerminalLine("已停止", TerminalLine.Kind.Info),
            ),
            lines,
        )
        assertTrue(stoppedLines("", true).any { it.text == TRUNCATION_NOTICE })
    }

    @Test
    fun `unavailable reason prefers the engine issue`() {
        assertEquals("尚未安装", unavailableReason("尚未安装"))
        assertEquals(ENGINE_MISSING, unavailableReason(null))
    }
}
