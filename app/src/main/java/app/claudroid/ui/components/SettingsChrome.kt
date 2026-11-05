package app.claudroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * Settings-only chrome. These components deliberately sit apart from the generic
 * paper controls: the floating bar, the big page header and the selectable option
 * rows are the new three-level IA's visual vocabulary, not things the workspace
 * needs.
 */

/** Large page title plus one explanatory sentence, directly under the floating bar. */
@Composable
fun SettingsPageHeader(
    title: String,
    intro: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(title, style = SettingsType.h1, color = colors.ink)
        if (intro != null) {
            Text(
                intro,
                style = SettingsType.intro,
                color = colors.stone,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Floating settings chrome: only the round back capsule is glass. The page title is the
 * H1 in the scrolling content, not a title pill — glass is reserved for controls, while
 * headings stay plain paper text.
 */
@Composable
fun SettingsTopBar(
    backLabel: String,
    onBack: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = AppIcons.Back,
            label = backLabel,
            onClick = onBack,
            frostedState = frostedState,
        )
    }
}

/**
 * Root category row. Each category is its own capsule instead of a divider-separated
 * line inside one big card: the larger gaps make the root read as a map of settings
 * rather than a form.
 */
@Composable
fun SettingsCategoryCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressFeedback(shape = CircleShape)
            .clip(CircleShape)
            .background(colors.paper)
            .border(1.dp, colors.sand.copy(alpha = 0.85f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(Radii.xsShape)
                .background(colors.surface1)
                .border(1.dp, colors.sand.copy(alpha = 0.55f), Radii.xsShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.graphite, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = SettingsType.rowTitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                color = colors.stone,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.stone,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Full-width selectable option used by third-level pages (permission mode). Selection
 * uses the quiet ink/surface language instead of flooding the page with terracotta;
 * the check mark carries the state for accessibility as a radio button.
 */
@Composable
fun SettingsOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (selected) colors.surface1 else Color.Transparent,
        animationSpec = tween(if (reduceMotion) 0 else Motion.FAST, easing = Motion.EaseChrome),
        label = "option-bg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .pressFeedback(shape = Radii.mdShape)
            .clip(Radii.mdShape)
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SettingsType.rowTitle, color = colors.ink)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = colors.stone,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.ink else Color.Transparent)
                .border(1.dp, if (selected) colors.ink else colors.sand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = colors.paper,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * In-window replacement for [ClaudeDialog] in settings. A Dialog is a separate window,
 * so Haze cannot sample the page behind it; this overlay stays in the settings window
 * and blurs the real background. Workspace keeps the regular paper dialog.
 */
@Composable
fun SettingsGlassDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.appColors
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (reduceMotion) progress.snapTo(0f)
            else progress.animateTo(0f, tween(Motion.MEDIUM, easing = Motion.EaseChrome))
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.EaseOut))
    }
    BackHandler { close() }

    Box(modifier.fillMaxSize()) {
        GlassScrim(
            frostedState = frostedState,
            onTap = { close() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value },
            blurRadius = 24.dp,
            tintAlpha = 0.20f,
            dimAlpha = 0.12f,
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = progress.value
                    val scale = 0.96f + 0.04f * progress.value
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = 20.dp,
                    shape = Radii.xlShape,
                    clip = false,
                    ambientColor = colors.ink.copy(alpha = 0.16f),
                    spotColor = colors.ink.copy(alpha = 0.20f),
                )
                .clip(Radii.xlShape)
                .background(colors.paper)
                .border(1.dp, colors.sand, Radii.xlShape)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(24.dp),
        ) {
            Text(
                title,
                style = SettingsType.h2,
                color = colors.ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.graphite,
            )
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}
