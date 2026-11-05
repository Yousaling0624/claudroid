package app.claudroid.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import app.claudroid.ui.theme.AppIcons

/**
 * Pure mapping from a Claude Code tool name to (a) a short Chinese verb for the
 * pre-output "activity line" and (b) one of the hand-drawn line icons.
 *
 * Kept free of Compose/Android runtime so the mapping can be unit-tested and so a
 * stream of tool events does not need to resolve an icon inside a recomposition.
 * Names are matched case-insensitively on whole words so `mcp__server__search` and
 * `WebSearch` land on the same family as `search`.
 */
enum class ToolVisual(val verb: String) {
    TERMINAL("执行"),
    READ("读取"),
    WRITE("写入"),
    GLOBE("查看网页"),
    SEARCH("搜索"),
    AGENTS("调用代理"),
    CHECKLIST("整理清单"),
    PLUG("连接服务"),
    WRENCH("处理"),
    SPARKLE("生成"),
    GENERIC("调用工具"),
}

object ToolPresentation {

    /**
     * Order matters: check the more specific families (web, task, todo) before the
     * generic file/edit verbs, otherwise `WebFetch` would match "fetch" -> generic.
     */
    fun visual(name: String): ToolVisual {
        val n = name.lowercase()
        return when {
            // Terminal / shell
            n.contains("bash") || n.contains("shell") || n.contains("terminal") ||
                n.contains("command") || n == "kill" || n.contains("powershell") ||
                n.contains("computer") -> ToolVisual.TERMINAL

            // MCP discovery/listing: `list_tools` is a connector action, not a web/file
            // search, so it must be recognised before the generic "list" match below.
            n.contains("mcp") && (n.contains("list_tools") || n.contains("listtools") ||
                (n.contains("tool") && n.contains("list"))) -> ToolVisual.PLUG

            // Search first for WebSearch, Grep/Glob and MCP search tools; WebFetch and
            // browser tools fall through to the globe family.
            n == "grep" || n == "glob" || n.contains("search") || n.contains("grep") ||
                n.contains("glob") || n.contains("find") || n.contains("list") ||
                n.contains("ls") -> ToolVisual.SEARCH

            // Web/network
            n.contains("webfetch") || n.contains("web") ||
                n.contains("http") || n.contains("url") || n.contains("fetch") ||
                n.contains("browser") -> ToolVisual.GLOBE

            // Agents / subagents / tasks
            n == "task" || n.contains("agent") || n.contains("subagent") ||
                n.contains("team") || n.contains("dispatch") || n.contains("delegate") ||
                n.contains("workflow") -> ToolVisual.AGENTS

            // Todo / plan / checklist
            n.contains("todo") || n.contains("checklist") || n.contains("taskcreate") ||
                n.contains("taskupdate") || n.contains("plan") -> ToolVisual.CHECKLIST

            // MCP / connectors / plugins
            n.contains("mcp") || n.contains("plugin") || n.contains("connector") ->
                ToolVisual.PLUG

            // Write / edit / notebook
            n.contains("write") || n.contains("edit") || n.contains("create") ||
                n.contains("notebook") || n.contains("replace") || n.contains("patch") ||
                n.contains("delete") || n.contains("mkdir") || n.contains("move") ||
                n.contains("copy") -> ToolVisual.WRITE

            // Read-only file access
            n.contains("read") || n.contains("view") || n.contains("cat") ||
                n.contains("open") || n.contains("file") || n.contains("pdf") ||
                n.contains("image") -> ToolVisual.READ

            // Generation / thinking helpers
            n.contains("think") || n.contains("reason") || n.contains("generate") ||
                n.contains("spark") || n.contains("summar") -> ToolVisual.SPARKLE

            // Tools that do not fit a noun above (skill, config, etc.)
            n.contains("skill") || n.contains("config") || n.contains("setting") ||
                n.contains("hook") || n.contains("wrench") -> ToolVisual.WRENCH

            else -> ToolVisual.GENERIC
        }
    }

    fun verb(name: String): String = visual(name).verb

    /** Each ImageVector is cached by AppIcons, so repeated calls are cheap. */
    fun icon(name: String): ImageVector = when (visual(name)) {
        ToolVisual.TERMINAL -> AppIcons.Terminal
        ToolVisual.READ -> AppIcons.ReadFile
        ToolVisual.WRITE -> AppIcons.WriteFile
        ToolVisual.GLOBE -> AppIcons.Globe
        ToolVisual.SEARCH -> AppIcons.Search
        ToolVisual.AGENTS -> AppIcons.Agents
        ToolVisual.CHECKLIST -> AppIcons.Checklist
        ToolVisual.PLUG -> AppIcons.Plug
        ToolVisual.WRENCH -> AppIcons.Wrench
        ToolVisual.SPARKLE -> AppIcons.Sparkle
        ToolVisual.GENERIC -> AppIcons.Tool
    }
}

/** Stable, human-readable label for the collapsed "工具调用 N" disclosure. */
fun toolCallLabel(count: Int, errorCount: Int): String =
    if (errorCount > 0) "工具调用 $count · $errorCount 个错误"
    else "工具调用 $count"

/**
 * Label for the unified work disclosure that merges thinking segments and tool calls
 * once the answer starts (or the turn ends). Both halves are optional, so a turn that
 * only reasoned or only called tools still gets a meaningful label; an empty string
 * means there is nothing to disclose.
 */
fun workLabel(thinkingCount: Int, toolCount: Int, errorCount: Int): String {
    val parts = buildList {
        if (thinkingCount > 0) add("思考过程 $thinkingCount 段")
        if (toolCount > 0 || errorCount > 0) add(toolCallLabel(toolCount, errorCount))
    }
    return parts.joinToString(" · ")
}
