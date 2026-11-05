package app.claudroid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.claudroid.AppContainer
import app.claudroid.ClaudroidApp
import app.claudroid.data.AppSettings
import app.claudroid.data.ChatSession
import app.claudroid.ui.chat.ChatScreen
import app.claudroid.ui.components.AppDrawer
import app.claudroid.ui.components.Motion
import app.claudroid.ui.home.HomeScreen
import app.claudroid.ui.settings.SettingsScreen
import app.claudroid.ui.theme.ClaudroidTheme
import app.claudroid.ui.workspace.WorkspaceScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        val container = (application as ClaudroidApp).container
        setContent {
            val settings by container.settings.flow.collectAsState(initial = AppSettings())
            ClaudroidTheme(themeMode = settings.themeMode) {
                AppRoot(container)
            }
        }
    }

    /** Only needed so the "turn still running" notification can appear on Android 13+. */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    // Four destinations do not justify a navigation dependency; a saved route stack does
    // the job and makes back behave like the user expects (workspace → chat → home).
    var stack by rememberSaveable(
        stateSaver = listSaver(
            save = { it },
            restore = { it },
        )
    ) { mutableStateOf(listOf("home")) }
    val route = stack.last()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Hand-off for "send from the landing page": create the session here, then let
    // ChatScreen send after its controller has loaded. The prompt is cleared the moment
    // it is consumed so a configuration change cannot replay it.
    var pendingSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped by every "new chat" action so HomeScreen drops its draft even though the
    // route string does not change.
    var homeEpoch by rememberSaveable { mutableIntStateOf(0) }

    fun push(next: String) {
        if (stack.last() != next) stack = stack + next
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun openChat(sessionId: String) {
        // Opening a conversation always resets to home beneath it: switching chats from
        // settings/workspace should not leave those screens behind the back button.
        pendingSessionId = null
        pendingPrompt = null
        stack = listOf("home", "chat:$sessionId")
        scope.launch { drawerState.close() }
    }

    fun startNewChat() {
        pendingSessionId = null
        pendingPrompt = null
        homeEpoch += 1
        stack = listOf("home")
        scope.launch { drawerState.close() }
    }

    fun sendFromHome(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val session = ChatSession()
        pendingSessionId = session.id
        pendingPrompt = trimmed
        stack = listOf("home", "chat:${session.id}")
    }

    // Use the target value, not the settled value: back pressed while the drawer is
    // still animating in must close it instead of popping the page underneath.
    val drawerOpening = drawerState.targetValue == DrawerValue.Open
    BackHandler(enabled = stack.size > 1 && !drawerOpening) { pop() }
    // ModalNavigationDrawer normally consumes back itself, but our route handler is
    // registered in the same activity; make the drawer's priority explicit so back
    // closes the sheet first instead of popping the page behind it.
    BackHandler(enabled = drawerOpening) { scope.launch { drawerState.close() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route != "settings",
            drawerContent = {
                AppDrawer(
                    container = container,
                    // Keep the active conversation highlighted while settings/workspace
                    // is pushed on top of it; otherwise the drawer selection fades out
                    // during the close animation and reads as a stray press feedback.
                    currentSessionId = stack.asReversed()
                        .firstOrNull { it.startsWith("chat:") }
                        ?.removePrefix("chat:"),
                    onOpenSession = { id -> openChat(id) },
                    onNewChat = { startNewChat() },
                    onSettings = {
                        if (route != "settings") stack = stack + "settings"
                        scope.launch { drawerState.close() }
                    },
                    onWorkspace = {
                        if (route != "workspace") stack = stack + "workspace"
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                    onDeletedCurrent = { startNewChat() },
                )
            },
        ) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    fun level(value: String) = if (value == "home") 0 else 1
                    val forward = targetState != "home" && level(targetState) >= level(initialState)
                    if (forward) {
                        (fadeIn(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { it / 24 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { -it / 40 }
                            )
                    } else {
                        (fadeIn(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { -it / 24 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { it / 40 }
                            )
                    }
                },
                label = "route",
            ) { current ->
                when {
                    current == "settings" -> SettingsScreen(
                        container = container,
                        onBack = { pop() },
                        onOpenWorkspace = { push("workspace") },
                    )
                    current == "workspace" -> WorkspaceScreen(
                        container = container,
                        onBack = { pop() },
                    )
                    current.startsWith("chat:") -> {
                        val sessionId = current.removePrefix("chat:")
                        ChatScreen(
                            container = container,
                            sessionId = sessionId,
                            initialPrompt = pendingPrompt.takeIf { pendingSessionId == sessionId },
                            onInitialPromptConsumed = {
                                pendingSessionId = null
                                pendingPrompt = null
                            },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNewChat = { startNewChat() },
                            onOpenWorkspace = { push("workspace") },
                            onOpenSettings = { push("settings") },
                        )
                    }
                    else -> HomeScreen(
                        container = container,
                        resetKey = homeEpoch,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = { startNewChat() },
                        onOpenSettings = { push("settings") },
                        onOpenWorkspace = { push("workspace") },
                        onSend = { sendFromHome(it) },
                    )
                }
            }
        }
    }
}
