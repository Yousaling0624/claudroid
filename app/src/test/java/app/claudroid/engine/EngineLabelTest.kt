package app.claudroid.engine

import app.claudroid.core.Installation
import app.claudroid.data.EngineMode
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineLabelTest {

    private fun label(
        mode: EngineMode,
        rootMode: Boolean = false,
        rootGranted: Boolean = false,
        chroot: Boolean = false,
        proot: Boolean = true,
    ): String = EngineManager.State(
        rootMode = rootMode,
        rootGranted = rootGranted,
        chrootAvailable = chroot,
        prootAvailable = proot,
    ).engineLabel(mode)

    @Test
    fun `proot mode never claims root or chroot when root is granted`() {
        assertEquals(
            "PRoot（免 root）",
            label(EngineMode.PROOT, rootGranted = true, chroot = true),
        )
    }

    @Test
    fun `proot mode with a root-mode installation asks for a reinstall`() {
        assertEquals(
            "PRoot（root 目录引擎，需重装）",
            label(EngineMode.PROOT, rootMode = true, rootGranted = true, chroot = true),
        )
    }

    @Test
    fun `proot mode reports missing components`() {
        assertEquals("PRoot 组件缺失", label(EngineMode.PROOT, proot = false))
    }

    @Test
    fun `root mode without authorization is explicit`() {
        assertEquals("Root（未授权）", label(EngineMode.ROOT, proot = true))
    }

    @Test
    fun `auto and root use chroot when granted and available`() {
        assertEquals("root + chroot", label(EngineMode.AUTO, rootGranted = true, chroot = true))
        assertEquals("root + chroot", label(EngineMode.ROOT, rootGranted = true, chroot = true))
    }

    @Test
    fun `auto and root fall back to the loader when chroot is missing`() {
        assertEquals("root（加载器直启）", label(EngineMode.AUTO, rootGranted = true))
        assertEquals("root（加载器直启）", label(EngineMode.ROOT, rootGranted = true))
    }

    @Test
    fun `auto without root uses proot when available`() {
        assertEquals("PRoot（免 root）", label(EngineMode.AUTO))
    }

    @Test
    fun `auto with a root-mode installation but no root is explicit`() {
        assertEquals(
            "root 目录中的引擎（当前无 root）",
            label(EngineMode.AUTO, rootMode = true, proot = true),
        )
    }

    @Test
    fun `auto without root or proot falls back to direct execution`() {
        assertEquals("应用目录直启", label(EngineMode.AUTO, proot = false))
    }

    @Test
    fun `shell plan labels match the four launch paths`() {
        val installation = Installation(
            version = "2.1.0",
            jailPath = "/data/user/0/app.claudroid/files/engine/jail",
            arch = "aarch64",
            rootMode = false,
        )
        assertEquals(
            "PRoot（免 root）",
            EngineManager.ShellPlan(installation, useRoot = false, useChroot = false, useProot = true).label,
        )
        assertEquals(
            "root + chroot",
            EngineManager.ShellPlan(installation, useRoot = true, useChroot = true, useProot = false).label,
        )
        assertEquals(
            "root（加载器直启）",
            EngineManager.ShellPlan(installation, useRoot = true, useChroot = false, useProot = false).label,
        )
        assertEquals(
            "应用目录直启",
            EngineManager.ShellPlan(installation, useRoot = false, useChroot = false, useProot = false).label,
        )
    }
}
