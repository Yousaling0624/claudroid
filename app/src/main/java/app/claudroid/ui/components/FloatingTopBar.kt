package app.claudroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState

/** Height of the floating bar itself, excluding the status-bar inset. */
val FloatingTopBarHeight = 62.dp

/** The two dropdowns share one width budget so they read as the same family. */
val TitleMenuWidth = 236.dp
val ModelMenuWidth = 248.dp

private val MenuVerticalOffset = 6.dp

/**
 * Four detached glass capsules instead of a full-width app bar: a round drawer
 * button, the long title pill, a compact model switcher and a round new-chat button.
 *
 * The row draws nothing behind the gaps, so scrolling content stays visible between
 * the pills; on the chat screen each pill mounts the same Haze child so the text
 * underneath blurs through. On the landing page there is no scrolling source, so the
 * pills fall back to translucent paper and keep the same silhouette.
 *
 * Both pills own a downward dropdown anchored to their own bounds. The slots receive
 * the already-computed offset so the screens only declare menu content; title menus
 * centre under the pill (negative offsets are allowed when the pill is narrower than
 * the menu) and model menus right-align to it. M3's popup position provider clamps
 * both to the window.
 */
@Composable
fun FloatingTopBar(
    title: String,
    subtitle: String?,
    modelLabel: String,
    running: Boolean,
    onOpenDrawer: () -> Unit,
    onModelClick: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    frostedState: HazeState? = null,
    titleMenuOpen: Boolean = false,
    modelMenuOpen: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    titleMenu: (@Composable (DpOffset) -> Unit)? = null,
    modelMenu: (@Composable (DpOffset) -> Unit)? = null,
) {
    val density = LocalDensity.current
    // The model pill is content-sized, so its measured width (not the row's remaining
    // maxWidth) is what right-aligns the dropdown to the pill's trailing edge.
    var modelPillWidth by remember { mutableStateOf(0.dp) }

    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = AppIcons.Menu,
            label = "会话记录",
            onClick = onOpenDrawer,
            frostedState = frostedState,
        )

        BoxWithConstraints(Modifier.weight(1f)) {
            TitlePill(
                title = title,
                subtitle = subtitle,
                running = running,
                hasMenu = titleMenu != null,
                menuOpen = titleMenuOpen,
                onClick = onTitleClick,
                frostedState = frostedState,
            )
            titleMenu?.invoke(
                DpOffset(
                    // Negative when the title pill is narrower than the menu: the menu
                    // still centres under the pill, and M3's popup position provider
                    // keeps it inside the window.
                    (maxWidth - TitleMenuWidth) / 2,
                    MenuVerticalOffset,
                )
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .height(46.dp)
                .onSizeChanged { modelPillWidth = with(density) { it.width.toDp() } },
            contentAlignment = Alignment.CenterEnd,
        ) {
            ModelPill(
                label = modelLabel,
                menuOpen = modelMenuOpen,
                onClick = onModelClick,
                frostedState = frostedState,
            )
            modelMenu?.invoke(
                DpOffset(
                    (modelPillWidth - ModelMenuWidth).coerceAtMost(0.dp),
                    MenuVerticalOffset,
                )
            )
        }

        GlassIconButton(
            icon = AppIcons.Plus,
            label = "新对话",
            onClick = onNewChat,
            frostedState = frostedState,
        )
    }
}

/**
 * Shared dropdown chrome for the title and model menus: paper surface, 22dp corners,
 * no tonal wash and a 10dp ambient shadow. The default M3 menu surface reads too cool
 * and too square next to the floating capsules.
 */
@Composable
fun FloatingMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    offset: DpOffset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.appColors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(width),
        offset = offset,
        shape = Radii.menuShape,
        containerColor = colors.paper,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, colors.sand.copy(alpha = 0.7f)),
        content = content,
    )
}

/** One icon + label row in a [FloatingMenu], with disabled colors from the M3 menu. */
@Composable
fun FloatingMenuItem(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    DropdownMenuItem(
        text = {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = colors.ink,
            leadingIconColor = colors.graphite,
            disabledTextColor = colors.bone,
            disabledLeadingIconColor = colors.bone,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun TitlePill(
    title: String,
    subtitle: String?,
    running: Boolean,
    hasMenu: Boolean,
    menuOpen: Boolean,
    onClick: (() -> Unit)?,
    frostedState: HazeState?,
) {
    val colors = MaterialTheme.appColors
    Row(
        Modifier
            .fillMaxSize()
            .pressFeedback(enabled = hasMenu, shape = CircleShape, dragMorph = true)
            .glass(frostedState, CircleShape, tintAlpha = 0.72f)
            .quietClickable(enabled = hasMenu) { onClick?.invoke() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp,
                ),
                color = colors.ink,
            )
            AnimatedVisibility(
                visible = running && subtitle != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(dotSize = 2.4.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        subtitle.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AppType.caption.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = colors.stone,
                    )
                }
            }
        }
        if (hasMenu) {
            Spacer(Modifier.width(4.dp))
            RotatingChevron(expanded = menuOpen, tint = colors.stone, size = 12.dp)
        }
    }
}

@Composable
private fun ModelPill(
    label: String,
    menuOpen: Boolean,
    onClick: () -> Unit,
    frostedState: HazeState?,
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.appColors
    Row(
        Modifier
            .height(46.dp)
            .widthIn(max = 132.dp)
            .pressFeedback(shape = CircleShape, dragMorph = true)
            .glass(frostedState, CircleShape, tintAlpha = 0.70f)
            .quietClickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClaudeMark(size = 12.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.graphite,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        RotatingChevron(expanded = menuOpen, tint = colors.stone, size = 11.dp)
    }
}
