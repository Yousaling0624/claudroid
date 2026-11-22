package app.claudroid.bridge

import app.claudroid.data.AppRelease
import app.claudroid.data.AppSettings
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.ModelTier
import app.claudroid.data.ProviderPreset
import app.claudroid.data.ReleaseCheck
import app.claudroid.core.WorkspaceEntry
import app.claudroid.core.WorkspaceFiles
import app.claudroid.core.WorkspaceInfo
import app.claudroid.engine.EngineManager
import app.claudroid.engine.openai.ModelCatalog
import java.io.File
import app.claudroid.chat.ChatEvent
import app.claudroid.chat.PendingPermission
import app.claudroid.chat.chatStatusLine
import app.claudroid.data.activeTierSummary
import app.claudroid.data.chatModelLabel
import app.claudroid.data.composerModelLabel
import app.claudroid.data.shortModelLabel
import app.claudroid.data.tierMappingLabel
import app.claudroid.remote.RemoteFileEntry
import app.claudroid.remote.RemoteHealth
import app.claudroid.remote.RemoteHostProfile
import app.claudroid.remote.RemoteHostsState
import app.claudroid.remote.RemotePermissionModes
import app.claudroid.remote.RemotePhase
import app.claudroid.remote.RemoteRuntime
import app.claudroid.remote.RemoteTunnelStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Serialisation half of the bridge, kept free of any Flutter class so it also compiles
 * (and is unit-tested) in the default build. Field names follow
 * docs/BRIDGE_CONTRACT.md §3–§6 verbatim; changing one here requires changing the
 * contract and the Dart side first.
 */
object BridgeWire {

    fun settingsJson(settings: AppSettings): String =
        BridgeJson.json.encodeToString(AppSettings.serializer(), settings)

    fun decodeSettings(json: String): AppSettings =
        BridgeJson.json.decodeFromString(AppSettings.serializer(), json)

    /** `messagesJson` / `chatJson.messages`: the exact disk format (contract §3). */
    fun messagesJson(messages: List<ChatMessage>): String =
        BridgeJson.json.encodeToString(ListSerializer(ChatMessage.serializer()), messages)

    fun messageJson(message: ChatMessage): String =
        BridgeJson.json.encodeToString(ChatMessage.serializer(), message)

    /** §6 `chatJson`: transcript plus the low-frequency flags the UI renders. */
    fun chatJson(
        messages: List<ChatMessage>,
        title: String,
        titleBusy: Boolean,
        titleNotice: String?,
        running: Boolean,
        status: String,
        statusLine: String,
        permissions: List<PendingPermission> = emptyList(),
    ): String = buildJsonObject {
        put("messages", BridgeJson.json.parseToJsonElement(messagesJson(messages)))
        put("title", title)
        put("titleBusy", titleBusy)
        put("titleNotice", titleNotice)
        put("running", running)
        put("status", status)
        put("statusLine", statusLine)
        put("permissions", buildJsonArray { permissions.forEach { add(permissionJson(it)) } })
    }.toString()

    /** §4 `sessionsJson`: summaries only — the drawer never needs the transcripts. */
    fun sessionsJson(sessions: List<ChatSession>): String =
        sessions.joinToString(prefix = "[", postfix = "]") { sessionJson(it) }

