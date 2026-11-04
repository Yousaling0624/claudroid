package app.claudroid.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One place for the app's motion vocabulary. Claude's own UI moves in short,
 * decelerating fades rather than bouncy springs; keeping the curves here avoids
 * every screen inventing its own timing.
 */
object Motion {
    const val FAST = 150
    const val MEDIUM = 260
    const val SLOW = 420

    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
}

/**
 * The three-dot "working" indicator from Claude's chat. Dots pulse in sequence so a
 * stalled-looking turn still reads as alive without a spinner taking over the screen.
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
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
    tint: Color = MaterialTheme.colorScheme.outline,
    size: Dp = 16.dp,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "chevron",
    )
    Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation),
    )
}
