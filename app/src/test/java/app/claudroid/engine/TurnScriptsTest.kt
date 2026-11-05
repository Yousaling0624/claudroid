package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnScriptsTest {

    @Test
    fun `quote escapes single quotes for the system shell`() {
        assertEquals("'plain'", TurnScripts.quote("plain"))
        assertEquals("'it'\\''s'", TurnScripts.quote("it's"))
    }

    @Test
    fun `root exec script records the pid and runs an arbitrary argv`() {
        val script = TurnScripts.rootExec(
            env = mapOf("PROOT_NO_SECCOMP" to "1"),
            argv = listOf("/data/app/lib/libproot.so", "-0", "-r", "/jail", "-b", "a b:/work"),
            pidFile = "/data/user/0/app/files/engine/turn-9.pid",
            cwd = "/data/user/0/app/files",
        )
        val pidAt = script.indexOf("echo \$\$ > '/data/user/0/app/files/engine/turn-9.pid'")
        val execAt = script.indexOf("exec ")
        assertTrue("pid line missing:\n$script", pidAt >= 0)
        assertTrue("pid line must come before exec:\n$script", pidAt < execAt)
        assertTrue(script.contains("export PROOT_NO_SECCOMP='1'"))
        assertTrue(script.contains("cd '/data/user/0/app/files' 2>/dev/null"))
        // Nothing is re-split: the bind containing a space stays one argv word.
        assertTrue(script.contains("exec '/data/app/lib/libproot.so' '-0' '-r' '/jail' '-b' 'a b:/work'"))
        assertEquals(1, script.lines().count { it.startsWith("exec ") })
    }

    @Test
    fun `root direct script records the pid before execing the cli`() {
        val script = TurnScripts.rootDirect(
            env = mapOf("ANTHROPIC_MODEL" to "a'b"),
            cwd = "/data/user/0/app/files/workspace",
            binary = "/jail/opt/claude/2.1.0/claude",
            args = listOf("-p", "it's a prompt"),
            pidFile = "/data/user/0/app/files/engine/turn-7.pid",
        )
        val pidAt = script.indexOf("echo \$\$ > '/data/user/0/app/files/engine/turn-7.pid'")
        val execAt = script.indexOf("exec ")
        assertTrue("pid line missing:\n$script", pidAt >= 0)
        assertTrue("pid line must come before exec:\n$script", pidAt < execAt)
        assertTrue(script.contains("export ANTHROPIC_MODEL='a'\\''b'"))
        assertTrue(script.contains("'-p' 'it'\\''s a prompt'"))
        assertEquals(1, script.lines().count { it.startsWith("exec ") })
    }

    @Test
    fun `chroot script records the pid before mounting and execing`() {
        val script = TurnScripts.chroot(
            env = mapOf("PATH" to "/usr/bin:/bin"),
            jail = "/data/adb/claudroid",
            workspace = "/data/user/0/app/files/workspace",
            home = "/data/user/0/app/files/home",
            chrootTool = "/data/adb/magisk/busybox",
            binary = "/opt/claude/2.1.0/claude",
            args = listOf("-p", "hello"),
            hasBash = true,
            pidFile = "/data/user/0/app/files/engine/turn-8.pid",
        )
        val pidAt = script.indexOf("echo \$\$ > '/data/user/0/app/files/engine/turn-8.pid'")
        assertTrue("pid line missing:\n$script", pidAt >= 0)
        assertTrue(pidAt < script.indexOf("bind_mount()"))
        assertTrue(pidAt < script.indexOf("exec "))
        assertTrue(script.contains("bind_mount '/data/user/0/app/files/home' \"\$R/root\""))
        assertTrue(script.contains("export SHELL='/bin/bash'"))
        assertTrue(script.contains("exec '/data/adb/magisk/busybox' chroot \"\$R\""))
    }

    @Test
    fun `turn scripts skip environment keys that are not identifiers`() {
        val script = TurnScripts.rootExec(
            env = mapOf(
                "OK_KEY" to "1",
                "FOO;touch /data/pwned" to "2",
                "1BAD" to "3",
            ),
            argv = listOf("/bin/true"),
            pidFile = null,
        )
        assertTrue(script.contains("export OK_KEY='1'"))
        assertTrue("injected command leaked:\n$script", !script.contains("touch"))
        assertTrue("invalid key leaked:\n$script", !script.contains("1BAD"))
    }

    @Test
    fun `chroot shell mounts the workspace and runs the guest script`() {
        val guestScript = TerminalRunner.buildScript(
            cwd = "/work/my dir",
            fallback = "/work",
            command = "pwd",
            statusFile = "/root/term.status",
        )
        val script = TurnScripts.chrootShell(
            env = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/root", "TMPDIR" to "/tmp"),
            jail = "/data/adb/claudroid",
            workspace = "/data/user/0/app/files/workspace",
            home = "/data/user/0/app/files/home",
            chrootTool = "/data/adb/magisk/busybox",
            guestScript = guestScript,
            pidFile = "/data/user/0/app/files/engine/term.pid",
        )

        assertTrue(script.contains("echo \$\$ > '/data/user/0/app/files/engine/term.pid'"))
        assertTrue(script.contains("bind_mount '/data/user/0/app/files/workspace' \"\$R/work\""))
        assertTrue(script.contains("bind_mount '/data/user/0/app/files/home' \"\$R/root\""))
        // The guest script is one shell word; its own single quotes are escaped.
        assertTrue(
            script.contains(
                "exec '/data/adb/magisk/busybox' chroot \"\$R\" /bin/sh -c " +
                    TurnScripts.quote(guestScript)
            )
        )
    }
}