    fun sessionJson(session: ChatSession): String = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("claudeSessionId", session.claudeSessionId)
        put("model", session.model)
        put("hostId", session.hostId)
        put("remoteCwd", session.remoteCwd)
        put("hasContent", session.hasContent)
        put("preview", session.preview)
        put("searchText", searchText(session))
    }.toString()

    /**
     * Drawer search runs in Dart but the transcripts never leave Kotlin. The newest
     * messages are the ones users search for, so walk backwards and stop at a budget
     * that keeps the sessions payload small even with hundreds of conversations.
     */
    fun searchText(session: ChatSession, limit: Int = 2000): String = buildString {
        for (message in session.messages.asReversed()) {
            for (block in message.blocks) {
                val piece = when (block) {
                    is Block.Text -> block.text
                    is Block.Thinking -> block.text
                    is Block.Error -> block.text
                    is Block.Tool -> listOfNotNull(block.name, block.input, block.output).joinToString(" ")
                }
                if (piece.isBlank()) continue
                if (length > 0) append(' ')
                append(piece)
                if (length >= limit) return@buildString
            }
        }
    }.take(limit)

    fun workspaceEntriesJson(entries: List<WorkspaceEntry>, root: File): String =
        entries.joinToString(prefix = "[", postfix = "]") { entry ->
            buildJsonObject {
                put("name", entry.name)
                put("path", WorkspaceFiles.relativePath(root, entry.file))
                put("kind", entry.kind.name)
                put("size", entry.size)
                put("modifiedAt", entry.modifiedAt)
                put("isDirectory", entry.isDirectory)
            }.toString()
        }

    /** §6.6 `workspacesJson`: the switchable spaces plus which one is mounted at /work. */
    fun workspacesJson(activeId: String, spaces: List<WorkspaceInfo>): String = buildJsonObject {
        put("activeId", activeId)
        put("spaces", buildJsonArray {
            spaces.forEach { space ->
                add(buildJsonObject {
                    put("id", space.id)
                    put("name", space.name)
                    put("createdAt", space.createdAt)
                })
            }
        })
    }.toString()

    fun providersJson(presets: List<ProviderPreset>): String =        presets.joinToString(prefix = "[", postfix = "]") { preset ->
            buildJsonObject {
                put("id", preset.id)
                put("label", preset.label)
                put("flavor", preset.flavor.name)
                put("baseUrl", preset.baseUrl)
                put("authStyle", preset.authStyle.name)
                put("model", preset.model)
                put("hint", preset.hint)
            }.toString()
        }

    fun releaseJson(release: AppRelease, currentVersion: String): String = buildJsonObject {
        put("tag", release.tag)
        put("url", release.url)
        put("publishedAt", release.publishedAt)
        put("status", ReleaseCheck.status(currentVersion, release.tag).name)
        put("currentVersion", currentVersion)
    }.toString()

    fun stringListJson(values: List<String>): String =
        buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }.toString()

    /** §6.5 `modelDetailsJson`; labels and metadata come from the endpoint verbatim. */
    fun modelDetailsJson(details: List<ModelCatalog.ModelInfo>): String =
        buildJsonArray {
            details.forEach { model ->
                add(buildJsonObject {
                    put("id", model.id)
                    put("displayName", model.displayName)
                    put("contextWindow", model.contextWindow)
                    put("maxOutputTokens", model.maxOutputTokens)
                    put("maxInputTokens", model.maxInputTokens)
                    put("ownedBy", model.ownedBy)
                    put("reasoning", model.reasoning)
                    put("label", model.label)
                    put("reasoningLevels", stringArray(model.reasoningLevels))
                    put("modalities", stringArray(model.modalities))
                    put("supportedParameters", stringArray(model.supportedParameters))
                })
            }
        }.toString()

    private fun stringArray(values: List<String>) =
        buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }

    /** §4 `engineJson`; `engineLabel` is derived here because it needs `settings`. */
    fun engineJson(state: EngineManager.State, settings: AppSettings): String = buildJsonObject {
        put("phase", state.phase.name)
        put("stage", state.stage)
        put("detail", state.detail)
        put("fraction", state.fraction)
        put("installedVersion", state.installedVersion)
        put("latestVersion", state.latestVersion)
        put("arch", state.arch)
        put("jailPath", state.jailPath)
        put("rootMode", state.rootMode)
        put("rootGranted", state.rootGranted)
        put("chrootAvailable", state.chrootAvailable)
        put("prootAvailable", state.prootAvailable)
        put("error", state.error)
        put("selfTest", state.selfTest)
        put("ready", state.ready)
        put("engineLabel", state.engineLabel(settings.engineMode))
    }.toString()

    /** §4 `labelsJson`: display strings derived in Kotlin so Dart never re-derives them. */
    fun labelsJson(settings: AppSettings): String = buildJsonObject {
        put("composer", composerModelLabel(settings))
        put("chat", chatModelLabel(settings))
        put("short", shortModelLabel(settings))
        put("tierSummary", activeTierSummary(settings))
        putJsonObject("tierMapping") {
            ModelTier.entries.forEach { put(it.name, tierMappingLabel(settings, it)) }
        }
    }.toString()

    /**
     * §5 per-session event → channel map. [model] only feeds the derived `statusLine`.
     *
     * `statusLine` is computed from the raw status plus the running flag captured on
     * the event itself, so a slow collector cannot pair an old status with a new turn.
     */
    fun chatEvent(event: ChatEvent, model: String): Map<String, Any?> = when (event) {
        is ChatEvent.Messages -> mapOf("type" to "messages", "messagesJson" to messagesJson(event.messages))
        is ChatEvent.Delta -> mapOf(
            "type" to "delta",
            "messageId" to event.messageId,
            "block" to event.block.wire,
            "append" to event.append,
        )
        is ChatEvent.ToolUse -> mapOf(
            "type" to "tool_use",
            "messageId" to event.messageId,
            "id" to event.id,
            "name" to event.name,
            "inputJson" to event.inputJson,
        )
        is ChatEvent.ToolResult -> mapOf(
            "type" to "tool_result",
            "messageId" to event.messageId,
            "id" to event.id,
            "output" to event.output,
            "isError" to event.isError,
        )
        is ChatEvent.ErrorBlock -> mapOf(
            "type" to "error_block",
            "messageId" to event.messageId,
            "text" to event.text,
        )
        is ChatEvent.Status -> mapOf(
            "type" to "status",
            "status" to event.status,
            "statusLine" to chatStatusLine(event.running, event.status, model),
        )
        is ChatEvent.Running -> mapOf("type" to "running", "running" to event.running)
        is ChatEvent.Title -> mapOf("type" to "title", "title" to event.title)
        is ChatEvent.TitleBusy -> mapOf("type" to "titleBusy", "busy" to event.busy)
        is ChatEvent.TitleNotice -> mapOf("type" to "titleNotice", "notice" to event.notice)
        is ChatEvent.Permission -> mapOf(
            "type" to "permission",
            "requestId" to event.requestId,
            "toolName" to event.toolName,
            "inputJson" to event.inputJson,
            "title" to event.title,
            "description" to event.description,
            "expiresAt" to event.expiresAt,
        )
        is ChatEvent.PermissionResolved -> mapOf(
            "type" to "permissionResolved",
            "requestId" to event.requestId,
            "allowed" to event.allowed,
        )
    }

    // ------------------------------------------------------------ remote hosts

    /** §4/§6.9 `hostsJson`: paired hosts plus runtime state; never includes a token. */
    fun hostsJson(state: RemoteHostsState): String = buildJsonObject {
        put("defaultHostId", state.defaultHostId)
        put("hosts", buildJsonArray {
            state.hosts.forEach { host -> add(hostJson(host, state.runtime[host.id])) }
        })
    }.toString()

    fun hostJson(host: RemoteHostProfile, runtime: RemoteRuntime?): JsonElement = buildJsonObject {
        put("id", host.id)
        put("name", host.name)
        put("baseUrl", host.baseUrl)
        put("hostName", host.hostName)
        put("platform", host.platform)
        put("protocol", host.protocol)
        put("defaultCwd", host.defaultCwd)
        put("workspaceRoots", stringArray(host.workspaceRoots))
        put("lastSeenAt", host.lastSeenAt)
        put("phase", (runtime?.phase ?: RemotePhase.OFFLINE).name.lowercase())
        put("error", runtime?.error)
        put("hasAccessToken", host.accessClientId.isNotBlank())
        put("permissionMode", RemotePermissionModes.normalize(host.permissionMode))
        // A fresh health wins; the persisted value keeps the page correct while offline.
        put("allowBypassPermissions", runtime?.health?.allowBypassPermissions ?: host.allowBypassPermissions)
        val tunnel = runtime?.health?.tunnel ?: host.tunnel
        put("tunnel", tunnelJson(tunnel))
    }

    fun tunnelJson(status: RemoteTunnelStatus): JsonElement = buildJsonObject {
        put("mode", status.mode)
        put("state", status.state)
        put("url", status.url)
        put("error", status.error)
        put("changedAt", status.changedAt)
    }

    /** §6.9 `healthJson` for the host detail page and connection diagnostics. */
    fun healthJson(health: RemoteHealth): String = buildJsonObject {
        put("protocol", health.protocol)
        put("daemonVersion", health.daemonVersion)
        put("claudeVersion", health.claudeVersion)
        put("hostName", health.hostName)
        put("platform", health.platform)
        put("arch", health.arch)
        put("workspaceRoots", stringArray(health.workspaceRoots))
        put("defaultCwd", health.defaultCwd)
        put("defaultModel", health.defaultModel)
        put("permissionModes", stringArray(health.permissionModes))
        put("allowBypassPermissions", health.allowBypassPermissions)
        put("tunnel", tunnelJson(health.tunnel))
    }.toString()

    fun remoteEntriesJson(entries: List<RemoteFileEntry>): String =
        buildJsonArray { entries.forEach { add(remoteEntryJson(it)) } }.toString()

    fun remoteEntryJson(entry: RemoteFileEntry): JsonElement = buildJsonObject {
        put("name", entry.name)
        put("path", entry.path)
        put("isDir", entry.isDir)
        put("size", entry.size)
        put("mtimeMs", entry.mtimeMs)
    }

    fun permissionJson(permission: PendingPermission): JsonElement = buildJsonObject {
        put("requestId", permission.requestId)
        put("toolName", permission.toolName)
        put("inputJson", permission.inputJson)
        put("title", permission.title)
        put("description", permission.description)
        put("expiresAt", permission.expiresAt)
    }
}
