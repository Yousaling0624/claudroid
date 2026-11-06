package app.claudroid.ui.chat

import app.claudroid.data.ChatMessage

/**
 * Incremental view of a conversation, published next to [ChatController.messages].
 *
 * Compose can re-render off the full `StateFlow<List<ChatMessage>>` for free (it is
 * in-process). A platform channel cannot: serialising a long transcript on every
 * token would stall the main thread, so the controller also emits these events and
 * the bridge forwards them one by one. `_messages` stays the single source of truth —
 * every event here is a notification, never state of its own.
 *
 * The wire names live in `app.claudroid.bridge.BridgeWire` (see docs/BRIDGE_CONTRACT.md §5).
 */
sealed interface ChatEvent {

    /** Full transcript replacement; the receiver must discard its local copy. */
    data class Messages(val messages: List<ChatMessage>) : ChatEvent

    /**
     * Streaming hot path: append [append] to an **existing** text/thinking block of
     * [messageId]. The first chunk of a new block is structural and goes out as
     * [Messages] instead, so a receiver never has to invent a block.
     */
    data class Delta(val messageId: String, val block: DeltaBlock, val append: String) : ChatEvent

    /** A tool card appeared (`running = true` on the receiver). */
    data class ToolUse(val messageId: String, val id: String, val name: String, val inputJson: String) : ChatEvent

    /** A tool card finished; [output] is null when the CLI never reported one. */
    data class ToolResult(
        val messageId: String,
        val id: String,
        val output: String?,
        val isError: Boolean,
    ) : ChatEvent

    /** A `Block.Error` was appended to [messageId]. */
    data class ErrorBlock(val messageId: String, val text: String) : ChatEvent

    /**
     * Raw status text. [running] is captured at emission time because the derived
     * `statusLine` (`chatStatusLine(running, raw, model)`) depends on it and a slow
     * collector could otherwise read a newer flag.
     */
    data class Status(val status: String, val running: Boolean) : ChatEvent

    /** A turn started or ended. */
    data class Running(val running: Boolean) : ChatEvent

    /** Conversation title changed (auto summary, rename, or reset). */
    data class Title(val title: String) : ChatEvent

    /** Title summarisation in progress. */
    data class TitleBusy(val busy: Boolean) : ChatEvent

    /** One-shot user-facing notice; the receiver clears it with `chat.consumeTitleNotice`. */
    data class TitleNotice(val notice: String?) : ChatEvent
}

/** Which text block a [ChatEvent.Delta] appends to. [wire] is the §5 channel value. */
enum class DeltaBlock(val wire: String) {
    TEXT("text"),
    THINKING("thinking"),
}
