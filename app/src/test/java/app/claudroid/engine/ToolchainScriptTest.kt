package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainScriptTest {

    private val script = Toolchain.toolsScript("2.1.0")

    @Test
    fun `provisioning installs every tool the agent needs in one apt transaction`() {
        assertTrue(script.contains("apt-get -q --error-on=any -o Acquire::Retries=3"))
        val installLine = script.lines().first { it.contains("--no-install-recommends") }
        listOf(
            "bash", "git", "grep", "coreutils", "sed", "mawk", "tar", "gzip",
            "xz-utils", "curl", "ca-certificates", "less",
            "procps", "psmisc", "iproute2", "iputils-ping", "whiptail", "nano",
            "file", "wget", "unzip", "zip", "ncurses-term",
        ).forEach { pkg ->
            assertTrue("missing package $pkg in $installLine", installLine.contains(" $pkg"))
        }
        // Commands checked afterwards use the Debian command name, not the package name.
        listOf(
            "bash", "git", "grep", "sed", "awk", "tar", "gzip", "xz", "curl", "less",
            "top", "ps", "free", "pgrep", "pkill", "killall", "ip", "ping",
            "whiptail", "nano", "file", "wget", "unzip", "zip",
        ).forEach { tool ->
            assertTrue("missing tool $tool", script.contains("for t in ") && script.contains(" $tool"))
        }
    }

    @Test
    fun `apt update falls back to the official sources when the mirror is unreachable`() {
        // apt-get update returns 0 even when every source failed unless asked otherwise,
        // so the fallback decision below depends on this flag being present.
        assertEquals(2, Regex("--error-on=any").findAll(script).count())
        assertTrue(script.contains("echo APT_MIRROR_FALLBACK"))
        assertTrue(
            script.contains(
                "printf '%s' ${TurnScripts.quote(Toolchain.officialAptSources())} > " +
                    "/etc/apt/sources.list.d/debian.sources",
            )
        )
        // The mirror retries run first, then the rewrite, then the official retries; a
        // failure of both sets still maps to the stable token and exit code.
        val mirrorRetryAt = script.indexOf("APT_UPDATE_RETRY \$i")
        val fallbackAt = script.indexOf("echo APT_MIRROR_FALLBACK")
        val officialRetryAt = script.indexOf("APT_UPDATE_RETRY_OFFICIAL")
        val updateFailAt = script.indexOf("echo APT_UPDATE_FAIL")
        assertTrue(mirrorRetryAt in 0 until fallbackAt)
        assertTrue(fallbackAt < officialRetryAt)
        assertTrue(officialRetryAt < updateFailAt)
        assertTrue(script.contains("exit 21"))
    }

    @Test
    fun `apt runs non interactively with retries and a bounded dpkg lock wait`() {
        assertTrue(script.contains("export DEBIAN_FRONTEND=noninteractive"))
        assertTrue(script.contains("for i in 1 2 3; do"))
        assertTrue(script.contains("Acquire::http::Timeout=30"))
        assertTrue(script.contains("Acquire::https::Timeout=30"))
        assertTrue(script.contains("DPkg::Lock::Timeout=60"))
        assertTrue(script.contains("dpkg --configure -a"))
    }

    @Test
    fun `missing tools are detected before and after apt`() {
        // First scan decides whether apt is needed at all (idempotent reinstall).
        assertTrue(script.contains("APT_NEED:\$missing"))
        // Second scan is the real gate: "apt said ok" must never be mistaken for installed.
        assertTrue(script.contains("TOOLS_MISSING:\$missing"))
        assertEquals(2, Regex("""for t in bash git grep""").findAll(script).count())
    }

    @Test
    fun `debian and cli markers gate success`() {
        assertTrue(script.contains("test -f /etc/debian_version"))
        assertTrue(script.contains("NOT_DEBIAN"))
        assertTrue(script.contains("test -x /opt/claude/2.1.0/claude"))
        assertTrue(script.contains("CLAUDE_MISSING"))
        assertTrue(script.contains("/opt/claude/2.1.0/claude --version"))
        assertTrue(script.trimEnd().endsWith("echo TOOLS_OK\necho CLAUDE_OK"))
    }

    @Test
    fun `failure tokens are stable and have distinct exit codes`() {
        assertTrue(script.contains("exit 21")) // APT_UPDATE_FAIL
        assertTrue(script.contains("exit 22")) // APT_INSTALL_FAIL
        assertTrue(script.contains("exit 23")) // TOOLS_MISSING
        assertTrue(script.contains("exit 24")) // NOT_DEBIAN
        assertTrue(script.contains("exit 25")) // CLAUDE_MISSING
    }

    @Test
    fun `obvious apt leftovers are purged from the container`() {
        assertTrue(script.contains("rm -f /var/cache/apt/archives/*.deb"))
        assertTrue(script.contains("rm -f /var/lib/apt/lists/*.lz4"))
    }

    @Test
    fun `guest env has a debian path and no host paths`() {
        val env = Toolchain.guestEnv()
        assertEquals("/root", env["HOME"])
        assertEquals("/tmp", env["TMPDIR"])
        assertTrue(env.getValue("PATH").startsWith("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin"))
        assertTrue(!env.getValue("PATH").contains("/system"))
    }

    @Test
    fun `apt update failure is actionable`() {
        val text = Toolchain.toolsFailureText(21, "E: Could not resolve\nAPT_UPDATE_FAIL")
        assertTrue(text, text.contains("Debian 软件源"))
        assertTrue(text, text.contains("apt-get update"))
        assertTrue(text, text.contains("exit=21"))
        assertTrue(text, text.contains("Could not resolve"))
    }

    @Test
    fun `apt install failure mentions space and the manual command`() {
        val text = Toolchain.toolsFailureText(22, "E: You don't have enough free space\nAPT_INSTALL_FAIL")
        assertTrue(text, text.contains("磁盘空间"))
        assertTrue(text, text.contains("apt-get install -y bash git grep"))
        assertTrue(text, text.contains("procps psmisc iproute2"))
        assertTrue(text, text.contains("whiptail nano file wget unzip zip ncurses-term"))
        assertTrue(text, text.contains("exit=22"))
    }

    @Test
    fun `a second scan failure names the missing tools`() {
        val text = Toolchain.toolsFailureText(23, "TOOLS_MISSING: git xz")
        assertTrue(text, text.contains("仍缺少工具：git xz"))
        assertTrue(text, text.contains("exit=23"))
    }

    @Test
    fun `missing cli and non debian rootfs are distinguished`() {
        assertTrue(
            Toolchain.toolsFailureText(25, "CLAUDE_MISSING").contains("Claude Code 可执行文件未放入容器"),
        )
        assertTrue(
            Toolchain.toolsFailureText(24, "NOT_DEBIAN").contains("不是有效的 Debian rootfs"),
        )
    }

    @Test
    fun `a timeout is reported as a timeout`() {
        val text = Toolchain.toolsFailureText(-1, "")
        assertTrue(text, text.contains("超时"))
        assertTrue(text, text.contains("exit=-1"))
    }

    @Test
    fun `an unknown failure still carries the tail of the log`() {
        val text = Toolchain.toolsFailureText(7, "line one\nline two")
        assertTrue(text, text.contains("Debian 容器初始化失败"))
        assertTrue(text, text.contains("line one line two"))
    }

    @Test
    fun `the root swap asserts the old tree is gone before moving the jail`() {
        val script = Toolchain.swapScript("'/data/adb/claudroid'", "'/data/adb/claudroid.new'",
            "'/data/adb/claudroid.old'", "2.1.0").trimEnd()
        val assertAt = script.indexOf("test ! -e \"\$O\"")
        val moveOldAt = script.indexOf("if [ -e \"\$J\" ]; then mv \"\$J\" \"\$O\"; fi")
        // A surviving `.old` would swallow the live jail; the assertion must come first.
        assertTrue(assertAt >= 0)
        assertTrue(moveOldAt > assertAt)
        // set -e turns a failed assertion into an abort instead of a nested move.
        assertTrue(script.lines().first() == "set -e")
        assertTrue(script.endsWith("echo SWAP_OK"))
    }
}
