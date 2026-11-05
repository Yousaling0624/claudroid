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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlin.math.sqrt

/**
 * One choice inside [GlassOptionSheet]. Menus are title-only: the page's Hint/Note
 * carries the explanation, so an option never grows a second text line that makes the
 * compact panel taller and noisier. [onSelect] runs immediately on tap, before the
 * sheet animates out, so a settings write can start while the glass is still on screen.
 */
class GlassOption(
    val label: String,
    val selected: Boolean = false,
    val onSelect: () -> Unit,
)

/** Everything the picker needs; the page that owns the setting builds it on tap. */
class GlassPickerRequest(
    val title: String,
    val options: List<GlassOption>,
    /**
     * Bounds of the value button that opened the picker, in window pixels. The panel
     * grows out of this rect, covers it, and aligns its right edge to it. The overlay
     * subtracts its own window origin before using it. Null in previews or any host
     * without a row (the picker then falls back to a centred fade).
     */
    val anchor: Rect? = null,
    /**
     * Measured width of the enclosing select row, px. The panel may grow leftwards for
     * a long option title but must never be wider than its row. Null falls back to the
     * picker's own width cap.
     */
    val rowWidth: Float? = null,
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
 * Only the value + chevron is interactive. The left-hand label is plain text: tapping
 * it must not open anything, so it carries no ripple, no press feedback and no click
 * semantics. TalkBack announces one compact button with the current value as its state
 * description; the actual choice happens in [GlassOptionSheet] at the screen root.
 */
@Composable
fun GlassSelectRow(
    title: String,
    value: String,
    options: List<GlassOption>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    sheetTitle: String = title,
) {
    val picker = LocalGlassPicker.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    // Window coordinates rather than local ones: the picker overlay is mounted at the
    // screen root, so it needs an origin-independent rect to subtract from. Plain
    // holders keep scrolling from invalidating composition just to update a rect the
    // tap handler reads later.
    val metrics = remember { RowMetrics() }
    val pressed by interaction.collectIsPressedAsState()
    val buttonBackground by animateColorAsState(
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
            .fillMaxWidth()
            .onGloballyPositioned { metrics.rowWidth = it.size.width.toFloat() }
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inert on purpose: the row label explains the field, it is not a second tap
        // target. A 56dp row with a 40dp value button keeps the touch target large
        // without making the entire line react.
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
        Row(
            Modifier
                .pressFeedback(shape = Radii.smShape)
                // The picker anchors to this button, not to the whole row: its right
                // edge becomes the panel's right edge and its top edge is covered by
                // the panel, so the list reads as unfolding from the value itself.
                .onGloballyPositioned { metrics.buttonBounds = it.boundsInWindow() }
                .clip(Radii.smShape)
                .background(buttonBackground)
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
                            // Read at tap time so a scroll between layout and tap
                            // cannot leave the picker anchored to an old position.
                            anchor = metrics.buttonBounds,
                            rowWidth = metrics.rowWidth.takeIf { it > 0f },
                        )
                    )
                }
                .semantics { stateDescription = value }
                .heightIn(min = 40.dp)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
}

/** Non-observable row metrics; only the tap handler reads them. */
private class RowMetrics {
    var rowWidth: Float = 0f
    var buttonBounds: Rect? = null
}

// ---------------------------------------------------------------- picker placement
//
// Kept as plain pixels with no Compose or Android types so the geometry can be tested
// on the JVM. The overlay converts window coordinates to its own origin before calling.

/** A rectangle in overlay-local pixels. */
internal data class PickerRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val shortestSide: Float get() = minOf(width, height)
    val longestSide: Float get() = maxOf(width, height)
}

/** The measured panel rectangle plus the side it grew towards. */
internal data class PickerPlacement(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val expandsUp: Boolean,
)

/**
 * Final panel width. The panel has to be wide enough for the longest option title and
 * never narrower than the value button it unfolds from; it may grow leftwards but stays
 * inside [maxWidth] and the viewport margins. On phones this keeps the picker compact
 * instead of stretching it across the whole settings row.
 */
internal fun fitPickerWidth(
    anchorWidth: Float,
    contentWidth: Float,
    maxWidth: Float,
    viewportWidth: Float,
    margin: Float,
): Float {
    val usable = (viewportWidth - margin * 2f).coerceAtLeast(0f)
    val upper = minOf(maxWidth, usable).coerceAtLeast(0f)
    // A value button can itself be wider than the cap on a tiny screen; the cap wins.
    val lower = minOf(anchorWidth.coerceAtLeast(0f), upper)
    return contentWidth.coerceAtLeast(0f).coerceIn(lower, upper)
}

