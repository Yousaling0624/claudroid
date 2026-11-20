package app.claudroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Getters: the rule creates the folder after the instance is constructed.
    private val defaultDir: File get() = File(temp.root, "workspace")
    private val rootDir: File get() = File(temp.root, "workspaces")

    private fun store() = WorkspaceStore(defaultDir, rootDir)

    @Test
    fun `starts on the default workspace`() {
        val store = store()
        assertEquals(WorkspaceStore.DEFAULT_ID, store.activeId())
        assertEquals(defaultDir.absolutePath, store.activeDir().absolutePath)
        assertEquals(listOf(WorkspaceStore.DEFAULT), store.all())
        assertTrue(defaultDir.isDirectory)
    }

    @Test
    fun `creates a directory per workspace and can switch to it`() {
        val store = store()
        val info = store.create("项目 A")

        assertTrue(File(rootDir, info.id).isDirectory)
        assertEquals("项目 A", info.name)
        assertTrue(store.select(info.id))
        assertEquals(info.id, store.activeId())
        assertEquals(info.id, store.active().id)
        assertEquals(File(rootDir, info.id).absolutePath, store.activeDir().absolutePath)
        assertEquals(2, store.all().size)
    }

    @Test
    fun `persists spaces and the active choice across reloads`() {
        val first = store()
        val info = first.create("测试")
        assertTrue(first.select(info.id))
        assertTrue(first.rename(info.id, "改名后"))

        val second = store()
        assertEquals(info.id, second.activeId())
        assertEquals("改名后", second.active().name)
        assertEquals(2, second.all().size)
    }

    @Test
    fun `the default workspace cannot be renamed or removed`() {
        val store = store()
        assertFalse(store.rename(WorkspaceStore.DEFAULT_ID, "别的名字"))
        assertFalse(store.remove(WorkspaceStore.DEFAULT_ID))
        assertFalse(store.select("missing"))
        assertEquals(WorkspaceStore.DEFAULT_ID, store.activeId())
    }

    @Test
    fun `removing the active workspace falls back to the default and deletes its files`() {
        val store = store()
        val info = store.create("临时")
        store.select(info.id)
        File(rootDir, info.id).resolve("a.txt").writeText("x")

        assertTrue(store.remove(info.id))
        assertEquals(WorkspaceStore.DEFAULT_ID, store.activeId())
        assertFalse(File(rootDir, info.id).exists())
        assertEquals(1, store.all().size)
    }

    @Test
    fun `index entries with unsafe ids are ignored`() {
        rootDir.mkdirs()
        File(rootDir, "workspaces.json").writeText(
            """
            {"activeId":"../escape","spaces":[
              {"id":"../escape","name":"坏路径"},
              {"id":"ws-ok_1","name":"合法","createdAt":5}
            ]}
            """.trimIndent(),
        )

        val store = store()
        assertEquals(WorkspaceStore.DEFAULT_ID, store.activeId())
        assertEquals(listOf("ws-ok_1"), store.all().drop(1).map { it.id })
        // An unknown or unsafe id must never resolve to a path outside the workspace root.
        assertFalse(store.dir("../escape").absolutePath.contains("escape"))
    }

    @Test
    fun `restored workspaces only fill the missing ids`() {
        val store = store()
        val local = store.create("本机")
        store.select(local.id)

        val added = store.mergeRestored(
            listOf(
                WorkspaceInfo(local.id, "备份里的名字", 1),
                WorkspaceInfo("ws-restored", "恢复的项目", 42),
                WorkspaceInfo("ws-restored", "重复", 43),
                WorkspaceInfo("../escape", "坏路径", 44),
                WorkspaceInfo(WorkspaceStore.DEFAULT_ID, "默认", 45),
            ),
        )

        assertEquals(1, added)
        assertEquals(local.id, store.activeId())
        assertEquals("本机", store.active().name)
        assertEquals(listOf(WorkspaceStore.DEFAULT_ID, local.id, "ws-restored"), store.all().map { it.id })
        assertEquals("恢复的项目", store.all().last().name)
        assertTrue(File(rootDir, "ws-restored").isDirectory)

        // A reload must see the same merged index; nothing was renamed or removed.
        val reloaded = store()
        assertEquals(local.id, reloaded.activeId())
        assertEquals("本机", reloaded.all().first { it.id == local.id }.name)
        assertEquals(3, reloaded.all().size)
    }

    @Test
    fun `display names are trimmed, shortened and never blank`() {
        assertEquals(WorkspaceStore.DEFAULT_NAME, WorkspaceStore.normalizeName("   "))
        assertEquals("项目", WorkspaceStore.normalizeName("  项目  "))
        assertEquals("ab", WorkspaceStore.normalizeName("a\nb"))
        assertEquals(24, WorkspaceStore.normalizeName("x".repeat(80)).length)
    }
}
