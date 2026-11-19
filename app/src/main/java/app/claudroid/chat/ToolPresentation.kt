package app.claudroid.chat

/**
 * Pure mapping from a Claude Code tool name to a short Chinese verb for the
 * pre-output "activity line" and the live status line.
 *
 * Kept free of Android/Flutter runtime so the mapping can be unit-tested and so
 * a stream of tool events does not need to resolve anything inside a frame.
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
}
