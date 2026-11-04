package app.claudroid.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decoder for `claude --output-format stream-json --verbose --include-partial-messages`.
 *
 * Event shapes were verified against the real 2.1.x CLI:
 *   {"type":"system","subtype":"init",...}
 *   {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta",...}}}
 *   {"type":"assistant","message":{"content":[{"type":"tool_use",...}]}}
 *   {"type":"user","message":{"content":[{"type":"tool_result",...}]}}
 *   {"type":"result","subtype":"success",...}
 */
class StreamParser {

    private val json = Json { ignoreUnknownKeys = true }

    private var sawDelta = false
    private var sawThinkingDelta = false

    fun parse(rawLine: String): List<EngineEvent> {
        val line = rawLine.trim()
        if (line.isEmpty()) return emptyList()
        if (!line.startsWith("{")) {
            return listOf(EngineEvent.Log(line))
        }
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return listOf(EngineEvent.Log(line))

        return when (root.str("type")) {
            "system" -> parseSystem(root)
            "stream_event" -> parseStreamEvent(root)
            "assistant" -> parseAssistant(root)
            "user" -> parseUser(root)
            "result" -> parseResult(root)
            "error" -> listOf(EngineEvent.Failed(root.str("message") ?: line))
            else -> emptyList()
        }
    }

    private fun parseSystem(root: JsonObject): List<EngineEvent> {
        if (root.str("subtype") != "init") return emptyList()
        val tools = (root["tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return listOf(
            EngineEvent.SessionInit(
                sessionId = root.str("session_id") ?: "",
                model = root.str("model"),
                tools = tools,
            )
        )
    }

    private fun parseStreamEvent(root: JsonObject): List<EngineEvent> {
        val ev = root["event"] as? JsonObject ?: return emptyList()
        return when (ev.str("type")) {
            "message_start" -> {
                sawDelta = false
                sawThinkingDelta = false
                emptyList()
            }
            "content_block_delta" -> {
                val delta = ev["delta"] as? JsonObject ?: return emptyList()
                when (delta.str("type")) {
                    "text_delta" -> {
                        sawDelta = true
                        val t = delta.str("text").orEmpty()
                        if (t.isEmpty()) emptyList() else listOf(EngineEvent.TextDelta(t))
                    }
                    "thinking_delta" -> {
                        sawThinkingDelta = true
                        val t = delta.str("thinking").orEmpty()
                        if (t.isEmpty()) emptyList() else listOf(EngineEvent.ThinkingDelta(t))
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun parseAssistant(root: JsonObject): List<EngineEvent> {
        val msg = root["message"] as? JsonObject ?: return emptyList()
        val content = msg["content"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<EngineEvent>()
        for (element in content) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> if (!sawDelta) {
                    val t = block.str("text").orEmpty()
                    if (t.isNotEmpty()) out += EngineEvent.TextDelta(t)
                }
                "thinking" -> if (!sawThinkingDelta) {
                    val t = block.str("thinking").orEmpty()
                    if (t.isNotEmpty()) out += EngineEvent.ThinkingDelta(t)
                }
                "tool_use" -> out += EngineEvent.ToolUse(
                    id = block.str("id").orEmpty(),
                    name = block.str("name").orEmpty(),
                    inputJson = pretty(block["input"]),
                )
            }
        }
        return out
    }

    private fun parseUser(root: JsonObject): List<EngineEvent> {
        val msg = root["message"] as? JsonObject ?: return emptyList()
        val content = msg["content"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<EngineEvent>()
        for (element in content) {
            val block = element as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val id = block.str("tool_use_id").orEmpty()
            val text = toolResultText(block["content"]) ?: root.str("tool_use_result").orEmpty()
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            out += EngineEvent.ToolResult(id, text, isError)
        }
        return out
    }

    private fun parseResult(root: JsonObject): List<EngineEvent> {
        val out = mutableListOf<EngineEvent>()
        out += EngineEvent.TurnDone(
            resultText = root.str("result").orEmpty(),
            costUsd = (root["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull,
            durationMs = (root["duration_ms"] as? JsonPrimitive)?.longOrNull,
            isError = (root["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sessionId = root.str("session_id"),
            numTurns = (root["num_turns"] as? JsonPrimitive)?.intOrNull,
            subtype = root.str("subtype"),
        )
        return out
    }

    private fun toolResultText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.content
        is JsonArray -> element.mapNotNull { item ->
            val obj = item as? JsonObject
            if (obj?.str("type") == "text") obj.str("text") else null
        }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun pretty(element: JsonElement?): String = when (element) {
        null -> "{}"
        is JsonObject -> json.encodeToString(JsonObject.serializer(), element)
        else -> element.toString()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
