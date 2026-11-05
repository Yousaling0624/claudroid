package app.claudroid.engine

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing / request-shaping tests for [TitleGenerator]. No sockets: real upstream
 * response samples are fed straight into the internal functions.
 */
class TitleGeneratorTest {

    private val anthropic = AppSettings(apiFlavor = ApiFlavor.ANTHROPIC)
    private val openai = AppSettings(apiFlavor = ApiFlavor.OPENAI)

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parses an anthropic content array`() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"修复登录崩溃"}],
             "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        assertEquals("修复登录崩溃", TitleGenerator.parseTitle(body, anthropic))
    }

    @Test
    fun `parses an anthropic content string`() {
        val body = """{"content":"会话标题","unknown":{"nested":[1,2,3]}}"""
        assertEquals("会话标题", TitleGenerator.parseTitle(body, anthropic))
    }

    @Test
    fun `skips thinking blocks when parsing anthropic content`() {
        val body = """
            {"content":[
              {"type":"thinking","thinking":"让我想想……"},
              {"type":"text","text":"优化启动速度"}
            ]}
        """.trimIndent()
        assertEquals("优化启动速度", TitleGenerator.parseTitle(body, anthropic))
    }

    @Test
    fun `parses an openai string content`() {
        val body = """
            {"id":"c1","choices":[{"index":0,
              "message":{"role":"assistant","content":"数据库迁移方案"},
              "finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":7}}
        """.trimIndent()
        assertEquals("数据库迁移方案", TitleGenerator.parseTitle(body, openai))
    }

    @Test
    fun `parses an openai content array`() {
        val body = """
            {"choices":[{"message":{"content":[
              {"type":"text","text":"网络超时"},
              {"type":"text","text":"重试策略"}
            ]}}]}
        """.trimIndent()
        assertEquals("网络超时重试策略", TitleGenerator.parseTitle(body, openai))
    }

    @Test
    fun `unknown fields and non-json input never throw`() {
        assertEquals(
            "正常标题",
            TitleGenerator.parseTitle("""{"foo":1,"content":"正常标题","bar":[{}]}""", anthropic),
        )
        assertNull(TitleGenerator.parseTitle("not json at all", anthropic))
        assertNull(TitleGenerator.parseTitle("", anthropic))
        assertNull(TitleGenerator.parseTitle("   ", openai))
        assertNull(TitleGenerator.parseTitle("{}", anthropic))
        assertNull(TitleGenerator.parseTitle("{}", openai))
        assertNull(TitleGenerator.parseTitle("[]", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"content":null}""", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"choices":[{}]}""", openai))
        assertNull(TitleGenerator.parseTitle("""{"choices":[{"message":{"content":null}}]}""", openai))
    }

    @Test
    fun `empty and whitespace responses return null`() {
        assertNull(TitleGenerator.parseTitle("""{"content":""}""", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"content":"   "}""", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"content":[]}""", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"content":[{"type":"text","text":null}]}""", anthropic))
        assertNull(TitleGenerator.parseTitle("""{"choices":[{"message":{"content":""}}]}""", openai))
        assertNull(
            TitleGenerator.parseTitle(
                """{"choices":[{"message":{"content":[{"type":"text","text":null}]}}]}""",
                openai,
            ),
        )
    }

    // ------------------------------------------------------------------ sanitizing

    @Test
    fun `strips the title label prefix`() {
        assertEquals("登录崩溃", TitleGenerator.sanitizeTitle("标题：登录崩溃"))
        assertEquals("登录崩溃", TitleGenerator.sanitizeTitle("标题: 登录崩溃"))
        assertEquals("登录崩溃", TitleGenerator.sanitizeTitle("标题 登录崩溃"))
        assertEquals("登录崩溃", TitleGenerator.sanitizeTitle("标题：标题：登录崩溃"))
        assertEquals("登录崩溃", TitleGenerator.sanitizeTitle("标题：「标题：登录崩溃」"))
        assertEquals("网络问题", TitleGenerator.sanitizeTitle("Title: 网络问题"))
        // A real title that merely starts with 标题 must not lose its first characters.
        assertEquals("标题栏适配", TitleGenerator.sanitizeTitle("标题栏适配"))
    }

    @Test
    fun `strips quotes and book title marks`() {
        assertEquals("性能优化", TitleGenerator.sanitizeTitle("「性能优化」"))
        assertEquals("性能优化", TitleGenerator.sanitizeTitle("《性能优化》"))
        assertEquals("性能优化", TitleGenerator.sanitizeTitle("\"性能优化\""))
        assertEquals("性能优化", TitleGenerator.sanitizeTitle("'性能优化'"))
        assertEquals("性能优化", TitleGenerator.sanitizeTitle("“性能优化”"))
    }

    @Test
    fun `strips trailing punctuation and markdown wrappers`() {
        assertEquals("缓存策略", TitleGenerator.sanitizeTitle("缓存策略。"))
        assertEquals("缓存策略", TitleGenerator.sanitizeTitle("缓存策略!!!"))
        assertEquals("缓存策略", TitleGenerator.sanitizeTitle("**缓存策略**"))
        assertEquals("缓存策略", TitleGenerator.sanitizeTitle("`缓存策略`"))
    }

    @Test
    fun `takes the first non-empty line`() {
        assertEquals("第一行标题", TitleGenerator.sanitizeTitle("\n\n  第一行标题\n这是解释文字\n"))
    }

    @Test
    fun `collapses whitespace within the chosen line`() {
        // Only the first non-empty line is kept, so whitespace is collapsed inside it.
        assertEquals("修复 登录 崩溃", TitleGenerator.sanitizeTitle("修复 \t  登录   崩溃"))
        assertEquals("第一行", TitleGenerator.sanitizeTitle("第一行\n第二行"))
    }

    @Test
    fun `caps the title at 24 characters`() {
        val long = "这是一个非常非常非常非常非常非常长的标题"
        val result = TitleGenerator.sanitizeTitle(long)
        assertTrue("result was $result", result!!.length <= 24)
        assertEquals(long.take(24), result)
    }

    @Test
    fun `blank sanitizer input returns null`() {
        assertNull(TitleGenerator.sanitizeTitle(null))
        assertNull(TitleGenerator.sanitizeTitle(""))
        assertNull(TitleGenerator.sanitizeTitle("   \n  "))
        assertNull(TitleGenerator.sanitizeTitle("标题：  。"))
        assertNull(TitleGenerator.sanitizeTitle("《》"))
    }

    // ------------------------------------------------------------------ request shaping

    @Test
    fun `anthropic body uses haiku mapping system and a single user message`() {
        val body = TitleGenerator.anthropicBody("gateway-haiku", "Q")
        assertEquals("gateway-haiku", body["model"]!!.jsonPrimitive.content)
        assertEquals(64, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("14个汉字"))
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("不要"))
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Q", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai body never forces streaming`() {
        val body = TitleGenerator.openAiBody("gateway-haiku", "Q")
        assertEquals("gateway-haiku", body["model"]!!.jsonPrimitive.content)
        assertEquals(64, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(body["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt is truncated to the agreed limits`() {
        val user = "用".repeat(3_000)
        val assistant = "答".repeat(6_000)
        val prompt = TitleGenerator.buildPrompt(user, assistant)
        assertEquals(2_000, prompt.substringAfter("用户问题：\n").substringBefore("\n\n助手回答：").length)
        assertEquals(4_000, prompt.substringAfter("助手回答：\n").length)
    }

    @Test
    fun `title model prefers the haiku mapping and stays cheap on official anthropic`() {
        assertEquals(
            "gateway-haiku",
            TitleGenerator.titleModel(anthropic.copy(haikuModel = "gateway-haiku", model = "gateway-main")),
        )
        // Official Anthropic with no mapping: use Haiku instead of the main model.
        assertEquals(
            "claude-haiku-5-5",
            TitleGenerator.titleModel(
                anthropic.copy(baseUrl = "https://api.anthropic.com", model = "claude-sonnet-5-5"),
            ),
        )
        // Third-party / local gateways keep their configured model: a Haiku id may not exist.
        assertEquals(
            "deepseek-chat",
            TitleGenerator.titleModel(
                anthropic.copy(baseUrl = "https://gateway.example.com", model = "deepseek-chat"),
            ),
        )
        // OpenAI flavor never assumes an Anthropic id.
        assertEquals(
            "deepseek-chat",
            TitleGenerator.titleModel(openai.copy(model = "deepseek-chat")),
        )
    }

    @Test
    fun `auth headers follow authStyle and never emit an empty bearer`() {
        val key = "sk-secret"
        assertEquals(
            mapOf("x-api-key" to key),
            TitleGenerator.authHeaders(AppSettings(apiKey = key, authStyle = AuthStyle.API_KEY)),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer $key"),
            TitleGenerator.authHeaders(AppSettings(apiKey = key, authStyle = AuthStyle.BEARER)),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer $key", "x-api-key" to key),
            TitleGenerator.authHeaders(AppSettings(apiKey = key, authStyle = AuthStyle.BOTH)),
        )
        // Local endpoints have no key: no empty Authorization header may be sent.
        assertTrue(TitleGenerator.authHeaders(AppSettings(apiKey = "")).isEmpty())
        assertTrue(
            TitleGenerator.authHeaders(
                AppSettings(apiKey = "  ", authStyle = AuthStyle.BEARER),
            ).isEmpty(),
        )
    }

    @Test
    fun `sanitizing is applied to parsed answers`() {
        val body = """{"content":[{"type":"text","text":"标题：《修复登录崩溃》。"}]}"""
        assertEquals("修复登录崩溃", TitleGenerator.parseTitle(body, anthropic))
    }
}
