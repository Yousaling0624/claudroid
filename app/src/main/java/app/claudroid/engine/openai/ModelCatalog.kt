package app.claudroid.engine.openai

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.engine.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Model discovery. Both protocols expose `GET /models` returning `{ "data": [ { "id": ... } ] }`,
 * so one implementation covers Anthropic and OpenAI; only the auth header and the base path differ.
 *
 * Gateways commonly enrich each entry with `display_name`, context limits, reasoning levels and an
 * owner. Those are surfaced to the Flutter picker; every field is optional so a plain ID list from
 * an older endpoint still works.
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    data class ModelInfo(
        val id: String,
        val displayName: String = "",
        val contextWindow: Long? = null,
        val maxOutputTokens: Long? = null,
        val maxInputTokens: Long? = null,
        val ownedBy: String = "",
        val reasoning: Boolean = false,
        val reasoningLevels: List<String> = emptyList(),
        val label: String = "",
        val modalities: List<String> = emptyList(),
        val supportedParameters: List<String> = emptyList(),
    )

    data class Result(
        val models: List<String>,
        val endpoint: String,
        val details: List<ModelInfo> = emptyList(),
    )

    suspend fun fetch(settings: AppSettings): Result = withContext(Dispatchers.IO) {
        val (url, headers) = endpoint(settings)
        // Same problem as the chat proxy: a system HTTP proxy breaks 127.0.0.1/LAN hosts.
        val body = HttpUtil.getText(url, headers, direct = settings.isLocalEndpoint())
        val details = parseDetails(body)
        Result(details.map { it.id }, url, details)
    }

    fun endpoint(settings: AppSettings): Pair<String, Map<String, String>> {
        val auth = authHeaders(settings)
        return when (settings.apiFlavor) {
            ApiFlavor.ANTHROPIC -> {
                val base = settings.normalizedBaseUrl()
                "$base/v1/models" to auth + mapOf(
                    "anthropic-version" to "2023-06-01",
                    "accept" to "application/json",
                )
            }
            ApiFlavor.OPENAI -> {
                var base = settings.baseUrl.trim().trimEnd('/')
                if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
                if (!base.endsWith("/v1")) base = "$base/v1"
                "$base/models" to auth + mapOf("accept" to "application/json")
            }
        }
    }

    /** Same rules as the proxy: 401s here usually mean the header style is wrong. */
    private fun authHeaders(settings: AppSettings): Map<String, String> {
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

    /** ID-only compatibility: the shape every caller had before metadata was added. */
    fun parse(body: String): List<String> = parseDetails(body).map { it.id }

    fun parseDetails(body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            else -> null
        } ?: return emptyList()
        val merged = linkedMapOf<String, ModelInfo>()
        for (element in array) {
            val info = infoOf(element) ?: continue
            val existing = merged[info.id]
            merged[info.id] = if (existing == null) info else merge(existing, info)
        }
        return merged.values.sortedBy { it.id }
    }

    private fun infoOf(element: JsonElement): ModelInfo? = when (element) {
        is JsonPrimitive -> element.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { ModelInfo(id = it) }
        is JsonObject -> {
            val id = text(element, "id").ifBlank { text(element, "name") }
            id.takeIf { it.isNotBlank() }?.let {
                ModelInfo(
                    id = it,
                    displayName = text(element, "display_name"),
                    contextWindow = number(element, "context_window"),
                    maxOutputTokens = number(element, "max_output_tokens"),
                    maxInputTokens = number(element, "max_input_tokens"),
                    ownedBy = text(element, "owned_by"),
                    reasoning = flag(element, "reasoning"),
                    reasoningLevels = stringList(element["reasoning_levels"])
                        .ifEmpty { effortList(element["supported_reasoning_levels"]) },
                    label = text(element, "magpie_label").ifBlank { text(element, "label") },
                    modalities = modalities(element),
                    supportedParameters = stringList(element["supported_parameters"]),
                )
            }
        }
        else -> null
    }

    /** Duplicate IDs are allowed; keep the first position and fill what the later entry knows. */
    private fun merge(first: ModelInfo, second: ModelInfo): ModelInfo = first.copy(
        displayName = second.displayName.ifBlank { first.displayName },
        contextWindow = second.contextWindow ?: first.contextWindow,
        maxOutputTokens = second.maxOutputTokens ?: first.maxOutputTokens,
        maxInputTokens = second.maxInputTokens ?: first.maxInputTokens,
        ownedBy = second.ownedBy.ifBlank { first.ownedBy },
        reasoning = first.reasoning || second.reasoning,
        reasoningLevels = (first.reasoningLevels + second.reasoningLevels).distinct(),
        label = second.label.ifBlank { first.label },
        modalities = (first.modalities + second.modalities).distinct(),
        supportedParameters = (first.supportedParameters + second.supportedParameters).distinct(),
    )

    private fun stringList(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> text(item, "id").ifBlank { text(item, "name") }
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }

    /** `supported_reasoning_levels` is a list of `{ "effort": "low" }` objects. */
    private fun effortList(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            (item as? JsonObject)
                ?.let { text(it, "effort").ifBlank { text(it, "id") } }
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun modalities(obj: JsonObject): List<String> = when (val value = obj["modalities"]) {
        is JsonObject -> stringList(value["input"])
        else -> stringList(value)
    }

    private fun text(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it != "null" }
            .orEmpty()

    private fun number(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun flag(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
}
