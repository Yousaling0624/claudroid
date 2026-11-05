package app.claudroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors

/**
 * Warm-paper building blocks shared by settings, workspace and the model sheets.
 *
 * These intentionally avoid stock Material surfaces/buttons: a flat paper card with a
 * 1dp sand hairline is the app's card language, and the only strong fill is terracotta.
 */

/** Left-aligned page bar used by settings/workspace; 52dp tall, no elevation. */
@Composable
fun ClaudeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    AppIcons.Back,
                    contentDescription = "返回",
                    tint = MaterialTheme.appColors.graphite,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.stone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A paper section card: rounded from the shared scale, hairline sand border, no shadow. */
@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = Radii.lgShape
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(MaterialTheme.appColors.paper)
            .border(1.dp, MaterialTheme.appColors.sand, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        SectionLabel(title)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.stone,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * 56dp settings row; interactive rows get the shared press physics (scale + settle)
 * and a quiet pressed tint instead of a ripple. A leading icon slot lets the new
 * two-level settings IA label each row without adding ad-hoc padding elsewhere.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed && onClick != null) MaterialTheme.appColors.surface1.copy(alpha = 0.55f)
        else Color.Transparent,
        animationSpec = tween(Motion.FAST),
        label = "settings-row",
    )
    val shape = Radii.smShape
    Row(
        Modifier
            .then(if (onClick != null) Modifier.pressFeedback(shape = shape) else Modifier)
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .heightIn(min = 60.dp)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.stone,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
fun ClaudeDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.appColors.sand.copy(alpha = 0.7f), modifier = modifier)
}

@Composable
fun ClaudeField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var revealed by remember { mutableStateOf(false) }
    // Local buffer: DataStore writes lag behind fast typing, and binding directly to the
    // persisted value causes dropped/reordered characters. External updates are adopted
    // only while the field is idle (provider presets clear focus first).
    var text by remember { mutableStateOf(value) }
    var committed by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.appColors.orange else MaterialTheme.appColors.sand,
        animationSpec = tween(Motion.FAST, easing = Motion.EaseChrome),
        label = "field-border",
    )
    LaunchedEffect(value, focused) {
        if (!focused && value != committed) {
            text = value
            committed = value
        }
    }
    Column(modifier.padding(top = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.appColors.stone,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radii.smShape)
                .background(MaterialTheme.appColors.surface1)
                .border(1.dp, borderColor, Radii.smShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.stone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        committed = it
                        onChange(it)
                    },
                    singleLine = singleLine,
                    maxLines = if (singleLine) 1 else 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.appColors.ink),
                    cursorBrush = SolidColor(MaterialTheme.appColors.orange),
                    visualTransformation = if (secret && !revealed) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (secret) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (revealed) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.accentInk,
                    modifier = Modifier
                        .clip(Radii.xsShape)
                        .clickable { revealed = !revealed }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Selected chip: quiet ink fill, not terracotta. Orange is reserved for the one key
 * action / focus ring, so a row of selected options no longer floods the page with it.
 * Unselected stays paper with a hairline sand border.
 */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.appColors.surface2.copy(alpha = 0.5f)
            selected -> MaterialTheme.appColors.ink
            else -> MaterialTheme.appColors.paper
        },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "chip-bg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) MaterialTheme.appColors.ink else MaterialTheme.appColors.sand,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "chip-border",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.appColors.bone
            selected -> MaterialTheme.appColors.paper
            else -> MaterialTheme.appColors.graphite
        },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "chip-fg",
    )
    Box(
        modifier
            .pressFeedback(enabled = enabled, shape = CircleShape)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    // Primary is the one place a terracotta fill is allowed: it is the key action.
    val shape = Radii.mdShape
    val fill = when {
        !enabled -> MaterialTheme.appColors.surface2
        destructive -> MaterialTheme.appColors.error
        else -> MaterialTheme.appColors.orange
    }
    val foreground = when {
        !enabled -> MaterialTheme.appColors.bone
        destructive -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier
            .pressFeedback(enabled = enabled, shape = shape)
            .clip(shape)
            .background(fill)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val shape = Radii.mdShape
    val border = if (destructive) MaterialTheme.appColors.error.copy(alpha = 0.45f)
    else MaterialTheme.appColors.sand
    Box(
        modifier
            .pressFeedback(enabled = enabled, shape = shape)
            .clip(shape)
            .background(MaterialTheme.appColors.paper)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.appColors.bone
                destructive -> MaterialTheme.appColors.error
                else -> MaterialTheme.appColors.ink
            },
        )
    }
}

/** Hand-drawn switch; stock M3 Switch reads too cool next to the paper palette. */
@Composable
fun WarmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val track by animateColorAsState(
        targetValue = if (checked) MaterialTheme.appColors.ink else MaterialTheme.appColors.surface2,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseChrome),
        label = "switch-track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = Motion.ReleaseDp,
        label = "switch-thumb",
    )
    Box(
        Modifier
            .pressFeedback(shape = CircleShape)
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, MaterialTheme.appColors.sand.copy(alpha = 0.55f), CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.paper)
        )
    }
}

/**
 * Paper dialog shell. Actions are supplied by the caller so two-option and
 * three-option confirmations can share the same visual language.
 */
@Composable
fun ClaudeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = Radii.xlShape,
            color = MaterialTheme.appColors.paper,
            border = BorderStroke(1.dp, MaterialTheme.appColors.sand),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.appColors.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.appColors.graphite,
                )
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

/** Dark mono block with a copy affordance, used for logs, self-test and engine output. */
@Composable
fun CodeCard(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "LOG",
    maxHeight: androidx.compose.ui.unit.Dp = 280.dp,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(Radii.mdShape)
            .background(MaterialTheme.appColors.codeBg),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.6.sp,
                ),
                color = MaterialTheme.appColors.codeFg.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(text))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (copied) AppIcons.Check else AppIcons.Copy,
                    contentDescription = "复制",
                    tint = MaterialTheme.appColors.codeFg.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.appColors.codeFg.copy(alpha = 0.08f))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Text(
                text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                ),
                color = MaterialTheme.appColors.codeFg,
            )
        }
    }
}
