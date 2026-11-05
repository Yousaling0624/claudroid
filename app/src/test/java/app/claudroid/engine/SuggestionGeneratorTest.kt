package app.claudroid.engine

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure request-shaping / parsing tests for [SuggestionGenerator]. No sockets: real
 * upstream response samples are fed into the internal functions, and the only calls to
 * [SuggestionGenerator.generate] use settings that bail out before any HTTP request.
 */
class SuggestionGeneratorTest {

    private val anthropic = AppSettings(apiFlavor = ApiFlavor.ANTHROPIC)
    private val openai = AppSettings(apiFlavor = ApiFlavor.OPENAI)

    private fun parse(raw: String, settings: AppSettings = anthropic, count: Int = 3) =
        SuggestionGenerator.parseSuggestions(TitleGenerator.responseText(raw, settings), count)

    /** Wraps model text in an Anthropic envelope, escaping it as a real JSON string. */
    private fun anthropicText(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"content":[{"type":"text","text":"$escaped"}]}"""
    }

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parses a plain json array from an anthropic response`() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"[\"优化启动速度\",\"修复深色模式\",\"解释这个函数\"]"}],
             "stop_reason":"end_turn"}
        """.trimIndent()
        assertEquals(
            listOf("优化启动速度", "修复深色模式", "解释这个函数"),
            parse(body),
        )
    }

    @Test
    fun `parses a fenced json array from an openai response`() {
        val body = """
            {"choices":[{"message":{"content":"```json\n[\"优化启动速度\",\"修复深色模式\"]\n```"}}]}
        """.trimIndent()
        assertEquals(listOf("优化启动速度", "修复深色模式"), parse(body, openai))
    }

    @Test
    fun `parses json surrounded by prose`() {
        val text = "以下是几条建议：\n[\"优化启动速度\",\"修复深色模式\"]\n希望有帮助。"
        assertEquals(listOf("优化启动速度", "修复深色模式"), parse(anthropicText(text)))
    }

    @Test
    fun `skips thinking blocks and reads the text block`() {
        val body = """
            {"content":[
              {"type":"thinking","thinking":"先想想……"},
              {"type":"text","text":"[\"优化启动速度\",\"修复深色模式\"]"}
            ]}
        """.trimIndent()
        assertEquals(listOf("优化启动速度", "修复深色模式"), parse(body))
    }

    @Test
    fun `falls back to a numbered list`() {
        val body = """
            {"content":[{"type":"text","text":"1. 优化启动速度\n2. 修复深色模式\n3. 解释这个函数"}]}
        """.trimIndent()
        assertEquals(
            listOf("优化启动速度", "修复深色模式", "解释这个函数"),
            parse(body),
        )
    }

    @Test
    fun `falls back to bullets and strips dirty quotes`() {
        val text = """
            ```
            - “优化启动速度”
            • '修复深色模式。'
            * 「解释这个函数」
            ```
        """.trimIndent()
        assertEquals(
            listOf("优化启动速度", "修复深色模式", "解释这个函数"),
            parse(anthropicText(text)),
        )
    }

    @Test
    fun `recovers quoted items from a malformed array`() {
        val body = """{"content":[{"type":"text","text":"[\"优化启动速度\", \"修复深色模式\""}]}"""
        assertEquals(listOf("优化启动速度", "修复深色模式"), parse(body))
    }

    @Test
    fun `deduplicates and caps at the requested count`() {
        val body = """{"content":"[\"A\",\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]"}"""
        assertEquals(listOf("A", "B"), parse(body, count = 2))
        // The empty-screen chip row never needs more than a handful.
        assertEquals(5, parse(body, count = 10).size)
    }

    @Test
    fun `caps each suggestion at 32 characters`() {
        val long = "很长的输入建议".repeat(8)
        val body = """{"content":"[\"$long\",\"短建议\"]"}"""
        val result = parse(body)
        assertEquals(32, result[0].length)
        assertEquals(long.take(32), result[0])
        assertEquals("短建议", result[1])
    }

    @Test
    fun `blank and unparseable responses produce an empty list`() {
        assertTrue(SuggestionGenerator.parseSuggestions(null, 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("", 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("   ", 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("```json\n```", 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("[]", 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("[\"\"]", 3).isEmpty())
        assertTrue(SuggestionGenerator.parseSuggestions("[\"A\"]", 0).isEmpty())
    }

    @Test
    fun `cleaning handles labels marks and blank items`() {
        assertEquals("优化启动速度", SuggestionGenerator.cleanSuggestion("1. 优化启动速度"))
        assertEquals("优化启动速度", SuggestionGenerator.cleanSuggestion("（2）优化启动速度。"))
        assertEquals("优化启动速度", SuggestionGenerator.cleanSuggestion("\"优化启动速度\""))
        assertEquals("优化启动速度", SuggestionGenerator.cleanSuggestion("“优化启动速度”"))
        assertEquals("优化启动速度", SuggestionGenerator.cleanSuggestion("「优化启动速度」"))
        assertEquals("优化 启动 速度", SuggestionGenerator.cleanSuggestion("优化 \t  启动   速度"))
        assertNull(SuggestionGenerator.cleanSuggestion(""))
        assertNull(SuggestionGenerator.cleanSuggestion("  「」  "))
    }

    // ------------------------------------------------------------------ request shaping

    @Test
    fun `anthropic body uses the small model a short system prompt and one user message`() {
        val body = SuggestionGenerator.anthropicBody("gateway-haiku", 3, "P")
        assertEquals("gateway-haiku", body["model"]!!.jsonPrimitive.content)
        assertEquals(200, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() <= 200)
        val system = body["system"]!!.jsonPrimitive.content
        assertTrue(system, system.contains("JSON 字符串数组"))
        assertTrue(system, system.contains("3 条"))
        assertTrue(system, system.contains("不超过18个汉字"))
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("P", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai body never forces streaming and carries the system prompt`() {
        val body = SuggestionGenerator.openAiBody("gateway-haiku", 3, "P")
        assertEquals("gateway-haiku", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() <= 200)
        assertFalse(body["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt carries a changing seed`() {
        val first = SuggestionGenerator.buildPrompt(3, "", SuggestionGenerator.nextSeed())
        val second = SuggestionGenerator.buildPrompt(3, "", SuggestionGenerator.nextSeed())
        assertNotEquals(first, second)
        assertTrue(first.startsWith("随机种子："))
        assertTrue(SuggestionGenerator.buildPrompt(3, "", 42L).contains("随机种子：42"))
    }

    @Test
    fun `prompt includes and truncates the optional context`() {
        val prompt = SuggestionGenerator.buildPrompt(2, "上".repeat(2_000), 7L)
        assertTrue(prompt.contains("请生成 2 条"))
        assertTrue(prompt.contains("[context]"))
        assertEquals(1_000, prompt.substringAfter("[context]\n").length)
    }

    @Test
    fun `unconfigured settings return an empty list without a request`() {
        val result = runBlocking { SuggestionGenerator.generate(AppSettings(apiKey = "")) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `blank model returns an empty list without a request`() {
        val local = AppSettings(
            apiFlavor = ApiFlavor.OPENAI,
            baseUrl = "http://127.0.0.1:11434",
            apiKey = "",
            model = "",
            haikuModel = "",
        )
        assertTrue(runBlocking { SuggestionGenerator.generate(local) }.isEmpty())
        assertTrue(runBlocking { SuggestionGenerator.generate(AppSettings(), count = 0) }.isEmpty())
    }
}
