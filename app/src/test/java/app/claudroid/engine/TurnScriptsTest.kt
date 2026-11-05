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
    fun `root direct script records the pid before execing the cli`() {
        val script = TurnScripts.rootDirect(
            env = mapOf("ANTHROPIC_MODEL" to "a'b"),
            cwd = "/data/user/0/app/files/workspace",
            loader = "/jail/lib/ld-musl-aarch64.so.1",
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
            loaderName = "/lib/ld-musl-aarch64.so.1",
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
}
