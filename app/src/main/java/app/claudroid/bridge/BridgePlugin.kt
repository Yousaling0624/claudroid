package app.claudroid.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import app.claudroid.AppContainer
import app.claudroid.BuildConfig
import app.claudroid.ClaudroidApp
import app.claudroid.core.Attachments
import app.claudroid.core.FileKind
import app.claudroid.core.Logx
import app.claudroid.core.WorkspaceEntry
import app.claudroid.core.WorkspaceFiles
import app.claudroid.data.AppSettings
import app.claudroid.data.Providers
import app.claudroid.data.ReleaseCheck
import app.claudroid.engine.SuggestionGenerator
import app.claudroid.engine.TerminalRunner
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.chat.ChatController
import app.claudroid.chat.chatStatusLine
import app.claudroid.data.chatModelLabel
import app.claudroid.data.activeProviderPreset
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.StandardMethodCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin adapter exposing [AppContainer] to the Flutter frontend as one MethodChannel
 * and two kinds of EventChannel. It must stay logic-free: every decision lives in
 * `engine/`, `data/` or `ChatController` (docs/BRIDGE_CONTRACT.md §1).
 *
 * The Flutter UI is the app's only frontend, so this is compiled into every build.
 */
class BridgePlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var container: AppContainer
    private lateinit var messenger: BinaryMessenger

    /**
     * Last seen settings. `engineLabel` and `statusLine` are derived values that need
     * them; caching avoids re-reading DataStore on every engine tick.
     */
    @Volatile private var settingsSnapshot: AppSettings = AppSettings()

    private var methodChannel: MethodChannel? = null
    private var globalChannel: EventChannel? = null
    private var globalJob: Job? = null

    private val chatChannels = mutableMapOf<String, EventChannel>()
    private val chatJobs = mutableMapOf<String, Job>()
    private val chatLock = Mutex()

    /** The host Activity, used only for the document picker and for opening URLs. */
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPick: CompletableDeferred<Uri?>? = null

    /** One terminal pane at a time; `terminal.run` rejects overlap. */
    private var terminalChannel: EventChannel? = null
    private var terminalSink: EventChannel.EventSink? = null
    private var terminalJob: Job? = null
    private val terminalRunner by lazy {
        TerminalRunner(container.appContext, container.engine.rootExec)
    }

    private val pickResultListener = PluginRegistry.ActivityResultListener { code, result, data ->
        if (code != ATTACHMENT_REQUEST) {
            false
        } else {
            val pending = pendingPick
            pendingPick = null
            pending?.complete(if (result == Activity.RESULT_OK) data?.data else null)
            true
        }
    }

    // ------------------------------------------------------------------ plugin

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        container = (binding.applicationContext as ClaudroidApp).container
        messenger = binding.binaryMessenger
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL, StandardMethodCodec.INSTANCE).also {
            it.setMethodCallHandler(this)
        }
        globalChannel = EventChannel(messenger, GLOBAL_EVENTS, StandardMethodCodec.INSTANCE).also {
            it.setStreamHandler(GlobalStreamHandler())
        }
        terminalChannel = EventChannel(messenger, TERMINAL_EVENTS, StandardMethodCodec.INSTANCE).also {
            it.setStreamHandler(TerminalStreamHandler())
        }
        binding.platformViewRegistry.registerViewFactory(
            HtmlPreviewView.VIEW_TYPE,
            HtmlPreviewView.Factory(messenger),
        )
        Logx.i("bridge: attached", binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        globalChannel?.setStreamHandler(null)
        globalChannel = null
        globalJob?.cancel()
        globalJob = null
        terminalJob?.cancel()
        terminalJob = null
        terminalSink = null
        terminalChannel?.setStreamHandler(null)
        terminalChannel = null
        val ids = synchronized(chatChannels) { chatChannels.keys.toList() }
        ids.forEach { unregisterChatChannel(it) }
        // Controllers and running turns belong to the app, not the engine: detaching
        // must not kill a turn (the user may still be streaming in another host).
    }

    // The Activity is only needed for the document picker (attachments.pick) and for
    // launching an external browser; nothing else in the bridge is Activity-bound.
    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attachActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        attachActivity(binding)

    override fun onDetachedFromActivity() = detachActivity()

    private fun attachActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addActivityResultListener(pickResultListener)
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(pickResultListener)
        activityBinding = null
        activity = null
        // A picker that never returned must not leave the Dart caller awaiting forever.
        pendingPick?.complete(null)
        pendingPick = null
    }

    // ----------------------------------------------------------------- methods

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // Handler runs on the main thread; never block it. Every body is suspend.
        scope.launch {
            runCatching { dispatch(call) }
                .onSuccess { result.success(it) }
                .onFailure {
                    Logx.e("bridge: ${call.method} 失败: ${it.message}")
                    result.success(mapOf("ok" to false, "error" to (it.message ?: "桥接调用失败")))
                }
        }
    }

    private suspend fun dispatch(call: MethodCall): Map<String, Any?> {
        Logx.i("bridge: ${call.method}")
        return when (call.method) {
            "app.init" -> appInit()
            "app.suggestions" -> appSuggestions(call)
            "app.info" -> appInfo()
            "app.release" -> appRelease()
            "app.openUrl" -> appOpenUrl(call)
            "settings.update" -> settingsUpdate(call)
            "providers.list" -> providersList()
            "providers.apply" -> providersApply(call)
            "models.list" -> modelsFetch(testOnly = false)
            "models.test" -> modelsFetch(testOnly = true)
            "attachments.pick" -> attachmentsPick()
            "workspace.list" -> workspaceList(call)
            "workspace.preview" -> workspacePreview(call)
            "workspace.delete" -> workspaceDelete(call)
            "workspace.open" -> workspaceIntent(call, share = false)
            "workspace.share" -> workspaceIntent(call, share = true)
            "workspace.spaces" -> workspaceState()
            "workspace.create" -> workspaceCreate(call)
            "workspace.select" -> workspaceSelect(call)
            "workspace.rename" -> workspaceRename(call)
            "workspace.remove" -> workspaceRemove(call)
            "terminal.info" -> terminalInfo()
            "terminal.run" -> terminalRun(call)
            "terminal.stop" -> terminalStop()
            "logs.read" -> mapOf("ok" to true, "linesJson" to BridgeWire.stringListJson(Logx.lines.value))
            "logs.clear" -> { Logx.clear(); ok() }
            "sessions.list" ->
                mapOf("ok" to true, "sessionsJson" to BridgeWire.sessionsJson(container.sessions.sessions.value))
            "sessions.new" -> sessionsNew(call)
            "sessions.delete" -> sessionsDelete(call)
            "sessions.rename" -> sessionsRename(call)
            "engine.bootstrap" -> { container.engine.bootstrap(); ok() }
            "engine.install" -> { container.engine.install(call.bool("force")); ok() }
            "engine.checkUpdate" -> { container.engine.checkForUpdate(call.bool("quiet")); ok() }
            "engine.probeRoot" -> {
                // `probeRoot` is not suspend and performs a blocking `su` round trip;
                // on `Dispatchers.Main.immediate` it would freeze the UI mid-dialog.
                withContext(Dispatchers.IO) { container.engine.probeRoot() }
                mapOf("ok" to true, "granted" to container.engine.state.value.rootGranted)
            }
            "engine.selfTest" -> mapOf("ok" to true, "report" to container.engine.selfTest())
            "engine.rootProbeReport" -> mapOf("ok" to true, "report" to container.engine.rootProbeReport())
            "engine.refreshNetwork" -> {
                val result = container.engine.ensureNetworkConfig(force = true)
                mapOf(
                    "ok" to true,
                    "changed" to result.changed,
                    "skipped" to result.skipped,
                    "dns" to result.dns,
                    "message" to result.message,
                )
            }
            "engine.storageUsage" -> {
                val usage = container.engine.storageUsage()
                mapOf(
                    "ok" to true,
                    "jailBytes" to usage.jailBytes,
                    "workspaceBytes" to usage.workspaceBytes,
                    "sessionBytes" to usage.sessionBytes,
                )
            }
            "engine.uninstall" -> { container.engine.uninstall(call.bool("deleteWorkspace")); ok() }
            "engine.cancel" -> { container.engine.cancel(); ok() }
            "chat.attach" -> chatAttach(call)
            "chat.send" -> chatSend(call)
            "chat.resendFrom" -> chatResendFrom(call)
            "chat.regenerate" -> chatRegenerate(call)
            "chat.fork" -> chatFork(call)
            "chat.stop" -> chatStop(call)
            "chat.clear" -> chatClear(call)
            "chat.consumeTitleNotice" -> chatConsumeTitleNotice(call)
            "chat.markTitleEdited" -> chatMarkTitleEdited(call)
            "chat.regenerateTitle" -> chatRegenerateTitle(call)
            "chat.forget" -> chatForget(call)
            "chat.close" -> chatClose(call)
            else -> mapOf("ok" to false, "error" to "未知方法 ${call.method}")
        }
    }

    private suspend fun appSuggestions(call: MethodCall): Map<String, Any?> {
        val count = call.int("count", 3).coerceIn(1, 5)
        val context = call.str("context").orEmpty()
        val items = SuggestionGenerator.generate(container.settings.current(), count, context)
        return mapOf("ok" to true, "itemsJson" to BridgeWire.stringListJson(items))
    }

    private fun appInfo(): Map<String, Any?> = mapOf(
        "ok" to true,
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
    )

    private suspend fun appRelease(): Map<String, Any?> {
        val release = ReleaseCheck.fetch().getOrThrow()
        return mapOf(
            "ok" to true,
            "releaseJson" to BridgeWire.releaseJson(release, BuildConfig.VERSION_NAME),
        )
    }

    private fun appOpenUrl(call: MethodCall): Map<String, Any?> {
        val raw = call.require("url")
        val uri = Uri.parse(raw)
        require(uri.scheme == "http" || uri.scheme == "https") { "只允许打开 http/https 链接" }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val host = activity
        if (host != null) {
            host.startActivity(intent)
        } else {
            container.appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return ok()
    }

    private suspend fun providersList(): Map<String, Any?> {
        val settings = container.settings.current()
        return mapOf(
            "ok" to true,
            "presetsJson" to BridgeWire.providersJson(Providers.all),
            "activeId" to activeProviderPreset(settings)?.id,
        )
    }

    private suspend fun providersApply(call: MethodCall): Map<String, Any?> {
        val id = call.require("presetId")
        val preset = Providers.all.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("未知服务商 $id")
        container.settings.edit { Providers.apply(it, preset) }
        val settings = container.settings.current()
        settingsSnapshot = settings
        return mapOf(
            "ok" to true,
            "settingsJson" to BridgeWire.settingsJson(settings),
            "labelsJson" to BridgeWire.labelsJson(settings),
        )
    }

    private suspend fun modelsFetch(testOnly: Boolean): Map<String, Any?> {
        val result = ModelCatalog.fetch(container.settings.current())
        return if (testOnly) {
            mapOf("ok" to true, "endpoint" to result.endpoint, "count" to result.models.size)
        } else {
            mapOf(
                "ok" to true,
                "modelsJson" to BridgeWire.stringListJson(result.models),
                "modelDetailsJson" to BridgeWire.modelDetailsJson(result.details),
                "endpoint" to result.endpoint,
            )
        }
    }

    private suspend fun attachmentsPick(): Map<String, Any?> {
        val host = activity ?: throw IllegalStateException("界面不在前台，无法选择文件")
        val deferred = CompletableDeferred<Uri?>()
        pendingPick = deferred
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        host.startActivityForResult(intent, ATTACHMENT_REQUEST)
        val uri = try {
            deferred.await()
        } finally {
            if (pendingPick === deferred) pendingPick = null
        } ?: return mapOf(
            "ok" to true,
            "cancelled" to true,
            "relativePathsJson" to "[]",
        )
        // Copying can exceed 512 MB and must not run on the platform thread.
        val workspace = workspaceRoot()
        val relative = withContext(Dispatchers.IO) {
            val file = Attachments.import(host, uri, workspace).getOrThrow()
            Attachments.relativePath(workspace, file)
        }
        return mapOf(
            "ok" to true,
            "cancelled" to false,
            "relativePathsJson" to BridgeWire.stringListJson(listOf(relative)),
        )
    }

    // ------------------------------------------------------------- workspace

    private fun workspaceRoot(): File = container.workspaces.activeDir()

    private fun workspaceState(): Map<String, Any?> = mapOf(
        "ok" to true,
        "workspacesJson" to BridgeWire.workspacesJson(
            container.workspaces.activeId(),
            container.workspaces.all(),
        ),
    )

    /**
     * A running turn or terminal already holds the previous `/work` bind (and in
     * chroot mode a fixed bind-mount), so switching underneath it would mix two trees
     * in one command. Refuse until the work is done instead of silently disagreeing.
     */
    private fun requireWorkspaceIdle() {
        if (container.engine.busy || terminalJob?.isActive == true) {
            throw IllegalStateException("任务正在运行，等这一轮结束后再切换工作区")
        }
    }

    private suspend fun workspaceCreate(call: MethodCall): Map<String, Any?> {
        requireWorkspaceIdle()
        val info = container.workspaces.create(call.require("name"))
        container.workspaces.select(info.id)
        return workspaceState()
    }

    private suspend fun workspaceSelect(call: MethodCall): Map<String, Any?> {
        requireWorkspaceIdle()
        if (!container.workspaces.select(call.require("id"))) {
            throw IllegalArgumentException("工作区不存在")
        }
        return workspaceState()
    }

    private suspend fun workspaceRename(call: MethodCall): Map<String, Any?> {
        if (!container.workspaces.rename(call.require("id"), call.require("name"))) {
            throw IllegalArgumentException("默认工作区不能重命名，或工作区不存在")
        }
        return workspaceState()
    }

    private suspend fun workspaceRemove(call: MethodCall): Map<String, Any?> {
        requireWorkspaceIdle()
        if (!container.workspaces.remove(call.require("id"))) {
            throw IllegalArgumentException("默认工作区不能删除，或工作区不存在")
        }
        return workspaceState()
    }

    /** Resolves a channel-supplied relative path, refusing anything outside the active /work. */
    private fun workspaceFile(call: MethodCall): Pair<File, String> {
        val root = workspaceRoot()
        val file = WorkspaceFiles.resolve(root, call.str("path").orEmpty())
            ?: throw IllegalArgumentException("路径不在工作区内")
        return file to WorkspaceFiles.relativePath(root, file)
    }

    private suspend fun workspaceList(call: MethodCall): Map<String, Any?> {
        val (dir, relative) = workspaceFile(call)
        if (!dir.isDirectory) throw IllegalArgumentException("不是文件夹")
        val entries = withContext(Dispatchers.IO) { WorkspaceFiles.list(dir) }
        return mapOf(
            "ok" to true,
            "path" to relative,
            "entriesJson" to BridgeWire.workspaceEntriesJson(entries, workspaceRoot()),
            "totalBytes" to entries.filterNot { it.isDirectory }.sumOf { it.size },
        )
    }

    private suspend fun workspacePreview(call: MethodCall): Map<String, Any?> {
        val (file, relative) = workspaceFile(call)
        if (!file.isFile) throw IllegalArgumentException("文件不存在")
        val kind = WorkspaceFiles.kindOf(file)
        val entry = WorkspaceEntry(file, file.name, kind, file.length(), file.lastModified())
        val common = mapOf<String, Any?>(
            "ok" to true,
            "path" to relative,
            "name" to file.name,
            "kind" to kind.name,
            "mime" to WorkspaceFiles.mimeOf(entry),
            "size" to file.length(),
            "modifiedAt" to file.lastModified(),
            "truncated" to false,
            "tooLarge" to false,
        )
        if (kind == FileKind.IMAGE) {
            val bytes = if (file.length() <= MAX_IMAGE_BYTES) {
                withContext(Dispatchers.IO) { file.readBytes() }
            } else {
                null
            }
            return if (bytes == null) {
                common + ("tooLarge" to true)
            } else {
                common + ("bytes" to bytes)
            }
        }
        val preview = withContext(Dispatchers.IO) { WorkspaceFiles.readPreview(file) }
            ?: return common
        return common + mapOf(
            "text" to preview.text,
            "truncated" to preview.truncated,
        )
    }

    private suspend fun workspaceDelete(call: MethodCall): Map<String, Any?> {
        val (file, _) = workspaceFile(call)
        if (file.isDirectory) throw IllegalArgumentException("不能删除文件夹")
        if (!file.isFile) throw IllegalArgumentException("文件不存在")
        val deleted = withContext(Dispatchers.IO) { file.delete() }
        if (!deleted) throw IllegalStateException("删除失败，文件可能仍被占用")
        return ok()
    }

    private fun workspaceIntent(call: MethodCall, share: Boolean): Map<String, Any?> {
        val (file, _) = workspaceFile(call)
        if (!file.isFile) throw IllegalArgumentException("文件不存在")
        val kind = WorkspaceFiles.kindOf(file)
        val entry = WorkspaceEntry(file, file.name, kind, file.length(), file.lastModified())
        val uri = FileProvider.getUriForFile(
            container.appContext,
            "${container.appContext.packageName}.files",
            file,
        )
        val target = if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = WorkspaceFiles.mimeOf(entry)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, WorkspaceFiles.mimeOf(entry))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val intent = if (share) Intent.createChooser(target, "分享 ${file.name}") else target
        val host = activity
        try {
            if (host != null) {
                host.startActivity(intent)
            } else {
                container.appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            throw IllegalStateException(if (share) "无法分享：${e.message}" else "没有能打开该文件的应用")
        }
        return ok()
    }

    // -------------------------------------------------------------- terminal

    private suspend fun terminalInfo(): Map<String, Any?> {
        val settings = container.settings.current()
        settingsSnapshot = settings
        return mapOf(
            "ok" to true,
            "label" to container.engine.shellPlan(settings)?.label,
            "issue" to container.engine.startIssue(settings),
        )
    }

    private suspend fun terminalRun(call: MethodCall): Map<String, Any?> {
        if (terminalJob?.isActive == true) throw IllegalStateException("终端已有命令在运行")
        if (terminalSink == null) throw IllegalStateException("终端未连接")
        val command = call.require("command")
        val cwdRel = TerminalRunner.sanitizeCwd(call.str("cwdRel").orEmpty())
        val settings = container.settings.current()
        settingsSnapshot = settings
        val issue = container.engine.startIssue(settings)
        if (issue != null) throw IllegalStateException(issue)
        val plan = container.engine.shellPlan(settings)
            ?: throw IllegalStateException("引擎未安装，请先在设置中安装 Claude Code。")

        // LAZY so `terminalJob` is assigned before the body can reach its finally block.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = terminalRunner.run(plan, command, cwdRel, workspaceRoot()) { chunk ->
                    withContext(Dispatchers.Main) {
                        terminalSink?.success(mapOf("type" to "chunk", "text" to chunk))
                    }
                }
                withContext(Dispatchers.Main) {
                    terminalSink?.success(
                        mapOf(
                            "type" to "done",
                            "exitCode" to result.exitCode,
                            "cwdRel" to result.cwdRel,
                            "error" to result.error,
                            "truncated" to result.truncated,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    terminalSink?.success(mapOf("type" to "stopped"))
                }
                throw ce
            } catch (e: Exception) {
                // A cancelled job can surface the pipe-closed IOException instead of a
                // CancellationException (see TerminalRunner's reader); report a stop
                // rather than an execution failure.
                val stopped = terminalJob?.isCancelled == true
                if (!stopped) Logx.e("bridge: terminal failed: ${e.message}")
                withContext(NonCancellable + Dispatchers.Main) {
                    terminalSink?.success(
                        if (stopped) {
                            mapOf("type" to "stopped")
                        } else {
                            mapOf("type" to "error", "message" to (e.message ?: "执行失败"))
                        },
                    )
                }
            } finally {
                terminalJob = null
            }
        }
        terminalJob = job
        job.start()
        return ok()
    }

    private fun terminalStop(): Map<String, Any?> {
        terminalJob?.cancel()
        return ok()
    }

    private suspend fun appInit(): Map<String, Any?> {
        container.sessions.load()
        val settings = container.settings.current()
        settingsSnapshot = settings
        val sessions = container.sessions.sessions.value
        // Dart may subscribe to any listed session right after this call.
        sessions.forEach { ensureChatChannel(it.id) }
        return mapOf(
            "ok" to true,
            "settingsJson" to BridgeWire.settingsJson(settings),
            "labelsJson" to BridgeWire.labelsJson(settings),
            "engineJson" to BridgeWire.engineJson(container.engine.state.value, settings),
            "sessionsJson" to BridgeWire.sessionsJson(sessions),
        )
    }

    private suspend fun settingsUpdate(call: MethodCall): Map<String, Any?> {
        val raw = call.require("settingsJson")
        val decoded = BridgeWire.decodeSettings(raw)
        container.settings.edit { decoded }
        val settings = container.settings.current()
        settingsSnapshot = settings
        // The payload carries apiKey and extraEnv: it must never reach the log.
        return mapOf(
            "ok" to true,
            "settingsJson" to BridgeWire.settingsJson(settings),
            "labelsJson" to BridgeWire.labelsJson(settings),
        )
    }

    private suspend fun sessionsNew(call: MethodCall): Map<String, Any?> {
        // Deliberately not persisted here: an empty session is litter, and the first
        // turn writes it (ChatController.persist). Dart navigates into it directly.
        val session = container.sessions.newSession(call.str("model"))
        ensureChatChannel(session.id)
        return mapOf("ok" to true, "sessionJson" to BridgeWire.sessionJson(session))
    }

    private suspend fun sessionsDelete(call: MethodCall): Map<String, Any?> {
        val id = call.require("id")
        container.closeChat(id)
        unregisterChatChannel(id)
        container.sessions.delete(id)
        return ok()
    }

    private suspend fun sessionsRename(call: MethodCall): Map<String, Any?> {
        val id = call.require("id")
        val title = call.require("title")
        container.sessions.rename(id, title)
        // The drawer and the open chat read different sources; keep the controller in
        // sync so a rename is visible immediately (and is protected from auto-titles).
        val chat = container.chat(id)
        chat.load()
        chat.markTitleUserEdited(title)
        return ok()
    }

    private suspend fun chatAttach(call: MethodCall): Map<String, Any?> {
        val controller = controller(call)
        controller.load()
        val settings = container.settings.current()
        settingsSnapshot = settings
        return mapOf("ok" to true, "chatJson" to chatJson(controller, settings))
    }

    private suspend fun chatSend(call: MethodCall): Map<String, Any?> {
        val controller = controller(call)
        controller.load()
        val text = call.require("text")
        return mapOf("ok" to true, "accepted" to controller.send(text))
    }

    private suspend fun chatResendFrom(call: MethodCall): Map<String, Any?> {
        val controller = controller(call)
        controller.load()
        return mapOf(
            "ok" to true,
            "accepted" to controller.resendFrom(call.require("messageId"), call.require("text")),
        )
    }

    private suspend fun chatRegenerate(call: MethodCall): Map<String, Any?> {
        val controller = controller(call)
        controller.load()
        controller.regenerate(call.require("messageId"))
        return ok()
    }

    /**
     * Branches at one message. The returned id is a new `ChatSession` already in
     * the session list; the UI opens it through the normal `chat.attach` path.
     */
    private suspend fun chatFork(call: MethodCall): Map<String, Any?> {
        val controller = controller(call)
        controller.load()
        val session = controller.forkAt(call.require("messageId"))
            ?: return mapOf("ok" to false, "error" to "要分叉的消息已不存在，请重新打开对话")
        return mapOf("ok" to true, "sessionId" to session.id)
    }

    private suspend fun chatStop(call: MethodCall): Map<String, Any?> {
        controller(call).stop()
        return ok()
    }

    private suspend fun chatClear(call: MethodCall): Map<String, Any?> {
        controller(call).clear()
        return ok()
    }

    private suspend fun chatConsumeTitleNotice(call: MethodCall): Map<String, Any?> {
        controller(call).consumeTitleNotice()
        return ok()
    }

    private suspend fun chatMarkTitleEdited(call: MethodCall): Map<String, Any?> {
        controller(call).markTitleUserEdited(call.require("title"))
        return ok()
    }

    private suspend fun chatRegenerateTitle(call: MethodCall): Map<String, Any?> {
        controller(call).regenerateTitle()
        return ok()
    }

    private suspend fun chatForget(call: MethodCall): Map<String, Any?> {
        val id = call.require("sessionId")
        // Drops the controller only; a running turn keeps going (contract §6), and the
        // channel stays subscribed so the in-flight turn's deltas still arrive. The
        // next `chat.attach` rebuilds the controller from disk.
        container.forgetChat(id)
        return ok()
    }

    private suspend fun chatClose(call: MethodCall): Map<String, Any?> {
        val id = call.require("sessionId")
        container.closeChat(id)
        unregisterChatChannel(id)
        return ok()
    }

    private suspend fun controller(call: MethodCall): ChatController {
        val id = call.require("sessionId")
        ensureChatChannel(id)
        return container.chat(id)
    }

    // ------------------------------------------------------------ event wiring

    private inner class GlobalStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            globalJob?.cancel()
            globalJob = scope.launch {
                container.sessions.load()
                val settings = container.settings.current()
                settingsSnapshot = settings
                val sessions = container.sessions.sessions.value
                // §4: one `ready` message so Dart never has to poll for initial state.
                sink.success(
                    mapOf(
                        "type" to "ready",
                        "settingsJson" to BridgeWire.settingsJson(settings),
                        "labelsJson" to BridgeWire.labelsJson(settings),
                        "engineJson" to BridgeWire.engineJson(container.engine.state.value, settings),
                        "sessionsJson" to BridgeWire.sessionsJson(sessions),
                    ),
                )
                sessions.forEach { ensureChatChannel(it.id) }
                // Each collector re-emits its current value once; Dart treats these
                // as idempotent snapshots, so the duplicate is harmless.
                launch {
                    container.settings.flow.collect { s ->
                        settingsSnapshot = s
                        sink.success(
                            mapOf(
                                "type" to "settings",
                                "settingsJson" to BridgeWire.settingsJson(s),
                                "labelsJson" to BridgeWire.labelsJson(s),
                            ),
                        )
                    }
                }
                launch {
                    container.engine.state.collect { st ->
                        sink.success(
                            mapOf("type" to "engine", "engineJson" to BridgeWire.engineJson(st, settingsSnapshot)),
                        )
                    }
                }
                launch {
                    container.sessions.sessions.collect { list ->
                        sink.success(
                            mapOf("type" to "sessions", "sessionsJson" to BridgeWire.sessionsJson(list)),
                        )
                    }
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            globalJob?.cancel()
            globalJob = null
        }
    }

    private inner class TerminalStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            terminalSink = sink
        }

        override fun onCancel(arguments: Any?) {
            terminalSink = null
            // The terminal page cancels its command on dispose; this covers a dropped
            // subscription too, so a child process never outlives the pane.
            terminalJob?.cancel()
            terminalJob = null
        }
    }

    private inner class ChatStreamHandler(private val sessionId: String) : EventChannel.StreamHandler {

        /**
         * Bumped by every `onListen` / `onCancel`. Both callbacks run on the platform
         * thread, but the body below suspends in `chat.load()`, so a cancel can land
         * before the collector is registered; the token lets the late body notice and
         * cancel its own job instead of leaking a collector on a dead sink.
         */
        private var listenToken = 0

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            val token = ++listenToken
            scope.launch {
                chatLock.withLock { chatJobs.remove(sessionId)?.cancel() }
                val chat = container.chat(sessionId)
                chat.load()
                val settings = container.settings.current()
                settingsSnapshot = settings
                // Snapshot first: SharedFlow replays nothing, so anything emitted
                // before the collector attaches is covered by the full state. The
                // reverse order would deliver those deltas *and* include them in the
                // snapshot, doubling the text on the Dart side.
                sink.success(snapshotEvent(chat, settings))
                val job = launch {
                    chat.events.collect { ev ->
                        // `settingsSnapshot` follows the DataStore stream, so a model
                        // change while the chat is open is reflected in statusLine.
                        sink.success(BridgeWire.chatEvent(ev, chatModelLabel(settingsSnapshot)))
                    }
                }
                chatLock.withLock {
                    if (token == listenToken) chatJobs[sessionId] = job else job.cancel()
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            // Only the subscription ends. The turn and controller survive a page
            // switch; `chat.close` is the explicit release (§2).
            listenToken++
            scope.launch { chatLock.withLock { chatJobs.remove(sessionId)?.cancel() } }
        }
    }

    private fun snapshotEvent(chat: ChatController, settings: AppSettings): Map<String, Any?> =
        mapOf("type" to "snapshot", "chatJson" to chatJson(chat, settings))

    private fun chatJson(chat: ChatController, settings: AppSettings): String = BridgeWire.chatJson(
        messages = chat.messages.value,
        title = chat.title.value,
        titleBusy = chat.titleBusy.value,
        titleNotice = chat.titleNotice.value,
        running = chat.running.value,
        status = chat.status.value,
        statusLine = chatStatusLine(chat.running.value, chat.status.value, chatModelLabel(settings)),
    )

    private fun ensureChatChannel(id: String) {
        if (!::messenger.isInitialized) return
        synchronized(chatChannels) {
            if (chatChannels.containsKey(id)) return
            chatChannels[id] = EventChannel(messenger, chatChannel(id), StandardMethodCodec.INSTANCE).also {
                it.setStreamHandler(ChatStreamHandler(id))
            }
        }
    }

    private fun unregisterChatChannel(id: String) {
        synchronized(chatChannels) { chatChannels.remove(id) }?.setStreamHandler(null)
        scope.launch { chatLock.withLock { chatJobs.remove(id)?.cancel() } }
    }

    private fun ok(): Map<String, Any?> = mapOf("ok" to true)

    private fun MethodCall.require(key: String): String =
        str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("缺少 $key")

    /** Complex values arrive as JSON strings; anything else is untrusted input. */
    private fun MethodCall.str(key: String): String? =
        (arguments as? Map<*, *>)?.get(key) as? String

    private fun MethodCall.bool(key: String, default: Boolean = false): Boolean =
        (arguments as? Map<*, *>)?.get(key) as? Boolean ?: default

    private fun MethodCall.int(key: String, default: Int): Int =
        when (val value = (arguments as? Map<*, *>)?.get(key)) {
            is Int -> value
            is Number -> value.toInt()
            else -> default
        }

    // ----------------------------------------------------------------- statics

    companion object {
        const val METHOD_CHANNEL = "app.claudroid/bridge"
        const val GLOBAL_EVENTS = "app.claudroid/events"
        const val TERMINAL_EVENTS = "app.claudroid/terminal"
        const val ENGINE_CACHE_KEY = "claudroid"

        private const val ATTACHMENT_REQUEST = 0x0A77
        private const val MAX_IMAGE_BYTES = 6L * 1024 * 1024

        /** Guards against attaching two plugins to the same engine (duplicate channels). */
        @Volatile private var attached = false

        fun chatChannel(sessionId: String) = "app.claudroid/chat/$sessionId"

        /**
         * Creates (or reuses) the one cached engine and registers the bridge on it.
         * Called from `ClaudroidApp.onCreate` and from `MainActivity` before the
         * engine is resolved; idempotent. `FlutterEngine` may only be constructed on
         * the main thread, and callers may come from any thread, so hop first.
         */
        fun prewarm(app: Context) {
            Handler(Looper.getMainLooper()).post { attachCachedEngine(app) }
        }

        /** Adds the bridge to an engine the caller already created. */
        @JvmStatic
        fun register(engine: FlutterEngine) {
            if (attached) return
            attached = true
            engine.plugins.add(BridgePlugin())
        }

        /**
         * The cached engine is created once and reused across Activity recreation,
         * so channels never disappear (§2).
         */
        internal fun attachCachedEngine(app: Context) {
            // An explicit `register` may have won the race while this was posted.
            if (attached) return
            val cache = FlutterEngineCache.getInstance()
            val engine = cache.get(ENGINE_CACHE_KEY) ?: FlutterEngine(app).also { cache.put(ENGINE_CACHE_KEY, it) }
            register(engine)
        }
    }
}
