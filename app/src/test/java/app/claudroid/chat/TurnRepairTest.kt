package app.claudroid.chat

import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the crash-repair rules: an interrupted turn must never stay silent. */
class TurnRepairTest {

    @Test
    fun `empty streaming assistant gets a visible interruption note`() {
        val streaming = ChatMessage(role = Role.ASSISTANT, streaming = true)
        val (repaired, changed) = TurnRepair.repair(listOf(streaming))

        assertTrue(changed)
        val message = repaired.single()
        assertFalse(message.streaming)
        val error = message.blocks.single() as Block.Error
        assertEquals(TurnRepair.INTERRUPTED, error.text)
    }

    @Test
    fun `streaming assistant with text only closes, without an extra error`() {
        val streaming = ChatMessage(
            role = Role.ASSISTANT,
            blocks = listOf(Block.Text("已经输出了一半")),
            streaming = true,
        )
        val (repaired, changed) = TurnRepair.repair(listOf(streaming))

        assertTrue(changed)
        val message = repaired.single()
        assertFalse(message.streaming)
        assertEquals(listOf(Block.Text("已经输出了一半")), message.blocks)
    }

    @Test
    fun `running tool card becomes a failed interrupted card`() {
        val tool = Block.Tool(id = "t1", name = "Bash", input = "{}", running = true)
        val streaming = ChatMessage(
            role = Role.ASSISTANT,
            blocks = listOf(tool),
            streaming = true,
        )
        val (repaired, _) = TurnRepair.repair(listOf(streaming))

        val repairedTool = repaired.single().blocks.single() as Block.Tool
        assertFalse(repairedTool.running)
        assertTrue(repairedTool.isError)
        assertEquals("（任务中断）", repairedTool.output)
    }

    @Test
    fun `finished transcript is untouched`() {
        val user = ChatMessage(role = Role.USER, blocks = listOf(Block.Text("你好")))
        val assistant = ChatMessage(
            role = Role.ASSISTANT,
            blocks = listOf(Block.Text("你好！")),
            costUsd = 0.01,
            durationMs = 800,
        )
        val (repaired, changed) = TurnRepair.repair(listOf(user, assistant))

        assertFalse(changed)
        assertEquals(listOf(user, assistant), repaired)
    }
}
