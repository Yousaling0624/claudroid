package app.claudroid.backup

import kotlinx.serialization.json.Json

/**
 * JSON codec for every backup payload. Kept separate from [BackupStore] so the bridge
 * can encode a status snapshot without touching the filesystem, and so tests can pin
 * the exact field names from docs/BACKUP.md §4/§6.
 */
object BackupWire {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun configJson(config: BackupConfig): String =
        json.encodeToString(BackupConfig.serializer(), config)

    fun decodeConfig(raw: String): BackupConfig = try {
        json.decodeFromString(BackupConfig.serializer(), raw)
    } catch (e: Exception) {
        // Never echo the payload: it carries passwords, Access Keys and tokens.
        throw IllegalArgumentException("备份配置格式无效")
    }

    fun statusJson(status: BackupStatus): String =
        json.encodeToString(BackupStatus.serializer(), status)

    fun runResultJson(result: BackupRunResult): String =
        json.encodeToString(BackupRunResult.serializer(), result)

    fun restoreResultJson(result: BackupRestoreResult): String =
        json.encodeToString(BackupRestoreResult.serializer(), result)

    internal fun decodeStatus(raw: String): BackupStatus? = runCatching {
        json.decodeFromString(BackupStatus.serializer(), raw)
    }.getOrNull()

    internal fun decodeManifest(raw: String): BackupManifest? = runCatching {
        json.decodeFromString(BackupManifest.serializer(), raw)
    }.getOrNull()

    internal fun manifestJson(manifest: BackupManifest): String =
        json.encodeToString(BackupManifest.serializer(), manifest)

    internal fun settingsJson(settings: app.claudroid.data.AppSettings): String =
        json.encodeToString(app.claudroid.data.AppSettings.serializer(), settings)

    internal fun workspaceIndexJson(index: BackupWorkspaceIndex): String =
        json.encodeToString(BackupWorkspaceIndex.serializer(), index)
}
