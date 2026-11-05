package app.claudroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.appColors

/**
 * The one composer shared by the landing page and the transcript: paper card with a
 * hairline sand border, auto-growing text field, attachment + model affordances and
 * a single round send / stop control.
 */
@Composable
fun ClaudeComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    model: String,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.appColors
    val shape = RoundedCornerShape(24.dp)
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
        targetValue = when {
            running -> colors.orange
            enabled -> colors.orange
            else -> colors.surface2
        },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseChrome),
        label = "send-color",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (sendPressed) 0.94f else 1f,
        animationSpec = Motion.Press,
        label = "send-scale",
    )

    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.paper)
                .border(borderWidth, borderColor, shape)
                .animateContentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 2.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = AppType.userBody,
                        color = colors.stone,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = AppType.userBody.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.orange),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onAttach),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Plus,
                        "添加附件",
                        tint = colors.graphite,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onModelClick)
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClaudeMark(size = 12.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = model.ifBlank { "选择模型" },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp,
                        ),
                        color = colors.graphite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 168.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.stone,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
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
                            modifier = Modifier.size(14.dp),
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
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Agent 可能修改系统文件，请确认它执行的命令。",
            style = AppType.caption,
            color = colors.stone,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
