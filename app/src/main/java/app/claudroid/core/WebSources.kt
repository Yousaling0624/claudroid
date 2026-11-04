package app.claudroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale

data class WebSource(val title: String, val url: String) {
    val host: String = runCatching { URI(url).host.orEmpty() }
        .getOrDefault("")
        .removePrefix("www.")
}

/**
 * Claude Code's WebSearch tool returns prose followed by a `Links: [{"title","url"}]`
 * payload (older builds) or plain markdown links. The transcript used to show that
 * payload as a wall of monospace text; this turns it into tappable source cards.
 */
object WebSources {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val markdownLink = Regex("""\[([^\]\n]{1,160})]\((https?://[^)\s]+)\)""")
    private val bareUrl = Regex("""https?://[^\s<>"']+""")
    private val leadingNumber = Regex("""^\d{1,2}[.)]\s+""")

    fun parse(output: String): List<WebSource> {
        if (output.isBlank()) return emptyList()
        val sources = mutableListOf<WebSource>()

        val marker = listOf("Links:", "Sources:", "\"links\"")
            .mapNotNull { needle ->
                output.indexOf(needle, ignoreCase = true).takeIf { it >= 0 }
            }
            .minOrNull()
        if (marker != null) {
            val open = output.indexOf('[', marker)
            val close = open.takeIf { it >= 0 }?.let { jsonArrayEnd(output, it) }
            if (open >= 0 && close != null) {
                val parsed = runCatching {
                    json.parseToJsonElement(output.substring(open, close + 1)) as? JsonArray
                }.getOrNull()
                parsed?.forEach { element ->
                    val obj = element as? JsonObject ?: return@forEach
                    val url = obj["url"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (url.startsWith("http")) {
                        sources += WebSource(cleanTitle(title).ifBlank { hostOf(url) }, url)
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            markdownLink.findAll(output).forEach { match ->
                sources += WebSource(
                    title = cleanTitle(match.groupValues[1]).ifBlank { hostOf(match.groupValues[2]) },
                    url = match.groupValues[2],
                )
            }
        }

        if (sources.isEmpty()) {
            bareUrl.findAll(output).forEach { match ->
                val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
                sources += WebSource(hostOf(url), url)
            }
        }

        return sources
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url.trimEnd('/').lowercase(Locale.ROOT) }
            .take(MAX_SOURCES)
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(leadingNumber, "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")

    /** Finds the bracket matching [start], skipping brackets inside JSON strings. */
    private fun jsonArrayEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    const val MAX_SOURCES = 12
}
