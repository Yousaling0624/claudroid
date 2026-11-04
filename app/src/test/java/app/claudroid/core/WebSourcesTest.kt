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
}
