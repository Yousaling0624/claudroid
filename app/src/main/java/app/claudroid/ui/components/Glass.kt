package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState

/**
 * The shared liquid-glass surface: soft shadow, a clipped Haze child (or translucent
 * paper when there is no source to blur) and a bright-to-sand rim that catches the
 * light like iOS's floating capsules.
 *
 * Extracted from the chat top bar so the settings chrome can reuse the exact same
 * material instead of growing a second, slightly different glass recipe.
 */
@Composable
fun Modifier.glass(
    frostedState: HazeState?,
    shape: Shape,
    blurRadius: Dp = 28.dp,
    tintAlpha: Float = 0.64f,
): Modifier {
    val colors = MaterialTheme.appColors
    // A brighter top edge and a slightly deeper sand edge separate the capsule from the
    // paper even when the tint is low; in dark mode the rim is dimmer white but still
    // visibly catches the light.
    val rim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (colors.dark) 0.22f else 0.78f),
            colors.sand.copy(alpha = if (colors.dark) 0.38f else 0.55f),
        )
    )
    return this
        .shadow(
            elevation = 9.dp,
            shape = shape,
            clip = false,
            ambientColor = colors.ink.copy(alpha = if (colors.dark) 0.42f else 0.12f),
            spotColor = colors.ink.copy(alpha = if (colors.dark) 0.50f else 0.16f),
        )
        .clip(shape)
        .then(
            if (frostedState != null) {
                Modifier.frosted(
                    state = frostedState,
                    surface = colors.canvas,
                    blurRadius = blurRadius,
                    tintAlpha = tintAlpha,
                )
            } else {
                // No same-window source (home, drawer): translucent paper plus the rim
                // and shadow still reads as a floating material, without faking blur.
                Modifier.background(colors.paper.copy(alpha = if (colors.dark) 0.92f else 0.90f))
            }
        )
        .border(1.dp, rim, shape)
}

/**
 * Round glass action used by the floating chrome. It is deliberately limited to
 * chrome: the drag morph is the tactile signature of a floating capsule, not of a
 * row inside a scrollable list.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.appColors.graphite,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(size)
            .pressFeedback(shape = CircleShape, dragMorph = true)
            .glass(frostedState, CircleShape)
            .quietClickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Full-window scrim that blurs the settings content behind an in-window modal.
 *
 * Haze can only sample nodes from the same window, which is why the model picker no
 * longer uses ModalBottomSheet/Dialog. The scrim also owns the tap-to-dismiss
 * gesture; being the topmost hit-testable node, it keeps drags away from the
 * scrollable page underneath. The dim wash sits *above* the blur so the card keeps
 * its contrast in both themes; on Android 11 and older Haze falls back to the
 * opaque-ish tint and the scrim still reads as a modal backdrop.
 */
@Composable
fun GlassScrim(
    frostedState: HazeState?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 26.dp,
    tintAlpha: Float = 0.34f,
    dimAlpha: Float = 0.14f,
) {
    val colors = MaterialTheme.appColors
    val blur = if (frostedState != null) {
        Modifier.frosted(
            state = frostedState,
            surface = colors.canvas,
            blurRadius = blurRadius,
            tintAlpha = tintAlpha,
            fallbackAlpha = 0.94f,
        )
    } else {
        Modifier.background(colors.canvas.copy(alpha = 0.94f))
    }
    val dim = if (colors.dark) {
        // `ink` is near-white in the dark palette; dimming with it would lift the
        // whole backdrop to gray. Canvas is the dark neutral that belongs to the
        // palette, so the sheet stays dark and the card separation stays intact.
        colors.canvas.copy(alpha = (dimAlpha * 1.6f).coerceAtMost(0.5f))
    } else {
        colors.ink.copy(alpha = dimAlpha)
    }
    Box(
        modifier
            .then(blur)
            .background(dim)
            .pointerInput(onTap) {
                detectTapGestures { onTap() }
            }
    )
}
