package app.claudroid.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.appColors

/**
 * One place for the app's motion vocabulary. Chrome moves in short, decelerating
 * fades rather than bouncy springs; keeping the curves here avoids every screen
 * inventing its own timing.
 */
object Motion {
    const val FAST = 120
    const val MEDIUM = 180
    const val SLOW = 240

    /** Material's standard deceleration curve: quick out, soft landing. */
    val EaseChrome = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    /** Settles quickly and without visible rebound; paper should not feel rubbery. */
    val Press = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh)

    /**
     * Quiet press used by rows, chips and copy actions: no spring, no deformation,
     * just a short fade into the pressed scale. Floating chrome keeps its velocity
     * spring via [Release]; large actions use the same quiet timing plus a color/rim
     * change so the press reads without a bounce.
     */
    val PressQuiet = tween<Float>(FAST, easing = EaseChrome)

    /**
     * Release curve for [pressFeedback]: lower stiffness than [Press] and just-under-
     * critical damping give a soft, slightly springy "Q" settle instead of a hard snap,
     * without the wobble that would read as playful on paper.
     */
    val Release = spring<Float>(dampingRatio = 0.72f, stiffness = 340f)

    /** Dp variant of [Release] for size/layout animations (switch thumb, code fold). */
    val ReleaseDp = spring<Dp>(dampingRatio = 0.72f, stiffness = 340f)

    /**
     * Bubble-split picker morph. The platter opens on a lightly under-damped spring
     * (the source of the small overshoot as the drop stretches into the list); its
     * corners are squared off by a second, softer spring that starts 70ms later. Closing
     * uses a critically damped curve so the drop retracts without wobbling.
     */
    val PickerMorph = spring<Float>(dampingRatio = 0.754f, stiffness = 400f)
    val PickerCorners = spring<Float>(dampingRatio = 0.75f, stiffness = 324f)
    val PickerRetract = spring<Float>(dampingRatio = 1f, stiffness = 340f)

    /** Corner spring delay and the short content fade that leads the retract. */
    const val PICKER_CORNERS_DELAY = 70L
    const val PICKER_CONTENT_OUT = 36

    /**
     * The picker's first bubble is the paper droplet; the glass panel fades in between
     * these two points of the morph, once the platter has outgrown the droplet and covers
     * it. Without the delay the tiny glass platter reads as a bright (or, in dark mode,
     * darker) core inside the capsule.
     */
    const val PICKER_GLASS_IN = 0.12f
    const val PICKER_GLASS_FULL = 0.26f

    /** Content blur at the start of the morph, in dp; skipped below API 31. */
    const val PICKER_CONTENT_BLUR = 4f
}

/**
 * The three-dot "working" indicator from Claude's chat. Dots pulse in sequence so a
 * stalled-looking turn still reads as alive without a spinner taking over the screen.
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.appColors.orange,
    dotSize: Dp = 3.5.dp,
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.78f,
                targetValue = 0.18f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, delayMillis = index * 160, easing = Motion.EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape)
            )
            if (index < 2) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(3.dp))
            }
        }
    }
}

/** Caret that turns instead of swapping between two icons. */
@Composable
fun RotatingChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.appColors.stone,
    size: Dp = 16.dp,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseChrome),
        label = "chevron",
    )
    Icon(
        AppIcons.ChevronDown,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation),
    )
}
