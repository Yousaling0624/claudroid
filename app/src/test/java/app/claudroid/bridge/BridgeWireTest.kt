package app.claudroid.bridge

import app.claudroid.data.AppSettings
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.Role
import app.claudroid.ui.chat.ChatEvent
import app.claudroid.ui.chat.DeltaBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire format of docs/BRIDGE_CONTRACT.md §3 and §5. The Dart side decodes
 * these names literally, so a rename here must break a test, not the UI.
 */
class BridgeWireTest {

    private val richMessage = ChatMessage(
        id = "m1",
        role = Role.ASSISTANT,
        blocks = listOf(
            Block.Text("hi"),
            Block.Thinking("hmm"),
            Block.Tool(id = "t1", name = "Bash", input = "{}", output = "ok", isError = false, running = false),
            Block.Error("boom"),
        ),
        ts = 1_700_000_000_000L,
        costUsd = 0.01,
        durationMs = 1234L,
        streaming = false,
    )

    @Test
    fun `chat message round-trips through the shared json config`() {
        val encoded = BridgeWire.messageJson(richMessage)
        val decoded = BridgeJson.json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(richMessage, decoded)
    }

    @Test
    fun `block discriminator is type with the contract names`() {
        val encoded = BridgeWire.messagesJson(listOf(richMessage))
        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"type\":\"thinking\""))
        assertTrue(encoded.contains("\"type\":\"tool\""))
        assertTrue(encoded.contains("\"type\":\"error\""))
        assertTrue(encoded.contains("\"role\":\"ASSISTANT\""))
    }

    @Test
    fun `session list never carries transcripts`() {
        val json = BridgeWire.sessionsJson(listOf(ChatSession(id = "s1", title = "t", messages = listOf(richMessage))))
        assertFalse("messages must stay out of the session list", json.contains("\"messages\""))
        assertTrue(json.startsWith("["))
        assertTrue(json.contains("\"id\":\"s1\""))
    }

    @Test
    fun `messages event carries a full transcript`() {
        val map = BridgeWire.chatEvent(ChatEvent.Messages(listOf(richMessage)), model = "m")
        assertEquals("messages", map["type"])
        assertEquals(BridgeWire.messagesJson(listOf(richMessage)), map["messagesJson"])
    }

    @Test
    fun `delta maps to the contract event names`() {
        val text = BridgeWire.chatEvent(ChatEvent.Delta("m1", DeltaBlock.TEXT, "a"), model = "m")
        assertEquals("delta", text["type"])
        assertEquals("m1", text["messageId"])
        assertEquals("text", text["block"])
        assertEquals("a", text["append"])

        val thinking = BridgeWire.chatEvent(ChatEvent.Delta("m1", DeltaBlock.THINKING, "b"), model = "m")
        assertEquals("thinking", thinking["block"])
    }

    @Test
    fun `status event carries both raw text and the derived line`() {
        val idle = BridgeWire.chatEvent(ChatEvent.Status("", running = false), model = "auto/x")
        assertEquals("status", idle["type"])
        assertEquals("auto/x", idle["statusLine"])

        // Diagnostics must not reach the 11sp subtitle.
        val diagnostic = BridgeWire.chatEvent(
            ChatEvent.Status("""[claude-code:unrecognized_model] {"a":1}""", running = true),
            model = "auto/x",
        )
        assertEquals("正在回复…", diagnostic["statusLine"])
    }

    @Test
    fun `tool events keep their contract fields`() {
        val use = BridgeWire.chatEvent(ChatEvent.ToolUse("m1", "t1", "Bash", "{}"), model = "m")
        assertEquals("tool_use", use["type"])
        assertEquals("t1", use["id"])
        assertEquals("{}", use["inputJson"])

        val result = BridgeWire.chatEvent(ChatEvent.ToolResult("m1", "t1", null, true), model = "m")
        assertEquals("tool_result", result["type"])
        assertNull(result["output"])
        assertEquals(true, result["isError"])
    }

    @Test
    fun `title events use the busy and notice field names`() {
        assertEquals(true, BridgeWire.chatEvent(ChatEvent.TitleBusy(true), model = "m")["busy"])
        assertEquals("x", BridgeWire.chatEvent(ChatEvent.Title("x"), model = "m")["title"])
        assertEquals("n", BridgeWire.chatEvent(ChatEvent.TitleNotice("n"), model = "m")["notice"])
        assertNull(BridgeWire.chatEvent(ChatEvent.TitleNotice(null), model = "m")["notice"])
        assertEquals(true, BridgeWire.chatEvent(ChatEvent.Running(true), model = "m")["running"])
    }

    @Test
    fun `settings json round-trips with the contract config`() {
        val settings = AppSettings(apiKey = "secret", baseUrl = "http://127.0.0.1:1")
        val encoded = BridgeWire.settingsJson(settings)
        assertEquals(settings, BridgeWire.decodeSettings(encoded))
        assertTrue("encodeDefaults must keep defaulted fields", encoded.contains("\"maxTurns\""))
    }
}
