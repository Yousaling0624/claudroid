package app.claudroid.ui.md

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.RotatingChevron
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
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
        val heading: Boolean = false,
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

/**
 * Folding policy for fenced code blocks. Pure so it can be unit-tested and so a
 * streaming recomposition cannot flip a block between folded/unfolded on a whim.
 * A block is "long" past either threshold (lines or characters); short code stays whole.
 */
object CodeFold {
    const val LINE_THRESHOLD = 8
    const val CHAR_THRESHOLD = 480
    const val COLLAPSED_LINES = 6

    fun isLong(code: String): Boolean {
        val lines = code.count { it == '\n' } + if (code.isEmpty()) 0 else 1
        return lines > LINE_THRESHOLD || code.length > CHAR_THRESHOLD
    }

    /** Line/character count shown in the code header. */
    fun headerMeta(code: String): String {
        val lines = code.count { it == '\n' } + if (code.isEmpty()) 0 else 1
        return "$lines 行 · ${code.length} 字"
    }
}

object Markdown {

    private val listMarker = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val ruleLine = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val tableDivider = Regex("""^:?-{2,}:?$""")

    fun parse(
        source: String,
        accent: Color,
        codeBackground: Color,
        codeColor: Color = accent,
    ): List<MdBlock> {
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
                        content = inline(text, accent, codeBackground, codeColor),
                        style = AppType.assistantBody,
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
                        headers = header.map { inline(it, accent, codeBackground, codeColor) },
                        rows = rows.map { row -> row.map { inline(it, accent, codeBackground, codeColor) } },
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
                        val style = when (level) {
                            1 -> AppType.assistantHeadingLarge
                            2 -> AppType.assistantHeading
                            3 -> AppType.assistantHeadingSmall
                            else -> AppType.assistantHeadingTiny
                        }
                        blocks += MdBlock.Text(
                            content = inline(content, accent, codeBackground, codeColor),
                            style = style,
                            heading = true,
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
                            content = inline(text, accent, codeBackground, codeColor),
                            style = AppType.assistantBody.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 24.sp,
                                fontStyle = FontStyle.Italic,
                            ),
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
                                append(inline(body, accent, codeBackground, codeColor))
                            },
                            style = AppType.assistantBody,
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

    fun inline(
        text: String,
        accent: Color,
        codeBackground: Color,
        codeColor: Color = accent,
    ): AnnotatedString = buildAnnotatedString {
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
                                fontSize = 14.sp,
                                background = codeBackground,
                                color = codeColor,
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

private const val CARET_ID = "streaming-caret"

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = AppType.assistantBody,
    streaming: Boolean = false,
) {
    val accent = MaterialTheme.appColors.accentInk
    val codeBg = MaterialTheme.appColors.surface1
    val codeColor = MaterialTheme.appColors.ink
    val sand = MaterialTheme.appColors.sand
    val blocks = remember(text, accent, codeBg, codeColor) {
        Markdown.parse(text, accent, codeBg, codeColor)
    }

    // A blinking terracotta block at the true end of the stream. The animation state
    // is only read inside the inline composable so the transcript does not recompose
    // on every frame.
    val caretAlpha: State<Float>? = if (streaming) {
        val transition = rememberInfiniteTransition(label = "caret")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(520),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "caret-alpha",
        )
    } else {
        null
    }
    val lastTextIndex = remember(blocks) { blocks.indexOfLast { it is MdBlock.Text } }

    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Text -> {
                    val showCaret = streaming && caretAlpha != null && index == lastTextIndex
                    val content = if (showCaret) {
                        buildAnnotatedString {
                            append(block.content)
                            append(" ")
                            appendInlineContent(CARET_ID, " ")
                        }
                    } else {
                        block.content
                    }
                    val inlineContent = if (showCaret) {
                        mapOf(
                            CARET_ID to InlineTextContent(
                                Placeholder(8.sp, 18.sp, PlaceholderVerticalAlign.TextCenter),
                            ) {
                                StreamingCaret(caretAlpha!!)
                            }
                        )
                    } else {
                        emptyMap()
                    }
                    Text(
                        text = content,
                        style = baseStyle.merge(block.style),
                        color = MaterialTheme.appColors.ink,
                        inlineContent = inlineContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indent * 16).dp)
                            .then(
                                if (block.quote) Modifier
                                    .drawBehind {
                                        drawRect(
                                            color = sand,
                                            topLeft = Offset.Zero,
                                            size = Size(2.dp.toPx(), size.height),
                                        )
                                    }
                                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                                else Modifier
                            )
                            .padding(
                                top = when {
                                    index == 0 -> 0.dp
                                    block.heading -> 18.dp
                                    else -> 8.dp
                                }
                            ),
                    )
                }

                is MdBlock.Code -> CodeBlock(
                    code = block.code,
                    lang = block.lang,
                    modifier = Modifier.padding(top = 12.dp),
                )

                is MdBlock.Table -> TableBlock(block)

                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    color = sand,
                )
            }
        }
    }
}

