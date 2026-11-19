package app.claudroid.chat

import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkTest {

    private fun assistant(id: String, text: String, streaming: Boolean = false) =
        ChatMessage(id = id, role = Role.ASSISTANT, blocks = listOf(Block.Text(text)), streaming = streaming)

    private fun user(id: String, text: String) =
        ChatMessage(id = id, role = Role.USER, blocks = listOf(Block.Text(text)))

    @Test
    fun `history ends at the chosen message`() {
        val messages = listOf(user("u1", "一"), assistant("a1", "一答"), user("u2", "二"), assistant("a2", "二答"))
        val history = Fork.history(messages, "a1")
        assertEquals(listOf("u1", "a1"), history?.map { it.id })
    }

    @Test
    fun `forking a user turn keeps only the turns before it plus that message`() {
        val messages = listOf(user("u1", "一"), assistant("a1", "一答"))
        assertEquals(listOf("u1"), Fork.history(messages, "u1")?.map { it.id })
    }

    @Test
    fun `a message that no longer exists yields null`() {
        assertNull(Fork.history(listOf(user("u1", "一")), "missing"))
    }

    @Test
    fun `an interrupted answer is repaired instead of opening with a spinner`() {
        val messages = listOf(user("u1", "一"), assistant("a1", "", streaming = true))
        val history = Fork.history(messages, "a1")!!
        val last = history.last()
        assertEquals(false, last.streaming)
        // The empty text block stays; repair appends the explanation next to it.
        assertEquals(Block.Error(TurnRepair.INTERRUPTED), last.blocks.filterIsInstance<Block.Error>().single())
    }

    @Test
    fun `title appends a branch suffix`() {
        assertEquals("部署排错 · 分支", Fork.title("部署排错"))
    }

    @Test
    fun `default title falls back to a branch name`() {
        assertEquals("分支对话", Fork.title("新对话"))
        assertEquals("分支对话", Fork.title("   "))
    }

    @Test
    fun `long titles are clipped before the suffix is added`() {
        val title = Fork.title("这是一个非常非常长的会话标题".repeat(4))
        assertEquals(Fork.MAX_TITLE_LENGTH, title.length)
        assertEquals(true, title.endsWith(" · 分支"))
    }
}
