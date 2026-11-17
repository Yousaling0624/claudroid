package app.claudroid.ui.chat

import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role

/**
 * Pure repair rules for a transcript whose turn was killed by process death. Kept
 * outside [ChatController] so the rules are testable without an Android container.
 */
object TurnRepair {
    const val INTERRUPTED = "上一次回复被中断（应用或引擎退出），可以重新发送。"

    /**
     * Closes every streaming message, fails tool cards that were still running, and
     * gives an assistant bubble that never received any content a visible explanation.
     *
     * Returns the repaired list and whether anything changed (so the caller only
     * persists when needed).
     */
    fun repair(messages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        var changed = false
        val repaired = messages.map { msg ->
            val blocks = msg.blocks.map { block ->
                if (block is Block.Tool && block.running) {
                    block.copy(
                        running = false,
                        isError = true,
                        output = block.output ?: "（任务中断）",
                    )
                } else {
                    block
                }
            }
            var result = msg.copy(streaming = false, blocks = blocks)
            if (msg.role == Role.ASSISTANT && msg.streaming &&
                blocks.none {
                    (it is Block.Text && it.text.isNotBlank()) ||
                        it is Block.Tool || it is Block.Error
                }
            ) {
                result = result.copy(blocks = blocks + Block.Error(INTERRUPTED))
            }
            if (result != msg) changed = true
            result
        }
        return repaired to changed
    }
}
