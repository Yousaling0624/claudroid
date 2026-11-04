package app.claudroid.ui.chat

import app.claudroid.AppContainer
import app.claudroid.core.Logx
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.ApiFlavor
import app.claudroid.data.Role
import app.claudroid.engine.EngineEvent
import app.claudroid.engine.TurnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns one conversation. Lives in [AppContainer] rather than a ViewModel on purpose:
 * a turn keeps running while the user navigates back to the session list, and the chat
 * screen simply re-attaches to the same controller.
 */
class ChatController(private val container: AppContainer, val sessionId: String) {

    private val scope = container.scope

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _title = MutableStateFlow("新对话")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _session = MutableStateFlow<ChatSession?>(null)
    var claudeSessionId: String? = null
        private set

    private var job: Job? = null
    private var loaded = false

    /** Bumped by [clear] so events from a cancelled turn cannot repopulate the chat. */
    @Volatile private var generation = 0

    @Volatile private var dismissed = false

    suspend fun load() {
        if (loaded) return
        loaded = true
        container.sessions.load()
        val session = container.sessions.find(sessionId) ?: ChatSession(id = sessionId)
        _session.value = session
        _title.value = session.title
        claudeSessionId = session.claudeSessionId

        // A process killed mid-turn leaves streaming=true and tool cards spinning forever.
        // Repair them the first time the transcript is opened after a crash.
        var changed = false
        val repaired = session.messages.map { msg ->
            val blocks = msg.blocks.map { block ->
                if (block is Block.Tool && block.running) {
                    changed = true
                    block.copy(running = false, isError = true, output = block.output ?: "（任务中断）")
                } else {
                    block
                }
            }
            if (msg.streaming) changed = true
            msg.copy(streaming = false, blocks = blocks)
        }
        _messages.value = repaired
        if (changed) persist()
    }

    /** Fire-and-forget error surface for callers outside the send coroutine. */
    fun notifyError(message: String) {
        scope.launch { appendError(message) }
    }

    fun stop() {
        if (!_running.value) return
        _status.value = "正在停止…"
        container.engine.cancel()
    }

    /**
     * Detach a deleted session: stop the turn, drop the controller from the cache so the
     * transcript cannot resurrect itself through a later persist().
     */
    fun dismiss() {
        dismissed = true
        container.engine.cancel()
        job?.cancel()
        loaded = true
        container.forgetChat(sessionId)
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        // Claim the turn synchronously; the Flow check alone races with a fast double tap.
        if (!_running.compareAndSet(false, true)) return
        job = scope.launch {
            try {
                sendLocked(text)
            } finally {
                withContext(NonCancellable) {
                    _running.value = false
                    _status.value = ""
                    TurnService.stop(container.appContext)
                    runCatching { persist() }
                }
            }
        }
    }

