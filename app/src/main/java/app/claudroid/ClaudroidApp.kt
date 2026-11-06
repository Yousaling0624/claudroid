package app.claudroid

import android.app.Application
import android.content.Context
import app.claudroid.bridge.FlutterBridgeHost
import app.claudroid.data.SettingsRepository
import app.claudroid.data.SessionStore
import app.claudroid.engine.EngineManager
import app.claudroid.ui.chat.ChatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClaudroidApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // No-op in the default build: only the optional Flutter source set installs a
        // registrar, so `src/main/java` never has to name a Flutter class.
        FlutterBridgeHost.start(this)
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
    val engine = EngineManager(app, settings)

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
