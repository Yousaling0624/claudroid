package app.claudroid.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistence for `filesDir/backup/target.json` and `status.json` (docs/BACKUP.md
 * §4/§6). Both files are read defensively: a half-written or hand-edited file falls
 * back to defaults instead of crashing the settings page.
 */
class BackupStore(private val dir: File) {

    private val configFile: File get() = File(dir, CONFIG_NAME)
    private val statusFile: File get() = File(dir, STATUS_NAME)
    private val lock = Any()

    /** Missing or unreadable configs are treated as "not configured yet". */
    fun loadConfig(): BackupConfig = synchronized(lock) {
        val text = runCatching { configFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?: return BackupConfig()
        runCatching { BackupWire.json.decodeFromString(BackupConfig.serializer(), text) }
            .getOrDefault(BackupConfig())
    }

    fun saveConfig(config: BackupConfig) {
        synchronized(lock) { writeAtomic(configFile, BackupWire.configJson(config)) }
    }

    /**
     * Restores the persisted `last*` fields, but never a stale Working/Error phase:
     * the process that owned that task is gone, so the UI must start Idle.
     */
    fun loadStatus(): BackupStatus = synchronized(lock) {
        val text = runCatching { statusFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?: return BackupStatus()
        val saved = BackupWire.decodeStatus(text) ?: return BackupStatus()
        saved.copy(
            phase = BackupPhase.Idle,
            stage = "",
            detail = "",
            fraction = 0f,
            action = "",
            error = null,
        )
    }

    fun saveStatus(status: BackupStatus) {
        synchronized(lock) { writeAtomic(statusFile, BackupWire.statusJson(status)) }
    }

    private fun writeAtomic(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    companion object {
        const val CONFIG_NAME = "target.json"
        const val STATUS_NAME = "status.json"
    }
}
