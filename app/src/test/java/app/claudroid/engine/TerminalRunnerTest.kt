package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRunnerTest {

    @Test
    fun `cwd accepts relative paths and normalizes separators`() {
        assertEquals("a/b", TerminalRunner.sanitizeCwd("a/b"))
        assertEquals("a/b", TerminalRunner.sanitizeCwd("./a//b/"))
        assertEquals("b", TerminalRunner.sanitizeCwd("a/../b"))
        assertEquals("", TerminalRunner.sanitizeCwd(""))
        assertEquals("", TerminalRunner.sanitizeCwd("."))
        assertEquals("", TerminalRunner.sanitizeCwd("a/.."))
        assertEquals("a", TerminalRunner.sanitizeCwd("  a  "))
    }

    @Test
    fun `cwd rejects absolute paths and escapes`() {
        assertEquals("", TerminalRunner.sanitizeCwd("/etc"))
        assertEquals("", TerminalRunner.sanitizeCwd(".."))
        assertEquals("", TerminalRunner.sanitizeCwd("../x"))
        assertEquals("", TerminalRunner.sanitizeCwd("a/../../b"))
        assertEquals("", TerminalRunner.sanitizeCwd("a\u0000b"))
    }

    @Test
    fun `script changes directory, keeps the command verbatim and writes the status`() {
        val script = TerminalRunner.buildScript(
            cwd = "/work/my dir",
            fallback = "/work",
            command = "printf '%s' \"hello world\"",
            statusFile = "/root/term-1.status",
        )

        assertEquals(
            listOf(
                "cd '/work/my dir' 2>/dev/null || cd '/work'",
                "printf '%s' \"hello world\"",
                "__claudroid_exit=\$?",
                "pwd > '/root/term-1.status'",
                "printf '%s\\n' \"\$__claudroid_exit\" >> '/root/term-1.status'",
            ),
            script.trimEnd('\n').lines(),
        )
    }

    @Test
    fun `script quotes paths containing apostrophes`() {
        val script = TerminalRunner.buildScript(
            cwd = "/work/it's here",
            fallback = "/work",
            command = "pwd",
            statusFile = "/root/term's.status",
        )
        assertTrue(script.startsWith("cd '/work/it'\\''s here' 2>/dev/null"))
        assertTrue(script.contains("pwd > '/root/term'\\''s.status'"))
    }

    @Test
    fun `status parser reads pwd and the last line as the exit code`() {
        assertEquals(TerminalStatus("/work/sub", 0), TerminalRunner.parseStatus("/work/sub\n0\n"))
        assertEquals(TerminalStatus("/work/a b", 7), TerminalRunner.parseStatus("/work/a b\n7"))
        assertEquals(TerminalStatus("/work/sub", 0), TerminalRunner.parseStatus("/work/sub\r\n0\r\n"))
        assertNull(TerminalRunner.parseStatus(""))
        assertNull(TerminalRunner.parseStatus("/work/sub\n"))
        assertNull(TerminalRunner.parseStatus("\n0\n"))
        assertNull(TerminalRunner.parseStatus("/work/sub\nnot-a-number\n"))
    }

    @Test
    fun `pwd is converted back to a workspace relative path`() {
        assertEquals("sub", TerminalRunner.relativizeCwd("/work/sub", "/work"))
        assertEquals("", TerminalRunner.relativizeCwd("/work", "/work"))
        assertEquals("", TerminalRunner.relativizeCwd("/work/", "/work"))
        assertEquals("b", TerminalRunner.relativizeCwd("/work/a/../b", "/work"))
        assertNull(TerminalRunner.relativizeCwd("/etc", "/work"))
        assertNull(TerminalRunner.relativizeCwd("/workspace", "/work"))
        assertNull(TerminalRunner.relativizeCwd("/work/../etc", "/work"))
        assertNull(TerminalRunner.relativizeCwd("/work/../../etc", "/work"))
    }

    @Test
    fun `host pwd is relative to the real workspace`() {
        val workspace = "/data/user/0/app.claudroid/files/workspace"
        assertEquals("a/b", TerminalRunner.relativizeCwd("$workspace/a/b", workspace))
        assertEquals("", TerminalRunner.relativizeCwd(workspace, workspace))
        assertNull(TerminalRunner.relativizeCwd("$workspace-other/a", workspace))
    }

    @Test
    fun `terminal environment has the basics but no model credentials`() {
        val guest = TerminalRunner.baseEnv(
            guest = true,
            home = "/root",
            tmp = "/tmp",
            shell = "/bin/bash",
        )
        assertEquals("/root", guest["HOME"])
        assertEquals("/tmp", guest["TMPDIR"])
        assertEquals("C.UTF-8", guest["LANG"])
        assertEquals("xterm-256color", guest["TERM"])
        assertEquals("/bin/bash", guest["SHELL"])
        assertTrue(guest["PATH"]!!.contains("/usr/bin"))
        assertFalse(guest.keys.any { it.startsWith("ANTHROPIC") })
        assertFalse(guest.keys.any { it.contains("KEY", ignoreCase = true) })

        val direct = TerminalRunner.baseEnv(
            guest = false,
            home = "/data/user/0/app/files/home",
            tmp = "/data/user/0/app/cache",
            shell = "/system/bin/sh",
        )
        assertEquals("/system/bin:/system/xbin", direct["PATH"])
        assertFalse(direct.keys.any { it.startsWith("ANTHROPIC") })
    }

    @Test
    fun `root wrapper records the pid and exports the environment before the body`() {
        val script = TerminalRunner.hostScript(
            env = mapOf("PATH" to "/system/bin", "HOME" to "/data/home"),
            cwd = "/data/workspace",
            fallback = "/data/workspace",
            command = "id",
            statusFile = "/data/home/term.status",
            pidFile = "/data/engine/term.pid",
        )

        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        val pidAt = script.indexOf("echo \$\$ > '/data/engine/term.pid'")
        val exportAt = script.indexOf("export PATH='/system/bin'")
        val cdAt = script.indexOf("cd '/data/workspace' 2>/dev/null || cd '/data/workspace'")
        assertTrue("pid line missing:\n$script", pidAt >= 0)
        assertTrue(pidAt < exportAt)
        assertTrue(exportAt < cdAt)
        assertTrue(script.contains("id\n"))
        assertTrue(script.contains("pwd > '/data/home/term.status'"))
    }

    @Test
    fun `output cap truncates and stops growing`() {
        val output = StringBuilder()
        assertEquals("abc", TerminalRunner.appendCapped(output, "abc", 5))
        // The crossing chunk keeps the part that still fits...
        assertEquals("de", TerminalRunner.appendCapped(output, "def", 5))
        // ...and every later chunk is drained without being retained or emitted.
        assertNull(TerminalRunner.appendCapped(output, "ghi", 5))
        assertEquals("abcde", output.toString())
        assertEquals(5, output.length)

        // Exactly filling the cap is not a truncation by itself.
        val exact = StringBuilder()
        assertEquals("12345", TerminalRunner.appendCapped(exact, "12345", 5))
        assertEquals(5, exact.length)
        assertNull(TerminalRunner.appendCapped(exact, "6", 5))
    }

    @Test
    fun `result defaults to not truncated`() {
        val result = TerminalRunner.Result(output = "", exitCode = 0, cwdRel = "")
        assertFalse(result.truncated)
        assertNull(result.error)
    }
}
