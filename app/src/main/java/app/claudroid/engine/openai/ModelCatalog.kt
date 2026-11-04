package app.claudroid.engine.openai

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.engine.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Model discovery. Both protocols expose `GET /models` returning `{ "data": [ { "id": ... } ] }`,
 * so one implementation covers Anthropic and OpenAI; only the auth header and the base path differ.
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val models: List<String>, val endpoint: String)

    suspend fun fetch(settings: AppSettings): Result = withContext(Dispatchers.IO) {
        val (url, headers) = endpoint(settings)
        // Same problem as the chat proxy: a system HTTP proxy breaks 127.0.0.1/LAN hosts.
        val body = HttpUtil.getText(url, headers, direct = settings.isLocalEndpoint())
        Result(parse(body), url)
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

    fun parse(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> ((element["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["name"] as? JsonPrimitive)?.contentOrNull)
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct().sorted()
    }
}
