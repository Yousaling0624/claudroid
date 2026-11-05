package app.claudroid.ui.chat

import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPromptTest {

    private fun user(text: String) = ChatMessage(role = Role.USER, blocks = listOf(Block.Text(text)))
    private fun assistant(text: String) =
        ChatMessage(role = Role.ASSISTANT, blocks = listOf(Block.Text(text)))

    @Test
    fun `empty history is just the new message`() {
        assertEquals("新的问题", ConversationPrompt.build(emptyList(), "新的问题"))
    }

    @Test
    fun `history is replayed before the new message`() {
        val prompt = ConversationPrompt.build(
            listOf(user("第一个问题"), assistant("第一个回答")),
            "改过的问题",
        )

        assertTrue(prompt.contains("用户：\n第一个问题"))
        assertTrue(prompt.contains("助手：\n第一个回答"))
        assertTrue(prompt.endsWith("改过的问题"))
        assertTrue(prompt.indexOf("第一个回答") < prompt.indexOf("改过的问题"))
    }

    @Test
    fun `tool-only and error messages are skipped`() {
        val history = listOf(
            user("问题"),
            ChatMessage(role = Role.ASSISTANT, blocks = listOf(Block.Tool(id = "t", name = "Bash", input = "{}"))),
            ChatMessage(role = Role.SYSTEM, blocks = listOf(Block.Error("boom"))),
        )

        val prompt = ConversationPrompt.build(history, "继续")
        assertTrue(prompt.contains("问题"))
        assertFalse(prompt.contains("Bash"))
        assertFalse(prompt.contains("boom"))
    }

    @Test
    fun `trailing source lists do not pollute the replay`() {
        val history = listOf(
            user("查一下"),
            assistant("结论。\n\nSources:\n- [A](https://example.com)"),
        )

        val prompt = ConversationPrompt.build(history, "再来一次")
        assertTrue(prompt.contains("结论。"))
        assertFalse(prompt.contains("Sources:"))
        assertFalse(prompt.contains("example.com"))
    }

    @Test
    fun `very long history keeps the newest turns`() {
        val history = buildList {
            repeat(8) { index ->
                add(user("旧问题 $index " + "x".repeat(3_000)))
                add(assistant("旧回答 $index " + "y".repeat(3_000)))
            }
        }

        val prompt = ConversationPrompt.build(history, "新问题")
        assertTrue(prompt.contains("旧问题 7"))
        assertFalse(prompt.contains("旧问题 0"))
        assertTrue(prompt.length < ConversationPrompt.MAX_CHARS + 2_000)
    }
}
