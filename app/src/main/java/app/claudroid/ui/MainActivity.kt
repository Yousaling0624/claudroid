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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import app.claudroid.ui.theme.appColors
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
    // Three screens do not justify a navigation dependency; a saved route stack does the
    // job and makes back behave like the user expects (folder → chat → home).
    var stack by rememberSaveable(
        stateSaver = listSaver(
            save = { it },
            restore = { it },
        )
    ) { mutableStateOf(listOf("home")) }
    val route = stack.last()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun push(next: String) {
        if (stack.last() != next) stack = stack + next
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun openChat(sessionId: String) {
        // Opening a conversation always resets to home beneath it: switching chats from
        // settings/workspace should not leave those screens behind the back button.
        stack = listOf("home", "chat:$sessionId")
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.appColors.paperTop, MaterialTheme.colorScheme.background),
                )
            )
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route != "settings",
            drawerContent = {
                AppDrawer(
                    container = container,
                    currentSessionId = route.removePrefix("chat:").takeIf { route.startsWith("chat:") },
                    onOpenSession = { id -> openChat(id) },
                    onNewChat = {
                        // Empty sessions are not persisted any more; the transcript is written
                        // on the first message. Backing out of a new chat leaves no litter.
                        val session = ChatSession()
                        openChat(session.id)
                    },
                    onSettings = {
                        if (route != "settings") stack = stack + "settings"
                        scope.launch { drawerState.close() }
                    },
                    onWorkspace = {
                        if (route != "workspace") stack = stack + "workspace"
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                targetState = route,
                transitionSpec = {
                    fun level(value: String) = if (value == "home") 0 else 1
                    val forward = targetState != "home" && level(targetState) >= level(initialState)
                    if (forward) {
                        (fadeIn(tween(Motion.FAST)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { it / 10 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { -it / 18 }
                            )
                    } else {
                        (fadeIn(tween(Motion.FAST)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { -it / 10 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { it / 18 }
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
                    current == "workspace" -> WorkspaceScreen(onBack = { pop() })
                    current.startsWith("chat:") -> ChatScreen(
                        container = container,
                        sessionId = current.removePrefix("chat:"),
                        onBack = { pop() },
                        onOpenWorkspace = { push("workspace") },
                    )
                    else -> HomeScreen(
                        container = container,
                        onOpenSession = { openChat(it) },
                        onOpenSettings = { push("settings") },
                        onOpenWorkspace = { push("workspace") },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
            }
        }
    }
}
}