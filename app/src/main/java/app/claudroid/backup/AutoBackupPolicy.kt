package app.claudroid.backup

import java.util.concurrent.TimeUnit

/**
 * Pure decisions for the background schedule (docs/BACKUP.md §4/§5.5): which
 * intervals are valid, whether a job should exist, and the signature that lets
 * [AutoBackupScheduler] skip a no-op reschedule.
 *
 * Android-free on purpose so the rules run as plain JVM tests; the scheduler only
 * translates these answers into JobScheduler calls.
 */
object AutoBackupPolicy {

    /** Daily, every three days, weekly. Any other saved value means daily. */
    val INTERVAL_HOURS: List<Int> = listOf(24, 72, 168)

    const val DEFAULT_INTERVAL_HOURS = 24

    fun normalizeInterval(hours: Int): Int =
        if (hours in INTERVAL_HOURS) hours else DEFAULT_INTERVAL_HOURS

    fun intervalHours(config: BackupConfig): Int =
        normalizeInterval(config.auto.intervalHours)

    /** JobInfo takes millis; the contract only has whole hours. */
    fun intervalMillis(config: BackupConfig): Long =
        TimeUnit.HOURS.toMillis(intervalHours(config).toLong())

    /**
     * LOCAL has no upload target and `enabled=false` means no background work. The job
     * re-checks this when it fires: a persisted registration can outlive the config
     * that created it (disabled in another process, or a reboot-time trigger).
     */
    fun shouldSchedule(config: BackupConfig): Boolean =
        config.auto.enabled && config.kind != BackupKind.LOCAL

    /**
     * Only fields that change the registered job. Endpoint and credential edits are
     * excluded on purpose: rescheduling a periodic job postpones its next run, so
     * typing a password must not push the backup back by a whole period. The interval
     * is normalized first, so 25 and 24 produce the same daily signature.
     */
    fun signature(config: BackupConfig): String =
        "${config.auto.enabled}:${intervalHours(config)}:${config.kind.name}"
}
