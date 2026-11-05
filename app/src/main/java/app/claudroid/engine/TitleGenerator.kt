package app.claudroid.engine

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.data.ModelTier
import app.claudroid.engine.openai.OpenAiProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Generates a short Chinese title for a session from the first user turn and the
 * assistant's reply, using the small/fast (Haiku) model mapping.
 *
 * This is a best-effort, network-only layer: a missing config, a failed request, or
 * an unparseable answer all produce `null` so the caller can keep its existing title.
 * The real API key never leaves this process except to the configured upstream; parse
 * and sanitize are pure functions so response shapes can be tested without sockets.
 */
object TitleGenerator {

    private const val MAX_TOKENS = 64
    private const val USER_LIMIT = 2_000
    private const val ASSISTANT_LIMIT = 4_000
    private const val TITLE_LIMIT = 24

    private const val SYSTEM_PROMPT =
        "你是会话标题生成器。请根据用户的问题和助手的回答，生成一个简短的中文标题。" +
            "要求：不超过14个汉字，概括核心主题；不要加引号、书名号、句号；" +
            "不要使用“关于”“总结”等前缀；只输出标题本身，不要解释。"

    private val json = Json { ignoreUnknownKeys = true }

    // A label must be followed by a colon or whitespace; otherwise a legitimate title
    // such as "标题栏适配" would lose its first two characters.
    private val labelPrefix = Regex("""^(?:标题|Title)(?:\s*[:：]\s*|\s+)""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")

    /**
     * Returns a cleaned short title, or null when the endpoint is unconfigured, the
     * request fails, or the answer cannot be parsed. Never throws.
     */
    suspend fun generate(
        settings: AppSettings,
        firstUserText: String,
        assistantText: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!settings.isConfigured) return@runCatching null
            val prompt = buildPrompt(firstUserText, assistantText)
            val model = titleModel(settings)
            val (url, body) = when (settings.apiFlavor) {
                ApiFlavor.ANTHROPIC -> {
                    val effectiveModel = model.ifBlank { settings.model.trim() }
                    if (effectiveModel.isBlank()) return@runCatching null
                    settings.normalizedBaseUrl() + "/v1/messages" to anthropicBody(effectiveModel, prompt)
                }
                ApiFlavor.OPENAI -> {
                    // A gateway can legitimately have an empty model field; OpenAI will
                    // reject an empty `model`, so bail out instead of sending a bad request.
                    if (model.isBlank()) return@runCatching null
                    OpenAiProxy.chatCompletionsUrl(settings) to openAiBody(model, prompt)
                }
            }
            val headers = authHeaders(settings) + when (settings.apiFlavor) {
                ApiFlavor.ANTHROPIC -> mapOf("anthropic-version" to "2023-06-01")
                ApiFlavor.OPENAI -> emptyMap()
            }
            val raw = HttpUtil.postJson(
                url = url,
                body = body.toString(),
                headers = headers,
                direct = settings.isLocalEndpoint(),
            )
            parseTitle(raw, settings)
        }.getOrNull()
    }

    /**
     * Title generation is a tiny task; do not spend the main model on it when the
     * endpoint is Anthropic's official API. Gateways keep the configured small/Haiku
     * mapping because an official Haiku id may not exist there.
     */
    internal fun titleModel(settings: AppSettings): String {
        val mapped = settings.tierModel(ModelTier.HAIKU).trim()
        if (mapped.isNotBlank()) return mapped
        return if (settings.apiFlavor == ApiFlavor.ANTHROPIC &&
            settings.normalizedBaseUrl() == "https://api.anthropic.com"
        ) {
            "claude-haiku-5-5"
        } else {
            settings.model.trim()
        }
    }

    /** Auth rules mirror [app.claudroid.engine.openai.ModelCatalog] and [OpenAiProxy]. */
    internal fun authHeaders(settings: AppSettings): Map<String, String> {
        // Local endpoints usually have no key; never send an empty `Bearer ` header.
        if (settings.apiKey.isBlank()) return emptyMap()
        return when (settings.authStyle) {
            AuthStyle.BEARER -> mapOf("Authorization" to "Bearer ${settings.apiKey}")
            AuthStyle.API_KEY -> mapOf("x-api-key" to settings.apiKey)
            AuthStyle.BOTH -> mapOf(
                "Authorization" to "Bearer ${settings.apiKey}",
                "x-api-key" to settings.apiKey,
            )
        }
    }

    internal fun buildPrompt(firstUserText: String, assistantText: String): String = buildString {
        append("用户问题：\n")
        append(firstUserText.take(USER_LIMIT))
        append("\n\n助手回答：\n")
        append(assistantText.take(ASSISTANT_LIMIT))
    }

    internal fun anthropicBody(model: String, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("system", SYSTEM_PROMPT)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    internal fun openAiBody(model: String, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("stream", false)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    /**
     * Extracts the title text from a raw upstream JSON body. Public to tests via
     * [internal]; [settings] selects the wire shape. Unknown keys are ignored and any
     * malformed body yields null rather than an exception.
     */
    internal fun parseTitle(raw: String, settings: AppSettings): String? {
        if (raw.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val text = when (settings.apiFlavor) {
            ApiFlavor.ANTHROPIC -> {
                // JsonNull is itself a JsonPrimitive in kotlinx.serialization; exclude it
                // so `{"content":null}` means "no title" instead of the literal "null".
                val content = root["content"]?.takeIf { it !is JsonNull }
                when (content) {
                    is JsonPrimitive -> content.contentOrNull
                    is JsonArray -> content.firstNotNullOfOrNull { block ->
                        val obj = block as? JsonObject ?: return@firstNotNullOfOrNull null
                        if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "thinking") {
                            return@firstNotNullOfOrNull null
                        }
                        (obj["text"] as? JsonPrimitive)
                            ?.takeIf { it !is JsonNull }
                            ?.contentOrNull
                    }
                    else -> null
                }
            }
            ApiFlavor.OPENAI -> {
                val message = (root["choices"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("message") as? JsonObject
                val content = message?.get("content")?.takeIf { it !is JsonNull }
                when (content) {
                    is JsonPrimitive -> content.contentOrNull
                    is JsonArray -> content.mapNotNull { part ->
                        val obj = part as? JsonObject
                        if (obj != null && (obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                            (obj["text"] as? JsonPrimitive)
                                ?.takeIf { it !is JsonNull }
                                ?.contentOrNull
                        } else {
                            (part as? JsonPrimitive)
                                ?.takeIf { it !is JsonNull }
                                ?.contentOrNull
                        }
                    }.joinToString("").ifBlank { null }
                    else -> null
                }
            }
        }
        return sanitizeTitle(text)
    }

    /**
     * First non-empty line, strip label prefixes / wrapping marks / trailing punctuation,
     * collapse whitespace, and cap the length. Returns null for blank results.
     */
    internal fun sanitizeTitle(raw: String?): String? {
        if (raw == null) return null
        var value = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null

        // Models often preface the answer with a label, sometimes twice, sometimes with
        // a full-width colon. Strip the label(s) but keep real titles starting with 标题.
        while (true) {
            val match = labelPrefix.find(value) ?: break
            value = value.substring(match.value.length).trimStart()
        }

        // Leading/trailing quotes, book-title marks, punctuation, and markdown wrappers
        // (**bold**, `code`) that models habitually add to a one-line title.
        val leading = "\"'“”‘’《〈「『【[（(*`"
        val trailing = "\"'“”‘’》〉」』】])）。.!！?？,，;；:：~～-—_*` \t"
        value = value.trim().trimStart(*leading.toCharArray()).trimEnd(*trailing.toCharArray())
        value = value.trim()

        // A model can wrap the whole labelled answer in quotes: `标题："标题：xxx"` →
        // the inner label only becomes visible after the outer marks are gone.
        while (true) {
            val match = labelPrefix.find(value) ?: break
            value = value.substring(match.value.length).trimStart()
                .trimStart(*leading.toCharArray()).trimEnd(*trailing.toCharArray()).trim()
        }

        value = whitespace.replace(value, " ").trim()
        if (value.isEmpty()) return null
        return value.take(TITLE_LIMIT).ifBlank { null }
    }
}
