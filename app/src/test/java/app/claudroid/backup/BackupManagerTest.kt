package app.claudroid.backup

import app.claudroid.core.WorkspaceStore
import app.claudroid.data.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The single-task slot, cancel and status transitions (docs/BACKUP.md §6). */
class BackupManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sessions get() = File(temp.root, "sessions")
    private val workspace get() = File(temp.root, "workspace")
    private val spaces get() = File(temp.root, "workspaces")
    private val cache get() = File(temp.root, "cache")

    private var scope = newScope()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager(): BackupManager {
        listOf(sessions, workspace, spaces, cache).forEach { it.mkdirs() }
        return BackupManager(
            archive = BackupArchive(sessions, workspace, spaces),
            store = BackupStore(File(temp.root, "backup")),
            workspaces = WorkspaceStore(workspace, spaces),
            settings = { AppSettings() },
            applySettings = {},
            appVersion = "test",
            versionCode = 1,
            cacheDir = cache,
            scope = scope,
            io = Dispatchers.Default,
        )
    }

    @Test
    fun `export packs once, reports bytes and removes the cache copy`() = runBlocking {
        val manager = manager()
        File(workspace, "hello.txt").writeText("hello")
        var existedDuringWrite = false
        var copiedBytes = 0L
        val destination = File(temp.root, "export.zip")

        val outcome = manager.export { file ->
            existedDuringWrite = file.isFile
            copiedBytes = file.length()
            file.copyTo(destination, overwrite = true)
        }

        assertFalse(outcome.cancelled)
        assertEquals(copiedBytes, outcome.bytes)
        assertTrue(existedDuringWrite)
        assertTrue(destination.isFile)
        assertFalse("the cache archive must not survive the export", File(cache, "claudroid-export.zip").exists())

        val status = manager.status.value
        assertEquals(BackupPhase.Idle, status.phase)
        assertEquals("export", status.lastAction)
        assertEquals(outcome.bytes, status.lastBytes)
        assertTrue(status.lastMessage, status.lastMessage.contains("已导出"))
        assertTrue(status.lastOk)
        assertTrue(status.lastAt > 0)
    }

    @Test
    fun `a second task is rejected while the first is running`() = runBlocking {
        val manager = manager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            manager.export {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue(manager.busy)

        val failure = runCatching { manager.export { } }
        assertEquals("已有备份任务在进行", failure.exceptionOrNull()?.message)
        // The running task is untouched by the rejected caller.
        assertEquals(BackupPhase.Working, manager.status.value.phase)

        release.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertFalse(manager.busy)
    }

    @Test
    fun `cancel stops the worker, returns to idle and answers the caller`() = runBlocking {
        val manager = manager()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Result<ExportOutcome>>()
        val job = launch(Dispatchers.Default) {
            result.complete(
                runCatching {
                    manager.export {
                        entered.complete(Unit)
                        blocked.await()
                    }
                },
            )
        }
        entered.await()
        manager.cancel()

        val failure = withTimeout(5_000) { result.await() }
        assertEquals("已取消", failure.exceptionOrNull()?.message)
        withTimeout(5_000) { job.join() }
        assertFalse(manager.busy)
        val status = manager.status.value
        assertEquals(BackupPhase.Idle, status.phase)
        assertEquals("已取消", status.lastMessage)
        assertNull(status.error)
    }

    @Test
    fun `local mode rejects cloud actions before opening a connection`() = runBlocking {
        val manager = manager()
        val failure = runCatching { manager.run() }

        assertEquals("当前目标是本地导出/导入，请改用本地备份或恢复", failure.exceptionOrNull()?.message)
        val status = manager.status.value
        assertEquals(BackupPhase.Error, status.phase)
        assertFalse(status.lastOk)
        assertEquals("当前目标是本地导出/导入，请改用本地备份或恢复", status.error)
        assertFalse(manager.busy)
    }

    @Test
    fun `test failures keep the previous last backup timestamp`() = runBlocking {
        val manager = manager()
        // Seed a successful result first.
        manager.export { }
        val lastAt = manager.status.value.lastAt
        val lastBytes = manager.status.value.lastBytes

        val failure = runCatching {
            manager.test(BackupConfig(kind = BackupKind.WEBDAV, webdav = WebDavConfig()))
        }
        assertEquals("请填写 WebDAV 备份地址", failure.exceptionOrNull()?.message)
        assertEquals(lastAt, manager.status.value.lastAt)
        assertEquals(lastBytes, manager.status.value.lastBytes)
        assertEquals("test", manager.status.value.action)
        assertFalse(manager.status.value.lastOk)
    }

}
