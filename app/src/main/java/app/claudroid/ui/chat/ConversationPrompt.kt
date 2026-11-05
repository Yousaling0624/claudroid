package app.claudroid.ui.chat

import app.claudroid.core.WebSources
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role

/**
 * Editing or regenerating a turn starts a fresh CLI session: the official CLI can
 * resume, but it has no rewind, so the old session already contains the answer we
 * want to replace. To keep the model's context, the local transcript before the
 * edited message is replayed as a compact preamble and the new text is the prompt.
 *
 * Pure and unit-tested: prompt shape is behavior, not presentation.
 */
internal object ConversationPrompt {

    /** Soft budget for the replayed context; the newest turns are kept first. */
    const val MAX_CHARS = 12_000

    private const val MAX_TURN_CHARS = 4_000

    fun build(history: List<ChatMessage>, newText: String): String {
        val turns = history.mapNotNull { message ->
            val text = message.blocks
                .filterIsInstance<Block.Text>()
                .joinToString("\n") { WebSources.stripTrailingSources(it.text) }
                .trim()
            if (text.isEmpty()) return@mapNotNull null
            val speaker = if (message.role == Role.USER) "用户" else "助手"
            speaker to clamp(text)
        }
        if (turns.isEmpty()) return newText

        // Walk backwards so a long transcript drops its oldest context, not the turns
        // immediately preceding the edited message.
        val kept = ArrayDeque<Pair<String, String>>()
        var total = 0
        for (turn in turns.asReversed()) {
            val cost = turn.second.length + 8
            if (kept.isNotEmpty() && total + cost > MAX_CHARS) break
            kept.addFirst(turn)
            total += cost
        }

        return buildString {
            appendLine("以下是此前的对话记录，请延续上下文：")
            appendLine()
            kept.forEach { (speaker, text) ->
                appendLine("$speaker：")
                appendLine(text)
                appendLine()
            }
            appendLine("现在请回答下面的新消息：")
            appendLine()
            append(newText)
        }
    }

    private fun clamp(text: String): String =
        if (text.length <= MAX_TURN_CHARS) text
        else text.take(MAX_TURN_CHARS) + "\n…（已截断）"
}
