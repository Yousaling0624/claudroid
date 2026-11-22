package app.claudroid

import android.app.Application
import android.content.Context
import app.claudroid.backup.AutoBackupScheduler
import app.claudroid.backup.BackupArchive
import app.claudroid.backup.BackupManager
import app.claudroid.backup.BackupStore
import app.claudroid.bridge.BridgePlugin
import app.claudroid.core.Paths
import app.claudroid.core.WorkspaceStore
import app.claudroid.data.SettingsRepository
import app.claudroid.data.SessionStore
import app.claudroid.engine.EngineManager
import app.claudroid.chat.ChatController
import app.claudroid.remote.RemoteHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ClaudroidApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Auto-backup scheduling is synced from MainActivity, not here: a JobService
        // cold start also runs Application.onCreate, and rescheduling there could
        // replace the job the system just started.
        // The Flutter UI is the only frontend: prewarm its engine and register the
        // platform channels once, so Activity recreation never rebuilds the bridge.
        BridgePlugin.prewarm(this)
        container.engine.startNetworkMonitoring()
        container.scope.launch {
            container.engine.bootstrap()
        }
    }
}

/** Manual DI container: small enough that a framework would only add noise. */
class AppContainer(app: Application) {
    val appContext: Context = app.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val sessions = SessionStore(app)
    val workspaces = WorkspaceStore(app)
    val engine = EngineManager(app, settings, workspaces)
    val remote = RemoteHostManager(
        stateFile = File(Paths.hosts(app), "hosts.json"),
        cacheDir = app.cacheDir,
        scope = scope,
    )

    /**
     * A remote turn does not touch [engine], but it still owns the app-wide single-turn
     * slot: backup, workspace switching and terminal guards must see it.
     */
    val turnRunning = AtomicBoolean(false)
    val busy: Boolean get() = engine.busy || turnRunning.get()

    /** Packing/merging is plain file I/O; the manager owns the single-task slot. */
    val backup = BackupManager(
        archive = BackupArchive(
            sessionsDir = Paths.sessions(app),
            defaultWorkspaceDir = Paths.workspace(app),
            workspacesRootDir = Paths.workspacesRoot(app),
        ),
        store = BackupStore(Paths.backup(app)),
        workspaces = workspaces,
        settings = { settings.current() },
        applySettings = { restored -> settings.edit { restored } },
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        cacheDir = Paths.backupCache(app),
        scope = scope,
    )

    /** Background schedule (docs/BACKUP.md §5.5); synced on launch and after saveConfig. */
    val backupScheduler = AutoBackupScheduler(app)

    /**
     * Terminal state is owned by BridgePlugin, but the auto-backup JobService can run
     * without a Flutter engine attached. The bridge installs this probe on attach;
     * null means no terminal can be running, so the job proceeds.
     */
    @Volatile
    var terminalProbe: (() -> Boolean)? = null

    private val chats = mutableMapOf<String, ChatController>()

    /** One controller per session, kept alive so a running turn survives navigation. */
    fun chat(sessionId: String): ChatController = synchronized(chats) {
        chats.getOrPut(sessionId) { ChatController(this, sessionId) }
    }

    fun forgetChat(sessionId: String) {
        synchronized(chats) { chats.remove(sessionId) }
    }

    /** Stops a running turn and drops the controller when its session is deleted. */
    fun closeChat(sessionId: String) {
        val chat = synchronized(chats) { chats.remove(sessionId) }
        chat?.dismiss()
    }
}