    private suspend fun sendLocked(text: String) {
        val settings = container.settings.flow.first()
        if (!settings.isConfigured) {
            appendError("还没有配置 API Key：请到「设置 → 接口」填写 Base URL 与 Key（本地地址可留空）")
            return
        }
        container.engine.startIssue(settings)?.let {
            appendError(it)
            return
        }
        val plan = container.engine.plan(settings)
        if (plan == null) {
            appendError("后端尚未就绪：请到「设置 → Claude Code 引擎」安装或修复内置 CLI")
            return
        }
        if (settings.apiFlavor == ApiFlavor.OPENAI && plan.proxyBaseUrl == null) {
            appendError("OpenAI 兼容代理启动失败，请检查端口占用后重试。")
            return
        }

        _status.value = "准备中…"
        TurnService.start(container.appContext, "Claudroid 正在执行", text.take(48))

        var session = _session.value ?: ChatSession(id = sessionId)
        if (session.title == "新对话") {
            session = session.copy(title = text.replace('\n', ' ').take(28))
            _title.value = session.title
        }
        session = session.copy(model = settings.model)
        _session.value = session

        val userMsg = ChatMessage(role = Role.USER, blocks = listOf(Block.Text(text)))
        val assistant = ChatMessage(role = Role.ASSISTANT, streaming = true)
        _messages.update { it + userMsg + assistant }
        persist()

        var finalText: String? = null
        var cost: Double? = null
        var duration: Long? = null
        val stderrTail = ArrayDeque<String>()
        val turnGeneration = generation

        suspend fun attempt(resumeId: String?): Int {
            var code = 0
            withContext(Dispatchers.IO) {
                code = container.engine.engine.runTurn(
                    prompt = text,
                    resumeSessionId = resumeId,
                    settings = settings,
                    plan = plan,
                ) { ev ->
                    // 「清空上下文」 during a turn must win over late stdout events.
                    if (turnGeneration != generation) return@runTurn
                    when (ev) {
                        is EngineEvent.SessionInit -> {
                            if (ev.sessionId.isNotBlank()) claudeSessionId = ev.sessionId
                            _status.value = "已连接 ${ev.model ?: settings.model}"
                        }
                        is EngineEvent.TextDelta -> {
                            _status.value = "正在回复…"
                            mutateAssistant(assistant.id) { appendText(it, ev.text) }
                        }
                        is EngineEvent.ThinkingDelta -> {
                            _status.value = "正在思考…"
                            mutateAssistant(assistant.id) { appendThinking(it, ev.text) }
                        }
                        is EngineEvent.ToolUse -> {
                            _status.value = "执行工具 ${ev.name}…"
                            mutateAssistant(assistant.id) { addTool(it, ev) }
                        }
                        is EngineEvent.ToolResult ->
                            mutateAssistant(assistant.id) { resolveTool(it, ev) }
                        is EngineEvent.TurnDone -> {
                            finalText = ev.resultText
                            cost = ev.costUsd
                            duration = ev.durationMs
                            if (ev.sessionId != null) claudeSessionId = ev.sessionId
                            if (ev.isError) {
                                // max_turns ends with exit code 1 and no `result` text; a raw
                                // "CLI 退出码 1" would hide the real cause.
                                val note = when (ev.subtype) {
                                    "error_max_turns" ->
                                        "已达到本轮最大轮数（--max-turns）。可以调大设置里的上限，或发送「继续」。"
                                    else -> ev.resultText.takeIf { it.isNotBlank() }
                                }
                                if (note != null) {
                                    mutateAssistant(assistant.id) { appendError(it, note) }
                                }
                            }
                        }
                        is EngineEvent.Log -> {
                            Logx.i("cli: ${ev.line}")
                            if (ev.isError) {
                                _status.value = ev.line.take(120)
                                if (stderrTail.size >= 8) stderrTail.removeFirst()
                                stderrTail.addLast(ev.line)
                            }
                        }
                        is EngineEvent.Failed -> {
                            Logx.e("引擎错误: ${ev.message}")
                            mutateAssistant(assistant.id) { appendError(it, ev.message) }
                        }
                        is EngineEvent.Exited -> Unit
                    }
                }
            }
            return code
        }

        var exitCode: Int
        try {
            var resumeAttempted = claudeSessionId != null
            exitCode = attempt(claudeSessionId)
            val joined = stderrTail.joinToString("\n") + "\n" + (finalText ?: "")
            val resumeMissing = joined.contains("No conversation found", ignoreCase = true) ||
                joined.contains("Invalid session", ignoreCase = true) ||
                joined.contains("requires a valid session ID", ignoreCase = true) ||
                (joined.contains("session", ignoreCase = true) && joined.contains("not found", ignoreCase = true))
            if (exitCode != 0 && resumeAttempted && resumeMissing) {
                // The CLI stores transcripts inside the jail. A reinstall or a cleared app
                // wipes them while the chat still points at the old session id; retry once
                // without --resume instead of leaving the conversation permanently broken.
                Logx.i("resume 会话不存在，改为新会话重试", container.appContext)
                _status.value = "原会话已不存在，正在重新开始…"
                claudeSessionId = null
                finalText = null
                cost = null
                duration = null
                stderrTail.clear()
                mutateAssistant(assistant.id) { it.copy(blocks = emptyList()) }
                exitCode = attempt(null)
            }
        } catch (t: Throwable) {
            Logx.e("对话异常: ${t.message}")
            mutateAssistant(assistant.id) { appendError(it, t.message ?: "未知错误") }
            exitCode = -1
        }

        if (turnGeneration != generation) return

        // If the backend never streamed deltas (older CLI), fall back to the final text.
        val hasText = _messages.value.firstOrNull { it.id == assistant.id }
            ?.blocks?.any { it is Block.Text && it.text.isNotBlank() } == true
        if (!hasText && !finalText.isNullOrBlank()) {
            mutateAssistant(assistant.id) { appendText(it, finalText ?: "") }
        }

        // A failed process must be visible. Silence was the worst part of the
        // "Bus error" report: the turn simply vanished.
        val current = _messages.value.firstOrNull { it.id == assistant.id }
        val producedText = current?.blocks?.any { it is Block.Text && it.text.isNotBlank() } == true
        val alreadyReported = current?.blocks?.any { it is Block.Error } == true
        if (!producedText && !alreadyReported && exitCode != 0 && exitCode != -2) {
            val stderrText = stderrTail.joinToString("\n")
            val hint = when {
                stderrText.contains("root/sudo privileges") ->
                    "\n\nCLI 以 root 运行时会拒绝「完全放行」模式（官方安全限制）。" +
                        "本版本已通过 IS_SANDBOX=1 规避；若仍出现，请把权限模式改成「默认（只读）」或「只读规划」。"
                exitCode == 135 -> "\n\n这通常是安装文件被截断（下载中断）或存储空间不足。" +
                    "请到「设置 → Claude Code 引擎」点「升级 / 重装」重新下载。"
                exitCode == 139 -> "\n\n段错误：请在设置里切到 PRoot 模式，或用「自检」查看具体启动方式。"
                else -> ""
            }
            val tail = if (stderrTail.isEmpty()) "" else "\n" + stderrTail.takeLast(4).joinToString("\n")
            mutateAssistant(assistant.id) { appendError(it, "CLI 退出码 $exitCode$tail$hint") }
        } else if (current?.blocks.isNullOrEmpty() && exitCode == 0) {
            mutateAssistant(assistant.id) {
                appendError(it, "模型没有返回任何内容。请确认接口支持工具调用，或换一个模型试试。")
            }
        }

        mutateAssistant(assistant.id) {
            it.copy(streaming = false, costUsd = cost, durationMs = duration)
        }
    }

