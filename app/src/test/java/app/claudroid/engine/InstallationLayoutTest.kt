package app.claudroid.engine

import app.claudroid.core.Installation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallationLayoutTest {

    private val root = Files.createTempDirectory("claudroid-jail").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun installation(arch: String, rootfsVersion: String = "13") = Installation(
        version = "2.1.0",
        jailPath = root.absolutePath,
        arch = arch,
        rootMode = false,
        rootfsVersion = rootfsVersion,
        rootfsSha256 = "sha256:" + "a".repeat(64),
    )

    @Test
    fun `an alpine era install is not debian`() {
        // Old install.json has no rootfsVersion. Even if a stray marker exists, the install
        // must be treated as unhealthy so it gets rebuilt as Debian.
        File(root, "etc").mkdirs()
        File(root, "etc/debian_version").writeText("13.0\n")

        assertFalse(installation("aarch64", rootfsVersion = "").isDebian())
    }

    @Test
    fun `a pinned rootfs without the debian marker is not healthy`() {
        assertFalse(installation("aarch64").isDebian())
    }

    @Test
    fun `debian version plus the pinned rootfs marks an install as debian`() {
        assertFalse(installation("aarch64").isDebian())
        File(root, "etc").mkdirs()
        File(root, "etc/debian_version").writeText("13.0\n")

        assertTrue(installation("aarch64").isDebian())
    }

    @Test
    fun `arm64 loader, bash and cli paths live inside the jail`() {
        val i = installation("aarch64")
        assertEquals(File(root, "lib/ld-linux-aarch64.so.1"), i.loader())
        assertEquals(File(root, "bin/bash"), i.bash())
        assertEquals(File(root, "opt/claude/2.1.0/claude"), i.binary())
        assertEquals("aarch64-linux-gnu", i.multiarch())
        assertEquals(
            listOf("usr/lib/aarch64-linux-gnu", "usr/lib", "lib")
                .joinToString(":") { File(root, it).absolutePath },
            i.libraryPath(),
        )
        // Every host-side library entry must be jail-absolute; a bare "lib" would make the
        // loader fall back to the Android host's libraries. (Split on ':' is not usable on
        // a Windows test host, where every absolute path starts with a drive colon.)
        listOf("usr/lib/aarch64-linux-gnu", "usr/lib", "lib").forEach { rel ->
            assertTrue(
                "missing jail path $rel in ${i.libraryPath()}",
                i.libraryPath().contains(File(root, rel).absolutePath),
            )
        }
    }

    @Test
    fun `x86_64 uses the lib64 loader and multiarch directory`() {
        val i = installation("x86_64")
        assertEquals(File(root, "lib64/ld-linux-x86-64.so.2"), i.loader())
        assertEquals("x86_64-linux-gnu", i.multiarch())
        assertEquals(
            listOf("usr/lib/x86_64-linux-gnu", "usr/lib", "lib")
                .joinToString(":") { File(root, it).absolutePath },
            i.libraryPath(),
        )
    }
}
