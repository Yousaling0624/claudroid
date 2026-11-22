package app.claudroid.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Wire format shared with `bridge/` (docs/REMOTE_HOST.md §协议). */
object RemoteJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
}

@Serializable
data class RemoteTunnelStatus(
    val mode: String = "off",
    val state: String = "off",
    val url: String? = null,
    val error: String? = null,
    val changedAt: Long = 0,
) {
    val isUp: Boolean get() = state == "up"
    val label: String
        get() = when (state) {
            "up" -> "隧道在线"
            "starting" -> "隧道启动中"
            "error" -> "隧道异常"
            else -> "隧道未开启"
        }
}

@Serializable
data class RemoteHealth(
    val protocol: Int = 0,
    val daemonVersion: String = "",
    val claudeVersion: String? = null,
    val hostName: String = "",
    val platform: String = "",
    val arch: String = "",
    val workspaceRoots: List<String> = emptyList(),
    val defaultCwd: String = "",
    val defaultModel: String? = null,
    val permissionModes: List<String> = emptyList(),
    val allowBypassPermissions: Boolean = false,
    /** Daemon exposes `GET /v1/events/batch`; quick tunnels buffer SSE, so the app polls. */
    val eventPoll: Boolean = false,
    val tunnel: RemoteTunnelStatus = RemoteTunnelStatus(),
)

@Serializable
data class RemotePairResponse(
    val token: String,
    val host: RemoteHealth = RemoteHealth(),
)

@Serializable
data class RemoteTurnSnapshot(
    val turnId: String,
    val status: String,
    val exitCode: Int? = null,
    val sessionId: String? = null,
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean = false,
    val size: Long = 0,
    /** Node reports fractional milliseconds; `Long` would reject the real payload. */
    val mtimeMs: Double = 0.0,
)

@Serializable
data class RemoteFileList(val entries: List<RemoteFileEntry> = emptyList())

@Serializable
data class RemotePairRequest(val code: String, val deviceName: String)

@Serializable
data class RemoteTurnBody(
    val cwd: String,
    val prompt: String,
    val resumeSessionId: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
)

@Serializable
data class RemotePermissionBody(
    val requestId: String,
    val behavior: String,
    val message: String? = null,
    val always: Boolean? = null,
)

@Serializable
data class RemoteTunnelAction(val action: String)

@Serializable
data class RemoteErrorBody(val error: String? = null)

/** Stream events published by the host. */
@Serializable
sealed interface RemoteEvent {
    val seq: Long

    @Serializable
    @SerialName("cli")
    data class Cli(
        override val seq: Long = 0,
        val turnId: String = "",
        val owner: String? = null,
        val line: String = "",
    ) : RemoteEvent

    @Serializable
    @SerialName("permission")
    data class Permission(
        override val seq: Long = 0,
        val turnId: String = "",
        val owner: String? = null,
        val requestId: String = "",
        val toolUseID: String = "",
        val toolName: String = "",
        val input: JsonElement? = null,
        val suggestions: JsonElement? = null,
        val title: String? = null,
        val displayName: String? = null,
        val description: String? = null,
        val decisionReason: String? = null,
        val expiresAt: Long = 0,
    ) : RemoteEvent

    @Serializable
    @SerialName("turn")
    data class Turn(
        override val seq: Long = 0,
        val turnId: String = "",
        val owner: String? = null,
        val phase: String = "",
        val exitCode: Int? = null,
        val error: String? = null,
    ) : RemoteEvent

    @Serializable
    @SerialName("tunnel")
    data class Tunnel(
        override val seq: Long = 0,
        val tunnel: RemoteTunnelStatus = RemoteTunnelStatus(),
    ) : RemoteEvent

    @Serializable
    @SerialName("host")
    data class Host(
        override val seq: Long = 0,
        val host: RemoteHealth = RemoteHealth(),
    ) : RemoteEvent
}

@Serializable
data class RemoteEventBatch(
    val events: List<RemoteEvent> = emptyList(),
    /** Sequence head of the daemon process; lower than a stored `since` means it restarted. */
    val latestSeq: Long = 0,
)

/** Per-host permission modes the daemon accepts (docs/BRIDGE_CONTRACT.md §6.9). */
object RemotePermissionModes {
    const val DEFAULT = "default"
    const val BYPASS = "bypassPermissions"

    val ALLOWED = setOf(DEFAULT, "acceptEdits", "plan", BYPASS)

    /**
     * hosts.json may predate this field or be hand-edited. The daemon rejects
     * anything outside [ALLOWED], so the UI and chat only ever see a valid value.
     */
    fun normalize(raw: String?): String =
        raw?.trim()?.takeIf { it in ALLOWED } ?: DEFAULT
}

@Serializable
data class RemoteHostProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** AES-GCM sealed by Android Keystore; never written to logs or backups. */
    val tokenSealed: String,
    val accessClientId: String = "",
    val accessSecretSealed: String = "",
    val hostName: String = "",
    val platform: String = "",
    val protocol: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = 0,
    val defaultCwd: String = "",
    val workspaceRoots: List<String> = emptyList(),
    val tunnel: RemoteTunnelStatus = RemoteTunnelStatus(),
    /** This host's own mode; the global setting only governs the local engine. */
    val permissionMode: String = RemotePermissionModes.DEFAULT,
    /** Last health capability, kept so the detail page stays correct after a restart. */
    val allowBypassPermissions: Boolean = false,
)

@Serializable
data class RemoteHostsFile(
    val hosts: List<RemoteHostProfile> = emptyList(),
    val defaultHostId: String = "",
)

enum class RemotePhase { OFFLINE, CONNECTING, ONLINE, ERROR }

data class RemoteRuntime(
    val phase: RemotePhase = RemotePhase.OFFLINE,
    val error: String? = null,
    val health: RemoteHealth? = null,
)

data class RemoteHostsState(
    val hosts: List<RemoteHostProfile> = emptyList(),
    val defaultHostId: String? = null,
    val runtime: Map<String, RemoteRuntime> = emptyMap(),
)

class RemoteException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
