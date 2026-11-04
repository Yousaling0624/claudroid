package app.claudroid.ui.md

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

/**
 * Deliberately small Markdown subset: headings, bullets, quotes, fenced/inline code,
 * bold/italic/strikethrough and links. Anything fancier would need a heavyweight
 * dependency; a chat transcript rarely needs more, and unknown syntax stays readable.
 */
sealed interface MdBlock {
    data class Text(val content: AnnotatedString, val style: TextStyle, val quote: Boolean = false) : MdBlock
    data class Code(val code: String, val lang: String?) : MdBlock
}

object Markdown {

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

        for (line in lines) {
            val trimmed = line.trimStart()
            if (fence != null) {
                if (trimmed.startsWith("```")) {
                    blocks += MdBlock.Code(code.toString().trimEnd(), fence)
                    code.setLength(0)
                    fence = null
                } else {
                    code.appendLine(line)
                }
                continue
            }
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    fence = trimmed.removePrefix("```").trim().ifEmpty { null }
                }
                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val content = trimmed.drop(level).trim()
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
                }
                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    blocks += MdBlock.Text(
                        content = inline(trimmed.removePrefix("> "), accent, codeBackground),
                        style = TextStyle(fontSize = 14.sp, fontStyle = FontStyle.Italic),
                        quote = true,
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    val body = trimmed.drop(2)
                    blocks += MdBlock.Text(
                        content = buildAnnotatedString {
                            append("•  ")
                            append(inline(body, accent, codeBackground))
                        },
                        style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
                    )
                }
                line.isBlank() -> flushParagraph()
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
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
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = accent)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
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
                        withStyle(
                            SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                        ) { append(text.substring(i + 1, close)) }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
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
                        .then(
                            if (block.quote) Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                            else Modifier
                        )
                        .padding(top = if (index == 0) 0.dp else 8.dp),
                )
                is MdBlock.Code -> CodeBlock(block.code, block.lang)
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp)
    ) {
        if (!lang.isNullOrBlank()) {
            Text(
                text = lang!!.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Suppress("unused")
private val CodePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