@Composable
private fun StreamingCaret(alpha: State<Float>) {
    val value by alpha
    Box(
        Modifier
            .size(8.dp, 18.dp)
            .graphicsLayer { this.alpha = value }
            .background(MaterialTheme.appColors.orange, Radii.xsShape)
    )
}

@Composable
private fun TableBlock(table: MdBlock.Table) {
    val shape = Radii.smShape
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.appColors.sand, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .background(MaterialTheme.appColors.surface1)
                .padding(horizontal = 4.dp),
        ) {
            table.headers.forEachIndexed { column, cell ->
                Text(
                    cell,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.appColors.ink,
                    modifier = Modifier
                        .width(table.widths.getOrElse(column) { 120 }.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.appColors.sand)
            Row(Modifier.padding(horizontal = 4.dp)) {
                table.headers.indices.forEach { column ->
                    Text(
                        row.getOrElse(column) { AnnotatedString("") },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = MaterialTheme.appColors.graphite,
                        modifier = Modifier
                            .width(table.widths.getOrElse(column) { 120 }.dp)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fenced code block. Long snippets (>8 lines or >480 chars) start folded at roughly
 * six lines with a fading tail; the header carries language + line/char count and a
 * spring-animated expand/collapse. The whole header row toggles the fold (the copy
 * button keeps its own tap). Copy is always available regardless of fold state. Fold
 * state is a per-slot [rememberSaveable]:
 * streaming recompositions (which append characters to earlier blocks) never reset a
 * user's explicit choice — content-derived keys would, and are forbidden here.
 */
@Composable
fun CodeBlock(code: String, lang: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val colors = MaterialTheme.appColors
    val long = remember(code) { CodeFold.isLong(code) }
    // Per-slot state, not a content key: streaming appends characters to the same block,
    // and a content-derived key would reset an explicit expand/collapse on every chunk.
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    // Collapsed height ~= 6 lines of AppType.code (21sp each) plus padding. Expanded is
    // unconstrained and animateContentSize walks the container from one to the other, so
    // even a 2000-line snippet reaches its true height instead of a hard 4000dp cap.
    val collapsedHeight = 6 * 21 + 24
    val shape = Radii.mdShape
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    // The whole header toggles the fold; the copy button below re-consumes
                    // its own tap so both stay reachable.
                    if (long) Modifier.clickable { expanded = !expanded } else Modifier
                )
                .background(colors.codeFg.copy(alpha = 0.045f))
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang?.lowercase()?.ifBlank { null } ?: "代码",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                color = colors.codeFg.copy(alpha = 0.62f),
            )
            if (long) {
                Spacer(Modifier.width(8.dp))
                Text(
                    CodeFold.headerMeta(code),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.codeFg.copy(alpha = 0.4f),
                )
            }
            Spacer(Modifier.weight(1f))
            if (long) {
                Row(
                    Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RotatingChevron(expanded = expanded, tint = colors.codeFg.copy(alpha = 0.62f), size = 12.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.codeFg.copy(alpha = 0.62f),
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
            Row(
                Modifier
                    .clip(Radii.xsShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = {
                        fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    },
                    label = "copy-state",
                ) { isCopied ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isCopied) AppIcons.Check else AppIcons.Copy,
                            contentDescription = null,
                            tint = if (isCopied) colors.codeFg else colors.codeFg.copy(alpha = 0.62f),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isCopied) "已复制" else "复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCopied) colors.codeFg else colors.codeFg.copy(alpha = 0.62f),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 340f),
                )
                .then(
                    // Tapping the folded tail is the most natural "show me the rest" gesture;
                    // once expanded the body is scrollable/selectable and must not intercept.
                    if (long && !expanded) Modifier.clickable { expanded = true } else Modifier
                )
                .then(if (long && !expanded) Modifier.height(collapsedHeight.dp) else Modifier)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    style = AppType.code,
                    color = colors.codeFg,
                )
            }
            if (long && !expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(30.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, colors.codeBg),
                            )
                        )
                )
            }
        }
    }
}
