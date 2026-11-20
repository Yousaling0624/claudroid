package app.claudroid.backup

import android.app.job.JobParameters
import android.app.job.JobService
import app.claudroid.AppContainer
import app.claudroid.ClaudroidApp
import app.claudroid.core.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One firing of the periodic auto-backup job (docs/BACKUP.md §5.5).
 *
 * Everything is re-read from disk here instead of trusting the registration: the job
 * may have been persisted before the user disabled auto, or a boot-time trigger can
 * arrive after the config changed. Chat turns and terminals keep the workspace and
 * session files busy, so those cycles are skipped without touching status; the next
 * period simply tries again.
 *
 * Failures never escape: [BackupManager] already records `lastOk=false` + `error` in
 * status.json, and a periodic job does not need an immediate retry.
 */
class AutoBackupJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // Application.onCreate always runs first, but a null container is cheap to
        // handle and must still acknowledge the job.
        val container = (applicationContext as? ClaudroidApp)?.container
        if (container == null) {
            jobFinished(params, false)
            return false
        }
        work = scope.launch {
            try {
                runAutoBackup(container)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // No notification by contract; status.json is the user-facing record.
                Logx.e("backup: 自动备份失败: ${t.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val running = work
        work = null
        if (running?.isActive == true) {
            running.cancel()
            // The manager's worker lives in the app scope; cancel it explicitly so a
            // long upload stops now instead of at its next archive chunk.
            (applicationContext as? ClaudroidApp)?.container?.backup?.cancel()
        }
        // true: let the system retry instead of waiting a full period after a stop.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAutoBackup(container: AppContainer) {
        // Disabled or LOCAL: end silently. A persisted job can outlive this config.
        if (!AutoBackupPolicy.shouldSchedule(container.backup.savedConfig())) return
        // A running turn or terminal may be writing the very files the archive reads.
        if (container.engine.busy || container.terminalProbe?.invoke() == true) return
        // Manual work owns the single-task slot; skip instead of racing or reporting
        // a busy error into status.json.
        if (container.backup.busy) return
        container.backup.run(auto = true)
    }
}
