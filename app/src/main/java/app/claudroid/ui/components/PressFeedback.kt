package app.claudroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.draw.clip
import app.claudroid.ui.theme.LocalReduceMotion
import kotlin.math.abs

/**
 * One shared "touch physics" modifier for clickable rows, chips, buttons and cards.
 *
 * Pressing scales to 0.96 and nudges the surface down ~1dp; releasing springs back with
 * [Motion.Release] (low stiffness, just-under-critical damping) for a quiet, slightly
 * springy settle. Unlike a ripple, this never fires on a scroll: the pointer is treated
 * as cancelled as soon as it travels past the view's touch slop, so a finger swiping a
 * list does not visibly depress every row it crosses.
 *
 * The caller still owns `clickable`/`combinedClickable`; this only adds the visual
 * transform and must sit *before* it in the chain so clicks are not swallowed. When the
 * system "remove animations" setting is on, the transform is skipped entirely.
 */
fun Modifier.pressFeedback(
    enabled: Boolean = true,
    shape: Shape? = null,
    pressedScale: Float = 0.96f,
    pressedTranslationY: Float = 1f,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    if (!enabled || reduceMotion) return@composed this

    var pressed by remember { mutableFloatStateOf(0f) }
    val slop = LocalViewConfiguration.current.touchSlop
    val fraction by animateFloatAsState(
        targetValue = pressed,
        animationSpec = Motion.Release,
        label = "press",
    )

    val clip = if (shape != null) Modifier.clip(shape) else Modifier
    return@composed this
        // Observe only: never consume, so the downstream `clickable` still fires.
        .pointerInput(enabled) {
            awaitEachGesture {
                // Main pass + requireUnconsumed=false: detect the down for the visual,
                // while leaving every change unconsumed for `clickable` / scroll parents.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                pressed = 1f
                awaitUpOrSlop(slop, down.position)
                pressed = 0f
            }
        }
        .then(clip)
        .graphicsLayer {
            scaleX = 1f + (pressedScale - 1f) * fraction
            scaleY = 1f + (pressedScale - 1f) * fraction
            translationY = pressedTranslationY * fraction
        }
}

/**
 * Waits for the finger to lift, but returns as soon as it moves past [slop] so the
 * parent scrollable can take over without a visible "squash" on the row.
 */
private suspend fun AwaitPointerEventScope.awaitUpOrSlop(slop: Float, start: Offset) {
    while (true) {
        // Main pass so this observer shares the same stream as the downstream clickable.
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull() ?: return
        val dx = abs(change.position.x - start.x)
        val dy = abs(change.position.y - start.y)
        if (dx > slop || dy > slop) return
        if (!change.pressed) return
    }
}
