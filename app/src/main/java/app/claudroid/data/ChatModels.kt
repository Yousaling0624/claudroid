package app.claudroid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

@Serializable
sealed interface Block {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Block

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : Block

    /** A tool call card: name + collapsible input/output. */
    @Serializable
    @SerialName("tool")
    data class Tool(
        val id: String,
        val name: String,
        val input: String,
        val output: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
    ) : Block

    @Serializable
    @SerialName("error")
    data class Error(val text: String) : Block
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val blocks: List<Block> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val streaming: Boolean = false,
)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** session_id reported by the CLI; used for `--resume`. */
    val claudeSessionId: String? = null,
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
) {
    val preview: String
        get() = messages.asReversed().firstOrNull { it.role == Role.ASSISTANT || it.role == Role.USER }
            ?.let { m -> m.blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text } }
            ?.takeIf { it.isNotBlank() } ?: "还没有消息"
}
