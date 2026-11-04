package app.claudroid.engine.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API  <->  OpenAI Chat Completions 的翻译层。
 *
 * Claude Code 只会说 Anthropic 协议，所以「支持 OpenAI 兼容接口」必须在本地做一次翻译，
 * 而不是把 base URL 直接指过去。这里只做纯函数转换，方便单测；网络由 [OpenAiProxy] 负责。
 *
 * 关键映射：
 *   system(字符串或 block 数组)      -> messages[0] role=system
 *   user.text                        -> role=user
 *   user.image(base64)               -> image_url(data URI)
 *   user.tool_result                 -> role=tool (tool_call_id)
 *   assistant.tool_use               -> assistant.tool_calls[].function(arguments=JSON 字符串)
 *   tools[].input_schema             -> tools[].function.parameters
 *   流式 chunk.delta.tool_calls      -> content_block_start(tool_use) + input_json_delta
 *   delta.reasoning_content          -> thinking block（DeepSeek / OpenRouter）
 */
object OpenAiMapping {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ request

    fun toChatRequest(anthropic: JsonObject, modelOverride: String?, stream: Boolean = true): JsonObject = buildJsonObject {
        val collected = mutableListOf<JsonObject>()

        systemText(anthropic["system"])?.takeIf { it.isNotBlank() }?.let {
            collected += buildJsonObject {
                put("role", "system")
                put("content", it)
            }
        }

        (anthropic["messages"] as? JsonArray)?.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            when (message.str("role")) {
                "user" -> collectUser(message, collected)
                "assistant" -> collectAssistant(message, collected)
                else -> Unit
            }
        }
        put("model", modelOverride ?: anthropic.str("model") ?: "")
        put("messages", JsonArray(collected))
        (anthropic["max_tokens"] as? JsonPrimitive)?.let { put("max_tokens", it) }
        (anthropic["temperature"] as? JsonPrimitive)?.let { put("temperature", it) }
        (anthropic["top_p"] as? JsonPrimitive)?.let { put("top_p", it) }
        (anthropic["stop_sequences"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("stop", it) }

        (anthropic["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    val tool = t as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.str("name") ?: "")
                            put("description", tool.str("description") ?: "")
                            put("parameters", tool["input_schema"] ?: buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject { })
                            })
                        })
                    })
                }
            })
        }
        toolChoice(anthropic["tool_choice"])?.let { put("tool_choice", it) }
        // Must follow the incoming request: asking a non-stream caller to parse SSE is the
        // classic "proxy works in one mode only" bug.
        put("stream", stream)
    }

    private fun collectUser(message: JsonObject, out: MutableList<JsonObject>) {
        val content = message["content"]
        if (content is JsonPrimitive) {
            out += textMessage("user", content.content)
            return
        }
        val blocks = content as? JsonArray ?: return
        val parts = mutableListOf<JsonObject>()

        fun addText(text: String) {
            if (text.isEmpty()) return
            val last = parts.lastOrNull()
            if (last != null && last.str("type") == "text") {
                parts[parts.lastIndex] = buildJsonObject {
                    put("type", "text")
                    put("text", (last.str("text") ?: "") + text)
                }
            } else {
                parts += buildJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }

        fun flushUser() {
            if (parts.isEmpty()) return
            // A pure-text message stays a plain string for maximum upstream compatibility.
            out += if (parts.size == 1 && parts[0].str("type") == "text") {
                textMessage("user", parts[0].str("text").orEmpty())
            } else {
                buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(parts.toList()))
                }
            }
            parts.clear()
        }

        blocks.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (block.str("type")) {
                "text" -> addText(block.str("text").orEmpty())
                "image" -> {
                    val source = block["source"] as? JsonObject
                    val data = source?.str("data")
                    val media = source?.str("media_type") ?: "image/png"
                    if (source?.str("type") == "base64" && !data.isNullOrBlank()) {
                        parts += buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:$media;base64,$data") })
                        }
                    } else {
                        addText("[图片无法转译]")
                    }
                }
                "tool_result" -> {
                    // tool messages cannot carry mixed content; flush any text/image first.
                    flushUser()
                    val id = block.str("tool_use_id").orEmpty()
                    out += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", toolResultText(block["content"]))
                    }
                }
            }
        }
        flushUser()
    }

    private fun collectAssistant(message: JsonObject, out: MutableList<JsonObject>) {
        val content = message["content"]
        if (content is JsonPrimitive) {
            out += textMessage("assistant", content.content)
            return
        }
        val blocks = content as? JsonArray ?: return
        val texts = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        blocks.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (block.str("type")) {
                "text" -> texts.append(block.str("text").orEmpty())
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", block.str("id") ?: "")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", block.str("name") ?: "")
                        put("arguments", (block["input"] ?: buildJsonObject { }).toString())
                    })
                }
            }
        }
        out += buildJsonObject {
            put("role", "assistant")
            if (texts.isBlank() && toolCalls.isNotEmpty()) put("content", JsonNull) else put("content", texts.toString())
            if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
        }
    }

    private fun textMessage(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun toolChoice(value: JsonElement?): JsonElement? {
        val obj = value as? JsonObject ?: return null
        return when (obj.str("type")) {
            "auto" -> JsonPrimitive("auto")
            "any" -> JsonPrimitive("required")
            "none" -> JsonPrimitive("none")
            "tool" -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", obj.str("name") ?: "") })
            }
            else -> null
        }
    }

    private fun systemText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { item ->
            (item as? JsonObject)?.let { if (it.str("type") == "text") it.str("text") else null }
        }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun toolResultText(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        is JsonArray -> element.mapNotNull { item ->
            val obj = item as? JsonObject
            when (obj?.str("type")) {
                "text" -> obj.str("text")
                "image" -> "[图片结果已省略]"
                else -> null
            }
        }.joinToString("\n")
        else -> element.toString()
    }

    // ------------------------------------------------------------------ response

    fun nonStreamResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = choice?.get("message") as? JsonObject
        val reasoning = message?.let { it.str("reasoning_content") ?: it.str("reasoning") }
        val toolCalls = message?.get("tool_calls") as? JsonArray
        val content = buildJsonArray {
            if (!reasoning.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "thinking")
                    put("thinking", reasoning)
                    put("signature", "")
                })
            }
            message?.get("content")?.let { c ->
                val text = if (c is JsonPrimitive) c.contentOrNull.orEmpty() else ""
                if (text.isNotBlank()) add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            toolCalls?.forEach { t ->
                val call = t as? JsonObject ?: return@forEach
                val fn = call["function"] as? JsonObject
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", call.str("id") ?: "")
                    put("name", fn?.str("name") ?: "")
                    put("input", parseArguments(fn?.str("arguments")))
                })
            }
        }
        val stop = when {
            choice?.str("finish_reason") == "length" -> "max_tokens"
            !toolCalls.isNullOrEmpty() || choice?.str("finish_reason") == "tool_calls" -> "tool_use"
            else -> "end_turn"
        }
        return buildJsonObject {
            put("id", openAi.str("id") ?: "msg_local")
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", content)
            put("stop_reason", stop)
            put("stop_sequence", JsonNull)
            put("usage", buildJsonObject {
                put("input_tokens", usageInt(openAi, "prompt_tokens"))
                put("output_tokens", usageInt(openAi, "completion_tokens"))
            })
        }
    }

    private fun usageInt(openAi: JsonObject, key: String): Int =
        ((openAi["usage"] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    private fun parseArguments(raw: String?): JsonElement = runCatching {
        json.parseToJsonElement(raw ?: "{}")
    }.getOrElse { buildJsonObject { } }

    /**
     * OpenAI SSE chunks -> Anthropic SSE frames.
     *
     * Anthropic 要求「先 content_block_start，再 delta，再 content_block_stop」，
     * 而 OpenAI 的 tool_calls 是增量下发的（第一个 chunk 才有 id/name，arguments 逐片到达），
     * 所以这里维护了一个按 index 的状态机。thinking 必须是第一个 block，所以一旦正文
     * 或工具调用开始，后续 reasoning 片段会被丢弃。
     */
    class StreamTranslator(private val model: String) {

        private var nextIndex = 0
        private var thinkingIndex = -1
        private var thinkingClosed = false
        private var textIndex = -1
        private var textClosed = false
        private val tools = LinkedHashMap<Int, ToolState>()
        private var finishReason: String? = null
        private var inputTokens = 0
        private var outputTokens = 0

        fun start(): String = frame(
            "message_start",
            buildJsonObject {
                put("type", "message_start")
                put("message", buildJsonObject {
                    put("id", "msg_" + System.nanoTime())
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    put("content", buildJsonArray { })
                    put("stop_reason", JsonNull)
                    put("stop_sequence", JsonNull)
                    put("usage", buildJsonObject {
                        put("input_tokens", 0)
                        put("output_tokens", 0)
                    })
                })
            },
        )

        fun onChunk(chunk: JsonObject): List<String> {
            val out = mutableListOf<String>()
            (chunk["usage"] as? JsonObject)?.let { usage ->
                (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull?.let { inputTokens = it }
                (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull?.let { outputTokens = it }
            }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return out
            (choice["delta"] as? JsonObject)?.let { delta ->
                val reasoning = delta.str("reasoning_content") ?: delta.str("reasoning")
                if (!reasoning.isNullOrEmpty() && textIndex < 0 && tools.isEmpty()) {
                    ensureThinkingBlock(out)
                    out += frame(
                        "content_block_delta",
                        buildJsonObject {
                            put("type", "content_block_delta")
                            put("index", thinkingIndex)
                            put("delta", buildJsonObject {
                                put("type", "thinking_delta")
                                put("thinking", reasoning)
                            })
                        },
                    )
                }
                val text = (delta["content"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    closeThinking(out)
                    ensureTextBlock(out)
                    out += frame(
                        "content_block_delta",
                        buildJsonObject {
                            put("type", "content_block_delta")
                            put("index", textIndex)
                            put("delta", buildJsonObject {
                                put("type", "text_delta")
                                put("text", text)
                            })
                        },
                    )
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                    val call = element as? JsonObject ?: return@forEach
                    closeThinking(out)
                    handleToolDelta(call, out)
                }
            }
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishReason = it }
            return out
        }

        fun finish(): List<String> {
            val out = mutableListOf<String>()
            closeThinking(out)
            if (textIndex >= 0 && !textClosed) {
                out += frame(
                    "content_block_stop",
                    buildJsonObject {
                        put("type", "content_block_stop")
                        put("index", textIndex)
                    },
                )
                textClosed = true
            }
            tools.values.filter { it.blockIndex >= 0 && !it.closed }.forEach { state ->
                out += frame(
                    "content_block_stop",
                    buildJsonObject {
                        put("type", "content_block_stop")
                        put("index", state.blockIndex)
                    },
                )
                state.closed = true
            }
            val stopReason = when {
                finishReason == "length" -> "max_tokens"
                tools.isNotEmpty() || finishReason == "tool_calls" -> "tool_use"
                else -> "end_turn"
            }
            out += frame(
                "message_delta",
                buildJsonObject {
                    put("type", "message_delta")
                    put("delta", buildJsonObject {
                        put("stop_reason", stopReason)
                        put("stop_sequence", JsonNull)
                    })
                    put("usage", buildJsonObject {
                        put("input_tokens", inputTokens)
                        put("output_tokens", outputTokens)
                    })
                },
            )
            out += frame("message_stop", buildJsonObject { put("type", "message_stop") })
            return out
        }

        private fun ensureThinkingBlock(out: MutableList<String>) {
            if (thinkingIndex >= 0) return
            thinkingIndex = nextIndex++
            out += frame(
                "content_block_start",
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", thinkingIndex)
                    put("content_block", buildJsonObject {
                        put("type", "thinking")
                        put("thinking", "")
                        put("signature", "")
                    })
                },
            )
        }

        private fun closeThinking(out: MutableList<String>) {
            if (thinkingIndex < 0 || thinkingClosed) return
            out += frame(
                "content_block_stop",
                buildJsonObject {
                    put("type", "content_block_stop")
                    put("index", thinkingIndex)
                },
            )
            thinkingClosed = true
        }

        private fun ensureTextBlock(out: MutableList<String>) {
            if (textIndex >= 0) return
            textIndex = nextIndex++
            out += frame(
                "content_block_start",
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", textIndex)
                    put("content_block", buildJsonObject {
                        put("type", "text")
                        put("text", "")
                    })
                },
            )
        }

        private fun handleToolDelta(call: JsonObject, out: MutableList<String>) {
            val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
            val state = tools.getOrPut(index) { ToolState() }
            val fn = call["function"] as? JsonObject
            val args = fn?.str("arguments").orEmpty()
            if (state.blockIndex < 0) {
                val name = fn?.str("name")
                if (!name.isNullOrBlank()) {
                    state.name = name
                    state.id = call.str("id") ?: "toolu_$index"
                    state.blockIndex = nextIndex++
                    out += frame(
                        "content_block_start",
                        buildJsonObject {
                            put("type", "content_block_start")
                            put("index", state.blockIndex)
                            put("content_block", buildJsonObject {
                                put("type", "tool_use")
                                put("id", state.id)
                                put("name", state.name)
                                put("input", buildJsonObject { })
                            })
                        },
                    )
                    // Some servers stream arguments before the name/id chunk.
                    if (state.pendingArgs.isNotEmpty()) {
                        out += inputDelta(state, state.pendingArgs)
                        state.pendingArgs = ""
                    }
                } else if (args.isNotEmpty()) {
                    state.pendingArgs += args
                }
            }
            if (args.isNotEmpty() && state.blockIndex >= 0) {
                out += inputDelta(state, args)
            }
        }

        private fun inputDelta(state: ToolState, partial: String): String = frame(
            "content_block_delta",
            buildJsonObject {
                put("type", "content_block_delta")
                put("index", state.blockIndex)
                put("delta", buildJsonObject {
                    put("type", "input_json_delta")
                    put("partial_json", partial)
                })
            },
        )

        private class ToolState {
            var id: String = ""
            var name: String = ""
            var blockIndex: Int = -1
            var closed: Boolean = false
            var pendingArgs: String = ""
        }
    }

    fun frame(event: String, payload: JsonObject): String =
        "event: $event\ndata: ${json.encodeToString(JsonObject.serializer(), payload)}\n\n"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Suppress("unused")
    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    fun stopReasonOf(finishReason: String?): String = when (finishReason) {
        "tool_calls" -> "tool_use"
        "length" -> "max_tokens"
        else -> "end_turn"
    }
}
