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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.claudroid.AppContainer
import app.claudroid.ClaudroidApp
import app.claudroid.data.AppSettings
import app.claudroid.data.ChatSession
import app.claudroid.ui.chat.ChatScreen
import app.claudroid.ui.components.AppDrawer
import app.claudroid.ui.home.HomeScreen
import app.claudroid.ui.settings.SettingsScreen
import app.claudroid.ui.theme.ClaudroidTheme
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
    // Three screens do not justify a navigation dependency; a saved route string does the job.
    var route by rememberSaveable { mutableStateOf("home") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentSessionId = if (route.startsWith("chat:")) route.removePrefix("chat:") else null

    BackHandler(enabled = route != "home") { route = "home" }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = route != "settings",
        drawerContent = {
            AppDrawer(
                container = container,
                currentSessionId = currentSessionId,
                onOpenSession = { id ->
                    route = "chat:$id"
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    scope.launch {
                        val session = ChatSession()
                        container.sessions.upsert(session)
                        drawerState.close()
                        route = "chat:${session.id}"
                    }
                },
                onSettings = {
                    route = "settings"
                    scope.launch { drawerState.close() }
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                route == "settings" -> SettingsScreen(container) { route = "home" }
                route.startsWith("chat:") -> ChatScreen(
                    container = container,
                    sessionId = route.removePrefix("chat:"),
                    onBack = { route = "home" },
                )
                else -> HomeScreen(
                    container = container,
                    onOpenSession = { route = "chat:$it" },
                    onOpenSettings = { route = "settings" },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
        }
    }
}
