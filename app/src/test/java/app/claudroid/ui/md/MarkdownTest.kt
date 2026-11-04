package app.claudroid.ui.md

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private val accent = Color(0xFFD97757)
    private val codeBackground = Color(0xFF262624)

    private fun blocks(markdown: String) = Markdown.parse(markdown, accent, codeBackground)

    @Test
    fun `ordered and nested lists keep their markers and depth`() {
        val text = blocks("1. 第一步\n2. 第二步\n  - 子项").filterIsInstance<MdBlock.Text>()
        assertEquals("1.  第一步", text[0].content.text)
        assertEquals(0, text[0].indent)
        assertEquals("•  子项", text[2].content.text)
        assertEquals(1, text[2].indent)
    }

    @Test
    fun `task lists become checkboxes`() {
        val text = blocks("- [x] 完成\n- [ ] 待办").filterIsInstance<MdBlock.Text>()
        assertEquals(listOf("☑  完成", "☐  待办"), text.map { it.content.text })
    }

    @Test
    fun `table requires a divider row`() {
        val table = blocks("| 项目 | 状态 |\n| --- | --- |\n| A | 完成 |").single()
        assertTrue(table is MdBlock.Table)
        table as MdBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals(1, table.rows.size)
        assertFalse(blocks("只有 A | B 的一行文字").any { it is MdBlock.Table })
    }

    @Test
    fun `rules and quotes are separate blocks`() {
        val parsed = blocks("---\n> 重要说明")
        assertTrue(parsed[0] is MdBlock.Rule)
        assertTrue((parsed[1] as MdBlock.Text).quote)
    }

    @Test
    fun `inline formatting leaves readable text`() {
        val text = blocks("普通 **粗体** 与 `代码`").single() as MdBlock.Text
        assertEquals("普通 粗体 与 代码", text.content.text)
    }
}
