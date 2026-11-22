package app.claudroid.engine

import app.claudroid.core.Installation
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preconditions of `Toolchain.ensure(cliOnly = true)`.
 *
 * A CLI-only update replaces the binary inside the existing jail. It must not be the
 * path that silently "repairs" a missing install, a mode switch or a stale rootfs: the
 * old CLI is pruned once the new one verifies, so refusing up front and pointing at
 * the full reinstall is the only safe behaviour. The messages are user-facing, hence
 * the substring checks instead of exact sentences.
 */
class ToolchainCliOnlyTest {

    private val spec = RootfsSpec("library/debian:13", "sha256:x", 1L, "13")
    private val prootInstall = Installation(
        version = "2.1.0",
        jailPath = "/tmp/jail",
        arch = "arm64",
        rootMode = false,
        rootfsVersion = "13",
        rootfsSha256 = "sha256:x",
    )

    @Test
    fun `no install must be rejected before any download`() {
        val message = Toolchain.cliOnlyBlocker(null, useRoot = false, spec = spec, healthy = false)
        assertTrue(message!!.contains("尚未安装"))
        assertTrue(message.contains("安装 Claude Code"))
    }

    @Test
    fun `mode mismatch is described in both directions`() {
        val installedRoot = prootInstall.copy(rootMode = true)
        val rootToProot = Toolchain.cliOnlyBlocker(installedRoot, useRoot = false, spec = spec, healthy = false)
        assertTrue(rootToProot!!.contains("Root"))
        assertTrue(rootToProot.contains("PRoot"))
        assertTrue(rootToProot.contains("重装 / 修复容器"))

        val prootToRoot = Toolchain.cliOnlyBlocker(prootInstall, useRoot = true, spec = spec, healthy = false)
        assertTrue(prootToRoot!!.contains("PRoot"))
        assertTrue(prootToRoot.contains("Root"))
        assertTrue(prootToRoot.contains("重装 / 修复容器"))
    }

    @Test
    fun `changed rootfs version requires the full reinstall`() {
        val stale = prootInstall.copy(rootfsVersion = "12")
        val message = Toolchain.cliOnlyBlocker(stale, useRoot = false, spec = spec, healthy = true)
        assertTrue(message!!.contains("容器运行时"))
        assertTrue(message.contains("13"))
        assertTrue(message.contains("重装 / 修复容器"))
    }

    @Test
    fun `changed rootfs digest requires the full reinstall`() {
        val stale = prootInstall.copy(rootfsSha256 = "sha256:y")
        val message = Toolchain.cliOnlyBlocker(stale, useRoot = false, spec = spec, healthy = true)
        assertTrue(message!!.contains("容器运行时"))
        assertTrue(message.contains("重装 / 修复容器"))
    }

    @Test
    fun `unhealthy container is rejected instead of receiving a new CLI`() {
        val message = Toolchain.cliOnlyBlocker(prootInstall, useRoot = false, spec = spec, healthy = false)
        assertTrue(message!!.contains("容器状态异常"))
        assertTrue(message.contains("重装 / 修复容器"))
    }

    @Test
    fun `healthy matching install may update the CLI`() {
        assertNull(Toolchain.cliOnlyBlocker(prootInstall, useRoot = false, spec = spec, healthy = true))
    }
}
