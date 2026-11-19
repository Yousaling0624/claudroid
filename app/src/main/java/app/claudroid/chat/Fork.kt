package app.claudroid.chat

import app.claudroid.data.ChatMessage

/**
 * Rules for branching a conversation at one message. Pure so the truncation and
 * title shape can be tested without a container or a disk.
 */
internal object Fork {

    /** Keeps a branch title drawer-friendly even when the source title is long. */
    const val MAX_TITLE_LENGTH = 40

    private const val DEFAULT_TITLE = "分支对话"

    /**
     * Everything up to and including [messageId], with a killed turn repaired so
     * the branch never opens with a spinner that can never finish.
     * `null` means the message is no longer in the transcript.
     */
    fun history(messages: List<ChatMessage>, messageId: String): List<ChatMessage>? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        return TurnRepair.repair(messages.take(index + 1)).first
    }

    fun title(source: String): String {
        val base = source.trim()
        if (base.isEmpty() || base == "新对话") return DEFAULT_TITLE
        val suffix = " · 分支"
        val room = (MAX_TITLE_LENGTH - suffix.length).coerceAtLeast(1)
        return base.take(room) + suffix
    }
}
