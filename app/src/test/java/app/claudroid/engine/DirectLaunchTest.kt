package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DirectLaunchTest {

    @Test
    fun `loader options disable the host cache and point at jail libraries`() {
        val path = "/data/user/0/app/files/engine/jail/usr/lib/aarch64-linux-gnu:" +
            "/data/user/0/app/files/engine/jail/usr/lib:" +
            "/data/user/0/app/files/engine/jail/lib"
        val options = ClaudeEngine.loaderOptions(path)
        assertEquals(listOf("--inhibit-cache", "--library-path", path), options)
        // Without --inhibit-cache the glibc loader consults the Android host cache and
        // fails before ever looking at the jail-internal library path.
        assertTrue(options.contains("--inhibit-cache"))
    }

    @Test
    fun `direct argv prepends the loader and keeps cli args verbatim`() {
        val loader = File("/data/user/0/app/files/engine/jail/lib/ld-linux-aarch64.so.1")
        val libraryPath = "/jail/usr/lib/aarch64-linux-gnu:/jail/usr/lib:/jail/lib"
        val binary = File("/data/user/0/app/files/engine/jail/opt/claude/2.1.0/claude")
        val args = listOf("-p", "it's a prompt", "--resume", "abc")

        assertEquals(
            listOf(
                loader.absolutePath,
                "--inhibit-cache",
                "--library-path",
                libraryPath,
                binary.absolutePath,
                "-p",
                "it's a prompt",
                "--resume",
                "abc",
            ),
            ClaudeEngine.directArgv(loader, libraryPath, binary, args),
        )
    }

    @Test
    fun `a launch plan labels the root proot fallback`() {
        val installation = app.claudroid.core.Installation(
            version = "2.1.0",
            jailPath = "/data/adb/claudroid",
            arch = "aarch64",
            rootMode = true,
        )
        assertEquals(
            "PRoot（root）",
            ClaudeEngine.LaunchPlan(installation, useRoot = true, useChroot = false, prootViaRoot = true).label,
        )
    }
}
