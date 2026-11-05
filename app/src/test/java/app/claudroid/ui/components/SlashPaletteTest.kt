package app.claudroid.ui.components

import app.claudroid.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashPaletteTest {

    private val commands = listOf(
        SlashCommand(SlashAction.NEW_CHAT, "new", "新建对话", "清空草稿，从空白开始", AppIcons.Plus),
        SlashCommand(SlashAction.MODEL, "model", "切换模型", "选择默认模型或模型档位", AppIcons.Layers),
        SlashCommand(SlashAction.STOP, "stop", "停止生成", "结束当前回答", AppIcons.Stop),
    )

    @Test
    fun `a lone slash is an empty query`() {
        assertEquals("", slashQuery("/"))
    }

    @Test
    fun `text after the slash filters the palette`() {
        assertEquals("mod", slashQuery("/mod"))
    }

    @Test
    fun `an empty draft is not a slash query`() {
        assertNull(slashQuery(""))
    }

    @Test
    fun `a space commits the draft and closes the palette`() {
        assertNull(slashQuery("/mo del"))
    }

    @Test
    fun `a slash inside ordinary text is not a command`() {
        assertNull(slashQuery("hello /model"))
    }

    @Test
    fun `only an empty composer opens the palette`() {
        assertTrue(opensSlashPalette("", "/"))
        assertFalse(opensSlashPalette("draft", "/"))
        assertFalse(opensSlashPalette("", "/m"))
    }

    @Test
    fun `blank query returns every command`() {
        assertEquals(commands, filterSlashCommands(commands, ""))
    }

    @Test
    fun `filter matches the key case-insensitively`() {
        assertEquals(listOf("model"), filterSlashCommands(commands, "MOD").map { it.key })
    }

    @Test
    fun `filter also matches titles and descriptions`() {
        assertEquals(listOf("stop"), filterSlashCommands(commands, "停止").map { it.key })
        assertEquals(listOf("stop"), filterSlashCommands(commands, "回答").map { it.key })
    }

    @Test
    fun `unknown query yields an empty result`() {
        assertTrue(filterSlashCommands(commands, "zzz").isEmpty())
    }
}
