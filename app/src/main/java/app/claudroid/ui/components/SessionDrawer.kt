package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.claudroid.AppContainer
import app.claudroid.data.ChatSession
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Claude-client style sidebar: history, new chat, and the engine status pinned at the
 * bottom. Reachable by the ☰ button or an edge swipe from any screen.
 */
@Composable
fun AppDrawer(
    container: AppContainer,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val sessions by container.sessions.sessions.collectAsState()
    val engineState by container.engine.state.collectAsState()
    LaunchedEffect(Unit) { container.sessions.load() }

    ModalDrawerSheet(
        modifier = Modifier.width(302.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClaudeMark(size = 18.dp)
                Spacer(Modifier.width(9.dp))
                Text("Claudroid", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "新对话",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel(
                "对话记录",
                Modifier.padding(start = 20.dp, bottom = 4.dp),
            )

            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    DrawerRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                engineState.ready -> MaterialTheme.colorScheme.primary
                                engineState.phase == app.claudroid.engine.EngineManager.Phase.Failed ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            engineState.ready -> "Claude Code ${engineState.installedVersion}"
                            engineState.phase == app.claudroid.engine.EngineManager.Phase.Working ->
                                "${engineState.stage}…"
                            else -> "引擎未就绪"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            engineState.rootGranted ->
                                "root · ${if (engineState.chrootAvailable) "chroot" else "直启"}"
                            engineState.prootAvailable -> "PRoot · 免 root"
                            else -> "PRoot 组件缺失"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.Settings,
                    "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerRow(session: ChatSession, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            compactTime(session.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun compactTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    if (diff < 60_000) return "刚刚"
    if (diff < 3_600_000) return "${diff / 60_000}m"
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = ts
    return if (cal.get(Calendar.DAY_OF_YEAR) == today) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
    }
}
