package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `target.json` / `status.json` persistence (docs/BACKUP.md §4/§6). */
class BackupStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = BackupStore(temp.root)

    @Test
    fun `config round trips every field`() {
        val config = BackupConfig(
            kind = BackupKind.GITHUB,
            webdav = WebDavConfig("https://dav.example.com/a/b.zip", "user", "pass"),
            s3 = S3Config(
                endpoint = "http://127.0.0.1:9000",
                region = "eu-west-1",
                bucket = "bucket",
                accessKey = "AK",
                secretKey = "SK",
                prefix = "p",
                pathStyle = false,
            ),
            github = GithubConfig("owner/name", "dev", "dir/backup.zip", "token"),
        )
        store().saveConfig(config)

        val loaded = BackupStore(temp.root).loadConfig()
        assertEquals(config, loaded)
        // The file is private app storage; the password/token must survive a restart.
        val raw = File(temp.root, BackupStore.CONFIG_NAME).readText()
        assertTrue(raw.contains("\"pass\""))
        assertTrue(raw.contains("\"token\""))
    }

    @Test
    fun `a corrupt or missing config falls back to defaults`() {
        assertEquals(BackupConfig(), store().loadConfig())

        File(temp.root, BackupStore.CONFIG_NAME).writeText("{not json")
        assertEquals(BackupConfig(), store().loadConfig())

        // A partial document is accepted: users save while still filling the form.
        File(temp.root, BackupStore.CONFIG_NAME).writeText("""{"kind":"S3"}""")
        val partial = store().loadConfig()
        assertEquals(BackupKind.S3, partial.kind)
        assertEquals("", partial.s3.bucket)
    }

    @Test
    fun `status keeps last result but never restores a transient phase`() {
        val saved = BackupStatus(
            phase = BackupPhase.Working,
            stage = "上传中",
            detail = "正在上传",
            fraction = 0.5f,
            action = "backup",
            lastAt = 123,
            lastBytes = 456,
            lastAction = "backup",
            lastMessage = "已备份到 S3 · bucket",
            lastOk = true,
            error = "boom",
        )
        store().saveStatus(saved)

        val loaded = BackupStore(temp.root).loadStatus()
        assertEquals(BackupPhase.Idle, loaded.phase)
        assertEquals("", loaded.stage)
        assertEquals("", loaded.detail)
        assertEquals(0f, loaded.fraction)
        assertEquals("", loaded.action)
        assertNull(loaded.error)
        assertEquals(123, loaded.lastAt)
        assertEquals(456, loaded.lastBytes)
        assertEquals("backup", loaded.lastAction)
        assertEquals("已备份到 S3 · bucket", loaded.lastMessage)
        assertTrue(loaded.lastOk)
    }

    @Test
    fun `malformed status falls back to idle with no history`() {
        File(temp.root, BackupStore.STATUS_NAME).writeText("[]")
        val loaded = store().loadStatus()
        assertEquals(BackupPhase.Idle, loaded.phase)
        assertEquals(0L, loaded.lastAt)
        assertFalse(loaded.lastOk)
    }
}
