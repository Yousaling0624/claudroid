package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionTest {

    @Test
    fun `outer script probes the pty and records the exit code`() {
        val script = TerminalSession.outerScript(
            guestCwd = "/work/my dir",
            shell = "/bin/bash",
            innerFile = "/root/term-1.inner",
            exitFile = "/root/term-1.exit",
        )

        assertEquals(
            listOf(
                "cd '/work/my dir' 2>/dev/null || cd '/work'",
                "if command -v script >/dev/null 2>&1 && " +
                    "script -q -c true /dev/null >/dev/null 2>&1; then",
                "  script -q -f -e -c '/bin/bash /root/term-1.inner' /dev/null",
                "else",
                "  printf '%s\\n' " +
                    "'[claudroid] 容器内无法创建 PTY，已回退为普通交互 shell" +
                    "（vim/top 等全屏程序不可用）。' >&2",
                "  '/bin/bash' '/root/term-1.inner'",
                "fi",
                "__claudroid_exit=\$?",
                "printf '%s\\n' \"\$__claudroid_exit\" > '/root/term-1.exit'",
            ),
            script.trimEnd('\n').lines(),
        )
    }

    @Test
    fun `outer script only starts script after a successful devpts probe`() {
        val script = TerminalSession.outerScript(
            guestCwd = "/work",
            shell = "/bin/sh",
            innerFile = "/root/a.inner",
            exitFile = "/root/a.exit",
        )

        // `script` existing is not enough: without a usable devpts the launch dies with
        // "failed to create pseudo-terminal", so the probe runs before the real start.
        assertTrue(
            script.contains(
                "if command -v script >/dev/null 2>&1 && " +
                    "script -q -c true /dev/null >/dev/null 2>&1; then",
            ),
        )
        // The probe must not use -e (busybox compatibility); the real start still does.
        assertTrue(!script.contains("script -q -e -c true"))
        assertTrue(script.contains("  script -q -f -e -c '/bin/sh /root/a.inner' /dev/null"))
        // The fallback names itself and runs the shell directly, without a PTY.
        assertTrue(
            script.contains(
                "printf '%s\\n' " +
                    TurnScripts.quote(TerminalSession.PTY_FALLBACK_NOTICE) + " >&2",
            ),
        )
        assertTrue(script.contains("  '/bin/sh' '/root/a.inner'"))
    }

    @Test
    fun `outer script keeps both the pty and the plain fallback branch`() {
        val script = TerminalSession.outerScript(
            guestCwd = "/work",
            shell = "/bin/sh",
            innerFile = "/root/a.inner",
            exitFile = "/root/a.exit",
        )
        // `script(1)` exists in the Debian rootfs, but the branch must still work when
        // the devpts probe fails: the fallback runs the shell directly (no PTY).
        assertTrue(script.contains("  script -q -f -e -c '/bin/sh /root/a.inner' /dev/null"))
        assertTrue(script.contains("  '/bin/sh' '/root/a.inner'"))
        // The exit file is written after PTY teardown so a natural `exit 3` is visible.
        val exitAt = script.indexOf("__claudroid_exit=\$?")
        assertTrue(exitAt >= 0)
        assertTrue(exitAt < script.indexOf("> '/root/a.exit'"))
    }

    @Test
    fun `outer script quotes paths with spaces and apostrophes`() {
        val script = TerminalSession.outerScript(
            guestCwd = "/work/it's here",
            shell = "/bin/sh",
            innerFile = "/root/it's file.inner",
            exitFile = "/root/it's.exit",
        )
        assertTrue(script.startsWith("cd '/work/it'\\''s here' 2>/dev/null || cd '/work'"))
        // The whole `shell + file` pair is one quoted word when it goes through sh -c.
        assertTrue(script.contains("-c '/bin/sh /root/it'\\''s file.inner'"))
        assertTrue(script.contains("  '/bin/sh' '/root/it'\\''s file.inner'"))
        assertTrue(script.contains("> '/root/it'\\''s.exit'"))
    }

    @Test
    fun `inner script reports pid and tty before execing the interactive shell`() {
        val script = TerminalSession.innerScript(
            pidFile = "/root/t.pid",
            ttyFile = "/root/t.tty",
            rows = 24,
            cols = 80,
            shell = "/bin/bash",
        )

        assertEquals(
            listOf(
                "echo \$\$ > '/root/t.pid'",
                "if [ -t 0 ]; then",
                "  tty > '/root/t.tty' 2>/dev/null",
                "  stty rows 24 cols 80 2>/dev/null",
                "fi",
                "exec '/bin/bash' -i",
            ),
            script.trimEnd('\n').lines(),
        )
    }

    @Test
    fun `inner script probes tty only when stdin is a terminal`() {
        val script = TerminalSession.innerScript(
            pidFile = "/root/t.pid",
            ttyFile = "/root/t.tty",
            rows = 24,
            cols = 80,
            shell = "/bin/sh",
        )

        // On the no-PTY fallback the shell reads from a pipe; tty/stty would fail and
        // print errors into the terminal stream, so they must stay behind the guard.
        val guardAt = script.indexOf("if [ -t 0 ]; then")
        val ttyAt = script.indexOf("tty >")
        val sttyAt = script.indexOf("stty rows")
        val endAt = script.indexOf("fi")
        assertTrue("guard missing:\n$script", guardAt >= 0)
        assertTrue("tty probe must be inside the guard:\n$script", guardAt < ttyAt)
        assertTrue("stty must be inside the guard:\n$script", guardAt < sttyAt)
        assertTrue("guard must close before exec:\n$script", endAt > sttyAt)
        assertTrue(endAt < script.indexOf("exec '/bin/sh' -i"))
    }

    @Test
    fun `inner script falls back to sh and clamps the window size`() {
        val script = TerminalSession.innerScript(
            pidFile = "/root/t.pid",
            ttyFile = "/root/t.tty",
            rows = 1,
            cols = 10_000,
            shell = "/bin/sh",
        )
        assertTrue(script.contains("stty rows 5 cols 500 2>/dev/null"))
        assertTrue(script.trimEnd('\n').endsWith("exec '/bin/sh' -i"))
    }

    @Test
    fun `rows and cols are clamped to useful bounds`() {
        assertEquals(TerminalSession.MIN_ROWS, TerminalSession.clampRows(0))
        assertEquals(TerminalSession.MIN_ROWS, TerminalSession.clampRows(-20))
        assertEquals(24, TerminalSession.clampRows(24))
        assertEquals(TerminalSession.MAX_ROWS, TerminalSession.clampRows(301))
        assertEquals(TerminalSession.MAX_ROWS, TerminalSession.clampRows(Int.MAX_VALUE))

        assertEquals(TerminalSession.MIN_COLS, TerminalSession.clampCols(1))
        assertEquals(80, TerminalSession.clampCols(80))
        assertEquals(TerminalSession.MAX_COLS, TerminalSession.clampCols(500))
        assertEquals(TerminalSession.MAX_COLS, TerminalSession.clampCols(10_000))
    }

    @Test
    fun `exit status parser accepts a shell code and rejects garbage`() {
        assertEquals(0, TerminalSession.parseExitCode("0\n"))
        assertEquals(7, TerminalSession.parseExitCode(" 7 \n"))
        assertEquals(130, TerminalSession.parseExitCode("130"))
        assertEquals(255, TerminalSession.parseExitCode("255"))
        assertNull(TerminalSession.parseExitCode(null))
        assertNull(TerminalSession.parseExitCode(""))
        assertNull(TerminalSession.parseExitCode(" \n"))
        assertNull(TerminalSession.parseExitCode("not-a-number"))
        assertNull(TerminalSession.parseExitCode("-1"))
        assertNull(TerminalSession.parseExitCode("256"))
    }

    @Test
    fun `pid parser only accepts a plausible process id`() {
        assertEquals(42, TerminalSession.parsePid("42\n"))
        assertEquals(42, TerminalSession.parsePid(" 42 \n"))
        assertNull(TerminalSession.parsePid(null))
        assertNull(TerminalSession.parsePid(""))
        assertNull(TerminalSession.parsePid("abc"))
        assertNull(TerminalSession.parsePid("1"))
        assertNull(TerminalSession.parsePid("-42"))
    }

    @Test
    fun `tty parser only trusts a device path`() {
        assertEquals("/dev/pts/3", TerminalSession.parseTty("/dev/pts/3\n"))
        assertEquals("/dev/pts/3", TerminalSession.parseTty("\n/dev/pts/3\n"))
        assertNull(TerminalSession.parseTty(null))
        assertNull(TerminalSession.parseTty(""))
        assertNull(TerminalSession.parseTty("not a tty"))
        assertNull(TerminalSession.parseTty("/root/tty"))
    }

    @Test
    fun `input larger than 64 KiB is truncated without splitting a character`() {
        val ascii = "a".repeat(70 * 1024)
        assertEquals(TerminalSession.MAX_INPUT_BYTES, TerminalSession.clampInput(ascii).length)

        // 65536 bytes is not divisible by 3, so a naive byte cut would split a CJK
        // character and produce U+FFFD inside the shell.
        val cjk = "你".repeat(70 * 1024)
        val clipped = TerminalSession.clampInput(cjk)
        assertEquals(65535, clipped.toByteArray(Charsets.UTF_8).size)
        assertTrue(clipped.all { it == '你' })

        val small = "ls -la\n"
        assertEquals(small, TerminalSession.clampInput(small))
    }

    @Test
    fun `resize script targets the recorded tty`() {
        assertEquals(
            "exec /usr/bin/stty -F '/dev/pts/7' rows 40 cols 120",
            TerminalSession.resizeScript("/dev/pts/7", 40, 120),
        )
        assertEquals(
            "exec /usr/bin/stty -F '/dev/pts/7' rows 5 cols 500",
            TerminalSession.resizeScript("/dev/pts/7", 0, 9000),
        )
    }
}
