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

    /**
     * A WebSearch turn often ends with a plain "Sources:" block that duplicates the
     * tappable cards already rendered under the tool call. It is model output, not
     * part of the answer, so the transcript hides the trailing block.
     *
     * The marker must start a line (or follow a wide gap) and be followed by something
     * that reads like a list; a sentence that merely mentions "Sources:" is preserved.
     */
    private val sourceHeading = Regex(
        """(?im)(?:^|[\n\r]|[ \t]{2,})[ \t]*(?:sources?|links?|来源|参考来源|参考资料|链接)[ \t]*[:：]"""
    )

    // One source item per line. The numbered form requires whitespace after the
    // marker so a decimal like "1.5 倍" is never mistaken for list item 1.
    private val bulletItem = Regex("""^[-*•·]\s*""")
    private val numberedItem = Regex("""^\d{1,2}[.、)]\s""")
    private val markdownLinkItem = Regex("""^\[[^\]\n]{1,160}]\(https?://""")
    private val bareUrlItem = Regex("""^https?://\S+""")

    fun stripTrailingSources(text: String): String {
        if (text.isBlank()) return text
        val matches = sourceHeading.findAll(text).toList()
        for (match in matches.asReversed()) {
            if (insideCodeFence(text, match.range.first)) continue
            val tail = text.substring(match.range.last + 1)
            if (!looksLikeSourceList(tail)) continue
            return text.substring(0, match.range.first).trimEnd()
        }
        return text
    }

    /**
     * A source block is only recognised when *every* non-empty line in the tail reads
     * like a list item, a URL, or an indented continuation of one. A normal prose line
     * means the heading was followed by part of the answer (a tip, a caveat, a closing
     * sentence), so stripping from the heading would eat real content and we leave the
     * text untouched instead.
     */
    private fun looksLikeSourceList(tail: String): Boolean {
        var sawItem = false
        for (line in tail.lineSequence()) {
            if (line.isBlank()) continue
            val item = line.trimStart()
            when {
                isSourceItem(item) -> sawItem = true
                // Only a line indented under an item counts as its continuation; an
                // unindented first line is prose (e.g. "Sources: 这只是一句解释").
                sawItem && item.length < line.length -> Unit
                else -> return false
            }
        }
        return true
    }

    private fun isSourceItem(line: String): Boolean =
        bulletItem.containsMatchIn(line) ||
            numberedItem.containsMatchIn(line) ||
            markdownLinkItem.containsMatchIn(line) ||
            bareUrlItem.containsMatchIn(line)

    /** Fences are markdown, so a heading inside one is code, never a source block. */
    private fun insideCodeFence(text: String, index: Int): Boolean {
        var fences = 0
        var lineStart = 0
        while (lineStart < index) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0 || newline > index) index else newline
            if (text.substring(lineStart, lineEnd).trimStart().startsWith("```")) fences++
            if (lineEnd >= index) break
            lineStart = lineEnd + 1
        }
        return fences % 2 == 1
    }

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
