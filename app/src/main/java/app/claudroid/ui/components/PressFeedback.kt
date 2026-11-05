package app.claudroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Peak stretch along the drag axis; the cross axis squashes to [DragMorphSquash] of it. */
private const val DragMorphMaxStretch = 0.15f
private const val DragMorphSquash = 0.65f

/** The surface follows the finger only a little; it should feel attached, not glued. */
private const val DragMorphNudge = 0.12f

/** Drag distance that reaches the full stretch; larger drags clamp. */
private val DragMorphMaxDrag = 120.dp

/** AnimationVector2D is unitless, so pixel velocity has to be scaled before it is reused. */
private const val DragMorphMaxVelocity = 6f
private const val DragMorphMaxNudgeVelocity = 600f

private val DragMorphSpring = spring<Offset>(dampingRatio = 0.55f, stiffness = 380f)

/** Scale offsets applied by a drag, kept pure so the gesture maths is unit-testable. */
internal data class DragMorphTarget(val scaleX: Float, val scaleY: Float)

internal fun dragMorphTarget(
    delta: Offset,
    maxDragPx: Float,
    maxStretch: Float = DragMorphMaxStretch,
    squashFactor: Float = DragMorphSquash,
): DragMorphTarget {
    if (maxDragPx <= 0f) return DragMorphTarget(0f, 0f)
    val horizontal = abs(delta.x) >= abs(delta.y)
    val axis = if (horizontal) abs(delta.x) else abs(delta.y)
    val stretch = (axis / maxDragPx * maxStretch).coerceIn(0f, maxStretch)
    val squash = stretch * squashFactor
    return if (horizontal) {
        DragMorphTarget(
            scaleX = if (delta.x >= 0f) stretch else -stretch,
            scaleY = -squash,
        )
    } else {
        DragMorphTarget(
            scaleX = -squash,
            scaleY = if (delta.y >= 0f) stretch else -stretch,
        )
    }
}

/**
 * Converts finger velocity (px/s) into the morph's unitless velocity. The scale target is
 * linear in drag distance, so the same slope maps px/s onto scale units.
 */
internal fun dragMorphVelocity(
    velocity: Offset,
    target: DragMorphTarget,
    maxDragPx: Float,
    maxStretch: Float = DragMorphMaxStretch,
    squashFactor: Float = DragMorphSquash,
): Offset {
    if (maxDragPx <= 0f) return Offset.Zero
    val perPx = maxStretch / maxDragPx
    val x = (velocity.x * perPx).coerceIn(-DragMorphMaxVelocity, DragMorphMaxVelocity)
    val y = (velocity.y * perPx).coerceIn(-DragMorphMaxVelocity, DragMorphMaxVelocity)
    val horizontal = abs(target.scaleX) > abs(target.scaleY)
    return if (horizontal) {
        Offset(x, -x * squashFactor)
    } else {
        Offset(-y * squashFactor, y)
    }
}

/** Stretch grows from the edge opposite the drag, like pulling soft material. */
internal fun dragMorphOrigin(target: DragMorphTarget): TransformOrigin {
    if (target.scaleX == 0f && target.scaleY == 0f) return TransformOrigin.Center
    val horizontal = abs(target.scaleX) > abs(target.scaleY)
    return if (horizontal) {
        TransformOrigin(if (target.scaleX >= 0f) 0f else 1f, 0.5f)
    } else {
        TransformOrigin(0.5f, if (target.scaleY >= 0f) 0f else 1f)
    }
}

/**
 * `clickable` without Material's ripple. The visible feedback is [pressFeedback], so
 * paper never shows a spreading ink circle underneath the quiet scale/wash.
 */
fun Modifier.quietClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * One shared "touch physics" modifier for clickable rows, chips, buttons and cards.
 *
 * The default is the quiet layer: scale to 0.98, nudge down half a dp, a faint ink wash
 * and a short [Motion.PressQuiet] fade. It is for rows, chips, switches and copy
 * actions; no spring and no deformation. Large actions pass `pressedScale = 0.97f` and
 * add their own pressed rim/fill at the composable. The press never fires on a scroll:
 * once the finger travels past the view's touch slop it is cancelled, so swiping a list
 * does not depress every row it crosses.
 *
 * `dragMorph = true` opts into the 2026 "liquid material" feedback for floating chrome:
 * the capsule stretches along the drag axis, squashes on the cross axis, follows the
 * finger with damping, and springs back with the release velocity instead of a fixed
 * duration curve. A faint white highlight fades as the drag grows. It is deliberately
 * limited to chrome that is not inside a scrollable list; rows keep the quiet press.
 *
 * The caller still owns `clickable`/`combinedClickable`; this only observes pointer
 * events and adds the visual transform, so clicks are never swallowed. When the system
 * "remove animations" setting is on, the transform is skipped entirely.
 */
