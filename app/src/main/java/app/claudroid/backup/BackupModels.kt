package app.claudroid.backup

import app.claudroid.core.WorkspaceInfo
import app.claudroid.core.WorkspaceStore
import app.claudroid.data.AppSettings
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Wire and on-disk models for docs/BACKUP.md. Field names are the frozen contract:
 * the ZIP manifest, `target.json`, `status.json` and every bridge payload are decoded
 * by Dart with these literal names, so renaming one must break a test first.
 */

@Serializable
enum class BackupKind { LOCAL, WEBDAV, S3, GITHUB }

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
)

@Serializable
data class S3Config(
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val prefix: String = "claudroid",
    /** Path-style works with MinIO and most S3-compatible services. */
    val pathStyle: Boolean = true,
)

@Serializable
data class GithubConfig(
    val repo: String = "",
    val branch: String = "main",
    val path: String = "claudroid-backup.zip",
    val token: String = "",
)

/**
 * Background schedule (docs/BACKUP.md §4/§5.5). `intervalHours` is kept as saved;
 * [AutoBackupPolicy] is the one that falls back to 24 for values outside 24/72/168.
 * The default lets an old `target.json` without an `auto` key decode unchanged.
 */
@Serializable
data class AutoBackupConfig(
    val enabled: Boolean = false,
    val intervalHours: Int = 24,
)

/**
 * Saved verbatim even when incomplete: users fill the form piece by piece and a
 * half-typed endpoint must survive a restart. [requireUsable] is called only when an
 * action actually runs.
 */
@Serializable
data class BackupConfig(
    val kind: BackupKind = BackupKind.LOCAL,
    val auto: AutoBackupConfig = AutoBackupConfig(),
    val webdav: WebDavConfig = WebDavConfig(),
    val s3: S3Config = S3Config(),
    val github: GithubConfig = GithubConfig(),
)

/** One archive's counts, written last so a truncated ZIP never looks complete. */
@Serializable
data class BackupManifest(
    val schema: Int,
    val createdAt: Long,
    val appVersion: String,
    val versionCode: Int,
    val sessions: Int,
    val workspaces: Int,
    val files: Int,
    val bytes: Long,
    val skipped: Int,
)

/** `workspaces/index.json`; activeId is informational and never applied on restore. */
@Serializable
data class BackupWorkspaceIndex(
    val activeId: String = WorkspaceStore.DEFAULT_ID,
    val spaces: List<WorkspaceInfo> = emptyList(),
)

@Serializable
enum class BackupPhase { Idle, Working, Error }

@Serializable
data class BackupStatus(
    val phase: BackupPhase = BackupPhase.Idle,
    val stage: String = "",
    val detail: String = "",
    val fraction: Float = 0f,
    val action: String = "",
    val lastAt: Long = 0L,
    val lastBytes: Long = 0L,
    val lastAction: String = "",
    val lastMessage: String = "",
    val lastOk: Boolean = false,
    val error: String? = null,
)

@Serializable
data class BackupRunResult(
    val bytes: Long,
    val sessions: Int,
    val workspaces: Int,
    val files: Int,
    val target: String,
    val createdAt: Long,
)

@Serializable
data class BackupRestoreResult(
    val sessionsAdded: Int,
    val sessionsSkipped: Int,
    val filesAdded: Int,
    val filesSkipped: Int,
    val workspacesAdded: Int,
    val settingsApplied: Boolean,
    val createdAt: Long,
)

/** Internal restore output; `settings` never reaches the wire (it holds no secrets). */
data class RestoreOutcome(
    val sessionsAdded: Int = 0,
    val sessionsSkipped: Int = 0,
    val filesAdded: Int = 0,
    val filesSkipped: Int = 0,
    val createdAt: Long = 0L,
    val restoredSpaces: List<WorkspaceInfo> = emptyList(),
    val settings: AppSettings? = null,
)

data class ExportOutcome(val cancelled: Boolean, val bytes: Long)

data class ImportOutcome(val cancelled: Boolean, val result: BackupRestoreResult?)

/** The local mode has no cloud endpoint; run/restore must point at export/import. */
fun BackupConfig.requireUsable(): BackupConfig {
    if (kind == BackupKind.LOCAL) {
        throw IllegalStateException("当前目标是本地导出/导入，请改用本地备份或恢复")
    }
    val problem = when (kind) {
        BackupKind.LOCAL -> "当前目标是本地导出/导入"
        BackupKind.WEBDAV -> webdav.problem()
        BackupKind.S3 -> s3.problem()
        BackupKind.GITHUB -> github.problem()
    }
    problem?.let { throw IllegalStateException(it) }
    return this
}

private fun WebDavConfig.problem(): String? {
    val raw = url.trim()
    if (raw.isEmpty()) return "请填写 WebDAV 备份地址"
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: return "WebDAV 地址格式无效"
    if (uri.scheme != "http" && uri.scheme != "https") return "WebDAV 地址必须以 http:// 或 https:// 开头"
    if (uri.host.isNullOrBlank()) return "WebDAV 地址缺少主机名"
    // Credentials in the URL would end up in exception messages and logs; the
    // dedicated fields keep them inside the Authorization header only.
    if (uri.userInfo != null) return "WebDAV 地址不能包含用户名或密码，请填写下方字段"
    return null
}

private fun S3Config.problem(): String? {
    if (bucket.trim().isEmpty()) return "请填写 S3 Bucket"
    if (region.trim().isEmpty()) return "请填写 S3 Region"
    if (accessKey.trim().isEmpty()) return "请填写 S3 Access Key"
    if (secretKey.isEmpty()) return "请填写 S3 Secret Key"
    val raw = endpoint.trim()
    if (raw.isNotEmpty()) {
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return "S3 Endpoint 格式无效"
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            return "S3 Endpoint 必须以 http:// 或 https:// 开头并包含主机名"
        }
    }
    // Bucket names are path / DNS labels; anything else could alter the signed path.
    if (!bucket.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,62}"))) return "S3 Bucket 名称无效"
    return null
}

private fun GithubConfig.problem(): String? {
    if (repo.trim().isEmpty()) return "请填写 GitHub 仓库（owner/name）"
    val parts = repo.trim().split('/')
    if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
        return "GitHub 仓库格式应为 owner/name"
    }
    if (token.isBlank()) return "请填写 GitHub Token"
    val cleanPath = path.trim()
    if (cleanPath.isNotEmpty()) {
        if (cleanPath.contains('\\') || cleanPath.contains('\u0000')) return "GitHub 文件路径无效"
        if (cleanPath.split('/').any { it == ".." }) return "GitHub 文件路径无效"
    }
    return null
}
