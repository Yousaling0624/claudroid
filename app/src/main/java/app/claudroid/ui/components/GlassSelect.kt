package app.claudroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors

/**
 * One choice inside [GlassOptionSheet]. [onSelect] runs immediately on tap, before the
 * sheet animates out, so a settings write can start while the glass is still on screen.
 */
class GlassOption(
    val label: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val onSelect: () -> Unit,
)

/** Everything the picker needs; the page that owns the setting builds it on tap. */
class GlassPickerRequest(
    val title: String,
    val options: List<GlassOption>,
    val description: String? = null,
)

/**
 * Hoisted picker state. Pages never host the sheet themselves: the settings root provides
 * one state, so every list lives in the same window (Haze can only sample there), shares
 * the page's Haze state, and always draws above the floating top bar.
 */
@Stable
class GlassPickerState {
    var request by mutableStateOf<GlassPickerRequest?>(null)
        private set

    fun show(request: GlassPickerRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberGlassPickerState(): GlassPickerState = remember { GlassPickerState() }

/**
 * A nullable local rather than an error default: rows keep working in previews or in any
 * future paper-only host, where tapping simply has no sheet to open.
 */
val LocalGlassPicker = staticCompositionLocalOf<GlassPickerState?> { null }

/**
 * A settings row for a small, mutually exclusive choice. It reads as one line — field
 * name, current value, chevron — instead of a wrapping chip row, so the paper card keeps
 * a calm vertical rhythm and long labels stop stealing width from their neighbours.
 *
 * The whole row is one button with the current value as its state description; the
 * actual choice happens in [GlassOptionSheet] at the screen root.
 */
@Composable
fun GlassSelectRow(
    title: String,
    value: String,
    options: List<GlassOption>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    sheetTitle: String = title,
    sheetDescription: String? = null,
) {
    val picker = LocalGlassPicker.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.appColors.surface1.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(if (reduceMotion) 0 else Motion.FAST),
        label = "glass-select-row",
    )
    Row(
        modifier
            .pressFeedback(shape = Radii.smShape)
            .fillMaxWidth()
            .clip(Radii.smShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "选择$title",
                role = Role.Button,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                picker?.show(
                    GlassPickerRequest(
                        title = sheetTitle,
                        options = options,
                        description = sheetDescription,
                    )
                )
            }
            .semantics { stateDescription = value }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = SettingsType.rowTitle,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    color = MaterialTheme.appColors.stone,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.graphite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 190.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            AppIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.appColors.stone,
            modifier = Modifier.size(15.dp),
        )
    }
}
