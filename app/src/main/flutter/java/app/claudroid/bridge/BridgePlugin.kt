package app.claudroid.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.claudroid.AppContainer
import app.claudroid.ClaudroidApp
import app.claudroid.core.Logx
import app.claudroid.data.AppSettings
import app.claudroid.ui.chat.ChatController
import app.claudroid.ui.chat.chatStatusLine
import app.claudroid.ui.components.chatModelLabel
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin adapter exposing [AppContainer] to the Flutter frontend as one MethodChannel
 * and two kinds of EventChannel. It must stay logic-free: every decision lives in
 * `engine/`, `data/` or `ChatController` (docs/BRIDGE_CONTRACT.md §1).
 *
 * Only compiled when `-PflutterUi=true` puts `src/main/flutter/java` on the classpath;
 * the default build never sees this file or the Flutter classes it imports.
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
        Logx.i("bridge: attached", binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        globalChannel?.setStreamHandler(null)
        globalChannel = null
        globalJob?.cancel()
        globalJob = null
        val ids = synchronized(chatChannels) { chatChannels.keys.toList() }
        ids.forEach { unregisterChatChannel(it) }
        // Controllers and running turns belong to the app, not the engine: detaching
        // must not kill a turn (the user may still be streaming in another host).
    }

    // ActivityAware is required for add-to-app lifecycle; the bridge never needs the
    // Activity itself (no permission dialogs, no Activity-bound channels).
    override fun onAttachedToActivity(binding: ActivityPluginBinding) = Unit
    override fun onDetachedFromActivityForConfigChanges() = Unit
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = Unit
    override fun onDetachedFromActivity() = Unit

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
            "settings.update" -> settingsUpdate(call)
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
        container.sessions.rename(call.require("id"), call.require("title"))
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
                        sink.success(BridgeWire.chatEvent(ev, chatModelLabel(settings)))
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

    // ----------------------------------------------------------------- statics

    companion object {
        const val METHOD_CHANNEL = "app.claudroid/bridge"
        const val GLOBAL_EVENTS = "app.claudroid/events"
        const val ENGINE_CACHE_KEY = "claudroid"

        /** Guards against attaching two plugins to the same engine (duplicate channels). */
        @Volatile private var attached = false

        fun chatChannel(sessionId: String) = "app.claudroid/chat/$sessionId"

        init {
            // Referencing this class at all (the Flutter host does) arms the seam, so
            // ClaudroidApp can start the bridge without knowing Flutter exists.
            installSeam()
        }

        /**
         * Arms [FlutterBridgeHost] with the engine-cache warm-up. Idempotent; also
         * called from [BridgeBootstrap] so an explicit `install()` is not required.
         */
        fun installSeam() {
            // The seam is typed `(Any) -> Unit` so this file owns every Flutter type.
            // `FlutterEngine` may only be constructed on the main thread, and this
            // companion can be initialised from any thread (whichever caller first
            // touches the class), so hop before touching the engine cache.
            FlutterBridgeHost.setInstaller { app ->
                Handler(Looper.getMainLooper()).post { attachCachedEngine(app as Context) }
            }
        }

        /**
         * Adds the bridge to an engine the caller already created. The explicit path
         * wins over the cached-engine path: [FlutterBridgeHost.markSatisfied] stops a
         * pending `start()` from creating a second engine.
         */
        @JvmStatic
        fun register(engine: FlutterEngine) {
            FlutterBridgeHost.markSatisfied()
            if (attached) return
            attached = true
            engine.plugins.add(BridgePlugin())
        }

        /**
         * [FlutterBridgeHost] entry point: the cached engine is created once and reused
         * across Activity recreation, so channels never disappear (§2).
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
