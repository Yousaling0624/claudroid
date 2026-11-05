package app.claudroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState

/**
 * The one composer shared by the landing page and the transcript.
 *
 * One rounded surface holds every affordance — attach, the growing text field and
 * send/stop — instead of stacking a toolbar under the input. The top-bar model pill
 * owns model switching now, so the composer stays a single uninterrupted surface.
 *
 * The surface uses a fixed 20dp corner rather than [CircleShape]: a capsule's radius is
 * always half the current height, so once the field grows to several lines the two
 * 40dp action buttons at the 6dp inset fall inside the bottom corner arc and get
 * clipped. 20dp is the largest Radii token that stays clear of those buttons at the
 * single-line height, while still reading as a capsule on one line.
 */
@Composable
fun ClaudeComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    slashCommands: List<SlashCommand> = emptyList(),
    onSlashAction: (SlashAction) -> Unit = {},
    frostedState: HazeState? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.appColors
    val shape = Radii.mdShape
    val reduceMotion = LocalReduceMotion.current
    var slashOpen by rememberSaveable { mutableStateOf(false) }
    val activeQuery = slashQuery(value)
    val showSlashPalette = slashOpen && slashCommands.isNotEmpty() && activeQuery != null

    fun handleValueChange(next: String) {
        if (opensSlashPalette(value, next)) slashOpen = true
        if (slashQuery(next) == null) slashOpen = false
        onValueChange(next)
    }

    // A modal card should be the first thing Back dismisses, ahead of the screen's
    // route handler. Sheets and dialogs elsewhere follow the same last-registered rule.
    BackHandler(enabled = showSlashPalette) { slashOpen = false }

    val borderWidth by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(Motion.FAST, easing = Motion.EaseChrome),
        label = "composer-border-width",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.orange else colors.sand,
        animationSpec = tween(Motion.FAST, easing = Motion.EaseChrome),
        label = "composer-border",
    )

    val enabled = running || value.isNotBlank()
    val sendColor by animateColorAsState(
        targetValue = if (enabled) colors.orange else colors.surface2,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseChrome),
        label = "send-color",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        AnimatedVisibility(
            visible = showSlashPalette,
            enter = expandVertically(
                animationSpec = tween(
                    if (reduceMotion) 0 else Motion.MEDIUM,
                    easing = Motion.EaseChrome,
                ),
                expandFrom = Alignment.Bottom,
            ) + fadeIn(tween(if (reduceMotion) 0 else Motion.FAST)),
            exit = shrinkVertically(
                animationSpec = tween(if (reduceMotion) 0 else Motion.FAST),
                shrinkTowards = Alignment.Bottom,
            ) + fadeOut(tween(if (reduceMotion) 0 else Motion.FAST)),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The card must not swallow the keyboard's space. Cap it at half of the
                // composer's available height (at most 300dp); the list scrolls inside.
                val paletteMaxHeight = minOf(300.dp, maxHeight * 0.55f)
                SlashPalette(
                    commands = slashCommands,
                    query = activeQuery.orEmpty(),
                    onPick = { command ->
                        slashOpen = false
                        handleValueChange("")
                        onSlashAction(command.action)
                    },
                    frostedState = frostedState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = paletteMaxHeight)
                        .padding(bottom = 8.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(CircleShape)
                    .background(colors.orangeSoft)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Edit,
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "正在编辑消息，发送后将从这里重新开始",
                    style = AppType.caption.copy(fontSize = 11.5.sp),
                    color = colors.accentInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "取消编辑",
                        tint = colors.accentInk,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = colors.ink.copy(alpha = 0.08f),
                    spotColor = colors.ink.copy(alpha = 0.10f),
                )
                .clip(shape)
                .background(colors.paper)
                .border(borderWidth, borderColor, shape)
                .animateContentSize()
                .padding(6.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .pressFeedback(shape = CircleShape, dragMorph = true)
                    .clip(CircleShape)
                    .background(colors.surface1)
                    .quietClickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Plus,
                    contentDescription = "添加附件",
                    tint = colors.graphite,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = AppType.userBody,
                        color = colors.stone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = ::handleValueChange,
                    textStyle = AppType.userBody.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.orange),
                    maxLines = 7,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .then(focusModifier)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(40.dp)
                    // The morph layer already presses to chromeScale; a second legacy
                    // scale on the same button would compound into a 0.90 press.
                    .pressFeedback(shape = CircleShape, dragMorph = true)
                    .clip(CircleShape)
                    .background(sendColor)
                    .clickable(
                        interactionSource = sendInteraction,
                        indication = null,
                        enabled = enabled,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (running) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    Icon(
                        AppIcons.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        AppIcons.ArrowUp,
                        contentDescription = "发送",
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimary else colors.bone,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "Agent 可能修改系统文件，请确认它执行的命令。",
            style = AppType.caption.copy(fontSize = 10.5.sp),
            color = colors.stone,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
