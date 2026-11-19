package app.claudroid.bridge

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppRelease
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.ProviderPreset
import app.claudroid.data.Role
import app.claudroid.core.WorkspaceFiles
import app.claudroid.core.WorkspaceInfo
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.chat.ChatEvent
import app.claudroid.chat.DeltaBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the wire format of docs/BRIDGE_CONTRACT.md §3 and §5. The Dart side decodes
 * these names literally, so a rename here must break a test, not the UI.
 */
class BridgeWireTest {

    @get:Rule
    val temp = TemporaryFolder()

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
        model = "Opus · claude-opus-4-5",
    )

    @Test
    fun `chat message round-trips through the shared json config`() {
        val encoded = BridgeWire.messageJson(richMessage)
        val decoded = BridgeJson.json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(richMessage, decoded)
    }

    @Test
    fun `assistant model label is part of the wire format`() {
        val encoded = BridgeWire.messageJson(richMessage)
        assertTrue(encoded.contains("\"model\":\"Opus · claude-opus-4-5\""))
        // Older records omit the field entirely; decoding keeps it null rather
        // than failing, so the frontend can fall back to the live label.
        val legacy = encoded.replace(",\"model\":\"Opus · claude-opus-4-5\"", "")
        val decoded = BridgeJson.json.decodeFromString(ChatMessage.serializer(), legacy)
        assertNull(decoded.model)
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
    fun `session list carries the drawer summary fields`() {
        val used = ChatSession(
            id = "s1",
            title = "t",
            messages = listOf(richMessage),
        )
        val empty = ChatSession(id = "s2", title = "empty")
        val json = BridgeWire.sessionsJson(listOf(used, empty))
        assertTrue(json.contains("\"hasContent\":true"))
        assertTrue(json.contains("\"hasContent\":false"))
        assertTrue(json.contains("\"preview\":"))
        assertTrue(json.contains("\"searchText\":"))
        assertFalse("searchText must not leak the tool JSON verbatim", json.contains("\"messages\":"))
    }

    @Test
    fun `search text walks newest messages first and respects the budget`() {
        val session = ChatSession(
            id = "s",
            messages = listOf(
                ChatMessage(id = "old", role = Role.USER, blocks = listOf(Block.Text("older words"))),
                ChatMessage(id = "new", role = Role.ASSISTANT, blocks = listOf(Block.Text("newest words"))),
            ),
        )
        val text = BridgeWire.searchText(session)
        assertTrue(text.indexOf("newest") < text.indexOf("older"))
        assertTrue(BridgeWire.searchText(session, limit = 10).length <= 10)
    }

    @Test
    fun `workspaces json carries the active id and every switchable space`() {
        val json = BridgeWire.workspacesJson(
            activeId = "ws-2",
            spaces = listOf(
                WorkspaceInfo("default", "默认工作区", 0),
                WorkspaceInfo("ws-2", "项目 A", 123),
            ),
        )
        assertTrue(json.contains("\"activeId\":\"ws-2\""))
        assertTrue(json.contains("\"id\":\"default\""))
        assertTrue(json.contains("\"name\":\"项目 A\""))
        assertTrue(json.contains("\"createdAt\":123"))
    }

    @Test
    fun `model details json carries the picker metadata`() {
        val json = BridgeWire.modelDetailsJson(
            listOf(
                ModelCatalog.ModelInfo(
                    id = "auto/dfmodel",
                    displayName = "DeepSeek-Flash",
                    contextWindow = 1_000_000,
                    maxOutputTokens = 128_000,
                    ownedBy = "qoder-cn",
                    reasoning = true,
                    reasoningLevels = listOf("low", "high"),
                    label = "routing",
                    modalities = listOf("text", "image"),
                    supportedParameters = listOf("tools"),
                ),
            ),
        )
        assertTrue(json.contains("\"id\":\"auto/dfmodel\""))
        assertTrue(json.contains("\"displayName\":\"DeepSeek-Flash\""))
        assertTrue(json.contains("\"contextWindow\":1000000"))
        assertTrue(json.contains("\"reasoning\":true"))
        assertTrue(json.contains("\"reasoningLevels\":[\"low\",\"high\"]"))
        assertTrue(json.contains("\"modalities\":[\"text\",\"image\"]"))
    }

    @Test
    fun `providers json keeps the preset fields the picker renders`() {
        val json = BridgeWire.providersJson(
            listOf(
                ProviderPreset(
                    id = "x",
                    label = "X",
                    flavor = ApiFlavor.OPENAI,
                    baseUrl = "https://example.invalid/v1",
                    authStyle = AuthStyle.BEARER,
                    model = "m",
                    hint = "h",
                ),
            ),
        )
        assertTrue(json.contains("\"id\":\"x\""))
        assertTrue(json.contains("\"flavor\":\"OPENAI\""))
        assertTrue(json.contains("\"authStyle\":\"BEARER\""))
        assertTrue(json.contains("\"baseUrl\":\"https://example.invalid/v1\""))
    }

    @Test
    fun `release json distinguishes update dev and current builds`() {
        val release = AppRelease(tag = "0.1.9", url = "https://example.invalid")
        assertTrue(
            BridgeWire.releaseJson(release, currentVersion = "0.1.8")
                .contains("\"status\":\"UPDATE_AVAILABLE\""),
        )
        assertTrue(
            BridgeWire.releaseJson(release, currentVersion = "0.1.10")
                .contains("\"status\":\"DEV_BUILD\""),
        )
        assertTrue(
            BridgeWire.releaseJson(release, currentVersion = "0.1.9")
                .contains("\"status\":\"UP_TO_DATE\""),
        )
    }

    @Test
    fun `workspace entries carry root-relative paths`() {
        val root = temp.newFolder("workspace")
        File(root, "docs").mkdirs()
        File(root, "note.md").writeText("# hi")
        val json = BridgeWire.workspaceEntriesJson(WorkspaceFiles.list(root), root)
        assertTrue(json.contains("\"path\":\"docs\""))
        assertTrue(json.contains("\"path\":\"note.md\""))
        assertTrue(json.contains("\"kind\":\"MARKDOWN\""))
        assertTrue(json.contains("\"isDirectory\":true"))
        assertTrue(json.contains("\"isDirectory\":false"))
    }

    @Test
    fun `settings json round-trips with the contract config`() {
        val settings = AppSettings(apiKey = "secret", baseUrl = "http://127.0.0.1:1")
        val encoded = BridgeWire.settingsJson(settings)
        assertEquals(settings, BridgeWire.decodeSettings(encoded))
        assertTrue("encodeDefaults must keep defaulted fields", encoded.contains("\"maxTurns\""))
    }
}
