package app.claudroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * The content that scrolls *under* a frosted bar mounts [Modifier.hazeSource]; the bar
 * on top mounts [Modifier.frosted]. Haze renders the source into a graphics layer and
 * blurs it into the bar. On Android 11 and older it degrades to the style's fallback
 * tint, so the bar must still be readable there.
 */
@Composable
fun rememberFrostedState(): HazeState = remember { HazeState() }

fun Modifier.hazeSource(state: HazeState): Modifier = haze(state)

/**
 * Glass tint for a top bar. [surface] should be the page canvas: it is drawn solid
 * behind the blurred layer and then washed over it with [tintAlpha], leaving just
 * enough of the moving content visible to signal depth without hurting text contrast.
 *
 * The shared recipe keeps a touch more blur than the old 26dp and raises the noise
 * slightly; the grain is what keeps a large blurred area from looking like a flat
 * translucent rectangle, while 0.06 stays below the threshold where it reads as dirt.
 */
fun Modifier.frosted(
    state: HazeState,
    surface: Color,
    blurRadius: Dp = 28.dp,
    tintAlpha: Float = 0.93f,
    fallbackAlpha: Float = 0.99f,
): Modifier = hazeChild(
    state = state,
    style = HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = tintAlpha)),
        blurRadius = blurRadius,
        noiseFactor = 0.06f,
        fallbackTint = HazeTint(surface.copy(alpha = fallbackAlpha)),
    ),
)
