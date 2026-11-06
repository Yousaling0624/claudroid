package app.claudroid.bridge

import app.claudroid.data.AppSettings
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.ModelTier
import app.claudroid.engine.EngineManager
import app.claudroid.ui.chat.ChatEvent
import app.claudroid.ui.chat.chatStatusLine
import app.claudroid.ui.components.activeTierSummary
import app.claudroid.ui.components.chatModelLabel
import app.claudroid.ui.components.composerModelLabel
import app.claudroid.ui.components.shortModelLabel
import app.claudroid.ui.components.tierMappingLabel
import kotlinx.serialization.builtins.ListSerializer
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
    ): String = buildJsonObject {
        put("messages", BridgeJson.json.parseToJsonElement(messagesJson(messages)))
        put("title", title)
        put("titleBusy", titleBusy)
        put("titleNotice", titleNotice)
        put("running", running)
        put("status", status)
        put("statusLine", statusLine)
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
    }.toString()

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
    }
}
