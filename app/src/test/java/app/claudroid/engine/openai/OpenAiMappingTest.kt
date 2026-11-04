package app.claudroid.engine.openai

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `maps anthropic system user assistant and tool_result messages`() {
        val anthropic = obj(
            """
            {
              "model": "claude-sonnet-4-5",
              "max_tokens": 1024,
              "system": [{"type":"text","text":"You are an agent."}],
              "messages": [
                {"role":"user","content":[{"type":"text","text":"看一下模块列表"}]},
                {"role":"assistant","content":[
                    {"type":"text","text":"好的"},
                    {"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls /data/adb/modules"}}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"adblock\nhosts"}]}
                ]}
              ],
              "tools": [{"name":"Bash","description":"run","input_schema":{"type":"object","properties":{}}}]
            }
            """.trimIndent()
        )

        val out = OpenAiMapping.toChatRequest(anthropic, "group/auto-deepseek-flash")
        val messages = out["messages"]!!.jsonArray

        assertEquals("group/auto-deepseek-flash", out["model"]!!.jsonPrimitive.content)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are an agent.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)

        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("看一下模块列表", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("好的", assistant["content"]!!.jsonPrimitive.content)
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("toolu_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("Bash", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content.contains("ls /data/adb/modules"))

        // tool_result becomes its own role=tool message
        val toolMsg = messages[3].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("adblock\nhosts", toolMsg["content"]!!.jsonPrimitive.content)

        // tools are wrapped in the OpenAI function shape
        val tool = out["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("Bash", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streaming chunk with text and tool_calls becomes anthropic events`() {
        val translator = OpenAiMapping.StreamTranslator("group/auto-deepseek-flash")
        val frames = mutableListOf<String>()
        frames += translator.start()

        fun chunk(raw: String) {
            frames += translator.onChunk(obj(raw))
        }

        chunk("""{"choices":[{"index":0,"delta":{"role":"assistant","content":"先看看"}}]}""")
        chunk("""{"choices":[{"index":0,"delta":{"content":"目录"}}]}""")
        chunk("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Bash","arguments":"{\"command\":\"ls\"}"}}]}}]}""")
        chunk("""{"choices":[{"index":0,"finish_reason":"tool_calls"}]}""")
        frames += translator.finish()

        val all = frames.joinToString("")
        assertTrue("message_start", all.contains("event: message_start"))
        assertTrue("text block", all.contains("\"type\":\"text_delta\",\"text\":\"先看看\""))
        assertTrue("text block 2", all.contains("\"text\":\"目录\""))
        assertTrue("tool block", all.contains("\"type\":\"tool_use\""))
        assertTrue("tool name", all.contains("\"name\":\"Bash\""))
        assertTrue("args delta", all.contains("input_json_delta"))
        assertTrue("stop tool_use", all.contains("\"stop_reason\":\"tool_use\""))
        assertTrue("message_stop", all.contains("event: message_stop"))

        // indices must be sequential: text block 0, tool block 1
        assertTrue(all.contains("\"index\":0"))
        assertTrue(all.contains("\"index\":1"))
    }

    @Test
    fun `stream flag follows the incoming anthropic request`() {
        val anthropic = obj(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":false}"""
        )
        val sync = OpenAiMapping.toChatRequest(anthropic, "m", stream = false)
        assertEquals("false", sync["stream"]!!.jsonPrimitive.content)
        val streamed = OpenAiMapping.toChatRequest(anthropic, "m", stream = true)
        assertEquals("true", streamed["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `base64 images become openai image parts`() {
        val anthropic = obj(
            """
            {"model":"m","messages":[{"role":"user","content":[
              {"type":"text","text":"看这张图"},
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
            ]}]}
            """.trimIndent()
        )
        val out = OpenAiMapping.toChatRequest(anthropic, "m")
        val content = out["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `reasoning content becomes a thinking block`() {
        val openAi = obj(
            """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"先分析","content":"答案"},"finish_reason":"stop"}]}"""
        )
        val mapped = OpenAiMapping.nonStreamResponse(openAi, "m")
        val content = mapped["content"]!!.jsonArray
        assertEquals("thinking", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("先分析", content[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streaming reasoning is emitted before text and length maps to max_tokens`() {
        val translator = OpenAiMapping.StreamTranslator("m")
        val frames = mutableListOf<String>()
        frames += translator.start()
        frames += translator.onChunk(
            obj("""{"choices":[{"index":0,"delta":{"reasoning_content":"想一想"}}]}""")
        )
        frames += translator.onChunk(
            obj("""{"choices":[{"index":0,"delta":{"content":"答"}}]}""")
        )
        frames += translator.onChunk(
            obj("""{"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":3}}""")
        )
        frames += translator.onChunk(
            obj("""{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""")
        )
        frames += translator.finish()
        val all = frames.joinToString("")
        val thinkingAt = all.indexOf("\"type\":\"thinking\"")
        val textAt = all.indexOf("\"type\":\"text_delta\"")
        assertTrue("thinking block missing: $all", thinkingAt >= 0)
        assertTrue("thinking must precede text", textAt > thinkingAt)
        assertTrue("length must map to max_tokens", all.contains("\"stop_reason\":\"max_tokens\""))
        assertTrue("usage must be forwarded", all.contains("\"input_tokens\":9"))
    }

    @Test
    fun `tools are not reported when the model stopped at max tokens`() {
        val translator = OpenAiMapping.StreamTranslator("m")
        translator.start()
        translator.onChunk(
            obj("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"Bash","arguments":"{}"}}]}}]}""")
        )
        translator.onChunk(obj("""{"choices":[{"index":0,"finish_reason":"length"}]}"""))
        val all = translator.finish().joinToString("")
        // A truncated tool call is not a usable tool_use; the stop reason must win.
        assertTrue(all.contains("\"stop_reason\":\"max_tokens\""))
    }

    @Test
    fun `non stream response maps content and finish reason`() {
        val openAi = obj(
            """{"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"done","tool_calls":[{"id":"call_9","type":"function","function":{"name":"Bash","arguments":"{\"command\":\"id\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":7}}"""
        )
        val mapped = OpenAiMapping.nonStreamResponse(openAi, "m")
        assertEquals("tool_use", mapped["stop_reason"]!!.jsonPrimitive.content)
        val content = mapped["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Bash", content[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(12, mapped["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content.toInt())
    }
}

class ModelCatalogTest {

    private val settings = AppSettings(
        apiFlavor = ApiFlavor.OPENAI,
        baseUrl = "https://gw.example.com/v1",
        apiKey = "sk-x",
        model = "m",
    )

    @Test
    fun `parses the standard data array`() {
        assertEquals(
            listOf("a-model", "b-model"),
            ModelCatalog.parse("""{"data":[{"id":"b-model"},{"id":"a-model"},{"id":"a-model"}]}"""),
        )
    }

    @Test
    fun `parses a bare array and name fields`() {
        assertEquals(
            listOf("x", "y"),
            ModelCatalog.parse("""[{"name":"x"},{"id":"y"}]"""),
        )
    }

    @Test
    fun `chat completions url handles both base forms`() {
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            OpenAiProxy.chatCompletionsUrl(settings),
        )
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            OpenAiProxy.chatCompletionsUrl(settings.copy(baseUrl = "https://gw.example.com")),
        )
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            OpenAiProxy.chatCompletionsUrl(settings.copy(baseUrl = "https://gw.example.com/")),
        )
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            OpenAiProxy.chatCompletionsUrl(
                settings.copy(baseUrl = "https://gw.example.com/v1/chat/completions"),
            ),
        )
    }

    @Test
    fun `model discovery honours the configured auth style`() {
        val anthropic = AppSettings(
            apiFlavor = ApiFlavor.ANTHROPIC,
            baseUrl = "https://gw.example.com",
            apiKey = "sk-x",
            authStyle = AuthStyle.BEARER,
        )
        val (url, headers) = ModelCatalog.endpoint(anthropic)
        assertEquals("https://gw.example.com/v1/models", url)
        assertEquals("Bearer sk-x", headers["Authorization"])
        assertEquals("2023-06-01", headers["anthropic-version"])

        val keyed = ModelCatalog.endpoint(anthropic.copy(authStyle = AuthStyle.API_KEY))
        assertEquals("sk-x", keyed.second["x-api-key"])

        val local = ModelCatalog.endpoint(
            settings.copy(baseUrl = "http://127.0.0.1:11434/v1", apiKey = ""),
        )
        assertFalse(local.second.containsKey("Authorization"))
    }
}
