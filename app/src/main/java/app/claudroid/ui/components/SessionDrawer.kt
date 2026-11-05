package app.claudroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.data.ChatSession
import app.claudroid.engine.EngineManager
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch

/**
 * Claude-client style sidebar: history grouped by day, search, new chat, and the
 * engine status pinned at the bottom. It takes roughly the full phone width (88%),
 * matching the official mobile drawer rather than a tablet rail.
 */
@Composable
fun AppDrawer(
    container: AppContainer,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    onWorkspace: () -> Unit,
    onClose: () -> Unit,
    onDeletedCurrent: () -> Unit,
) {
    val sessions by container.sessions.sessions.collectAsState()
    val engineState by container.engine.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { container.sessions.load() }

    // Empty aborted sessions are litter, not history.
    val groups = remember(sessions, query) {
        val content = sessions.filter { it.hasContent }
        groupSessions(filterSessions(content, query))
    }

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.88f),
        color = MaterialTheme.appColors.paper,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandLockup(starSize = 17.dp, textSize = 18.5.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        AppIcons.Close,
                        "收起",
                        tint = MaterialTheme.appColors.graphite,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            val newChatShape = Radii.smShape
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(46.dp)
                    .pressFeedback(shape = newChatShape)
                    .clip(newChatShape)
                    .background(MaterialTheme.appColors.paper)
                    .border(1.dp, MaterialTheme.appColors.sand, newChatShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNewChat()
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.appColors.orange,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "新对话",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.appColors.ink,
                )
            }

            DrawerSearch(query, { query = it })

            if (groups.isEmpty()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ClaudeMark(size = 26.dp, color = MaterialTheme.appColors.bone)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (query.isBlank()) "还没有对话" else "没有找到相关对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.graphite,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (query.isBlank()) "从上方「新对话」开始。" else "试试标题或消息里的关键词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.stone,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "group-${group.bucket.name}") {
                            SectionLabel(
                                group.bucket.label,
                                Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(group.sessions, key = { it.id }) { session ->
                            DrawerSessionRow(
                                session = session,
                                selected = session.id == currentSessionId,
                                onClick = { onOpenSession(session.id) },
                                onDelete = {
                                    onOpenSessionDelete(
                                        container = container,
                                        session = session,
                                        isCurrent = session.id == currentSessionId,
                                        onDeletedCurrent = onDeletedCurrent,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.appColors.sand)
            DrawerActionRow(AppIcons.Folder, "工作区文件", "/work", onWorkspace)
            DrawerActionRow(AppIcons.Settings, "设置", null, onSettings)
            EngineCard(engineState, onSettings, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
        }
    }
}

private fun onOpenSessionDelete(
    container: AppContainer,
    session: ChatSession,
    isCurrent: Boolean,
    onDeletedCurrent: () -> Unit,
) {
    container.closeChat(session.id)
    // Deletion is IO on the store; the drawer recomposes from the StateFlow.
    container.scope.launch { container.sessions.delete(session.id) }
    if (isCurrent) onDeletedCurrent()
}

@Composable
private fun DrawerSearch(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)
            .height(40.dp)
            .clip(Radii.smShape)
            .background(MaterialTheme.appColors.surface1)
            .border(1.dp, MaterialTheme.appColors.sand.copy(alpha = 0.6f), Radii.smShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.appColors.stone,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索对话",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.appColors.stone,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = MaterialTheme.appColors.ink),
                cursorBrush = SolidColor(MaterialTheme.appColors.orange),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                "清空搜索",
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .padding(5.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.appColors.surface1
            pressed -> MaterialTheme.appColors.surface1.copy(alpha = 0.62f)
            else -> Color.Transparent
        },
        animationSpec = tween(Motion.FAST, easing = Motion.EaseChrome),
        label = "drawer-row",
    )
    val shape = Radii.smShape
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(50.dp)
                .pressFeedback(shape = shape)
                .clip(shape)
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
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .background(MaterialTheme.appColors.orange, Radii.xsShape)
                    )
                }
            }
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                sessionTimeLabel(session.updatedAt),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.appColors.stone,
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.More,
                    "对话选项",
                    tint = MaterialTheme.appColors.stone,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("删除对话", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(AppIcons.Trash, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DrawerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressFeedback(shape = Radii.smShape)
            .clip(Radii.smShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.appColors.graphite,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.ink,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.stone,
            )
        }
    }
}

@Composable
private fun EngineCard(state: EngineManager.State, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val statusColor = when {
        state.ready -> MaterialTheme.appColors.success
        state.phase == EngineManager.Phase.Failed -> MaterialTheme.appColors.error
        else -> MaterialTheme.appColors.stone
    }
    val shape = Radii.smShape
    Row(
        modifier
            .fillMaxWidth()
            .pressFeedback(shape = shape)
            .clip(shape)
            .background(MaterialTheme.appColors.surface1)
            .border(1.dp, MaterialTheme.appColors.sand.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    state.ready -> "Claude Code ${state.installedVersion}"
                    state.phase == EngineManager.Phase.Working -> "${state.stage}…"
                    else -> "引擎未就绪"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    state.rootGranted -> "root · ${if (state.chrootAvailable) "chroot" else "直启"}"
                    state.prootAvailable -> "PRoot · 免 root"
                    else -> "PRoot 组件缺失"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.stone,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.appColors.stone,
            modifier = Modifier.size(14.dp),
        )
    }
}
