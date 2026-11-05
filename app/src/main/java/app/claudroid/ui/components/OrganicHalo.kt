package app.claudroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import app.claudroid.ui.theme.LocalReduceMotion

/**
 * A slow, organic halo built from real morphing curves rather than a scaled circle.
 *
 * `graphics-shapes` interpolates matched cubic segments between a circle and a heavily
 * rounded star, so the silhouette keeps its rhythm while it breathes. Kept behind the
 * Claude mark at low alpha: it should read as paper taking a breath, not as a spinner.
 */
@Composable
fun OrganicHalo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = Color.Unspecified,
) {
    val morph = remember { OrganicHaloMorph }
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "organic-halo")
    val breathing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "organic-halo-breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(42000, easing = LinearEasing),
        ),
        label = "organic-halo-spin",
    )
    val progress = if (reduceMotion) 0.35f else breathing
    val rotation = if (reduceMotion) 0f else spin

    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val path = Path()
        morph.asCubics(progress).forEachIndexed { index, cubic ->
            if (index == 0) {
                path.moveTo(center.x + cubic.anchor0X * radius, center.y + cubic.anchor0Y * radius)
            }
            path.cubicTo(
                center.x + cubic.control0X * radius,
                center.y + cubic.control0Y * radius,
                center.x + cubic.control1X * radius,
                center.y + cubic.control1Y * radius,
                center.x + cubic.anchor1X * radius,
                center.y + cubic.anchor1Y * radius,
            )
        }
        path.close()
        rotate(rotation, pivot = center) {
            drawPath(path, color)
        }
    }
}

private val OrganicHaloMorph = Morph(
    RoundedPolygon.circle(numVertices = 8),
    RoundedPolygon.star(
        numVerticesPerRadius = 5,
        radius = 1f,
        innerRadius = 0.8f,
        rounding = CornerRounding(radius = 0.26f),
        innerRounding = CornerRounding(radius = 0.3f),
    ),
)
