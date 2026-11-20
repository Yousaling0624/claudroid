package app.claudroid.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import app.claudroid.core.Logx

/**
 * Registers or cancels the one periodic auto-backup job (docs/BACKUP.md §5.5).
 *
 * The in-memory [signature] is the key to not postpone an already scheduled run:
 * [JobScheduler.schedule] on the same id resets the period, so every `saveConfig`
 * that merely edits a URL or token must be a no-op here. It is null after process
 * start, which forces one sync on launch; persisted jobs survive reboots on their own,
 * but the config may have changed while the app was not running.
 */
class AutoBackupScheduler(private val context: Context) {

    @Volatile
    private var signature: String? = null

    @Synchronized
    fun sync(config: BackupConfig) {
        val next = AutoBackupPolicy.signature(config)
        if (next == signature) return
        runCatching {
            val jobs = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: error("系统不支持 JobScheduler")
            if (AutoBackupPolicy.shouldSchedule(config)) {
                val interval = AutoBackupPolicy.intervalMillis(config)
                // schedule() on an existing id resets its period, and every user launch
                // syncs; registering blindly would push the next run back a whole
                // interval each time. Only a missing job or a changed cadence needs a
                // call. getPendingJob() also keeps a launch during a running job from
                // replacing the execution the system just started.
                val existing = jobs.getPendingJob(JOB_ID)
                if (existing == null || existing.intervalMillis != interval) {
                    val info = JobInfo.Builder(
                        JOB_ID,
                        ComponentName(context, AutoBackupJobService::class.java),
                    )
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        // Persisted jobs survive a reboot; RECEIVE_BOOT_COMPLETED is
                        // declared in the manifest, otherwise setPersisted throws.
                        .setPersisted(true)
                        .setPeriodic(interval)
                        .build()
                    check(jobs.schedule(info) == JobScheduler.RESULT_SUCCESS) {
                        "JobScheduler 拒绝了自动备份任务"
                    }
                }
            } else {
                jobs.cancel(JOB_ID)
            }
            signature = next
        }.onFailure {
            // Scheduling is best effort: a failure must never crash startup. Leaving
            // the signature unset makes the next sync (save or launch) retry.
            Logx.e("backup: 自动备份调度失败: ${it.message}", context)
        }
    }

    companion object {
        /** One stable id: scheduling again replaces the previous registration. */
        const val JOB_ID = 1080
    }
}
