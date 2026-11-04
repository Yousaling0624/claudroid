package app.claudroid.ui.md

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Deliberately small Markdown renderer: headings, ordered/nested lists, task lists,
 * quotes, fenced/inline code, tables, rules, bold/italic/strikethrough and links.
 * A chat transcript needs these to read like the CLI's terminal output; unknown syntax
 * is passed through as text rather than disappearing.
 */
sealed interface MdBlock {
    data class Text(
        val content: AnnotatedString,
        val style: TextStyle,
        val quote: Boolean = false,
        val indent: Int = 0,
    ) : MdBlock

    data class Code(val code: String, val lang: String?) : MdBlock

    data class Table(
        val headers: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
        /** Estimated column widths in dp, shared by header and body so they line up. */
        val widths: List<Int>,
    ) : MdBlock

    data object Rule : MdBlock
}

object Markdown {

    private val listMarker = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val ruleLine = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val tableDivider = Regex("""^:?-{2,}:?$""")

    fun parse(source: String, accent: Color, codeBackground: Color): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        val paragraph = StringBuilder()
        var fence: String? = null
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                val text = paragraph.toString().trim()
                if (text.isNotEmpty()) {
                    blocks += MdBlock.Text(
                        content = inline(text, accent, codeBackground),
                        style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
                    )
                }
                paragraph.setLength(0)
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (fence != null) {
                if (trimmed.startsWith("```")) {
                    blocks += MdBlock.Code(code.toString().trimEnd(), fence)
                    code.setLength(0)
                    fence = null
                } else {
                    code.appendLine(line)
                }
                i++
                continue
            }

            // A pipe row followed by a |---|:--:| divider is a table. Checking the
            // lookahead avoids mistaking prose containing "|" for one.
            if (trimmed.contains('|') && i + 1 < lines.size) {
                val header = splitRow(trimmed)
                val divider = splitRow(lines[i + 1])
                if (header.size >= 2 && divider.size >= 2 && divider.all { tableDivider.matches(it) }) {
                    flushParagraph()
                    val rows = mutableListOf<List<String>>()
                    var j = i + 2
                    while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|')) {
                        rows += splitRow(lines[j])
                        j++
                    }
                    val widths = columnWidths(header, rows)
                    blocks += MdBlock.Table(
                        headers = header.map { inline(it, accent, codeBackground) },
                        rows = rows.map { row -> row.map { inline(it, accent, codeBackground) } },
                        widths = widths,
                    )
                    i = j
                    continue
                }
            }

            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    fence = trimmed.removePrefix("```").trim().substringBefore(' ').ifEmpty { null }
                    i++
                }

                ruleLine.matches(trimmed) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                    i++
                }

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val content = trimmed.drop(level).trim().trimEnd('#').trim()
                    if (content.isEmpty()) {
                        paragraph.appendLine(line)
                    } else {
                        val size = when (level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            3 -> 16.sp
                            else -> 15.sp
                        }
                        blocks += MdBlock.Text(
                            content = inline(content, accent, codeBackground),
                            style = TextStyle(
                                fontSize = size,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = size * 1.35f,
                            ),
                        )
                    }
                    i++
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        if (quote.isNotEmpty()) quote.append('\n')
                        quote.append(lines[i].trimStart().removePrefix(">").trimStart())
                        i++
                    }
                    val text = quote.toString().trim()
                    if (text.isNotEmpty()) {
                        blocks += MdBlock.Text(
                            content = inline(text, accent, codeBackground),
                            style = TextStyle(fontSize = 14.sp, fontStyle = FontStyle.Italic, lineHeight = 21.sp),
                            quote = true,
                        )
                    }
                }

                else -> {
                    val match = listMarker.matchEntire(line)
                    if (match != null) {
                        flushParagraph()
                        val indent = match.groupValues[1]
                        val marker = match.groupValues[2]
                        var body = match.groupValues[3]
                        val depth = (indent.count { it == '\t' } * 2 + indent.count { it == ' ' }) / 2
                        val checkbox = when {
                            body.startsWith("[ ] ") -> "☐"
                            body.startsWith("[x] ", ignoreCase = true) -> "☑"
                            else -> null
                        }
                        if (checkbox != null) body = body.substring(4)
                        val prefix = checkbox ?: if (marker.first().isDigit()) marker else "•"
                        blocks += MdBlock.Text(
                            content = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = accent,
                                        fontWeight = if (checkbox == null && !marker.first().isDigit())
                                            FontWeight.Bold else FontWeight.Medium,
                                    )
                                ) { append(prefix) }
                                append("  ")
                                append(inline(body, accent, codeBackground))
                            },
                            style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
                            indent = depth.coerceIn(0, 4),
                        )
                        i++
                    } else if (line.isBlank()) {
                        flushParagraph()
                        i++
                    } else {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line.trim())
                        i++
                    }
                }
            }
        }
        if (fence != null && code.isNotEmpty()) {
            blocks += MdBlock.Code(code.toString().trimEnd(), fence)
        }
        flushParagraph()
        return blocks
    }

    fun inline(text: String, accent: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
        )
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                color = accent,
                            )
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append("***"); i += 3
                    }
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**"); i += 2
                    }
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("~~"); i += 2
                    }
                }

                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('*'); i++
                    }
                }

                text[i] == '[' -> {
                    val close = text.indexOf(']', i)
                    val open = if (close > 0) text.indexOf('(', close) else -1
                    val end = if (open == close + 1) text.indexOf(')', open) else -1
                    if (close > i && open == close + 1 && end > open) {
                        val url = text.substring(open + 1, end).trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            withLink(LinkAnnotation.Url(url, linkStyles)) {
                                append(text.substring(i + 1, close))
                            }
                        } else {
                            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, close))
                            }
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'）】") end++
                    while (end > i && text[end - 1] in ".,;:!?)]}>") end--
                    val url = text.substring(i, end)
                    withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                    i = end
                }

                else -> {
                    append(text[i]); i++
                }
            }
        }
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun columnWidths(header: List<String>, rows: List<List<String>>): List<Int> =
        header.indices.map { column ->
            val longest = (listOf(header) + rows)
                .maxOfOrNull { row -> visualWidth(row.getOrElse(column) { "" }) } ?: 6
            (longest * 6.5f + 24f).roundToInt().coerceIn(78, 230)
        }

    /** CJK glyphs occupy roughly two Latin advances at the same font size. */
    private fun visualWidth(text: String): Int = text.fold(0) { width, c ->
        val code = c.code
        width + if (code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
        ) 2 else 1
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val blocks = remember(text, accent, codeBg) { Markdown.parse(text, accent, codeBg) }

    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Text -> Text(
                    text = block.content,
                    style = baseStyle.merge(block.style),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.indent * 16).dp)
                        .then(
                            if (block.quote) Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                            else Modifier
                        )
                        .padding(top = if (index == 0) 0.dp else 8.dp),
                )

                is MdBlock.Code -> CodeBlock(block.code, block.lang)

                is MdBlock.Table -> TableBlock(block)

                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 4.dp),
        ) {
            table.headers.forEachIndexed { column, cell ->
                Text(
                    cell,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .width(table.widths.getOrElse(column) { 120 }.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 4.dp)) {
                table.headers.indices.forEach { column ->
                    Text(
                        row.getOrElse(column) { AnnotatedString("") },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(table.widths.getOrElse(column) { 120 }.dp)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang?.lowercase()?.ifBlank { null } ?: "代码",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
