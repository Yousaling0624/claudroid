package app.claudroid.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFoldTest {

    @Test
    fun `short code stays unfolded`() {
        assertFalse(CodeFold.isLong("val x = 1\nval y = 2"))
    }

    @Test
    fun `more than eight lines folds`() {
        val code = (1..9).joinToString("\n") { "line $it" }
        assertTrue(CodeFold.isLong(code))
    }

    @Test
    fun `exactly eight lines does not fold`() {
        val code = (1..8).joinToString("\n") { "line $it" }
        assertFalse(CodeFold.isLong(code))
    }

    @Test
    fun `long single line folds on character count`() {
        assertTrue(CodeFold.isLong("x".repeat(481)))
    }

    @Test
    fun `header meta reports line and character counts`() {
        assertEquals("2 行 · 5 字", CodeFold.headerMeta("ab\ncd"))
        assertEquals("0 行 · 0 字", CodeFold.headerMeta(""))
    }
}
