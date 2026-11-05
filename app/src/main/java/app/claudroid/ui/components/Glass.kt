package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The shared state for one screen's glass. The scrolling content mounts
 * [Modifier.glassSource] with this state and every floating surface in the same window
 * consumes it through [Modifier.glass].
 *
 * Haze can only sample nodes from the same window, which is why overlays that must blur
 * the page (slash palette, model sheets, scrims) live in the screen instead of a Dialog.
 */
@Composable
fun rememberGlassState(): HazeState = rememberHazeState()

/** Marks the scrolling content that the floating glass surfaces sample. */
fun Modifier.glassSource(state: HazeState): Modifier = hazeSource(state)

/**
 * Liquid-glass material on top of Haze 2's `hazeGlass`.
 *
 * Haze 2 renders the refraction, specular rim, edge shadow and Fresnel response itself;
 * this wrapper only owns the shared parts:
 * - the shape, because `hazeGlass` receives it through the Style rather than a modifier
 *   parameter;
 * - one shared specular/ambient/dispersion calibration so a capsule reads as glass
 *   instead of a flat translucent rectangle (kept subtle: 0.045 is visible on edges,
 *   not a rainbow, and the dark values stay below the milky threshold);
 * - the warm [tint] (applied to refracted content, not painted as an opaque wash);
 * - a lower-opacity lift shadow, so it does not fight Haze's own edge shadow.
 *
 * With no source (home, drawer, an opaque sheet) the surface falls back to a
 * low-opacity paper gradient. It stays translucent on purpose: a 0.86-0.95 fill was
 * what made the old chrome read as an outlined card rather than glass.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(
    state: HazeState?,
    shape: RoundedCornerShape,
    style: GlassStyle = GlassStyle.regular,
    tint: Color? = null,
): Modifier {
    val colors = MaterialTheme.appColors
    val dark = colors.dark

    // Styles compare by identity and are resolved on every frame; rebuilding this in
    // each composition would replace the node's material unnecessarily.
    val resolved = remember(style, shape, tint, dark) {
        var result = style.then {
            this.shape(shape)
            // One shared calibration on top of Regular/Clear: enough highlight and
            // dispersion to read as glass, still calmer than the built-in defaults in
            // dark mode where a bright ambient response turns the capsule milky.
            specularIntensity(if (dark) 0.46f else 0.54f)
            ambientResponse(if (dark) 0.16f else 0.24f)
            chromaticAberrationStrength(0.045f)
            edgeSoftness(1.dp)
            // Haze resolves its built-in light/dark base from the *system* appearance,
            // while this app can force its own theme. Override every appearance-dependent
            // field so Regular/Clear both follow `appColors.dark`, not the platform.
            edgeShadow(Color.Black.copy(alpha = if (dark) 0.32f else 0.14f))
            contrast(if (dark) 0.08f else 0f)
            whitePoint(if (dark) -0.22f else 0.55f)
            chromaMultiplier(if (dark) 1.1f else 1.5f)
        }
        if (tint != null) {
            val glassTint = tint
            result = result.then { this.tint(glassTint) }
        }
        result
    }

    // Haze's edge shadow already darkens the rim; a 10dp ambient lift keeps the capsule
    // floating without stacking a second hard outline on top of it.
    val raised = this.shadow(
        elevation = 10.dp,
        shape = shape,
        clip = false,
        ambientColor = colors.ink.copy(alpha = if (dark) 0.30f else 0.10f),
        spotColor = colors.ink.copy(alpha = if (dark) 0.38f else 0.14f),
    )

    return if (state != null) {
        raised.hazeGlass(input = HazeInput.Sources(state), style = resolved)
    } else {
        raised
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.paper.copy(alpha = if (dark) 0.72f else 0.62f),
                        // The lower stop is a touch warmer/darker than the page, so the
                        // capsule keeps an edge on paper without an opaque rim border.
                        (if (dark) colors.canvas else colors.surface1)
                            .copy(alpha = if (dark) 0.56f else 0.50f),
                    )
                )
            )
    }
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
    val glassTint = MaterialTheme.appColors.paper.copy(alpha = 0.16f)
    Box(
        modifier
            .size(size)
            .pressFeedback(shape = CircleShape, dragMorph = true)
            .glass(frostedState, CircleShape, tint = glassTint)
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
 * Full-window scrim that blurs the page behind an in-window modal.
 *
 * This uses Blur rather than Glass on purpose: a full-screen glass effect would pay for
 * refraction it cannot show and would warp the whole page. The scrim tint is a color
 * effect on the blurred input (not a solid background behind it), so the card on top
 * still has a predictable contrast base in both themes. On a screen without a source
 * the fallback is a translucent canvas wash, not the old near-opaque 0.94 fill.
 */
@OptIn(ExperimentalHazeApi::class)
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
    val style = remember(blurRadius, tintAlpha, colors.canvas) {
        HazeBlurStyle {
            blurRadius(blurRadius)
            noiseFactor(0.06f)
            colorEffects(listOf(HazeColorEffect.tint(colors.canvas.copy(alpha = tintAlpha))))
            // Blur is unavailable on some older devices; keep the backdrop
            // readable there instead of dropping the scrim to plain content.
            fallbackColorEffect(HazeColorEffect.tint(colors.canvas.copy(alpha = 0.62f)))
        }
    }
    val blur = if (frostedState != null) {
        Modifier.hazeBlur(HazeInput.Sources(frostedState), style)
    } else {
        Modifier.background(colors.canvas.copy(alpha = 0.62f))
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
