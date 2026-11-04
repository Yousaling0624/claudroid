package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotRunnerTest {

    private val proot = File("/data/app/lib/arm64/libproot.so")
    private val loader = File("/data/app/lib/arm64/libproot-loader.so")
    private val jail = File("/data/user/0/app.claudroid/files/engine/jail")
    private val workspace = File("/data/user/0/app.claudroid/files/workspace")
    private val home = File("/data/user/0/app.claudroid/files/home")
    private val tmp = File("/data/user/0/app.claudroid/cache/proot-tmp")

    private fun spec(hasBash: Boolean = true) = ProotRunner.build(
        proot = proot,
        loader = loader,
        jail = jail,
        workspace = workspace,
        home = home,
        arch = "aarch64",
        version = "2.1.0",
        hasBash = hasBash,
        args = listOf("-p", "hello", "--model", "deepseek-chat"),
        baseEnv = mapOf("PATH" to "/usr/bin:/bin", "ANTHROPIC_BASE_URL" to "http://127.0.0.1:1234"),
        binds = listOf(workspace.absolutePath to "/work", home.absolutePath to "/root"),
        tmpDir = tmp,
    )

    @Test
    fun `builds a proot command that enters the jail and execs the cli`() {
        val argv = spec().argv
        assertEquals(proot.absolutePath, argv[0])
        assertTrue(argv.containsAll(listOf("-0", "-r", jail.absolutePath, "-w", "/work")))
        assertTrue(argv.contains("--link2symlink"))
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("/lib/ld-musl-aarch64.so.1"))
        assertTrue(argv.contains("/opt/claude/2.1.0/claude"))
        assertEquals(listOf("-p", "hello", "--model", "deepseek-chat"), argv.takeLast(4))
        // binds are one argv token each, never re-quoted into a shell string
        assertTrue(argv.contains("${workspace.absolutePath}:/work"))
        assertTrue(argv.contains("${home.absolutePath}:/root"))
    }

    @Test
    fun `sets the environment proot and the guest need`() {
        val env = spec().env
        assertEquals("/root", env["HOME"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("/bin/bash", env["SHELL"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals(loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(tmp.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals(proot.parentFile!!.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("http://127.0.0.1:1234", env["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun `falls back to sh when bash was not provisioned`() {
        assertEquals("/bin/sh", spec(hasBash = false).env["SHELL"])
    }
}
