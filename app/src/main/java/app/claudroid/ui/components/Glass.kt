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
import androidx.compose.ui.draw.drawWithContent
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
 * How much denser the shared glass runs than the call site's hint. The lift is applied
 * in one place so every capsule keeps text contrast while the sheen and rim (not a flat
 * opaque fill) carry the liquid look.
 */
private const val GlassTintLift = 0.04f
private const val GlassTintMax = 0.84f

/**
 * The shared liquid-glass surface: warm soft shadow, a clipped Haze child (or a
 * translucent paper gradient when there is no source to blur), an interior sheen and a
 * bright-to-sand rim that catches the light like iOS's floating capsules.
 *
 * The layering is what separates a glass chip from the near-identical canvas in light
 * mode: the shadow lifts it off the page, the top-edge white sheen gathers light just
 * under the rim, and the bottom shade gives the material a little thickness. On dark
 * mode the same layers stay dim so the capsule does not turn gray or milky. All of it
 * lives here — call sites only choose shape, tint and blur.
 *
 * Extracted from the chat top bar so the settings chrome can reuse the exact same
 * material instead of growing a second, slightly different glass recipe.
 */
@Composable
fun Modifier.glass(
    frostedState: HazeState?,
    shape: Shape,
    blurRadius: Dp = 30.dp,
    tintAlpha: Float = 0.64f,
): Modifier {
    val colors = MaterialTheme.appColors
    val dark = colors.dark
    val tint = (tintAlpha + GlassTintLift).coerceAtMost(GlassTintMax)

    // A bright top edge and a warmer/deeper bottom edge. The contrast is raised from
    // the old two-stop rim so the 46dp capsules keep an outline even when the canvas
    // and paper fills are only a few RGB points apart.
    val rim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.28f else 0.92f),
            colors.sand.copy(alpha = if (dark) 0.48f else 0.80f),
        )
    )
    // Painted above the tint/blur but below the content: a specular top band, a soft
    // mid falloff and a warm bottom edge. Only the light values are strong; dark mode
    // gets a faint white kiss and a canvas-colored base so it stays charcoal, not gray.
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.06f else 0.28f),
        0.32f to Color.White.copy(alpha = if (dark) 0.015f else 0.07f),
        0.60f to Color.Transparent,
        1f to (if (dark) colors.canvas.copy(alpha = 0.24f) else colors.ink.copy(alpha = 0.045f)),
    )
    // Without a same-window Haze source (home, drawer, model sheet) the fill is still a
    // slightly translucent paper gradient rather than one flat swatch, so the sheen and
    // rim have something to sit on.
    val fallback = Brush.verticalGradient(
        listOf(
            colors.paper.copy(alpha = if (dark) 0.95f else 0.94f),
            colors.paper.copy(alpha = if (dark) 0.90f else 0.86f),
        )
    )

    return this
        .shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = colors.ink.copy(alpha = if (dark) 0.48f else 0.16f),
            spotColor = colors.ink.copy(alpha = if (dark) 0.58f else 0.22f),
        )
        .clip(shape)
        .then(
            if (frostedState != null) {
                Modifier.frosted(
                    state = frostedState,
                    surface = colors.canvas,
                    blurRadius = blurRadius,
                    tintAlpha = tint,
                )
            } else {
                Modifier.background(fallback)
            }
        )
        .drawWithContent {
            drawRect(brush = sheen)
            drawContent()
        }
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
