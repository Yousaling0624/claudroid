package app.claudroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * The little sunburst used as the assistant avatar. Drawn rather than shipped as an
 * asset so it stays crisp at any size and picks up the theme accent.
 */
@Composable
fun ClaudeMark(
    size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    rays: Int = 8,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.42f
        val core = outer * 0.26f
        for (i in 0 until rays) {
            val angle = (2 * PI * i / rays) - PI / 2
            val start = Offset(
                center.x + (cos(angle) * inner).toFloat(),
                center.y + (sin(angle) * inner).toFloat(),
            )
            val end = Offset(
                center.x + (cos(angle) * outer).toFloat(),
                center.y + (sin(angle) * outer).toFloat(),
            )
            drawLine(color, start, end, strokeWidth = outer * 0.11f, cap = StrokeCap.Round)
        }
        drawCircle(color, radius = core, center = center, style = Stroke(width = outer * 0.13f))
        drawCircle(color, radius = core * 0.34f, center = center)
    }
}

/** Small caps section label, as used in Claude's settings / sidebar. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun BrandRow(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClaudeMark(size = 20.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.size(9.dp))
            androidx.compose.foundation.layout.Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
