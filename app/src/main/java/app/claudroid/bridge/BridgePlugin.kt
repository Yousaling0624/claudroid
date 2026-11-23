package app.claudroid.bridge

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.claudroid.AppContainer
import app.claudroid.BuildConfig
import app.claudroid.ClaudroidApp
import app.claudroid.backup.BackupWire
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
import app.claudroid.engine.TerminalSession
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.chat.ChatController
import app.claudroid.chat.chatStatusLine
import app.claudroid.data.chatModelLabel
import app.claudroid.data.activeProviderPreset
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var backupChannel: EventChannel? = null
    private var backupJob: Job? = null

    private val chatChannels = mutableMapOf<String, EventChannel>()
    private val chatJobs = mutableMapOf<String, Job>()
    private val chatLock = Mutex()

    /** The host Activity, used for the document picker, QR scanner and opening URLs. */
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private var pendingScan: CompletableDeferred<String?>? = null
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null
    private var pendingCapture: CompletableDeferred<Boolean>? = null

    /** Guards the whole capture round trip, including the import after the camera. */
    private var captureInProgress = false

    /** One SAF picker at a time; export and import share this slot. */
    private var pendingDocument: CompletableDeferred<Uri?>? = null

    /**
     * One terminal pane at a time. `terminalSession` is the persistent TTY shell;
     * `terminalJob` still means "something is running" (a session's wait job or a
     * one-shot `terminal.run`), so the workspace-switch guard stays valid.
     */
    private var terminalChannel: EventChannel? = null
    private var terminalSink: EventChannel.EventSink? = null
    private var terminalJob: Job? = null
    private var terminalSession: TerminalSession? = null
    private val terminalRunner by lazy {
        TerminalRunner(container.appContext, container.engine.rootExec)
    }

    private val pickResultListener = PluginRegistry.ActivityResultListener { code, result, data ->
        val picked = if (result == Activity.RESULT_OK) data?.data else null
        when (code) {
            ATTACHMENT_REQUEST -> {
                val pending = pendingPick
                pendingPick = null
                pending?.complete(picked)
                true
            }
            ATTACHMENT_CAPTURE_REQUEST -> {
                // The photo arrives through EXTRA_OUTPUT, so only the result code
                // matters; the caller validates that the file has content.
                val pending = pendingCapture
                pendingCapture = null
                pending?.complete(result == Activity.RESULT_OK)
                true
            }
            BACKUP_EXPORT_REQUEST, BACKUP_IMPORT_REQUEST -> {
                val pending = pendingDocument
                pendingDocument = null
                pending?.complete(picked)
                true
            }
            SCAN_REQUEST -> {
                // ZXing's helper only accepts a real result intent; a user back-out
                // arrives as RESULT_CANCELED and simply yields no text.
                val text = if (result == Activity.RESULT_OK && data != null) {
                    runCatching {
                        ScanIntentResult.parseActivityResult(result, data).contents
                    }.getOrNull()
                } else {
                    null
                }
                val pending = pendingScan
                pendingScan = null
                pending?.complete(text)
                true
            }
            else -> false
        }
    }

    /**
     * The camera permission is requested at capture time: the manifest declares
     * CAMERA, and devices that enforce the declaration refuse `ACTION_IMAGE_CAPTURE`
     * with a SecurityException when the runtime grant is missing.
     */
    private val cameraPermissionListener =
        PluginRegistry.RequestPermissionsResultListener { requestCode, _, grantResults ->
            if (requestCode != CAMERA_PERMISSION_REQUEST) {
                false
            } else {
                val pending = pendingCameraPermission
                pendingCameraPermission = null
                pending?.complete(
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED,
                )
                true
            }
        }

    // ------------------------------------------------------------------ plugin

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        container = (binding.applicationContext as ClaudroidApp).container
        // The auto-backup JobService runs without this plugin attached, so it cannot
        // read terminalJob/terminalSession directly; the probe keeps that state in
        // the bridge while still letting the job ask "is a terminal busy?".
        container.terminalProbe = { terminalJob?.isActive == true || terminalSession != null }
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
        backupChannel = EventChannel(messenger, BACKUP_EVENTS, StandardMethodCodec.INSTANCE).also {
            it.setStreamHandler(BackupStreamHandler())
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
        val session = terminalSession
        if (terminalSession === session) terminalSession = null
        terminalJob?.cancel()
        terminalJob = null
        terminalSink = null
        terminalChannel?.setStreamHandler(null)
        terminalChannel = null
        backupJob?.cancel()
        backupJob = null
        backupChannel?.setStreamHandler(null)
        backupChannel = null
        // A live shell must not outlive the engine. `stop` is idempotent, so racing a
        // page-level `terminal.stop` is harmless.
        if (session != null) scope.launch { session.stop() }
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
        binding.addRequestPermissionsResultListener(cameraPermissionListener)
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(pickResultListener)
        activityBinding?.removeRequestPermissionsResultListener(cameraPermissionListener)
        activityBinding = null
        activity = null
        // A picker that never returned must not leave the Dart caller awaiting forever.
        pendingPick?.complete(null)
        pendingPick = null
        pendingDocument?.complete(null)
        pendingDocument = null
        pendingScan?.complete(null)
        pendingScan = null
        // Denying here aborts a capture whose result can no longer be delivered.
        pendingCameraPermission?.complete(false)
        pendingCameraPermission = null
        pendingCapture?.complete(false)
        pendingCapture = null
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
            "attachments.capture" -> attachmentsCapture()
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
            "share.text" -> shareText(call)
            "share.image" -> shareImage(call)
            "terminal.info" -> terminalInfo()
            "terminal.start" -> terminalStart(call)
            "terminal.write" -> terminalWrite(call)
            "terminal.resize" -> terminalResize(call)
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
            "engine.updateCli" -> { container.engine.install(cliOnly = true); ok() }
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
            "backup.config" -> backupConfig()
            "backup.saveConfig" -> backupSaveConfig(call)
            "backup.status" -> backupStatus()
            "backup.test" -> backupTest(call)
            "backup.run" -> backupRun()
            "backup.restore" -> backupRestore(call)
            "backup.export" -> backupExport()
            "backup.import" -> backupImport()
            "backup.cancel" -> { container.backup.cancel(); ok() }
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
            "chat.permission" -> chatPermission(call)
            "hosts.list" -> hostsState()
            "hosts.pair" -> hostsPair(call)
            "hosts.scanPairing" -> hostsScanPairing()
            "hosts.update" -> hostsUpdate(call)
            "hosts.remove" -> hostsRemove(call)
            "hosts.test" -> hostsTest(call)
            "hosts.setDefault" -> hostsSetDefault(call)
            "hosts.browse" -> hostsBrowse(call)
            "hosts.download" -> hostsDownload(call)
            "hosts.open" -> hostsIntent(call, share = false)
            "hosts.share" -> hostsIntent(call, share = true)
            "hosts.tunnel" -> hostsTunnel(call)
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

    /** Suspends until the runtime camera grant is known; a denial is a user-facing error. */
    private suspend fun ensureCameraPermission(host: Activity): Boolean {
        if (
            ContextCompat.checkSelfPermission(host, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission = deferred
        host.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        val granted = try {
            deferred.await()
        } finally {
            if (pendingCameraPermission === deferred) pendingCameraPermission = null
        }
        if (!granted) {
            throw IllegalStateException(
                "没有相机权限，无法拍照。请在系统设置里允许 Claudroid 使用相机后重试。",
            )
        }
        return true
    }

    private suspend fun attachmentsCapture(): Map<String, Any?> {
        val host = activity ?: throw IllegalStateException("界面不在前台，无法拍照")
        if (captureInProgress) {
            throw IllegalStateException("正在拍照，请等当前相机操作结束")
        }
        captureInProgress = true
        try {
            ensureCameraPermission(host)
            // The camera writes into our own cache over a FileProvider URI, so the
            // photo never has to round-trip through the result intent.
            val dir = File(host.cacheDir, "capture")
            val shot = withContext(Dispatchers.IO) {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IllegalStateException("无法创建拍照缓存目录")
                }
                CaptureFiles.prune(dir, System.currentTimeMillis())
                CaptureFiles.newPhotoFile(dir, System.currentTimeMillis())
            }
            val uri = FileProvider.getUriForFile(host, "${host.packageName}.files", shot)
            val deferred = CompletableDeferred<Boolean>()
            pendingCapture = deferred
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            try {
                host.startActivityForResult(intent, ATTACHMENT_CAPTURE_REQUEST)
            } catch (notFound: ActivityNotFoundException) {
                pendingCapture = null
                shot.delete()
                throw IllegalStateException("没有找到可用的相机应用", notFound)
            }
            val ok = try {
                deferred.await()
            } finally {
                if (pendingCapture === deferred) pendingCapture = null
            }
            if (!ok) {
                shot.delete()
                return mapOf(
                    "ok" to true,
                    "cancelled" to true,
                    "relativePathsJson" to "[]",
                )
            }
            if (!shot.exists() || shot.length() == 0L) {
                shot.delete()
                throw IllegalStateException("相机没有保存照片，请再试一次")
            }
            // Same pipeline as attachments.pick: copy through Attachments.import so
            // the CLI can reference @attachments/<name> inside its chroot.
            val workspace = workspaceRoot()
            val relative = withContext(Dispatchers.IO) {
                try {
                    val file = Attachments.import(host, uri, workspace).getOrThrow()
                    Attachments.relativePath(workspace, file)
                } finally {
                    shot.delete()
                }
            }
            return mapOf(
                "ok" to true,
                "cancelled" to false,
                "relativePathsJson" to BridgeWire.stringListJson(listOf(relative)),
            )
        } finally {
            captureInProgress = false
        }
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
        if (container.busy || terminalJob?.isActive == true) {
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
        startExternal(intent) { e ->
            if (share) "无法分享：${e.message}" else "没有能打开该文件的应用"
        }
        return ok()
    }

    // ---------------------------------------------------------------- sharing

    /**
     * Starts an Intent from the Activity when one is attached, otherwise from the app
     * context with `NEW_TASK` (the Flutter page may be recreated while a share is
     * pending). Launch failures become the caller's Chinese message.
     */
    private fun startExternal(intent: Intent, failure: (Exception) -> String) {
        val host = activity
        try {
            if (host != null) {
                host.startActivity(intent)
            } else {
                container.appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            throw IllegalStateException(failure(e))
        }
    }

    /** System share sheet for plain text; the text itself must never be logged. */
    private fun shareText(call: MethodCall): Map<String, Any?> {
        val text = call.str("text")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("没有可分享的文字")
        if (text.length > ShareFiles.MAX_TEXT_CHARS) {
            throw IllegalArgumentException("文字太长，请分段分享")
        }
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startExternal(Intent.createChooser(target, "分享文字")) { e ->
            "无法分享：${e.message ?: "系统未找到可用的分享应用"}"
        }
        return ok()
    }

    /**
     * Stages Flutter-generated PNG bytes in `cacheDir/share/` and shares them through
     * the existing FileProvider. Disk work runs on IO because a 24 MiB write would
     * otherwise block the platform thread. Bytes are never logged.
     */
    private suspend fun shareImage(call: MethodCall): Map<String, Any?> {
        val bytes = (call.arguments as? Map<*, *>)?.get("bytes") as? ByteArray
        val file = withContext(Dispatchers.IO) {
            if (bytes == null || bytes.isEmpty()) throw IllegalArgumentException("图片数据为空")
            if (bytes.size > ShareFiles.MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("图片太大，无法分享")
            }
            val now = System.currentTimeMillis()
            val dir = File(container.appContext.cacheDir, "share")
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IllegalStateException("无法创建分享缓存目录")
            }
            ShareFiles.prune(dir, now)
            ShareFiles.newImageFile(dir, now).also { it.writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(
            container.appContext,
            "${container.appContext.packageName}.files",
            file,
        )
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startExternal(Intent.createChooser(target, "分享图片")) { e ->
            "无法分享：${e.message ?: "系统未找到可用的分享应用"}"
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

    /**
     * Opens the persistent TTY shell. Returns as soon as the process exists; `started`
     * arrives on the event channel, and `exit` / `stopped` later. A failure to launch is
     * thrown so the caller can show it and retry.
     */
    private suspend fun terminalStart(call: MethodCall): Map<String, Any?> {
        if (terminalSession != null) throw IllegalStateException("终端会话正在运行")
        if (terminalJob?.isActive == true) throw IllegalStateException("终端已有命令在运行")
        // Captured by identity: the page can unsubscribe while the shell is starting
        // (a slow su dialog, PRoot startup). Then this sink is gone and assigning the
        // session would leave a shell nobody can see or stop.
        val expectedSink = terminalSink ?: throw IllegalStateException("终端未连接")
        val settings = container.settings.current()
        settingsSnapshot = settings
        val issue = container.engine.startIssue(settings)
        if (issue != null) throw IllegalStateException(issue)
        val plan = container.engine.shellPlan(settings)
            ?: throw IllegalStateException("引擎未安装，请先在设置中安装 Claude Code。")
        val session = TerminalSession(
            ctx = container.appContext,
            root = container.engine.rootExec,
            workspace = workspaceRoot(),
        ) { event ->
            // The page may be gone by the time a late chunk is flushed; a null sink is
            // normal shutdown, not a crash.
            withContext(Dispatchers.Main) { terminalSink?.success(event) }
        }
        // Assign before starting: `started` can reach Dart before start() returns, and
        // the first keystrokes must find the session instead of being dropped.
        terminalSession = session
        try {
            session.start(
                plan,
                call.int("rows", TerminalSession.DEFAULT_ROWS),
                call.int("cols", TerminalSession.DEFAULT_COLS),
            )
        } catch (e: Exception) {
            if (terminalSession === session) terminalSession = null
            throw e
        }
        if (terminalSink !== expectedSink || terminalSession !== session) {
            // The page unsubscribed, or terminal.stop ran while the shell was starting.
            // Do not leave a shell nobody tracks; the stop path may have observed a
            // null process, so call stop again now that the process exists.
            session.stop()
            if (terminalSession === session) terminalSession = null
            throw IllegalStateException("终端启动已取消")
        }
        // LAZY so `terminalJob` is assigned before the body can reach its finally block.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.awaitTermination()
            } finally {
                // A stop path may already have cleared these for an immediate
                // reconnect; only clear fields that still point at this session so a
                // newer session's job is never clobbered.
                if (terminalSession === session) {
                    terminalSession = null
                    terminalJob = null
                }
            }
        }
        terminalJob = job
        job.start()
        return ok()
    }

    private fun terminalWrite(call: MethodCall): Map<String, Any?> {
        // No session is a normal state (the page may still be attaching); stay silent
        // instead of failing the keystroke.
        val session = terminalSession ?: return ok()
        val data = (call.arguments as? Map<*, *>)?.get("data") as? String ?: return ok()
        // Enqueue on Main.immediate in call order; the session's single writer thread
        // drains the queue, so two fast calls cannot race or reorder.
        session.write(data)
        return ok()
    }

    private suspend fun terminalResize(call: MethodCall): Map<String, Any?> {
        terminalSession?.resize(
            call.int("rows", TerminalSession.DEFAULT_ROWS),
            call.int("cols", TerminalSession.DEFAULT_COLS),
        )
        return ok()
    }

    private suspend fun terminalRun(call: MethodCall): Map<String, Any?> {
        if (terminalSession != null) throw IllegalStateException("终端会话正在运行")
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

    private suspend fun terminalStop(): Map<String, Any?> {
        val session = terminalSession
        if (session != null) {
            // Idempotent and bounded (~1 s): emits `stopped` once. Clear the fields
            // before returning so the page can reconnect immediately; the wait job's
            // finally is identity-guarded and stays silent for this session.
            session.stop()
            if (terminalSession === session) {
                terminalSession = null
                terminalJob = null
            }
        } else {
            // One-shot `terminal.run` still stops through job cancellation.
            terminalJob?.cancel()
        }
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

    // ---------------------------------------------------------------- backup
    //
    // The bridge only moves bytes and JSON here; deciding what to pack, what to merge
    // and how to reach a target lives in backup/BackupManager. Config JSON carries
    // passwords and tokens, so it is never logged or echoed in errors.

    private fun backupConfig(): Map<String, Any?> = mapOf(
        "ok" to true,
        "configJson" to BackupWire.configJson(container.backup.savedConfig()),
    )

    private suspend fun backupSaveConfig(call: MethodCall): Map<String, Any?> {
        val raw = call.require("configJson")
        val config = BackupWire.decodeConfig(raw)
        container.backup.saveConfig(config)
        // §5.5: every save re-syncs the schedule. The in-memory signature makes edits
        // that do not affect the job (URL, credentials) leave the next run untouched.
        container.backupScheduler.sync(config)
        return mapOf("ok" to true, "configJson" to BackupWire.configJson(config))
    }

    private fun backupStatus(): Map<String, Any?> = mapOf(
        "ok" to true,
        "statusJson" to BackupWire.statusJson(container.backup.status.value),
    )

    private suspend fun backupTest(call: MethodCall): Map<String, Any?> {
        val raw = call.str("configJson")
        val config = if (raw.isNullOrBlank()) {
            container.backup.savedConfig()
        } else {
            BackupWire.decodeConfig(raw)
        }
        val message = container.backup.test(config)
        return mapOf("ok" to true, "message" to message)
    }

    /**
     * A running turn or terminal reads/writes the workspace and the session files the
     * archive is made of; a merge underneath it could see half-written JSON.
     */
    private fun requireBackupIdle() {
        if (container.busy || terminalJob?.isActive == true) {
            throw IllegalStateException("任务或终端正在运行，结束后再备份或恢复")
        }
        if (container.backup.busy) throw IllegalStateException("已有备份任务在进行")
    }

    private suspend fun backupRun(): Map<String, Any?> {
        requireBackupIdle()
        val result = container.backup.run()
        return mapOf("ok" to true, "resultJson" to BackupWire.runResultJson(result))
    }

    private suspend fun backupRestore(call: MethodCall): Map<String, Any?> {
        requireBackupIdle()
        val result = container.backup.restore(call.bool("restoreSettings", false))
        // The merge wrote files behind SessionStore/WorkspaceStore; refresh both so
        // the drawer and workspace picker show the restored entries immediately.
        container.sessions.load()
        container.workspaces.load()
        return mapOf("ok" to true, "resultJson" to BackupWire.restoreResultJson(result))
    }

    private suspend fun backupExport(): Map<String, Any?> {
        requireBackupIdle()
        val name = "claudroid-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val uri = requestDocument(intent, BACKUP_EXPORT_REQUEST)
            ?: return mapOf("ok" to true, "cancelled" to true, "bytes" to 0)
        val outcome = container.backup.export { file -> copyToUri(uri, file) }
        return mapOf("ok" to true, "cancelled" to false, "bytes" to outcome.bytes)
    }

    private suspend fun backupImport(): Map<String, Any?> {
        requireBackupIdle()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Some cloud providers label ZIPs as application/octet-stream.
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uri = requestDocument(intent, BACKUP_IMPORT_REQUEST)
            ?: return mapOf("ok" to true, "cancelled" to true)
        val result = container.backup.import(
            read = { dest -> copyFromUri(uri, dest) },
            restoreSettings = false,
        )
        container.sessions.load()
        container.workspaces.load()
        return mapOf(
            "ok" to true,
            "cancelled" to false,
            "resultJson" to BackupWire.restoreResultJson(result),
        )
    }

    private suspend fun requestDocument(intent: Intent, requestCode: Int): Uri? {
        val host = activity ?: throw IllegalStateException("界面不在前台，无法选择文件")
        if (pendingDocument != null) throw IllegalStateException("已有文件选择正在进行")
        val deferred = CompletableDeferred<Uri?>()
        pendingDocument = deferred
        host.startActivityForResult(intent, requestCode)
        return try {
            deferred.await()
        } finally {
            if (pendingDocument === deferred) pendingDocument = null
        }
    }

    private suspend fun copyToUri(uri: Uri, file: File) {
        withContext(Dispatchers.IO) {
            val resolver = container.appContext.contentResolver
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法写入所选位置")
            output.use { target ->
                file.inputStream().use { source -> source.copyTo(target) }
            }
        }
    }

    private suspend fun copyFromUri(uri: Uri, dest: File) {
        withContext(Dispatchers.IO) {
            val resolver = container.appContext.contentResolver
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { source ->
                dest.outputStream().buffered(256 * 1024).use { target ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) {
                            throw IllegalStateException("备份文件超过 4 GiB，无法导入")
                        }
                        target.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private suspend fun sessionsNew(call: MethodCall): Map<String, Any?> {
        // Deliberately not persisted here: an empty session is litter, and the first
        // turn writes it (ChatController.persist). Dart navigates into it directly.
        // The optional host binding survives in SessionStore drafts until then.
        val session = container.sessions.newSession(
            model = call.str("model"),
            hostId = call.str("hostId"),
            remoteCwd = call.str("remoteCwd"),
        )
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

    private suspend fun chatPermission(call: MethodCall): Map<String, Any?> {
        val chat = controller(call)
        chat.load()
        chat.respondPermission(
            requestId = call.require("requestId"),
            allow = call.bool("allow"),
            always = call.bool("always"),
        )
        return ok()
    }

    // --------------------------------------------------------------- hosts

    private suspend fun hostsState(): Map<String, Any?> {
        container.remote.load()
        return mapOf("ok" to true, "hostsJson" to hostsSnapshot())
    }

    private fun hostsSnapshot(): String = BridgeWire.hostsJson(container.remote.state.value)

    private suspend fun hostsPair(call: MethodCall): Map<String, Any?> {
        val profile = container.remote.pair(
            baseUrl = call.require("baseUrl"),
            code = call.require("code"),
            name = call.str("name").orEmpty(),
        )
        val runtime = container.remote.state.value.runtime[profile.id]
        return mapOf(
            "ok" to true,
            "hostsJson" to hostsSnapshot(),
            "hostJson" to BridgeWire.hostJson(profile, runtime).toString(),
        )
    }

    /**
     * Opens ZXing's capture activity and returns the raw QR text; parsing and
     * pairing stay in Dart/Kotlin. The decoder is bundled, so scanning works
     * offline and without Google Play services.
     */
    private suspend fun hostsScanPairing(): Map<String, Any?> {
        val host = activity ?: throw IllegalStateException("界面不在前台，无法扫码")
        if (pendingScan != null) throw IllegalStateException("已有扫码正在进行")
        val deferred = CompletableDeferred<String?>()
        pendingScan = deferred
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("对准主机终端上的配对二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        host.startActivityForResult(options.createScanIntent(host), SCAN_REQUEST)
        val text = try {
            deferred.await()
        } finally {
            if (pendingScan === deferred) pendingScan = null
        }
        return mapOf(
            "ok" to true,
            "cancelled" to text.isNullOrBlank(),
            "text" to (text ?: ""),
        )
    }

    private suspend fun hostsUpdate(call: MethodCall): Map<String, Any?> {
        container.remote.updateHost(
            id = call.require("id"),
            name = call.str("name"),
            baseUrl = call.str("baseUrl"),
            accessClientId = call.str("accessClientId"),
            accessSecret = call.str("accessSecret"),
            permissionMode = call.str("permissionMode"),
        )
        return hostsState()
    }

    private suspend fun hostsRemove(call: MethodCall): Map<String, Any?> {
        container.remote.remove(call.require("id"))
        return hostsState()
    }

    private suspend fun hostsTest(call: MethodCall): Map<String, Any?> {
        val health = container.remote.test(call.require("id"))
        return mapOf(
            "ok" to true,
            "healthJson" to BridgeWire.healthJson(health),
            "hostsJson" to hostsSnapshot(),
        )
    }

    private suspend fun hostsSetDefault(call: MethodCall): Map<String, Any?> {
        container.remote.setDefault(call.str("id")?.takeIf { it.isNotBlank() })
        return hostsState()
    }

    private suspend fun hostsBrowse(call: MethodCall): Map<String, Any?> {
        val id = call.require("id")
        val path = call.str("path")?.takeIf { it.isNotBlank() }
            ?: container.remote.host(id)?.defaultCwd?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("请先选择主机目录")
        val entries = container.remote.listFiles(id, path)
        return mapOf(
            "ok" to true,
            "path" to path,
            "entriesJson" to BridgeWire.remoteEntriesJson(entries),
        )
    }

    private suspend fun hostsDownload(call: MethodCall): Map<String, Any?> {
        val file = container.remote.download(call.require("id"), call.require("path"))
        val entry = WorkspaceEntry(file, file.name, WorkspaceFiles.kindOf(file), file.length(), file.lastModified())
        return mapOf(
            "ok" to true,
            "localPath" to file.absolutePath,
            "name" to file.name,
            "mime" to WorkspaceFiles.mimeOf(entry),
            "size" to file.length(),
            "modifiedAt" to file.lastModified(),
        )
    }

    /**
     * Downloads into `cacheDir/share/` first: that path is already exposed through
     * FileProvider, so remote files open/share with the same mechanism as workspace
     * files, and no new provider path (or permission) is needed.
     */
    private suspend fun hostsIntent(call: MethodCall, share: Boolean): Map<String, Any?> {
        val remotePath = call.require("path")
        val file = container.remote.download(call.require("id"), remotePath)
        if (!file.isFile) throw IllegalArgumentException("文件不存在")
        val entry = WorkspaceEntry(file, file.name, WorkspaceFiles.kindOf(file), file.length(), file.lastModified())
        val uri = FileProvider.getUriForFile(
            container.appContext,
            "${container.appContext.packageName}.files",
            file,
        )
        val displayName = remotePath.substringAfterLast('/').ifBlank { file.name }
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
        val intent = if (share) Intent.createChooser(target, "分享 $displayName") else target
        startExternal(intent) { e ->
            if (share) "无法分享：${e.message}" else "没有能打开该文件的应用"
        }
        return ok()
    }

    private suspend fun hostsTunnel(call: MethodCall): Map<String, Any?> {
        val status = container.remote.tunnel(
            id = call.require("id"),
            action = call.str("action")?.takeIf { it.isNotBlank() },
        )
        return mapOf(
            "ok" to true,
            "tunnelJson" to BridgeWire.tunnelJson(status).toString(),
            "hostsJson" to hostsSnapshot(),
        )
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
                container.remote.load()
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
                        "hostsJson" to BridgeWire.hostsJson(container.remote.state.value),
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
                launch {
                    container.remote.state.collect { state ->
                        sink.success(
                            mapOf("type" to "hosts", "hostsJson" to BridgeWire.hostsJson(state)),
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
            val session = terminalSession
            if (terminalSession === session) terminalSession = null
            terminalJob?.cancel()
            terminalJob = null
            if (session != null) scope.launch { session.stop() }
        }
    }

    private inner class BackupStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            backupJob?.cancel()
            backupJob = scope.launch {
                // Snapshot first, then follow: a status change between the two still
                // arrives through the StateFlow replay, so nothing is lost.
                sink.success(
                    mapOf(
                        "type" to "status",
                        "statusJson" to BackupWire.statusJson(container.backup.status.value),
                    ),
                )
                container.backup.status.collect { status ->
                    sink.success(
                        mapOf(
                            "type" to "status",
                            "statusJson" to BackupWire.statusJson(status),
                        ),
                    )
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            backupJob?.cancel()
            backupJob = null
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
        permissions = chat.pendingPermissions.value,
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
        const val BACKUP_EVENTS = "app.claudroid/backup"
        const val ENGINE_CACHE_KEY = "claudroid"

        private const val ATTACHMENT_REQUEST = 0x0A77
        private const val ATTACHMENT_CAPTURE_REQUEST = 0x0A7B
        private const val CAMERA_PERMISSION_REQUEST = 0x0A7C
        private const val SCAN_REQUEST = 0x0A7A
        private const val BACKUP_EXPORT_REQUEST = 0x0A78
        private const val BACKUP_IMPORT_REQUEST = 0x0A79
        private const val MAX_IMAGE_BYTES = 6L * 1024 * 1024

        /** Same cap as the archive extractor (docs/BACKUP.md §2/§5.4). */
        private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024

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
