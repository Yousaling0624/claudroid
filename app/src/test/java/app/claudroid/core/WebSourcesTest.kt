package app.claudroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourcesTest {

    @Test
    fun `parses the Links payload and ignores trailing prose`() {
        val output = """
            Web search results for query: "claude code"
            Links: [{"title":"1. Claude Code docs","url":"https://docs.claude.com/en/docs/claude-code"},{"title":"2. GitHub","url":"https://github.com/anthropics/claude-code"}]
            Some trailing note.
        """.trimIndent()

        val sources = WebSources.parse(output)
        assertEquals(2, sources.size)
        assertEquals("Claude Code docs", sources[0].title)
        assertEquals("docs.claude.com", sources[0].host)
        assertEquals("github.com", sources[1].host)
    }

    @Test
    fun `brackets inside a JSON title do not truncate the array`() {
        val output = """Links: [{"title":"Release [beta] notes","url":"https://example.com/beta"}]"""
        val sources = WebSources.parse(output)
        assertEquals(1, sources.size)
        assertEquals("Release [beta] notes", sources[0].title)
    }

    @Test
    fun `falls back to markdown links`() {
        val output = "结果如下：\n- [Kotlin docs](https://kotlinlang.org/docs)\n- [Android](https://developer.android.com)"
        val sources = WebSources.parse(output)
        assertEquals(listOf("Kotlin docs", "Android"), sources.map { it.title })
        assertEquals("kotlinlang.org", sources[0].host)
    }

    @Test
    fun `bare urls still produce usable sources and deduplicate`() {
        val output = "See https://example.com/a and https://example.com/a/ and https://other.dev/x."
        val sources = WebSources.parse(output)
        assertEquals(2, sources.size)
        assertEquals("example.com", sources[0].host)
        assertEquals("other.dev", sources[1].host)
    }

    @Test
    fun `blank output has no sources`() {
        assertTrue(WebSources.parse("").isEmpty())
        assertTrue(WebSources.parse("   \n ").isEmpty())
    }

    @Test
    fun `strips a trailing markdown sources block`() {
        val text = """
            结论写在前面。

            Sources:
            - [华为发布 2025 年年度报告](https://example.com/huawei)
            - [Huawei Releases 2025 Annual Report](https://example.com/en)
        """.trimIndent()

        assertEquals("结论写在前面。", WebSources.stripTrailingSources(text))
    }

    @Test
    fun `strips the single-line bullet form seen in the transcript`() {
        val text = "回答正文。\n\nSources:•  华为发布2025年年度报告：经营结果符合预期" +
            "•  Huawei Releases 2025 Annual Report•  2025年年度报告（含五年财务概要）"

        assertEquals("回答正文。", WebSources.stripTrailingSources(text))
    }

    @Test
    fun `keeps prose that merely mentions sources`() {
        val text = "Sources: 这只是一句解释，不是来源列表。"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }

    @Test
    fun `keeps a sources heading inside a fenced code block`() {
        val text = "示例：\n\n```\nSources:\n- one\n- two\n```"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }

    @Test
    fun `dangling sources heading is removed`() {
        assertEquals("答案", WebSources.stripTrailingSources("答案\n\nSources:"))
    }

    @Test
    fun `two-space gap before an inline heading is recognised`() {
        assertEquals("答案", WebSources.stripTrailingSources("答案  Sources:• one• two"))
    }

    @Test
    fun `strips a numbered source list`() {
        val text = "回答\n\n参考来源：\n1. 内部数据\n2. 公开报告"
        assertEquals("回答", WebSources.stripTrailingSources(text))
    }

    @Test
    fun `strips a source list with indented continuation lines`() {
        val text = "回答\n\nSources:\n- [Doc](https://example.com)\n  更新于 2025 年"
        assertEquals("回答", WebSources.stripTrailingSources(text))
    }

    @Test
    fun `keeps prose that follows a links heading`() {
        val text = "安装方法\n链接：https://example.com\n装完后重启"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }

    @Test
    fun `keeps a summary that follows a source list`() {
        val text = "回答\n\n参考来源：\n1. 内部数据\n2. 公开报告\n\n以上资料截至 2025 年。"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }

    @Test
    fun `a decimal number is not a numbered source item`() {
        val text = "结论\n\n参考来源：\n1.5 倍的增长来自公开报告"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }

    @Test
    fun `ordinary markdown bullet lists are untouched`() {
        val text = "清单：\n- 第一项\n- 第二项"
        assertEquals(text, WebSources.stripTrailingSources(text))
    }
}
