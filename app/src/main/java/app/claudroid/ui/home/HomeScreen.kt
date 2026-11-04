package app.claudroid.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.claudroid.AppContainer
import app.claudroid.data.Block
import app.claudroid.data.ChatSession
import app.claudroid.engine.EngineManager
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.SectionLabel
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.Spacing
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val allSessions by container.sessions.sessions.collectAsState()
    // Old builds persisted a session as soon as the FAB was tapped. Those empty files
    // are not conversations and should not occupy the history list.
    val sessions = remember(allSessions) { allSessions.filter { it.hasContent } }
    val engineState by container.engine.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { container.sessions.load() }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter {
            it.title.contains(query, true) || it.messages.any { m ->
                m.blocks.filterIsInstance<Block.Text>().any { b -> b.text.contains(query, true) }
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    val fabInteraction = remember { MutableInteractionSource() }
    val fabPressed by fabInteraction.collectIsPressedAsState()
    val fabScale by animateFloatAsState(
        targetValue = if (fabPressed) 0.94f else 1f,
        animationSpec = Motion.Press,
        label = "fab",
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            AppIcons.Menu,
                            "会话记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClaudeMark(size = 21.dp)
                        Spacer(Modifier.width(9.dp))
                        Text("Claudroid", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWorkspace) {
                        Icon(
                            AppIcons.Workspace,
                            "工作区文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            AppIcons.Settings,
                            "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Written on the first message, not here: an abandoned new chat
                    // should not leave an empty row in history.
                    onOpenSession(ChatSession().id)
                },
                containerColor = MaterialTheme.appColors.accentInk,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                interactionSource = fabInteraction,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    },
            ) {
                Icon(AppIcons.Plus, "新对话", modifier = Modifier.size(22.dp))
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.padding(horizontal = Spacing.page)) {
                Spacer(Modifier.height(2.dp))
                Text(
                    greeting(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "想让 Agent 在这台手机上做点什么？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                SearchRow(query, { query = it }, onClear = { query = "" })
                Spacer(Modifier.height(18.dp))
            }

            if (!engineState.ready) {
                EngineNotice(engineState, onClick = onOpenSettings)
                Spacer(Modifier.height(10.dp))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("最近对话")
                Spacer(Modifier.width(8.dp))
                if (sessions.isNotEmpty()) {
                    Text(
                        "${sessions.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.appColors.faintText,
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = Spacing.page - 8.dp,
                        end = Spacing.page - 8.dp,
                        bottom = 100.dp,
                    ),
                ) {
                    items(filtered, key = { it.id }) { session ->
                        Column(
                            Modifier.animateItem(
                                fadeInSpec = tween(240),
                                fadeOutSpec = tween(140),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        ) {
                            SessionRow(
                                session = session,
                                onClick = { onOpenSession(session.id) },
                                onDelete = {
                                    scope.launch {
                                        container.closeChat(session.id)
                                        container.sessions.delete(session.id)
                                    }
                                },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        targetValue = if (focused) MaterialTheme.appColors.accentInk.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(Motion.FAST),
        label = "search-border",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索对话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.appColors.faintText,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.appColors.accentInk),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                "清空搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .padding(5.dp),
            )
        }
    }
}

@Composable
private fun EngineNotice(state: EngineManager.State, onClick: () -> Unit) {
    val message = when (state.phase) {
        EngineManager.Phase.Working -> "${state.stage}… ${(state.fraction * 100).toInt()}%"
        EngineManager.Phase.Failed -> state.error ?: "后端初始化失败"
        else -> "内置 Claude Code 尚未就绪"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.brand)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "设置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.appColors.accentInk,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(session: ChatSession, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
        else Color.Transparent,
        animationSpec = tween(Motion.FAST),
        label = "row-press",
    )
    val failed = remember(session) {
        session.messages.lastOrNull()?.blocks?.any {
            it is Block.Error || (it is Block.Tool && it.isError)
        } == true
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    session.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    relativeTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall.merge(
                        TextStyle(fontFeatureSettings = "tnum")
                    ),
                    color = MaterialTheme.appColors.faintText,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("删除对话") },
                leadingIcon = { Icon(AppIcons.Trash, null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClaudeMark(size = 40.dp, color = MaterialTheme.appColors.brand.copy(alpha = 0.65f))
        Spacer(Modifier.height(18.dp))
        Text(
            "还没有对话",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "点右下角的按钮开始。免 root 走 PRoot，root 模式下 Agent 可以直接操作这台手机。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}

private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    if (diff < 60_000) return "刚刚"
    if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = ts
    val then = cal.get(Calendar.DAY_OF_YEAR)
    return when {
        then == today -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        diff < 7 * 86_400_000L -> SimpleDateFormat("E HH:mm", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
    }
}