    private suspend fun appendError(message: String) {
        val msg = ChatMessage(role = Role.SYSTEM, blocks = listOf(Block.Error(message)))
        _messages.update { it + msg }
        persist()
    }

    fun clear() {
        if (_running.value) {
            container.engine.cancel()
            job?.cancel()
        }
        generation += 1
        _messages.value = emptyList()
        claudeSessionId = null
        // The transcript is gone; keeping the old title would leave a conversation
        // named after a message it no longer contains.
        _title.value = "新对话"
        scope.launch { persist() }
    }

    suspend fun persist() {
        if (dismissed) return
        val base = _session.value ?: ChatSession(id = sessionId)
        val session = base.copy(
            id = sessionId,
            title = _title.value,
            messages = _messages.value,
            claudeSessionId = claudeSessionId,
        )
        _session.value = session
        container.sessions.upsert(session)
    }

    private fun mutateAssistant(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun appendText(msg: ChatMessage, chunk: String): ChatMessage {
        val blocks = msg.blocks.toMutableList()
        val last = blocks.lastOrNull()
        if (last is Block.Text) blocks[blocks.lastIndex] = last.copy(text = last.text + chunk)
        else blocks.add(Block.Text(chunk))
        return msg.copy(blocks = blocks)
    }

    private fun appendThinking(msg: ChatMessage, chunk: String): ChatMessage {
        val blocks = msg.blocks.toMutableList()
        val last = blocks.lastOrNull()
        if (last is Block.Thinking) blocks[blocks.lastIndex] = last.copy(text = last.text + chunk)
        else blocks.add(Block.Thinking(chunk))
        return msg.copy(blocks = blocks)
    }

    private fun addTool(msg: ChatMessage, ev: EngineEvent.ToolUse): ChatMessage {
        if (msg.blocks.any { it is Block.Tool && it.id == ev.id }) return msg
        val label = toolHeadline(ev.name, ev.inputJson)
        _status.value = "执行 $label…"
        return msg.copy(blocks = msg.blocks + Block.Tool(id = ev.id, name = ev.name, input = ev.inputJson))
    }

    private fun resolveTool(msg: ChatMessage, ev: EngineEvent.ToolResult): ChatMessage {
        _status.value = "工具返回"
        return msg.copy(
            blocks = msg.blocks.map { b ->
                if (b is Block.Tool && b.id == ev.id) {
                    b.copy(output = ev.output, isError = ev.isError, running = false)
                } else b
            }
        )
    }

    private fun appendError(msg: ChatMessage, message: String): ChatMessage =
        msg.copy(blocks = msg.blocks + Block.Error(message))

    /** Human headline for a tool card, e.g. `Bash · magisk --install-module x.zip`. */
    private fun toolHeadline(name: String, inputJson: String): String {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(inputJson).jsonObject
        }.getOrNull()
        val detail = obj?.let {
            (it["command"] ?: it["file_path"] ?: it["path"] ?: it["pattern"] ?: it["query"]
                ?: it["url"] ?: it["description"])
                ?.jsonPrimitive?.content
        }
        return if (detail.isNullOrBlank()) name else "$name · ${detail.take(60)}"
    }
}
