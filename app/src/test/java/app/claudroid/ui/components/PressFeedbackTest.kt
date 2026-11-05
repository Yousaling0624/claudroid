package app.claudroid.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drag physics is deliberately pure so the stretch/squash direction and the
 * release velocity can be verified without a device.
 */
class PressFeedbackTest {

    private val maxDrag = 100f

    @Test
    fun `horizontal drag stretches x and squashes y`() {
        val target = dragMorphTarget(Offset(100f, 0f), maxDrag)
        assertEquals(0.15f, target.scaleX, 0.0001f)
        assertEquals(-0.0975f, target.scaleY, 0.0001f)
    }

    @Test
    fun `leftward drag mirrors the stretch`() {
        val target = dragMorphTarget(Offset(-50f, 0f), maxDrag)
        assertEquals(-0.075f, target.scaleX, 0.0001f)
        assertEquals(-0.04875f, target.scaleY, 0.0001f)
    }

    @Test
    fun `vertical drag stretches y and squashes x`() {
        val target = dragMorphTarget(Offset(0f, 100f), maxDrag)
        assertEquals(-0.0975f, target.scaleX, 0.0001f)
        assertEquals(0.15f, target.scaleY, 0.0001f)
    }

    @Test
    fun `upward drag mirrors the stretch`() {
        val target = dragMorphTarget(Offset(0f, -25f), maxDrag)
        assertEquals(-0.024375f, target.scaleX, 0.0001f)
        assertEquals(-0.0375f, target.scaleY, 0.0001f)
    }

    @Test
    fun `drag beyond the reference distance clamps at peak stretch`() {
        val target = dragMorphTarget(Offset(400f, 0f), maxDrag)
        assertEquals(0.15f, target.scaleX, 0.0001f)
        assertEquals(-0.0975f, target.scaleY, 0.0001f)
    }

    @Test
    fun `dominant axis wins on a diagonal drag`() {
        val horizontal = dragMorphTarget(Offset(60f, 20f), maxDrag)
        assertEquals(0.09f, horizontal.scaleX, 0.0001f)

        val vertical = dragMorphTarget(Offset(20f, 60f), maxDrag)
        assertEquals(0.09f, vertical.scaleY, 0.0001f)
    }

    @Test
    fun `zero drag has no deformation`() {
        val target = dragMorphTarget(Offset.Zero, maxDrag)
        assertEquals(0f, target.scaleX, 0f)
        assertEquals(0f, target.scaleY, 0f)
        assertEquals(TransformOrigin.Center, dragMorphOrigin(target))
    }

    @Test
    fun `stretch grows from the edge opposite the finger`() {
        assertEquals(
            TransformOrigin(0f, 0.5f),
            dragMorphOrigin(dragMorphTarget(Offset(60f, 0f), maxDrag)),
        )
        assertEquals(
            TransformOrigin(1f, 0.5f),
            dragMorphOrigin(dragMorphTarget(Offset(-60f, 0f), maxDrag)),
        )
        assertEquals(
            TransformOrigin(0.5f, 0f),
            dragMorphOrigin(dragMorphTarget(Offset(0f, 60f), maxDrag)),
        )
        assertEquals(
            TransformOrigin(0.5f, 1f),
            dragMorphOrigin(dragMorphTarget(Offset(0f, -60f), maxDrag)),
        )
    }

    @Test
    fun `release velocity follows the drag axis and is damped`() {
        val target = dragMorphTarget(Offset(50f, 0f), maxDrag)
        val velocity = dragMorphVelocity(Offset(1_000f, 500f), target, maxDrag)
        assertEquals(1.5f, velocity.x, 0.0001f)
        assertEquals(-0.975f, velocity.y, 0.0001f)
    }

    @Test
    fun `release velocity is clamped before it reaches the spring`() {
        val target = dragMorphTarget(Offset(50f, 0f), maxDrag)
        val velocity = dragMorphVelocity(Offset(100_000f, 0f), target, maxDrag)
        assertEquals(6f, velocity.x, 0.0001f)
        assertEquals(-3.9f, velocity.y, 0.0001f)
    }

    @Test
    fun `a zero reference distance degrades safely`() {
        val target = dragMorphTarget(Offset(50f, 0f), maxDragPx = 0f)
        assertEquals(DragMorphTarget(0f, 0f), target)
        assertEquals(Offset.Zero, dragMorphVelocity(Offset(100f, 0f), target, maxDragPx = 0f))
    }
}
