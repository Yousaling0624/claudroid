package app.claudroid.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.appColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Claude-style starburst: eight tapered rays with curved shoulders, no outer
 * ring. Drawn instead of shipped as an asset so it stays crisp at every size and
 * follows the theme accent.
 *
 * [pulse] fades the mark while a turn is running: a quieter "alive" signal than a
 * spinner taking over the screen.
 */
@Composable
fun ClaudeMark(
    size: Dp = 22.dp,
    color: Color = MaterialTheme.appColors.orange,
    rays: Int = 8,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "mark-pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = Motion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mark-alpha",
        )
        value
    } else {
        1f
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension / 2f
        val baseRadius = outer * 0.055f
        val halfWidth = outer * 0.12f
        repeat(rays) { index ->
            val angle = (2 * PI * index / rays) - PI / 2
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            // Perpendicular to the ray, used to give the petal its width.
            val px = -dy
            val py = dx
            val base = Offset(center.x + dx * baseRadius, center.y + dy * baseRadius)
            val tip = Offset(center.x + dx * outer * 0.98f, center.y + dy * outer * 0.98f)
            val shoulder = Offset(
                center.x + dx * outer * 0.44f,
                center.y + dy * outer * 0.44f,
            )
            val path = Path().apply {
                moveTo(base.x + px * halfWidth, base.y + py * halfWidth)
                cubicTo(
                    shoulder.x + px * halfWidth * 1.35f,
                    shoulder.y + py * halfWidth * 1.35f,
                    tip.x - dx * outer * 0.09f + px * halfWidth * 0.6f,
                    tip.y - dy * outer * 0.09f + py * halfWidth * 0.6f,
                    tip.x,
                    tip.y,
                )
                cubicTo(
                    tip.x - dx * outer * 0.09f - px * halfWidth * 0.6f,
                    tip.y - dy * outer * 0.09f - py * halfWidth * 0.6f,
                    shoulder.x - px * halfWidth * 1.35f,
                    shoulder.y - py * halfWidth * 1.35f,
                    base.x - px * halfWidth,
                    base.y - py * halfWidth,
                )
                close()
            }
            drawPath(path, color)
        }
    }
}

/** Wordmark in the serif voice; CJK-only devices still fall back to Noto Serif. */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    size: TextUnit = 19.sp,
    color: Color = MaterialTheme.appColors.ink,
) {
    Text(
        text = "Claudroid",
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = size,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.2).sp,
        ),
        color = color,
    )
}

/** Star + wordmark lockup shared by the app bar and the drawer header. */
@Composable
fun BrandLockup(
    starSize: Dp = 18.dp,
    textSize: TextUnit = 19.sp,
    spacing: Dp = 7.dp,
    color: Color = MaterialTheme.appColors.ink,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClaudeMark(size = starSize)
        Spacer(Modifier.width(spacing))
        Wordmark(size = textSize, color = color)
    }
}

/** Small caps section label, as used in Claude's settings / sidebar. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        ),
        color = MaterialTheme.appColors.stone,
        modifier = modifier,
    )
}
