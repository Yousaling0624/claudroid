package app.claudroid.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.core.Attachments
import app.claudroid.data.AppSettings
import app.claudroid.engine.EngineManager
import app.claudroid.ui.components.ClaudeComposer
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.FloatingTopBar
import app.claudroid.ui.components.ModelDropdownMenu
import app.claudroid.ui.components.OrganicHalo
import app.claudroid.ui.components.pressFeedback
import app.claudroid.ui.components.shortModelLabel
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * Landing page: a quiet app bar, the serif greeting, and the composer. History lives
 * in the drawer; sending from here creates a session in MainActivity and hands the
 * first prompt to ChatScreen so the transcript is written exactly once.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    resetKey: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
) {
    val settings by container.settings.flow.collectAsState(initial = AppSettings())
    val engineState by container.engine.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // "新对话" from the drawer/top bar must also drop a half-typed draft.
    LaunchedEffect(resetKey) {
        input = ""
        attachmentError = null
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { Attachments.import(context, uri) }
                result.onSuccess { file ->
                    val rel = Attachments.relativePath(context, file)
                    input = (if (input.isBlank()) "" else input.trimEnd() + " ") + "@$rel "
                    attachmentError = null
                }.onFailure {
                    attachmentError = "附件导入失败：${it.message ?: "请换一个文件重试"}"
                }
            }
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        onSend(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        FloatingTopBar(
            title = "Claudroid",
            subtitle = null,
            modelLabel = shortModelLabel(settings),
            running = false,
            onOpenDrawer = onOpenDrawer,
            onModelClick = { showModelMenu = !showModelMenu },
            onNewChat = onNewChat,
            modelMenuOpen = showModelMenu,
            modelMenu = { offset ->
                ModelDropdownMenu(
                    expanded = showModelMenu,
                    settings = settings,
                    offset = offset,
                    onSelectTier = { tier ->
                        scope.launch {
                            container.settings.edit { it.copy(activeTier = tier?.name ?: "") }
                        }
                    },
                    onSelectAuto = {
                        scope.launch {
                            container.settings.edit { it.copy(activeTier = AppSettings.AUTO_MODEL) }
                        }
                    },
                    onManageTiers = onOpenSettings,
                    onDismiss = { showModelMenu = false },
                )
            },
        )

        Column(Modifier.weight(1f).fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    OrganicHalo(
                        size = 62.dp,
                        color = MaterialTheme.appColors.orange.copy(alpha = 0.11f),
                    )
                    ClaudeMark(size = 24.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    remember { greetingForHour(LocalTime.now().hour) },
                    style = AppType.greeting,
                    color = MaterialTheme.appColors.ink,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "今天想让 Claude 在这台手机上做什么？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.appColors.graphite,
                )
            }
            Spacer(Modifier.weight(1.15f))

            if (!engineState.ready) {
                EngineHint(engineState, onOpenSettings)
                Spacer(Modifier.height(8.dp))
            }
            attachmentError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.error,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEach { hint ->
                    SuggestionChip(hint) { input = hint }
                }
            }

            ClaudeComposer(
                value = input,
                onValueChange = {
                    input = it
                    attachmentError = null
                },
                placeholder = "有什么可以帮你的？",
                running = false,
                onSend = ::submit,
                onStop = {},
                onAttach = { filePicker.launch(arrayOf("*/*")) },
            )
        }
    }
}

private val SUGGESTIONS = listOf(
    "看看工作区里都有什么文件",
    "整理这台手机的存储空间",
    "写一个自动化脚本",
)

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            .pressFeedback(shape = shape)
            .clip(shape)
            .background(MaterialTheme.appColors.paper)
            .border(1.dp, MaterialTheme.appColors.sand, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.5.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            color = MaterialTheme.appColors.graphite,
            maxLines = 1,
        )
    }
}

/** A quiet, tappable status row instead of a loud error card. */
@Composable
private fun EngineHint(state: EngineManager.State, onOpenSettings: () -> Unit) {
    val message = when (state.phase) {
        EngineManager.Phase.Working -> "${state.stage}… ${(state.fraction * 100).toInt()}%"
        EngineManager.Phase.Failed -> "引擎初始化失败，点此查看原因"
        else -> "Claude Code 尚未就绪"
    }
    val dotColor = when {
        state.phase == EngineManager.Phase.Failed -> MaterialTheme.appColors.error
        else -> MaterialTheme.appColors.orange
    }
    val shape = CircleShape
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .pressFeedback(shape = shape)
            .clip(shape)
            .background(MaterialTheme.appColors.surface1)
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = MaterialTheme.appColors.graphite,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "去设置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.appColors.accentInk,
        )
    }
}
