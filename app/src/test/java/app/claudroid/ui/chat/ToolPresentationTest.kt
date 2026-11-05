package app.claudroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationTest {

    @Test
    fun `shell tools map to a terminal verb`() {
        assertEquals("执行", ToolPresentation.verb("Bash"))
        assertEquals("执行", ToolPresentation.verb("mcp__shell__run_command"))
        assertEquals("执行", ToolPresentation.verb("PowerShell"))
    }

    @Test
    fun `file tools split read and write`() {
        assertEquals("读取", ToolPresentation.verb("Read"))
        assertEquals("读取", ToolPresentation.verb("ViewImage"))
        assertEquals("写入", ToolPresentation.verb("Write"))
        assertEquals("写入", ToolPresentation.verb("Edit"))
        assertEquals("写入", ToolPresentation.verb("NotebookEdit"))
    }

    @Test
    fun `web tools map to globe and search tools to search`() {
        assertEquals("查看网页", ToolPresentation.verb("WebFetch"))
        assertEquals("搜索", ToolPresentation.verb("WebSearch"))
        assertEquals("搜索", ToolPresentation.verb("Grep"))
        assertEquals("搜索", ToolPresentation.verb("Glob"))
    }

    @Test
    fun `agents todo and mcp are distinct families`() {
        assertEquals("调用代理", ToolPresentation.verb("Task"))
        assertEquals("调用代理", ToolPresentation.verb("mcp__team__spawn_agent"))
        assertEquals("整理清单", ToolPresentation.verb("TodoWrite"))
        assertEquals("连接服务", ToolPresentation.verb("mcp__server__list_tools"))
    }

    @Test
    fun `unknown tools get a generic verb`() {
        assertEquals("调用工具", ToolPresentation.verb("SomeFutureTool"))
        assertEquals("调用工具", ToolPresentation.verb(""))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(ToolVisual.TERMINAL, ToolPresentation.visual("BASH"))
        assertEquals(ToolVisual.GLOBE, ToolPresentation.visual("webFETCH"))
    }

    @Test
    fun `collapsed label reports error count`() {
        assertEquals("工具调用 3", toolCallLabel(3, 0))
        assertEquals("工具调用 2 · 1 个错误", toolCallLabel(2, 1))
    }
}
