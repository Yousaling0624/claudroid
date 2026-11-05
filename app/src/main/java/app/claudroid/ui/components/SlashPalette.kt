package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle

/**
 * App-level actions offered by the slash palette. These are intentionally not Claude
 * Code CLI slash commands: the CLI does not expose a stable command protocol to the
 * app, so the palette only triggers things Claudroid itself owns.
 */
enum class SlashAction {
    NEW_CHAT,
    MODEL,
    ATTACH,
    WORKSPACE,
    SETTINGS,
    STOP,
    RETRY,
}

data class SlashCommand(
    val action: SlashAction,
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

/** Commands shared by the landing page and the transcript. */
val BaseSlashCommands: List<SlashCommand> = listOf(
    SlashCommand(SlashAction.NEW_CHAT, "new", "新建对话", "清空草稿，从空白开始", AppIcons.Plus),
    SlashCommand(SlashAction.MODEL, "model", "切换模型", "选择默认模型或模型档位", AppIcons.Layers),
    SlashCommand(SlashAction.ATTACH, "attach", "添加附件", "选一个文件放进工作区并引用", AppIcons.Attachment),
    SlashCommand(SlashAction.WORKSPACE, "workspace", "打开工作区", "查看 /work 里的文件", AppIcons.Folder),
    SlashCommand(SlashAction.SETTINGS, "settings", "打开设置", "接口、模型、引擎与外观", AppIcons.Settings),
)

val StopSlashCommand = SlashCommand(
    SlashAction.STOP, "stop", "停止生成", "结束当前回答", AppIcons.Stop,
)

val RetrySlashCommand = SlashCommand(
    SlashAction.RETRY, "retry", "重新回答", "用上一条提问重试", AppIcons.Regenerate,
)

/**
 * The query shown after the leading slash, or null when the palette should be closed.
 * A space commits the draft to the composer, and a draft that does not start with `/`
 * is never a command.
 */
internal fun slashQuery(text: String): String? {
    if (text == "/") return ""
    if (text.length < 2 || !text.startsWith('/')) return null
    val query = text.substring(1)
    return query.takeUnless { it.any(Char::isWhitespace) }
}

/**
 * Only an empty composer turning into exactly `/` opens the palette. Typing a slash
 * after an existing draft must stay ordinary text.
 */
internal fun opensSlashPalette(previous: String, next: String): Boolean =
    previous.isEmpty() && next == "/"

/** Case-insensitive match over the command key, title and description. */
internal fun filterSlashCommands(
    commands: List<SlashCommand>,
    query: String,
): List<SlashCommand> {
    val needle = query.trim()
    if (needle.isEmpty()) return commands
    return commands.filter {
        it.key.contains(needle, ignoreCase = true) ||
            it.title.contains(needle, ignoreCase = true) ||
            it.description.contains(needle, ignoreCase = true)
    }
}

/**
 * Floating command card above the composer. It uses the same glass family as the top
 * bar: real blur when the screen mounts a Haze source, translucent paper otherwise.
 * Rows are quiet-pressed list actions; only the card itself is floating chrome.
 */
@Composable
fun SlashPalette(
    commands: List<SlashCommand>,
    query: String,
    onPick: (SlashCommand) -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val filtered = remember(commands, query) { filterSlashCommands(commands, query) }
    Column(
        modifier
            .fillMaxWidth()
            .glass(
                frostedState,
                Radii.lgShape,
                style = GlassStyle.clear,
                tint = colors.paper.copy(alpha = 0.12f),
            )
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("快捷功能", style = SettingsType.eyebrow, color = colors.stone)
            Spacer(Modifier.weight(1f))
            Text(
                if (query.isEmpty()) "输入 / 选择" else "/$query",
                style = AppType.code.copy(fontSize = 11.sp),
                color = colors.stone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (filtered.isEmpty()) {
            Text(
                "没有匹配「/$query」的功能。清空输入，或直接发送你的问题。",
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = colors.stone,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(filtered, key = { it.key }) { command ->
                    SlashRow(command) { onPick(command) }
                }
            }
        }
    }
}

@Composable
private fun SlashRow(command: SlashCommand, onClick: () -> Unit) {
    val colors = MaterialTheme.appColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .heightIn(min = 54.dp)
            .pressFeedback(shape = CircleShape)
            .clip(CircleShape)
            .quietClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.surface1)
                .border(1.dp, colors.sand.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                command.icon,
                contentDescription = null,
                tint = colors.graphite,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                command.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                command.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.stone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "/${command.key}",
            style = AppType.code.copy(fontSize = 11.5.sp),
            color = colors.stone,
        )
    }
}