/**
 * Places the anchored picker so it covers the value button.
 *
 * The right edge line sits exactly on the button's right edge; the panel extends to the
 * left by its content width. Vertically it covers the button: opening down puts the top
 * edge on the button's top edge, flipping up puts the bottom edge on the button's bottom
 * edge. Covering the control makes the menu read as the value itself unfolding, instead
 * of a detached card floating below the row. It prefers opening downwards, flips up
 * only when the content cannot fit below, and when neither side fits it takes the
 * roomier side at the smaller height (the list scrolls inside). The result is clamped
 * to the margins so an anchor near a viewport edge can never place the panel off-screen;
 * that edge clamp is the only case where the right edge leaves the button.
 */
internal fun placePicker(
    anchor: PickerRect,
    viewportWidth: Float,
    viewportHeight: Float,
    contentWidth: Float,
    contentHeight: Float,
    maxWidth: Float,
    margin: Float,
    maxHeightFraction: Float,
): PickerPlacement {
    val width = fitPickerWidth(
        anchorWidth = anchor.width,
        contentWidth = contentWidth,
        maxWidth = maxWidth,
        viewportWidth = viewportWidth,
        margin = margin,
    )
    val left = safeClamp(anchor.right - width, margin, viewportWidth - margin - width)

    val cap = (viewportHeight * maxHeightFraction).coerceAtLeast(0f)
    val desired = contentHeight.coerceAtLeast(0f).coerceAtMost(cap)
    // Down: the panel's top edge is the button's top edge, so it grows into the space
    // between that edge and the bottom margin. Up: its bottom edge is the button's
    // bottom edge, so it grows into the space above the button.
    val belowSpace = (viewportHeight - margin - anchor.top).coerceAtLeast(0f)
    val aboveSpace = (anchor.bottom - margin).coerceAtLeast(0f)
    val expandsUp = when {
        belowSpace >= desired -> false
        aboveSpace >= desired -> true
        else -> aboveSpace > belowSpace
    }
    val sideSpace = if (expandsUp) aboveSpace else belowSpace
    val height = desired.coerceAtMost(sideSpace)
    val rawTop = if (expandsUp) anchor.bottom - height else anchor.top
    val top = safeClamp(rawTop, margin, viewportHeight - margin - height)

    return PickerPlacement(left, top, width, height, expandsUp)
}

/**
 * Rubber-band curve for the pull-to-dismiss drag: starts at 1:1 with the finger, then
 * asymptotically approaches [limit], so the panel never detaches from its anchor. Pure
 * so the feel and the release thresholds can be pinned by JVM tests.
 */
internal fun pickerDragOffset(raw: Float, limit: Float): Float {
    if (limit <= 0f || raw <= 0f) return 0f
    return limit * raw / (limit + raw)
}

/**
 * Scale deltas for the dragged panel. [stretch] lengthens the panel vertically,
 * [squash] narrows it a little, and [cornerGrowth] enlarges the corner radius so the
 * silhouette rounds as it is pulled. The square-root easing makes the first few
 * millimetres of pull visible instead of hiding the deformation behind the asymptote.
 */
internal data class PickerDragDeformation(
    val stretch: Float,
    val squash: Float,
    val cornerGrowth: Float,
)

internal fun pickerDragDeformation(offset: Float, limit: Float): PickerDragDeformation {
    if (limit <= 0f || offset <= 0f) return PickerDragDeformation(0f, 0f, 0f)
    val eased = sqrt((offset / limit).coerceIn(0f, 1f))
    return PickerDragDeformation(
        stretch = eased * 0.06f,
        squash = eased * 0.035f,
        cornerGrowth = eased * 0.50f,
    )
}

/**
 * A release dismisses when the pull is deep enough or fast enough. [offset] is the
 * distance the finger actually travelled, not the damped visual offset: the rubber
 * band compresses what the user sees, so judging intent on the finger keeps the
 * threshold honest ("pull it down about a row's height") instead of asking for a
 * drag roughly 1.5× longer than the number says.
 */
internal fun pickerShouldDismiss(
    offset: Float,
    velocityY: Float,
    distanceThreshold: Float,
    velocityThreshold: Float,
): Boolean = offset >= distanceThreshold || velocityY >= velocityThreshold

/** `coerceIn` throws when a tiny viewport makes the range empty; prefer the min edge. */
private fun safeClamp(value: Float, min: Float, max: Float): Float =
    if (max < min) min else value.coerceIn(min, max)
