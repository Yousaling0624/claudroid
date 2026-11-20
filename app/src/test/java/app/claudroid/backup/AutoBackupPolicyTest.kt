package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Interval and scheduling rules from docs/BACKUP.md §4/§5.5/§7. */
class AutoBackupPolicyTest {

    @Test
    fun `only 24 72 and 168 are valid intervals`() {
        assertEquals(listOf(24, 72, 168), AutoBackupPolicy.INTERVAL_HOURS)
        AutoBackupPolicy.INTERVAL_HOURS.forEach {
            assertEquals(it, AutoBackupPolicy.normalizeInterval(it))
        }
        listOf(-1, 0, 1, 12, 23, 25, 48, 167, 169, 1000).forEach {
            assertEquals(
                "interval $it must fall back to daily",
                24,
                AutoBackupPolicy.normalizeInterval(it),
            )
        }
    }

    @Test
    fun `config helpers normalize the saved interval`() {
        fun config(hours: Int) =
            BackupConfig(auto = AutoBackupConfig(enabled = true, intervalHours = hours))

        assertEquals(24, AutoBackupPolicy.intervalHours(config(5)))
        assertEquals(168, AutoBackupPolicy.intervalHours(config(168)))
        assertEquals(TimeUnit.HOURS.toMillis(72), AutoBackupPolicy.intervalMillis(config(72)))
        assertEquals(TimeUnit.HOURS.toMillis(24), AutoBackupPolicy.intervalMillis(config(25)))
    }

    @Test
    fun `a job exists only when enabled and the target is not local`() {
        assertFalse(AutoBackupPolicy.shouldSchedule(BackupConfig()))
        assertFalse(
            AutoBackupPolicy.shouldSchedule(
                BackupConfig(auto = AutoBackupConfig(enabled = true)),
            ),
        )
        assertFalse(AutoBackupPolicy.shouldSchedule(BackupConfig(kind = BackupKind.WEBDAV)))
        BackupKind.entries.filter { it != BackupKind.LOCAL }.forEach { kind ->
            assertTrue(
                AutoBackupPolicy.shouldSchedule(
                    BackupConfig(kind = kind, auto = AutoBackupConfig(enabled = true)),
                ),
            )
        }
    }

    @Test
    fun `signature ignores target fields and normalizes the interval`() {
        val base = BackupConfig(
            kind = BackupKind.WEBDAV,
            auto = AutoBackupConfig(enabled = true, intervalHours = 24),
            webdav = WebDavConfig(url = "https://dav.example.com/f.zip"),
        )
        // Editing URL/credentials must not reschedule (it would postpone the run).
        assertEquals(
            AutoBackupPolicy.signature(base),
            AutoBackupPolicy.signature(base.copy(webdav = WebDavConfig())),
        )
        // An invalid interval acts as daily, so it shares the daily signature.
        assertEquals(
            AutoBackupPolicy.signature(base),
            AutoBackupPolicy.signature(
                base.copy(auto = AutoBackupConfig(enabled = true, intervalHours = 25)),
            ),
        )
        assertNotEquals(
            AutoBackupPolicy.signature(base),
            AutoBackupPolicy.signature(
                base.copy(auto = AutoBackupConfig(enabled = true, intervalHours = 72)),
            ),
        )
        assertNotEquals(
            AutoBackupPolicy.signature(base),
            AutoBackupPolicy.signature(
                base.copy(auto = AutoBackupConfig(enabled = false, intervalHours = 24)),
            ),
        )
        assertNotEquals(
            AutoBackupPolicy.signature(base),
            AutoBackupPolicy.signature(base.copy(kind = BackupKind.S3)),
        )
    }
}