fun Modifier.pressFeedback(
    enabled: Boolean = true,
    shape: Shape? = null,
    pressedScale: Float = 0.98f,
    pressedTranslationY: Float = 0.5f,
    dragMorph: Boolean = false,
    chromeScale: Float = 0.96f,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    if (!enabled || reduceMotion) return@composed this

    val slop = LocalViewConfiguration.current.touchSlop

    if (!dragMorph) {
        var pressed by remember { mutableFloatStateOf(0f) }
        val fraction by animateFloatAsState(
            targetValue = pressed,
            animationSpec = Motion.PressQuiet,
            label = "press",
        )
        val colors = MaterialTheme.appColors
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
            .drawWithContent {
                drawContent()
                if (shape != null && fraction > 0.001f) {
                    // A wash instead of a spring: the surface darkens slightly on paper
                    // and brightens slightly in dark mode, so a pressed row reads even
                    // when its own background is transparent.
                    val overlay = if (colors.dark) {
                        Color.White.copy(alpha = 0.055f * fraction)
                    } else {
                        colors.ink.copy(alpha = 0.045f * fraction)
                    }
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val path = Path().apply { addOutline(outline) }
                    clipPath(path) { drawRect(overlay) }
                }
            }
    }

    // ---- liquid drag morph -------------------------------------------------
    var pressed by remember { mutableStateOf(false) }
    // Press-in is a fast tween (<100ms); release keeps the shared springy settle.
    val pressFraction by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) tween(90, easing = Motion.EaseOut) else Motion.Release,
        label = "press-morph",
    )
    val morph = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val nudge = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val velocityTracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxDragPx = with(density) { DragMorphMaxDrag.toPx() }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }

    this
        .pointerInput(maxDragPx, slop) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    pressed = true
                    origin = TransformOrigin.Center
                    velocityTracker.resetTracking()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var target = DragMorphTarget(0f, 0f)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            break
                        }
                        val delta = change.position - down.position
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging && delta.getDistance() > slop) dragging = true
                        if (dragging) {
                            // The floating pills sit inside the navigation drawer's
                            // horizontal drag area. Once this becomes a real drag (not a
                            // tap), claim the change so the drawer does not slide open
                            // underneath the deformation. Taps and the inner clickable
                            // have already seen the event; nothing else is swallowed.
                            change.consume()
                            target = dragMorphTarget(delta, maxDragPx)
                            // AwaitPointerEventScope is a restricted suspension scope, so
                            // Animatable's suspending snapTo runs on the composition scope.
                            scope.launch { morph.snapTo(Offset(target.scaleX, target.scaleY)) }
                            scope.launch { nudge.snapTo(delta * DragMorphNudge) }
                            origin = dragMorphOrigin(target)
                        }
                    }
                    pressed = false
                    if (dragging) {
                        val velocity = velocityTracker.calculateVelocity()
                        val v = Offset(velocity.x, velocity.y)
                        val morphVelocity = dragMorphVelocity(v, target, maxDragPx)
                        val nudgeVelocity = Offset(
                            (v.x * DragMorphNudge)
                                .coerceIn(-DragMorphMaxNudgeVelocity, DragMorphMaxNudgeVelocity),
                            (v.y * DragMorphNudge)
                                .coerceIn(-DragMorphMaxNudgeVelocity, DragMorphMaxNudgeVelocity),
                        )
                        // stop() drains any snapTo still queued from the drag, so the
                        // release spring cannot be cancelled by a stale drag frame.
                        scope.launch {
                            morph.stop()
                            nudge.stop()
                            launch {
                                morph.animateTo(
                                    Offset.Zero,
                                    DragMorphSpring,
                                    initialVelocity = morphVelocity,
                                )
                            }
                            launch {
                                nudge.animateTo(
                                    Offset.Zero,
                                    DragMorphSpring,
                                    initialVelocity = nudgeVelocity,
                                )
                            }
                        }
                    } else {
                        origin = TransformOrigin.Center
                        scope.launch {
                            morph.snapTo(Offset.Zero)
                            nudge.snapTo(Offset.Zero)
                        }
                    }
                }
            } finally {
                // A cancelled gesture must not leave the surface stretched.
                pressed = false
            }
        }
        .graphicsLayer {
            val base = 1f + (chromeScale - 1f) * pressFraction
            scaleX = base * (1f + morph.value.x)
            scaleY = base * (1f + morph.value.y)
            translationX = nudge.value.x
            translationY = nudge.value.y + pressedTranslationY * pressFraction
            transformOrigin = origin
        }
        .drawWithContent {
            drawContent()
            if (shape != null) {
                val morphAmount = (abs(morph.value.x) + abs(morph.value.y)) /
                    (DragMorphMaxStretch * (1f + DragMorphSquash))
                val fade = 1f - 0.7f * morphAmount.coerceIn(0f, 1f)
                val alpha = 0.07f * pressFraction * fade
                if (alpha > 0.001f) {
                    // Clip the highlight only: the shadow and stretched surface are allowed
                    // to paint outside the original bounds.
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val path = Path().apply { addOutline(outline) }
                    clipPath(path) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
                                startY = 0f,
                                endY = size.height,
                            ),
                        )
                    }
                }
            }
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
