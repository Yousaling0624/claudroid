package app.claudroid.backup

import app.claudroid.core.WorkspaceStore
import app.claudroid.data.AppSettings
import app.claudroid.engine.HttpUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Thrown inside the worker when [BackupManager.cancel] was requested. */
private class BackupCancelledException : Exception("已取消")

/**
 * Orchestrates packing, uploading, downloading and merging (docs/BACKUP.md §3/§5/§6).
 *
 * One task at a time: run/restore/export/import/test all take the same slot, and a
 * second caller gets「已有备份任务在进行」immediately instead of queueing behind a
 * possibly long upload. The work runs on [io] in [scope], so `backup.cancel` can stop
 * it without touching the MethodChannel handler.
 *
 * [settings] is suspend because [app.claudroid.data.SettingsRepository] only exposes
 * DataStore-backed suspend reads; every caller of the manager is already suspend.
 * [applySettings] is a callback so this class stays free of DataStore and testable.
 */
class BackupManager(
    private val archive: BackupArchive,
    private val store: BackupStore,
    private val workspaces: WorkspaceStore,
    private val settings: suspend () -> AppSettings,
    private val applySettings: suspend (AppSettings) -> Unit,
    private val appVersion: String,
    private val versionCode: Int,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _status = MutableStateFlow(store.loadStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val running = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

    /** Set by [cancel]; also makes [progress] stop repainting after the status reset. */
    @Volatile
    private var cancelRequested = false

    private var lastEmitAt = 0L
    private var lastFraction = -1f
    private var lastStage = ""
    private var lastDetail = ""

    val busy: Boolean get() = running.get()

    fun savedConfig(): BackupConfig = store.loadConfig()

    /** Saves an incomplete config verbatim; users fill the form piece by piece. */
    fun saveConfig(config: BackupConfig) = store.saveConfig(config)

    fun label(config: BackupConfig): String = when (config.kind) {
        BackupKind.LOCAL -> "仅本地"
        BackupKind.WEBDAV -> WebDavTarget.label(config.webdav)
        BackupKind.S3 -> S3Target.label(config.s3)
        BackupKind.GITHUB -> GitHubTarget.label(config.github)
    }

    /**
     * Best-effort stop. The status is reset here so the button feels immediate; the
     * worker notices the cancelled job at its next chunk/coroutine check and the
     * caller receives「已取消」once it actually stops.
     */
    fun cancel() {
        val job = currentJob ?: return
        // A finished worker (success already recorded) must not be relabelled as
        // cancelled when the user taps the button in the last moment.
        if (!job.isActive) return
        cancelRequested = true
        job.cancel()
        cancelledStatus()
    }

    suspend fun test(config: BackupConfig): String = exclusive("test", "连接测试") {
        config.requireUsable()
        val message = when (config.kind) {
            BackupKind.WEBDAV -> WebDavTarget.test(config.webdav)
            BackupKind.S3 -> S3Target.test(config.s3)
            BackupKind.GITHUB -> GitHubTarget.test(config.github)
            BackupKind.LOCAL -> throw IllegalStateException("当前目标是本地导出/导入，请改用本地备份或恢复")
        }
        // A connection test must not look like a backup: lastAt/lastBytes and
        // lastAction keep describing the last real transfer.
        updateStatus {
            it.copy(
                phase = BackupPhase.Idle,
                stage = "",
                detail = "",
                fraction = 0f,
                action = "",
                lastMessage = message,
                lastOk = true,
                error = null,
            )
        }
        message
    }

    /**
     * [auto] only changes the success wording: the archive, target, status and
     * single-task slot are identical to a manual run (docs/BACKUP.md §5.5).
     */
    suspend fun run(auto: Boolean = false): BackupRunResult = exclusive("backup", "打包中") {
        val config = store.loadConfig().requireUsable()
        val target = label(config)
        val file = File(cacheDir, RUN_ARCHIVE)
        try {
            val manifest = archive.write(
                out = file,
                settings = settings(),
                index = BackupWorkspaceIndex(workspaces.activeId(), workspaces.state.value.spaces),
                appVersion = appVersion,
                versionCode = versionCode,
                cancelCheck = ::checkCancelled,
            ) { done, total ->
                progress(0.6f * ratio(done, total), "打包中", "已写入 ${formatBytes(done)}")
            }
            val bytes = file.length()
            progress(0.6f, "上传中", "正在上传 ${formatBytes(bytes)}")
            upload(config, file)
            val result = BackupRunResult(
                bytes = bytes,
                sessions = manifest.sessions,
                workspaces = manifest.workspaces,
                files = manifest.files,
                target = target,
                createdAt = manifest.createdAt,
            )
            completeSuccess("backup", bytes, if (auto) "已自动备份到 $target" else "已备份到 $target")
            result
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun restore(restoreSettings: Boolean = false): BackupRestoreResult =
        exclusive("restore", "下载中") {
            val config = store.loadConfig().requireUsable()
            val file = File(cacheDir, CLOUD_ARCHIVE)
            try {
                progress(0f, "下载中", "正在连接 ${label(config)}")
                download(config, file) { fraction ->
                    progress(0.5f * fraction, "下载中", "正在下载备份")
                }
                applyRestore(file, restoreSettings, 0.5f, 0.5f, "restore")
            } finally {
                runCatching { file.delete() }
            }
        }

    /**
     * Packs an archive and hands it to [write] (the bridge streams it to a SAF URI).
     * The caller decides whether the user cancelled the picker; reaching here means
     * there is something to write.
     */
    suspend fun export(write: suspend (File) -> Unit): ExportOutcome =
        exclusive("export", "打包中") {
            val file = File(cacheDir, EXPORT_ARCHIVE)
            try {
                archive.write(
                    out = file,
                    settings = settings(),
                    index = BackupWorkspaceIndex(workspaces.activeId(), workspaces.state.value.spaces),
                    appVersion = appVersion,
                    versionCode = versionCode,
                    cancelCheck = ::checkCancelled,
                ) { done, total ->
                    progress(0.9f * ratio(done, total), "打包中", "已写入 ${formatBytes(done)}")
                }
                val bytes = file.length()
                progress(0.9f, "导出中", "正在保存到所选位置")
                write(file)
                completeSuccess("export", bytes, "已导出备份文件（${formatBytes(bytes)}）")
                ExportOutcome(cancelled = false, bytes = bytes)
            } finally {
                runCatching { file.delete() }
            }
        }

    /**
     * Restores from a caller-supplied archive. [read] copies the SAF stream into the
     * temporary file; a cancelled picker never reaches this method.
     */
    suspend fun import(
        read: suspend (File) -> Unit,
        restoreSettings: Boolean = false,
    ): BackupRestoreResult = exclusive("import", "导入中") {
        val file = File(cacheDir, IMPORT_ARCHIVE)
        try {
            file.delete()
            read(file)
            if (!file.isFile || file.length() <= 0L) {
                throw BackupFormatException("无法读取所选备份文件")
            }
            applyRestore(file, restoreSettings, 0f, 1f, "import")
        } finally {
            runCatching { file.delete() }
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun upload(config: BackupConfig, file: File) {
        val onProgress: (Long, Long) -> Unit = { done, total ->
            val detail = if (total > 0) {
                "已上传 ${formatBytes(done)} / ${formatBytes(total)}"
            } else {
                "已上传 ${formatBytes(done)}"
            }
            progress(0.6f + 0.4f * ratio(done, total), "上传中", detail)
        }
        when (config.kind) {
            BackupKind.WEBDAV -> WebDavTarget.upload(config.webdav, file, onProgress)
            BackupKind.S3 -> {
                // The payload hash is part of SigV4; hash before opening the upload so
                // the HTTP request itself still streams the file.
                progress(0.6f, "上传中", "正在计算校验值")
                S3Target.upload(config.s3, file, HttpUtil.sha256Hex(file), onProgress)
            }
            BackupKind.GITHUB -> GitHubTarget.upload(config.github, file, onProgress)
            BackupKind.LOCAL -> throw IllegalStateException("当前目标是本地导出/导入，请改用本地备份或恢复")
        }
    }

    private suspend fun download(config: BackupConfig, dest: File, onProgress: (Float) -> Unit) {
        val maxBytes = BackupArchive.MAX_TOTAL_BYTES
        when (config.kind) {
            BackupKind.WEBDAV -> WebDavTarget.download(config.webdav, dest, maxBytes) { done, total ->
                onProgress(ratio(done, total))
            }
            BackupKind.S3 -> S3Target.download(config.s3, dest, maxBytes) { done, total ->
                onProgress(ratio(done, total))
            }
            BackupKind.GITHUB -> GitHubTarget.download(config.github, dest, maxBytes) { done, total ->
                onProgress(ratio(done, total))
            }
            BackupKind.LOCAL -> throw IllegalStateException("当前目标是本地导出/导入，请改用本地备份或恢复")
        }
    }

    private suspend fun applyRestore(
        zipFile: File,
        restoreSettings: Boolean,
        progressBase: Float,
        progressSpan: Float,
        action: String,
    ): BackupRestoreResult {
        val stage = if (action == "import") "导入中" else "恢复中"
        progress(progressBase, stage, "正在校验备份")
        archive.readManifest(zipFile)
        val outcome = archive.restore(
            zipFile = zipFile,
            restoreSettings = restoreSettings,
            localSettings = settings(),
            cancelCheck = ::checkCancelled,
        ) { done, total ->
            progress(
                progressBase + progressSpan * ratio(done, total),
                stage,
                "已恢复 ${formatBytes(done)}",
            )
        }
        val applied = outcome.settings
        if (applied != null) applySettings(applied)
        val added = workspaces.mergeRestored(outcome.restoredSpaces)
        val result = BackupRestoreResult(
            sessionsAdded = outcome.sessionsAdded,
            sessionsSkipped = outcome.sessionsSkipped,
            filesAdded = outcome.filesAdded,
            filesSkipped = outcome.filesSkipped,
            workspacesAdded = added,
            settingsApplied = applied != null,
            createdAt = outcome.createdAt,
        )
        val prefix = if (action == "import") "导入完成" else "恢复完成"
        completeSuccess(action, zipFile.length(), restoreMessage(prefix, result))
        return result
    }

    private fun restoreMessage(prefix: String, result: BackupRestoreResult): String = buildString {
        append(prefix)
        append("：新增 ")
        append(result.sessionsAdded)
        append(" 个会话、")
        append(result.filesAdded)
        append(" 个文件")
        if (result.workspacesAdded > 0) {
            append("、")
            append(result.workspacesAdded)
            append(" 个工作区")
        }
        val skipped = result.sessionsSkipped + result.filesSkipped
        if (skipped > 0) {
            append("，跳过 ")
            append(skipped)
            append(" 项本机已有内容")
        }
    }

    /**
     * Runs [block] in the manager's scope and waits for it. Only one caller may hold
     * the slot; cancellation is converted into a normal `IllegalStateException` so the
     * bridge can answer the pending Dart call instead of leaving its Future hanging.
     */
    private suspend fun <T> exclusive(action: String, stage: String, block: suspend () -> T): T {
        if (!running.compareAndSet(false, true)) throw IllegalStateException(BUSY_MESSAGE)
        cancelRequested = false
        lastEmitAt = 0L
        lastFraction = -1f
        lastStage = ""
        lastDetail = ""
        setWorking(action, stage)
        val deferred = CompletableDeferred<T>()
        val worker = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                deferred.complete(block())
            } catch (ce: CancellationException) {
                deferred.completeExceptionally(BackupCancelledException())
                throw ce
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        currentJob = worker
        worker.start()
        try {
            return deferred.await()
        } catch (e: BackupCancelledException) {
            cancelledStatus()
            throw IllegalStateException(CANCELLED_MESSAGE)
        } catch (ce: CancellationException) {
            // The caller went away: stop the worker so it cannot outlive its result.
            worker.cancel()
            cancelledStatus()
            throw IllegalStateException(CANCELLED_MESSAGE)
        } catch (t: Throwable) {
            if (cancelRequested) {
                cancelledStatus()
                throw IllegalStateException(CANCELLED_MESSAGE)
            }
            failStatus(action, t)
            throw t
        } finally {
            if (currentJob === worker) currentJob = null
            running.set(false)
        }
    }

    private fun checkCancelled() {
        if (cancelRequested) throw BackupCancelledException()
        currentJob?.ensureActive()
    }

    /** Fraction updates are throttled: every archive chunk would otherwise rebuild the page. */
    private fun progress(fraction: Float, stage: String, detail: String = "") {
        if (cancelRequested) return
        val clamped = fraction.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        val significant = abs(clamped - lastFraction) >= 0.01f
        val stageChanged = stage != lastStage || detail != lastDetail
        if (!stageChanged && !significant && now - lastEmitAt < PROGRESS_INTERVAL_MS) return
        lastEmitAt = now
        lastFraction = clamped
        lastStage = stage
        lastDetail = detail
        updateStatus {
            it.copy(phase = BackupPhase.Working, stage = stage, detail = detail, fraction = clamped)
        }
    }

    private fun setWorking(action: String, stage: String) {
        updateStatus {
            it.copy(
                phase = BackupPhase.Working,
                stage = stage,
                detail = "",
                fraction = 0f,
                action = action,
                error = null,
            )
        }
    }

    private fun completeSuccess(action: String, bytes: Long, message: String) {
        updateStatus {
            it.copy(
                phase = BackupPhase.Idle,
                stage = "",
                detail = "",
                fraction = 0f,
                action = "",
                lastAt = System.currentTimeMillis(),
                lastBytes = bytes,
                lastAction = action,
                lastMessage = message,
                lastOk = true,
                error = null,
            )
        }
    }

    private fun cancelledStatus() {
        updateStatus {
            it.copy(
                phase = BackupPhase.Idle,
                stage = "",
                detail = "",
                fraction = 0f,
                action = "",
                lastMessage = CANCELLED_MESSAGE,
                error = null,
            )
        }
    }

    private fun failStatus(action: String, error: Throwable) {
        val message = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "操作失败"
        updateStatus {
            it.copy(
                phase = BackupPhase.Error,
                stage = "",
                detail = "",
                fraction = 0f,
                action = action,
                lastOk = false,
                lastMessage = message,
                error = message,
            )
        }
    }

    /**
     * A broken status file must never fail the actual backup. Only the durable
     * `last*` fields are written to disk: progress ticks arrive ~10/s and would
     * otherwise rewrite a tiny file for the whole duration of a large upload.
     */
    private fun updateStatus(transform: (BackupStatus) -> BackupStatus) {
        val before = _status.value
        val next = _status.updateAndGet(transform)
        val durableChanged = next.lastAt != before.lastAt ||
            next.lastBytes != before.lastBytes ||
            next.lastAction != before.lastAction ||
            next.lastMessage != before.lastMessage ||
            next.lastOk != before.lastOk
        if (durableChanged) runCatching { store.saveStatus(next) }
    }

    private fun ratio(done: Long, total: Long): Float =
        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 ->
            String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        const val BUSY_MESSAGE = "已有备份任务在进行"
        const val CANCELLED_MESSAGE = "已取消"

        private const val RUN_ARCHIVE = "claudroid-backup.zip"
        private const val CLOUD_ARCHIVE = "cloud-backup.zip"
        private const val EXPORT_ARCHIVE = "claudroid-export.zip"
        private const val IMPORT_ARCHIVE = "import.zip"

        /** At most one progress event per this interval (~10/s). */
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
