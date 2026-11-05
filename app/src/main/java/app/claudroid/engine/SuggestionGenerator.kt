package app.claudroid.engine

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.engine.openai.OpenAiProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Generates tappable input suggestions for the empty chat screen.
 *
 * Suggestions are always model output: an unconfigured endpoint, a failed request, or
 * an unparseable answer yields an empty list and the caller hides the section. Falling
 * back to canned strings would make the UI look alive while hiding real configuration
 * or network failures.
 *
 * Request shaping mirrors [TitleGenerator] (small/Haiku model, shared auth rules); the
 * model name and auth headers are reused from there so there is only one source of
 * truth for gateways.
 */
object SuggestionGenerator {

    /** The empty screen shows a short chip row, not a list; never ask for more. */
    private const val MAX_COUNT = 5
    private const val MAX_TOKENS = 200
    private const val ITEM_LIMIT = 32
    private const val CONTEXT_LIMIT = 1_000

    private val json = Json { ignoreUnknownKeys = true }

    // A changing seed keeps repeated calls from settling on the same three suggestions
    // every time the empty screen is shown; the counter also covers same-nanosecond calls.
    private val seedCounter = AtomicLong()

    private val fence = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
    private val whitespace = Regex("""\s+""")
    private val listPrefix =
        Regex("""^(?:[-*•·–—]+|[（(]?\d{1,2}[.)、:：）]|\[\d{1,2}\])\s*""")
    private val quoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    private const val LEADING_MARKS = "\"'“”‘’「」『』《》〈〉`"
    private const val TRAILING_MARKS = "\"'“”‘’「」『』《》〈〉`。.!！?？,，;；:：~～-—_* \t"

    /**
     * Generates [count] short Chinese input suggestions. Never throws: an unconfigured
     * endpoint, a blank model, a failed HTTP call, or an unparseable answer all return
     * an empty list, and no hard-coded text is ever substituted.
     */
    suspend fun generate(
        settings: AppSettings,
        count: Int = 3,
        context: String = "",
    ): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!settings.isConfigured || count <= 0) return@runCatching emptyList()
            val wanted = count.coerceAtMost(MAX_COUNT)
            val model = TitleGenerator.titleModel(settings)
            val prompt = buildPrompt(wanted, context, nextSeed())
            val (url, body) = when (settings.apiFlavor) {
                ApiFlavor.ANTHROPIC -> {
                    val effectiveModel = model.ifBlank { settings.model.trim() }
                    if (effectiveModel.isBlank()) return@runCatching emptyList()
                    settings.normalizedBaseUrl() + "/v1/messages" to
                        anthropicBody(effectiveModel, wanted, prompt)
                }
                ApiFlavor.OPENAI -> {
                    // An empty `model` is rejected by every OpenAI-compatible upstream.
                    if (model.isBlank()) return@runCatching emptyList()
                    OpenAiProxy.chatCompletionsUrl(settings) to openAiBody(model, wanted, prompt)
                }
            }
            val headers = TitleGenerator.authHeaders(settings) + when (settings.apiFlavor) {
                ApiFlavor.ANTHROPIC -> mapOf("anthropic-version" to "2023-06-01")
                ApiFlavor.OPENAI -> emptyMap()
            }
            val raw = HttpUtil.postJson(
                url = url,
                body = body.toString(),
                headers = headers,
                direct = settings.isLocalEndpoint(),
            )
            parseSuggestions(TitleGenerator.responseText(raw, settings), wanted)
        }.getOrDefault(emptyList())
    }

    /** Monotonic-ish seed: wall/nano clock plus a counter so two calls never match. */
    internal fun nextSeed(): Long = System.nanoTime() + seedCounter.incrementAndGet() * 1_000_003L

    internal fun systemPrompt(count: Int): String =
        "你在为一个跑在手机里的 Claude Code 客户端生成输入建议。" +
            "请生成 $count 条简短、具体、彼此不同的中文输入建议，每条不超过18个汉字。" +
            "不要编号、不要引号、不要解释；只输出 JSON 字符串数组，例如：[\"建议一\",\"建议二\"]"

    internal fun buildPrompt(count: Int, context: String, seed: Long): String = buildString {
        append("随机种子：$seed\n")
        append("请生成 $count 条彼此不同的建议。")
        if (context.isNotBlank()) {
            append("\n\n[context]\n")
            append(context.trim().take(CONTEXT_LIMIT))
        }
    }

    internal fun anthropicBody(model: String, count: Int, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("system", systemPrompt(count))
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    internal fun openAiBody(model: String, count: Int, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("stream", false)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt(count))
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    /**
     * Parses the assistant text into at most [count] cleaned, distinct suggestions.
     *
     * JSON is preferred (the prompt asks for it, optionally fenced); a numbered or
     * bulleted list is accepted as a fallback because models routinely ignore the
     * "only JSON" instruction. A successfully parsed array — even an empty one — does
     * not fall through to line parsing, so `[]` cannot turn into a bogus chip.
     */
    internal fun parseSuggestions(text: String?, count: Int): List<String> {
        if (text.isNullOrBlank() || count <= 0) return emptyList()
        val wanted = count.coerceAtMost(MAX_COUNT)
        val rawItems = extractJsonArray(text) ?: lineItems(text)
        return rawItems.asSequence()
            .mapNotNull { cleanSuggestion(it) }
            .distinct()
            .take(wanted)
            .toList()
    }

    /** Tries the whole body, a ```json fence, then the first bracketed slice. */
    private fun extractJsonArray(text: String): List<String>? {
        for (candidate in candidates(text)) {
            val array = runCatching { json.parseToJsonElement(candidate) }.getOrNull() as? JsonArray
                ?: continue
            return array.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
        }
        return null
    }

    private fun candidates(text: String): List<String> = buildList {
        val trimmed = text.trim()
        add(trimmed)
        fence.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { add(it) }
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) add(trimmed.substring(start, end + 1))
    }.distinct()

    private fun lineItems(text: String): List<String> = buildList {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("```")) continue
            if (trimmed.all { it in "[]{}," }) continue
            // A malformed array (`["a", "b"`) is still recoverable by pulling its
            // quoted strings; only do this for JSON-ish lines so a legitimate
            // suggestion containing quotes is not cut down to the quoted fragment.
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                val items = quoted.findAll(trimmed).map { unescape(it.groupValues[1]) }.toList()
                if (items.isNotEmpty()) {
                    addAll(items)
                    continue
                }
            }
            add(trimmed)
        }
    }

    /** Strips list numbering/bullets, wrapping quotes and trailing punctuation. */
    internal fun cleanSuggestion(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        value = stripPrefix(value)
        value = trimMarks(value)
        // Models can wrap the whole line in quotes: `"1. foo"` only reveals the
        // numbering after the outer marks are gone, so strip prefixes once more.
        value = stripPrefix(value)
        value = trimMarks(value)
        value = whitespace.replace(value, " ").trim()
        if (value.isEmpty()) return null
        return value.take(ITEM_LIMIT).trim().ifBlank { null }
    }

    private fun stripPrefix(value: String): String {
        var result = value
        while (true) {
            val match = listPrefix.find(result) ?: break
            result = result.substring(match.value.length).trimStart()
        }
        return result
    }

    private fun trimMarks(value: String): String = value
        .trimStart(*LEADING_MARKS.toCharArray())
        .trimEnd(*TRAILING_MARKS.toCharArray())
        .trim()

    private fun unescape(raw: String): String =
        raw.replace("\\\"", "\"").replace("\\\\", "\\")
}
