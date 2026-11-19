package app.claudroid.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ClaudeWorkDirTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sanitize normalizes separators and drops empty or dot segments`() {
        assertEquals("projects/app", ClaudeWorkDir.sanitize("projects/app"))
        assertEquals("projects/app", ClaudeWorkDir.sanitize(" projects//./app/ "))
        assertEquals("projects/app", ClaudeWorkDir.sanitize("projects\\app\\"))
    }

    @Test
    fun `sanitize treats empty and dot as the workspace root`() {
        assertEquals("", ClaudeWorkDir.sanitize(""))
        assertEquals("", ClaudeWorkDir.sanitize("   "))
        assertEquals("", ClaudeWorkDir.sanitize("."))
        assertEquals("", ClaudeWorkDir.sanitize("./"))
    }

    @Test
    fun `sanitize rejects parent segments`() {
        assertEquals("", ClaudeWorkDir.sanitize(".."))
        assertEquals("", ClaudeWorkDir.sanitize("../outside"))
        assertEquals("", ClaudeWorkDir.sanitize("a/../b"))
        assertEquals("", ClaudeWorkDir.sanitize("a\\..\\b"))
        // A directory literally named "..." is not a parent segment.
        assertEquals(".../x", ClaudeWorkDir.sanitize(".../x"))
    }

    @Test
    fun `sanitize rejects absolute paths`() {
        assertEquals("", ClaudeWorkDir.sanitize("/work/project"))
        assertEquals("", ClaudeWorkDir.sanitize("//server/share"))
        assertEquals("", ClaudeWorkDir.sanitize("C:\\project"))
    }

    @Test
    fun `resolve accepts a nested directory and maps it under work`() {
        val workspace = temp.newFolder("workspace")
        val dir = File(workspace, "teams/app").apply { mkdirs() }

        val resolved = ClaudeWorkDir.resolve(workspace, "teams/app")

        assertEquals(dir.canonicalFile, resolved)
        assertEquals("/work/teams/app", ClaudeWorkDir.guestPath(workspace, resolved))
    }

    @Test
    fun `resolve falls back to the workspace root for empty input`() {
        val workspace = temp.newFolder("workspace")

        val resolved = ClaudeWorkDir.resolve(workspace, "")

        assertEquals(workspace, resolved)
        assertEquals("/work", ClaudeWorkDir.guestPath(workspace, resolved))
    }

    @Test
    fun `resolve falls back when the directory is missing or is a file`() {
        val workspace = temp.newFolder("workspace")
        File(workspace, "notes.txt").writeText("x")

        assertEquals(
            workspace.canonicalFile,
            ClaudeWorkDir.resolve(workspace, "missing/dir"),
        )
        assertEquals(
            workspace.canonicalFile,
            ClaudeWorkDir.resolve(workspace, "notes.txt"),
        )
        assertEquals(
            "/work",
            ClaudeWorkDir.guestPath(workspace, ClaudeWorkDir.resolve(workspace, "missing")),
        )
    }

    @Test
    fun `resolve refuses a symlink that escapes the workspace`() {
        val workspace = temp.newFolder("workspace")
        val outside = temp.newFolder("outside")
        val link = File(workspace, "escape")
        // Windows needs Developer Mode / SeCreateSymbolicLinkPrivilege.
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("platform cannot create symlinks", created)

        val resolved = ClaudeWorkDir.resolve(workspace, "escape")

        assertEquals(workspace.canonicalFile, resolved)
        assertEquals("/work", ClaudeWorkDir.guestPath(workspace, resolved))
    }

    @Test
    fun `resolve follows a symlink that stays inside the workspace`() {
        val workspace = temp.newFolder("workspace")
        val real = File(workspace, "real").apply { mkdirs() }
        val link = File(workspace, "alias")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), Paths.get("real"))
        }.isSuccess
        assumeTrue("platform cannot create symlinks", created)

        val resolved = ClaudeWorkDir.resolve(workspace, "alias")

        assertEquals(real.canonicalFile, resolved)
        // The guest path is derived from the canonical target, which is what actually
        // exists inside the /work bind.
        assertEquals("/work/real", ClaudeWorkDir.guestPath(workspace, resolved))
    }

    @Test
    fun `guest path stays at the workspace root for a directory outside it`() {
        val workspace = temp.newFolder("workspace")
        val outside = temp.newFolder("outside")

        assertEquals("/work", ClaudeWorkDir.guestPath(workspace, outside))
    }
}
