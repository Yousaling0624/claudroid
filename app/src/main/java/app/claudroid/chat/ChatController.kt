package app.claudroid.chat

import app.claudroid.AppContainer
import app.claudroid.core.Logx
import app.claudroid.core.WebSources
import app.claudroid.data.AppSettings
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.ApiFlavor
import app.claudroid.data.Role
import app.claudroid.engine.CancelReason
import app.claudroid.engine.ClaudeEngine
import app.claudroid.engine.EngineEvent
import app.claudroid.engine.LocalTurnBackend
import app.claudroid.engine.PermissionDecision
import app.claudroid.engine.TitleGenerator
import app.claudroid.engine.TurnBackend
import app.claudroid.engine.TurnRequest
import app.claudroid.engine.TurnService
import app.claudroid.data.chatModelLabel
import app.claudroid.remote.RemoteHostManager
import app.claudroid.remote.RemotePermissionModes
import app.claudroid.remote.RemoteTurnBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

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

    /** True while the title is still the auto-derived first-message placeholder. */
    @Volatile private var titleIsTemporary = true

    /** Guards title jobs so a late network result cannot overwrite a rename/switch. */
    @Volatile private var titleGeneration = 0

    private val _titleBusy = MutableStateFlow(false)
    val titleBusy: StateFlow<Boolean> = _titleBusy.asStateFlow()

    private val _titleNotice = MutableStateFlow<String?>(null)
    val titleNotice: StateFlow<String?> = _titleNotice.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Tool calls a remote host is currently waiting on; never persisted. */
    private val _permissions = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingPermissions: StateFlow<List<PendingPermission>> = _permissions.asStateFlow()
    private val permissionJobs = mutableMapOf<String, Job>()

    /**
     * Incremental mirror of the flows above, for consumers that cannot cheaply
     * re-read the whole transcript (the Flutter bridge). `_messages` remains the only
     * source of truth; these are notifications emitted next to the existing writes.
     *
     * `tryEmit` everywhere: a slow or absent collector must never stall a turn.
     */
    private val _events = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    /** Drops the oldest event when the buffer is full; see [events]. */
    private fun emitEvent(event: ChatEvent) {
        _events.tryEmit(event)
    }

    /** Publishes the whole transcript; call sites that replace `_messages` wholesale. */
    private fun emitMessages() {
        emitEvent(ChatEvent.Messages(_messages.value))
    }

    /**
     * Appends one streaming chunk and publishes it.
     *
     * The tail block is sampled **before** the append. A chunk that opens a new block
     * is structural: the receiver has no block to append to yet, so it gets the full
     * list (which already contains the chunk) instead of a `delta`. Re-sending both
     * would double the text on the Dart side.
     */
    private fun streamChunk(messageId: String, block: DeltaBlock, chunk: String) {
        val before = _messages.value.firstOrNull { it.id == messageId }?.blocks?.lastOrNull()
        mutateAssistant(messageId) {
            when (block) {
                DeltaBlock.TEXT -> appendText(it, chunk)
                DeltaBlock.THINKING -> appendThinking(it, chunk)
            }
        }
        val continues = when (block) {
            DeltaBlock.TEXT -> before is Block.Text
            DeltaBlock.THINKING -> before is Block.Thinking
        }
        if (continues) emitEvent(ChatEvent.Delta(messageId, block, chunk)) else emitMessages()
    }

    private fun setStatus(value: String) {
        if (_status.value == value) return
        _status.value = value
        // The running flag travels with the event: `statusLine` depends on it, and a
        // collector that reads it later can see a newer value.
        emitEvent(ChatEvent.Status(value, _running.value))
    }

    /**
     * The atomic guard lives in [launchTurn]'s `compareAndSet`; this only publishes
     * the change, so every write of `_running` also reaches the bridge.
     */
    private fun setRunning(value: Boolean) {
        _running.value = value
        emitEvent(ChatEvent.Running(value))
    }

    private fun setTitle(value: String) {
        _title.value = value
        emitEvent(ChatEvent.Title(value))
    }

    private fun setTitleBusy(value: Boolean) {
        _titleBusy.value = value
        emitEvent(ChatEvent.TitleBusy(value))
    }

    private fun setTitleNotice(value: String?) {
        _titleNotice.value = value
        emitEvent(ChatEvent.TitleNotice(value))
    }

    private val _session = MutableStateFlow<ChatSession?>(null)
    var claudeSessionId: String? = null
        private set

    private var job: Job? = null
    private var loaded = false

    /** The executor of the in-flight turn; null while idle or preparing. */
    @Volatile private var activeBackend: TurnBackend? = null

    /** A Stop pressed while `prepareTurn` was still resolving the backend. */
    @Volatile private var stopRequested = false

    /** Bumped by [clear] so events from a cancelled turn cannot repopulate the chat. */
    @Volatile private var generation = 0

    @Volatile private var dismissed = false

    suspend fun load() {
        if (loaded) return
        loaded = true
        container.sessions.load()
        val session = container.sessions.find(sessionId) ?: ChatSession(id = sessionId)
        _session.value = session
        setTitle(session.title)
        claudeSessionId = session.claudeSessionId
        // A title that still matches the first-message placeholder has never been
        // summarized (or was never renamed); only those are eligible for auto-summary.
        titleIsTemporary = isPlaceholderTitle(session.title, session.messages)

        // A process killed mid-turn leaves streaming=true and tool cards spinning forever.
        // Repair them the first time the transcript is opened after a crash; an empty
        // interrupted answer also gets a visible note instead of a blank Claude bubble.
        val (repaired, changed) = TurnRepair.repair(session.messages)
        _messages.value = repaired
        emitMessages()
        if (changed) persist()
    }

    /** Fire-and-forget error surface for callers outside the send coroutine. */
    fun notifyError(message: String) {
        scope.launch { appendError(message) }
    }

    /**
     * A title is "placeholder" when it is still the default label or the truncated
     * first user message. Both are what [sendLocked] writes before a summary exists.
     */
    private fun isPlaceholderTitle(title: String, messages: List<ChatMessage>): Boolean {
        if (title == "新对话") return true
        val firstUser = messages.firstOrNull { it.role == Role.USER }
            ?.blocks?.filterIsInstance<Block.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            ?: return false
        return title == firstUser.replace('\n', ' ').take(28)
    }

    /**
     * Best-effort background title summary after the first completed turn. Runs off the
     * turn coroutine so it never blocks streaming; failures are silent for the auto path.
     */
    private fun maybeSummarizeTitle() {
        if (!titleIsTemporary) return
        val messages = _messages.value
        // Only the first exchange: once a second user turn exists, the placeholder has
        // been around long enough that the user likely accepts it.
        if (messages.count { it.role == Role.USER } != 1) return
        val firstUser = messages.firstOrNull { it.role == Role.USER }
            ?.blocks?.filterIsInstance<Block.Text>()
            ?.joinToString("\n") { it.text }?.trim().orEmpty()
        val assistant = messages.lastOrNull { it.role == Role.ASSISTANT }
            ?.blocks?.filterIsInstance<Block.Text>()
            ?.joinToString("\n") { WebSources.stripTrailingSources(it.text) }?.trim().orEmpty()
        if (firstUser.isEmpty() || assistant.isEmpty()) return
        val expectedTitle = _title.value
        val generationAtStart = titleGeneration
        scope.launch {
            val result = runCatching {
                TitleGenerator.generate(container.settings.flow.first(), firstUser, assistant)
            }.getOrNull()
            // Do not clobber a manual rename, a session switch, or a cleared transcript.
            if (generationAtStart != titleGeneration) return@launch
            if (_title.value != expectedTitle || !titleIsTemporary) return@launch
            if (dismissed) return@launch
            // The drawer may have renamed the persisted session while the request ran.
            if (container.sessions.find(sessionId)?.title?.let { it != expectedTitle } == true) return@launch
            if (!result.isNullOrBlank() && result != expectedTitle) {
                setTitle(result)
                titleIsTemporary = false
                runCatching { persist() }
            }
        }
    }

    /**
     * Manual "重新生成标题" from the chat menu. Unlike the auto path it reports short
     * progress/failure feedback and is disabled while a turn is running.
     */
    fun regenerateTitle() {
        if (_running.value || _titleBusy.value) return
        val messages = _messages.value
        val firstUser = messages.firstOrNull { it.role == Role.USER }
            ?.blocks?.filterIsInstance<Block.Text>()
            ?.joinToString("\n") { it.text }?.trim().orEmpty()
        val assistant = messages.lastOrNull { it.role == Role.ASSISTANT }
            ?.blocks?.filterIsInstance<Block.Text>()
            ?.joinToString("\n") { WebSources.stripTrailingSources(it.text) }?.trim().orEmpty()
        if (firstUser.isEmpty() || assistant.isEmpty()) {
            setTitleNotice("还没有可总结的对话内容")
            return
        }
        val expectedTitle = _title.value
        val generationAtStart = ++titleGeneration
        setTitleBusy(true)
        setTitleNotice("正在生成标题…")
        scope.launch {
            val result = runCatching {
                TitleGenerator.generate(container.settings.flow.first(), firstUser, assistant)
            }.getOrNull()
            if (generationAtStart != titleGeneration || dismissed) {
                setTitleBusy(false)
                return@launch
            }
            if (_title.value != expectedTitle) {
                // Renamed mid-flight; keep the user's choice.
                setTitleNotice("标题已手动修改，保留当前名称")
            } else if (!result.isNullOrBlank()) {
                setTitle(result)
                titleIsTemporary = false
                setTitleNotice("标题已更新")
                runCatching { persist() }
            } else {
                setTitleNotice("标题生成失败，已保留原名称。可稍后重试或检查接口配置。")
            }
            setTitleBusy(false)
        }
    }

    /** Clears a transient title notice once the UI has shown it. */
    fun consumeTitleNotice() { setTitleNotice(null) }

    /** Called by a rename action so a pending auto-summary can never overwrite it. */
    fun markTitleUserEdited(newTitle: String) {
        if (newTitle == _title.value) return
        titleGeneration++
        titleIsTemporary = false
        setTitle(newTitle)
        scope.launch { persist() }
    }

    fun stop() {
        if (!_running.value) return
        stopRequested = true
        setStatus("正在停止…")
        activeBackend?.cancel(CancelReason.USER) ?: container.engine.cancel(CancelReason.USER)
    }

    /**
     * Detach a deleted session: stop the turn, drop the controller from the cache so the
     * transcript cannot resurrect itself through a later persist().
     */
    fun dismiss() {
        dismissed = true
        // The engine is app-wide: deleting an idle conversation must not cancel a turn
        // that belongs to another controller.
        if (_running.value) {
            stopRequested = true
            activeBackend?.cancel(CancelReason.USER) ?: container.engine.cancel(CancelReason.USER)
        }
        job?.cancel()
        clearPermissions()
        loaded = true
        container.forgetChat(sessionId)
    }

    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val history = _messages.value
        // A forked or restored session can carry visible turns without a CLI session
        // id (the bundled CLI only resumes the transcript it recorded itself). Replay
        // the history the reader sees on the first send, or the model would answer the
        // new message with no context at all.
        val replay = claudeSessionId == null && history.isNotEmpty()
        return launchTurn { prepared ->
            executeTurn(
                prepared = prepared,
                baseMessages = history,
                userText = text,
                prompt = if (replay) ConversationPrompt.build(history, text) else text,
                resetCliSession = false,
                userMessageId = null,
            )
        }
    }

    /**
     * Edits a user message. The transcript is cut at that message and a fresh CLI
     * session starts with the earlier turns replayed as context; the official CLI can
     * resume but cannot rewind, so this is the honest equivalent of an edit-and-resend.
     *
     * Returns false when the message no longer exists, so the composer can keep the
     * draft and the editing banner instead of silently dropping both.
     */
    fun resendFrom(messageId: String, raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId && it.role == Role.USER }
        if (index < 0) {
            notifyError("要编辑的消息已不存在，请重新选择")
            return false
        }
        val history = messages.take(index)
        return launchTurn { prepared ->
            executeTurn(
                prepared = prepared,
                baseMessages = history,
                userText = text,
                prompt = ConversationPrompt.build(history, text),
                resetCliSession = true,
                userMessageId = messageId,
            )
        }
    }

    /** Re-runs the user turn that produced [messageId] and replaces that answer. */
    fun regenerate(messageId: String) {
        val messages = _messages.value
        // Only the newest answer can be regenerated; older ones would drop the turns
        // that came after them without the user asking for that.
        if (messages.lastOrNull { it.role == Role.ASSISTANT }?.id != messageId) return
        val assistantIndex = messages.indexOfFirst { it.id == messageId && it.role == Role.ASSISTANT }
        if (assistantIndex < 0) return
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].role == Role.USER }
            ?: return
        val text = messages[userIndex].blocks
            .filterIsInstance<Block.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (text.isEmpty()) return
        val history = messages.take(userIndex)
        launchTurn { prepared ->
            executeTurn(
                prepared = prepared,
                baseMessages = history,
                userText = text,
                prompt = ConversationPrompt.build(history, text),
                resetCliSession = true,
                userMessageId = messages[userIndex].id,
            )
        }
    }

    /**
     * Branches the conversation at [messageId] into a fresh session. The branch
     * intentionally has no CLI session id: its first send replays the visible
     * history (see [send]), which is the only honest way to cut a resumed CLI
     * transcript at an arbitrary message.
     */
    suspend fun forkAt(messageId: String): ChatSession? {
        val history = Fork.history(_messages.value, messageId) ?: return null
        if (history.isEmpty()) return null
        val base = _session.value
        val session = ChatSession(
            title = Fork.title(_title.value),
            model = base?.model,
            hostId = base?.hostId,
            remoteCwd = base?.remoteCwd,
            messages = history,
        )
        container.sessions.upsert(session)
        return session
    }

    private data class PreparedTurn(
        val settings: AppSettings,
        val backend: TurnBackend,
        /** Active local workspace; null for a remote turn. */
        val workspace: File?,
        /** Remote working directory; null for a local turn. */
        val remoteCwd: String?,
        val remoteHostId: String?,
        /** This host's own permission mode; null uses the local setting. */
        val remotePermissionMode: String?,
        /** The backend cancellation token registered when this turn was accepted. */
        val cancelToken: Long,
    )

    /**
     * Claims the single running slot synchronously (a Flow check races with a double
     * tap), then prepares the backend before touching the transcript. A failed
     * precheck must not delete messages when the user was editing or regenerating.
     */
    private fun launchTurn(block: suspend (PreparedTurn) -> Unit): Boolean {
        if (!_running.compareAndSet(false, true)) return false
        setRunning(true)
        container.turnRunning.set(true)
        stopRequested = false
        job = scope.launch {
            try {
                val prepared = prepareTurn() ?: return@launch
                activeBackend = prepared.backend
                if (stopRequested) prepared.backend.cancel(CancelReason.USER)
                block(prepared)
            } finally {
                withContext(NonCancellable) {
                    activeBackend = null
                    clearPermissions()
                    setRunning(false)
                    setStatus("")
                    container.turnRunning.set(false)
                    TurnService.stop(container.appContext)
                    runCatching { persist() }
                }
            }
        }
        return true
    }

    private suspend fun prepareTurn(): PreparedTurn? {
        val settings = container.settings.flow.first()
        val session = _session.value ?: container.sessions.find(sessionId)
        val hostId = session?.hostId
        if (hostId != null) return prepareRemoteTurn(settings, hostId, session.remoteCwd)

        if (!settings.isConfigured) {
            appendError("还没有配置 API Key：请到「设置 → 接口」填写 Base URL 与 Key（本地地址可留空）")
            return null
        }
        container.engine.startIssue(settings)?.let {
            appendError(it)
            return null
        }
        val plan = container.engine.plan(settings)
        if (plan == null) {
            appendError("后端尚未就绪：请到「设置 → Claude Code 引擎」安装或修复内置 CLI")
            return null
        }
        // A network switch can leave the jail on stale DNS; refresh before the
        // CLI performs WebFetch's domain check, but never block a turn on it.
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                container.engine.ensureNetworkConfig()
            }
        }.onFailure { Logx.e("本轮刷新容器 DNS 失败：${it.message}", container.appContext) }
        if (settings.apiFlavor == ApiFlavor.OPENAI && plan.proxyBaseUrl == null) {
            appendError("OpenAI 兼容代理启动失败，请检查端口占用后重试。")
            return null
        }
        val workspace = container.workspaces.activeDir()
        val backend = LocalTurnBackend(
            engine = container.engine.engine,
            settings = settings,
            plan = plan,
            workspace = workspace,
            modelLabel = chatModelLabel(settings),
        )
        return PreparedTurn(
            settings = settings,
            backend = backend,
            workspace = workspace,
            remoteCwd = null,
            remoteHostId = null,
            remotePermissionMode = null,
            cancelToken = backend.beginTurn(),
        )
    }

    /**
     * Remote preparation deliberately skips every local provider/engine check: the
     * host owns its Claude login and model configuration, and the phone must not send
     * its API key, Base URL or proxy settings.
     */
    private suspend fun prepareRemoteTurn(
        settings: AppSettings,
        hostId: String,
        storedCwd: String?,
    ): PreparedTurn? {
        val host = container.remote.host(hostId)
        if (host == null) {
            appendError("这条会话绑定的远程主机已被移除。请到「设置 → 远程主机」重新添加，或新建一个本机会话。")
            return null
        }
        val health = try {
            container.remote.test(hostId)
        } catch (t: Throwable) {
            appendError("无法连接「${host.name}」：${t.message ?: "未知错误"}")
            return null
        }
        if (health.protocol != 0 && health.protocol != RemoteHostManager.PROTOCOL_VERSION) {
            appendError(
                "主机协议版本不兼容（主机 v${health.protocol}，App v${RemoteHostManager.PROTOCOL_VERSION}）。" +
                    "请升级主机上的 claudroid-bridge。",
            )
            return null
        }
        // The phone's global mode only governs the local engine; each host keeps its own.
        val remotePermissionMode = RemotePermissionModes.normalize(host.permissionMode)
        if (remotePermissionMode == RemotePermissionModes.BYPASS && !health.allowBypassPermissions) {
            appendError(
                "主机「${host.name}」没有允许「完全放行」模式。" +
                    "请在设置 → 远程主机 → 这台主机 → 执行里改用其他权限模式，" +
                    "或在主机配置中开启 allowBypassPermissions。",
            )
            return null
        }
        val cwd = storedCwd?.takeIf { it.isNotBlank() }
            ?: health.defaultCwd.ifBlank { host.defaultCwd }
        if (cwd.isBlank()) {
            appendError("主机没有配置可用的工作目录，请先在主机配置里设置 defaultCwd。")
            return null
        }
        val modelLabel = health.defaultModel?.takeIf { it.isNotBlank() } ?: "主机默认模型"
        val backend = RemoteTurnBackend(
            manager = container.remote,
            hostId = hostId,
            cwd = cwd,
            label = host.name,
            modelLabel = modelLabel,
        )
        return PreparedTurn(
            settings = settings,
            backend = backend,
            workspace = null,
            remoteCwd = cwd,
            remoteHostId = hostId,
            remotePermissionMode = remotePermissionMode,
            cancelToken = backend.beginTurn(),
        )
    }

    private suspend fun executeTurn(
        prepared: PreparedTurn,
        baseMessages: List<ChatMessage>,
        userText: String,
        prompt: String,
        resetCliSession: Boolean,
        userMessageId: String?,
    ) {
        val settings = prepared.settings
        // Resolve the signature once: a later settings change must not retitle
        // answers that this turn already produced.
        val turnModel = prepared.backend.modelLabel ?: chatModelLabel(settings)
        if (resetCliSession) claudeSessionId = null

        setStatus("准备中…")
        TurnService.start(container.appContext, "Claudroid 正在执行", userText.take(48))

        var session = _session.value ?: ChatSession(id = sessionId)
        if (session.title == "新对话") {
            session = session.copy(title = userText.replace('\n', ' ').take(28))
            setTitle(session.title)
            titleIsTemporary = true
        }
        session = session.copy(
            model = if (prepared.remoteHostId != null) turnModel else settings.model,
            hostId = prepared.remoteHostId ?: session.hostId,
            remoteCwd = prepared.remoteCwd ?: session.remoteCwd,
        )
        _session.value = session

        // Reusing the edited message's id keeps the composer's editing target valid
        // after the transcript is rebuilt (edit-and-resend, regenerate).
        val userMsg = ChatMessage(
            id = userMessageId ?: UUID.randomUUID().toString(),
            role = Role.USER,
            blocks = listOf(Block.Text(userText)),
        )
        val assistant = ChatMessage(
            role = Role.ASSISTANT,
            streaming = true,
            model = turnModel,
        )
        _messages.value = baseMessages + userMsg + assistant
        emitMessages()
        persist()

        // The prompt already references `@attachments/<name>`. For a remote session
        // those files must exist under the host's working directory; the relative
        // path stays identical, so the model sees the same convention as locally.
        if (prepared.remoteHostId != null && prepared.remoteCwd != null) {
            try {
                uploadRemoteAttachments(prepared.remoteHostId, prepared.remoteCwd, prompt)
            } catch (t: Throwable) {
                Logx.e("远程附件上传失败: ${t.message}", container.appContext)
                val text = "附件上传失败：${t.message ?: "未知错误"}"
                mutateAssistant(assistant.id) { appendError(it, text).copy(streaming = false) }
                emitEvent(ChatEvent.ErrorBlock(assistant.id, text))
                emitMessages()
                return
            }
        }

        var finalText: String? = null
        var cost: Double? = null
        var duration: Long? = null
        val stderrTail = ArrayDeque<String>()
        val turnGeneration = generation

        suspend fun attempt(resumeId: String?): Int {
            val request = TurnRequest(
                prompt = prompt,
                resumeSessionId = resumeId,
                permissionMode = prepared.remotePermissionMode ?: settings.permissionMode.cli,
            )
            return prepared.backend.runTurn(request, prepared.cancelToken) { ev ->
                // 「清空上下文」 during a turn must win over late stdout events.
                if (turnGeneration != generation) return@runTurn
                when (ev) {
                    is EngineEvent.SessionInit -> {
                        if (ev.sessionId.isNotBlank()) claudeSessionId = ev.sessionId
                        val model = ev.model ?: turnModel
                        setStatus(
                            if (prepared.remoteHostId != null) {
                                "已连接 ${prepared.backend.label} · $model"
                            } else {
                                "已连接 $model"
                            },
                        )
                    }
                    is EngineEvent.TextDelta -> {
                        setStatus("正在回复…")
                        streamChunk(assistant.id, DeltaBlock.TEXT, ev.text)
                    }
                    is EngineEvent.ThinkingDelta -> {
                        setStatus("正在思考…")
                        streamChunk(assistant.id, DeltaBlock.THINKING, ev.text)
                    }
                    is EngineEvent.ToolUse -> {
                        // Human verb only: commands, paths and JSON stay out of the
                        // live status line and are revealed in the collapsed cards.
                        setStatus(ToolPresentation.verb(ev.name) + "…")
                        // `addTool` ignores a duplicate id; emitting then would make the
                        // Dart mirror append a second card for the same call.
                        val isNew = toolBlockExists(assistant.id, ev.id).not()
                        mutateAssistant(assistant.id) { addTool(it, ev) }
                        if (isNew) {
                            emitEvent(
                                ChatEvent.ToolUse(assistant.id, ev.id, ev.name, ev.inputJson),
                            )
                        }
                    }
                    is EngineEvent.ToolResult -> {
                        // Same rule: a result for a card the receiver never saw would
                        // force it to re-sync for nothing.
                        val known = toolBlockExists(assistant.id, ev.id)
                        mutateAssistant(assistant.id) { resolveTool(it, ev) }
                        if (known) {
                            emitEvent(
                                ChatEvent.ToolResult(assistant.id, ev.id, ev.output, ev.isError),
                            )
                        }
                    }
                    is EngineEvent.PermissionRequest -> addPermission(ev)
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
                                emitEvent(ChatEvent.ErrorBlock(assistant.id, note))
                            }
                        }
                    }
                    is EngineEvent.Log -> {
                        Logx.i("cli: ${ev.line}")
                        if (ev.isError) {
                            setStatus(ev.line.take(120))
                            if (stderrTail.size >= 8) stderrTail.removeFirst()
                            stderrTail.addLast(ev.line)
                        }
                    }
                    is EngineEvent.Failed -> {
                        Logx.e("引擎错误: ${ev.message}")
                        mutateAssistant(assistant.id) { appendError(it, ev.message) }
                        emitEvent(ChatEvent.ErrorBlock(assistant.id, ev.message))
                    }
                    is EngineEvent.Exited -> Unit
                }
            }
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
            if (exitCode != 0 && exitCode != -2 && resumeAttempted && resumeMissing) {
                // The CLI stores transcripts inside the jail. A reinstall or a cleared app
                // wipes them while the chat still points at the old session id; retry once
                // without --resume instead of leaving the conversation permanently broken.
                Logx.i("resume 会话不存在，改为新会话重试", container.appContext)
                setStatus("原会话已不存在，正在重新开始…")
                claudeSessionId = null
                finalText = null
                cost = null
                duration = null
                stderrTail.clear()
                mutateAssistant(assistant.id) { it.copy(blocks = emptyList()) }
                emitMessages()
                exitCode = attempt(null)
            }
        } catch (t: Throwable) {
            Logx.e("对话异常: ${t.message}")
            mutateAssistant(assistant.id) { appendError(it, t.message ?: "未知错误") }
            exitCode = -1
        }

        if (turnGeneration != generation) return

        if (exitCode == -2) {
            // Stop pressed (or an engine install/uninstall): keep whatever the model
            // produced, close the message and any tool card so nothing keeps spinning.
            mutateAssistant(assistant.id) {
                it.copy(
                    streaming = false,
                    blocks = it.blocks.map { b ->
                        if (b is Block.Tool && b.running) {
                            b.copy(running = false, isError = true, output = b.output ?: "（已停止）")
                        } else {
                            b
                        }
                    },
                )
            }
            // A stopped turn that produced nothing used to close silently: the transcript
            // kept an empty assistant bubble with no explanation. Always say what happened.
            val stopped = _messages.value.firstOrNull { it.id == assistant.id }
            val producedSomething = stopped?.blocks?.any {
                (it is Block.Text && it.text.isNotBlank()) || it is Block.Tool || it is Block.Error
            } == true
            if (!producedSomething) {
                val text = when (prepared.backend.cancelReason()) {
                    CancelReason.ENGINE_INSTALL ->
                        "引擎正在更新，本轮被中断。等更新完成后重新发送。"
                    CancelReason.ENGINE_UNINSTALL ->
                        "引擎已被卸载，本轮被中断。到「设置 → 引擎」重新安装后可以继续。"
                    else -> "已停止本轮，模型还没有输出内容。可以重新发送。"
                }
                mutateAssistant(assistant.id) { appendError(it, text) }
                emitEvent(ChatEvent.ErrorBlock(assistant.id, text))
            }
            emitMessages()
            return
        }

        // If the backend never streamed deltas (older CLI), fall back to the final text.
        val hasText = _messages.value.firstOrNull { it.id == assistant.id }
            ?.blocks?.any { it is Block.Text && it.text.isNotBlank() } == true
        val pendingText = finalText
        if (!hasText && !pendingText.isNullOrBlank()) {
            streamChunk(assistant.id, DeltaBlock.TEXT, pendingText)
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
            val text = "CLI 退出码 $exitCode$tail$hint"
            mutateAssistant(assistant.id) { appendError(it, text) }
            emitEvent(ChatEvent.ErrorBlock(assistant.id, text))
        } else if (exitCode == 0 && !producedText && !alreadyReported &&
            current?.blocks?.any { it is Block.Tool } != true
        ) {
            val text = "模型没有返回任何内容。请确认接口支持工具调用，或换一个模型试试。"
            mutateAssistant(assistant.id) { appendError(it, text) }
            emitEvent(ChatEvent.ErrorBlock(assistant.id, text))
        }

        mutateAssistant(assistant.id) {
            it.copy(streaming = false, costUsd = cost, durationMs = duration)
        }
        emitMessages()

        // Summarize only after a real answer exists; a failed/empty turn keeps the
        // first-message placeholder. This is deliberately fire-and-forget.
        maybeSummarizeTitle()
    }

    private suspend fun appendError(message: String) {
        val msg = ChatMessage(role = Role.SYSTEM, blocks = listOf(Block.Error(message)))
        _messages.update { it + msg }
        emitMessages()
        persist()
    }

    /**
     * Copies every `@attachments/<name>` the prompt references into the host working
     * directory. The local copy was made by `attachments.pick`; a missing local file is
     * skipped (it may come from replaying history whose attachment is already remote).
     */
    private suspend fun uploadRemoteAttachments(hostId: String, remoteCwd: String, prompt: String) {
        val names = ATTACHMENT_REF.findAll(prompt).map { it.groupValues[1] }.toSet()
        if (names.isEmpty()) return
        val localDir = File(container.workspaces.activeDir(), "attachments")
        val remoteDir = remoteCwd.trimEnd('/')
        for (name in names) {
            val local = File(localDir, name)
            if (!local.isFile) continue
            setStatus("正在上传附件…")
            container.remote.upload(hostId, "$remoteDir/attachments/$name", local)
        }
    }

    // ------------------------------------------------------------- permissions

    private fun addPermission(ev: EngineEvent.PermissionRequest) {
        val pending = PendingPermission(
            requestId = ev.requestId,
            toolName = ev.toolName,
            inputJson = ev.inputJson,
            title = ev.title,
            description = ev.description,
            expiresAt = ev.expiresAt,
        )
        _permissions.update { list ->
            list.filterNot { it.requestId == pending.requestId } + pending
        }
        emitEvent(
            ChatEvent.Permission(
                requestId = pending.requestId,
                toolName = pending.toolName,
                inputJson = pending.inputJson,
                title = pending.title,
                description = pending.description,
                expiresAt = pending.expiresAt,
            )
        )
        setStatus("等待确认：${pending.toolName}")
        val job = scope.launch {
            delay(permissionWaitMs(pending.expiresAt))
            expirePermission(pending.requestId)
        }
        synchronized(permissionJobs) {
            permissionJobs.remove(pending.requestId)?.cancel()
            permissionJobs[pending.requestId] = job
        }
    }

    /** Allows or denies one forwarded tool call; safe to call after the card expired. */
    fun respondPermission(requestId: String, allow: Boolean, always: Boolean = false) {
        if (_permissions.value.none { it.requestId == requestId }) return
        val backend = activeBackend ?: return
        removePermission(requestId, allow)
        scope.launch {
            runCatching {
                backend.respondPermission(
                    requestId,
                    PermissionDecision(allow = allow, always = allow && always),
                )
            }.onFailure {
                notifyError("确认发送失败：${it.message ?: "未知错误"}")
            }
        }
    }

    private fun expirePermission(requestId: String) {
        if (_permissions.value.none { it.requestId == requestId }) return
        removePermission(requestId, null)
        if (_permissions.value.isEmpty() && _running.value) {
            setStatus("确认已超时，主机将拒绝该操作")
        }
    }

    private fun removePermission(requestId: String, allowed: Boolean?) {
        if (_permissions.value.none { it.requestId == requestId }) return
        _permissions.update { list -> list.filterNot { it.requestId == requestId } }
        synchronized(permissionJobs) { permissionJobs.remove(requestId)?.cancel() }
        emitEvent(ChatEvent.PermissionResolved(requestId, allowed))
        if (_permissions.value.isNotEmpty()) {
            setStatus("等待确认：${_permissions.value.first().toolName}")
        } else if (_running.value) {
            setStatus(
                when (allowed) {
                    true -> "已允许，继续…"
                    false -> "已拒绝，继续…"
                    null -> "等待主机…"
                }
            )
        }
    }

    private fun clearPermissions() {
        val pending = _permissions.value
        if (pending.isEmpty()) return
        _permissions.value = emptyList()
        synchronized(permissionJobs) {
            permissionJobs.values.forEach { it.cancel() }
            permissionJobs.clear()
        }
        pending.forEach { emitEvent(ChatEvent.PermissionResolved(it.requestId, null)) }
    }

    /**
     * Host `expiresAt` is wall-clock and may be skewed; clamp to a window the daemon
     * actually uses (default 120 s) instead of trusting the difference verbatim.
     */
    private fun permissionWaitMs(expiresAt: Long): Long {
        if (expiresAt <= 0) return 120_000
        return (expiresAt - System.currentTimeMillis()).coerceIn(5_000, 130_000)
    }

    fun clear() {
        if (_running.value) {
            stopRequested = true
            activeBackend?.cancel(CancelReason.OTHER) ?: container.engine.cancel(CancelReason.OTHER)
            job?.cancel()
        }
        clearPermissions()
        generation += 1
        _messages.value = emptyList()
        emitMessages()
        claudeSessionId = null
        // The transcript is gone; keeping the old title would leave a conversation
        // named after a message it no longer contains.
        setTitle("新对话")
        titleIsTemporary = true
        titleGeneration += 1
        setTitleNotice(null)
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

    /**
     * True when the assistant message already carries a tool card with [id].
     *
     * Used to decide whether a tool event is worth emitting: the receiver mirrors
     * cards by id, so replaying a duplicate would make it append a second card.
     */
    private fun toolBlockExists(messageId: String, toolId: String): Boolean =
        _messages.value.firstOrNull { it.id == messageId }
            ?.blocks?.any { it is Block.Tool && it.id == toolId } == true

    private fun addTool(msg: ChatMessage, ev: EngineEvent.ToolUse): ChatMessage {
        if (msg.blocks.any { it is Block.Tool && it.id == ev.id }) return msg
        setStatus(ToolPresentation.verb(ev.name) + "…")
        return msg.copy(blocks = msg.blocks + Block.Tool(id = ev.id, name = ev.name, input = ev.inputJson))
    }

    private fun resolveTool(msg: ChatMessage, ev: EngineEvent.ToolResult): ChatMessage {
        setStatus("工具返回")
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

    private companion object {
        /** Same sanitised filenames `Attachments.import` produces (no spaces or slash). */
        val ATTACHMENT_REF = Regex("""@attachments/([A-Za-z0-9._\-一-龥]+)""")
    }
}
