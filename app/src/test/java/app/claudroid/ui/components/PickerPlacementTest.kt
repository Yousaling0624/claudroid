package app.claudroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry and feel for the anchored settings picker. Pure pixels, so these cases pin
 * down the cover-button placement (width, flip, clamp) and the pull-to-dismiss curves
 * without a device or Compose runtime.
 */
class PickerPlacementTest {

    private fun place(
        anchor: PickerRect,
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float = 360f,
        viewportHeight: Float = 800f,
        margin: Float = 12f,
        maxWidth: Float = 280f,
        maxHeightFraction: Float = 0.6f,
    ) = placePicker(
        anchor = anchor,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        maxWidth = maxWidth,
        margin = margin,
        maxHeightFraction = maxHeightFraction,
    )

    @Test
    fun `right-aligns to the value button and covers it when opening down`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 200f, width = 140f, height = 40f),
            contentWidth = 200f,
            contentHeight = 180f,
        )

        assertFalse(placement.expandsUp)
        assertEquals(60f, placement.left, 0.01f) // 260 - 200
        assertEquals(200f, placement.width, 0.01f)
        assertEquals(200f, placement.top, 0.01f) // covers the button, no gap
        assertEquals(180f, placement.height, 0.01f)
    }

    @Test
    fun `never gets narrower than the value button`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 200f, width = 140f, height = 40f),
            contentWidth = 100f,
            contentHeight = 180f,
        )

        assertEquals(140f, placement.width, 0.01f)
        assertEquals(120f, placement.left, 0.01f) // right edge stays on the button
    }

    @Test
    fun `caps width and slides inside the margin when the button sits at the edge`() {
        val placement = place(
            anchor = PickerRect(left = 0f, top = 200f, width = 80f, height = 40f),
            contentWidth = 300f,
            contentHeight = 180f,
        )

        assertEquals(280f, placement.width, 0.01f)
        assertEquals(12f, placement.left, 0.01f) // 80 - 280 clamped to the margin
        assertTrue(placement.left + placement.width <= 360f - 12f)
    }

    @Test
    fun `respects a row-width cap tighter than the picker max`() {
        val placement = place(
            anchor = PickerRect(left = 160f, top = 200f, width = 140f, height = 40f),
            contentWidth = 300f,
            contentHeight = 180f,
            maxWidth = 250f,
        )

        assertEquals(250f, placement.width, 0.01f)
        assertEquals(50f, placement.left, 0.01f)
    }

    @Test
    fun `flips above and covers the button's bottom edge when there is no room below`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 600f, width = 140f, height = 40f),
            contentWidth = 200f,
            contentHeight = 260f,
        )

        assertTrue(placement.expandsUp)
        assertEquals(380f, placement.top, 0.01f) // 640 - 260
        assertEquals(640f, placement.top + placement.height, 0.01f)
        assertEquals(260f, placement.height, 0.01f)
    }

    @Test
    fun `caps a long list at the viewport fraction`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 100f, width = 140f, height = 40f),
            contentWidth = 200f,
            contentHeight = 1000f,
        )

        assertFalse(placement.expandsUp)
        assertEquals(480f, placement.height, 0.01f) // 60% of 800
        assertEquals(100f, placement.top, 0.01f)
        assertTrue(placement.top + placement.height <= 800f - 12f)
    }

    @Test
    fun `picks the roomier side when neither side fits`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 140f, width = 140f, height = 40f),
            contentWidth = 200f,
            contentHeight = 200f,
            viewportHeight = 300f,
        )

        assertTrue(placement.expandsUp) // 168px above vs 148px below
        assertEquals(168f, placement.height, 0.01f)
        assertEquals(12f, placement.top, 0.01f)
    }

    @Test
    fun `clamps the panel to the top margin for an anchor near the top edge`() {
        val placement = place(
            anchor = PickerRect(left = 120f, top = 0f, width = 140f, height = 40f),
            contentWidth = 200f,
            contentHeight = 180f,
        )

        assertEquals(12f, placement.top, 0.01f)
    }

    @Test
    fun `fitPickerWidth clamps content between button width and the width cap`() {
        assertEquals(
            140f,
            fitPickerWidth(
                anchorWidth = 140f,
                contentWidth = 60f,
                maxWidth = 280f,
                viewportWidth = 360f,
                margin = 12f,
            ),
            0.01f,
        )
        assertEquals(
            280f,
            fitPickerWidth(
                anchorWidth = 140f,
                contentWidth = 500f,
                maxWidth = 280f,
                viewportWidth = 360f,
                margin = 12f,
            ),
            0.01f,
        )
        // A very wide value button cannot outgrow the viewport.
        assertEquals(
            336f,
            fitPickerWidth(
                anchorWidth = 900f,
                contentWidth = 900f,
                maxWidth = 420f,
                viewportWidth = 360f,
                margin = 12f,
            ),
            0.01f,
        )
    }

    // ------------------------------------------------------------- pull-to-dismiss

    @Test
    fun `rubber band follows the finger then approaches the limit`() {
        assertEquals(0f, pickerDragOffset(raw = 0f, limit = 220f), 0.01f)
        assertEquals(0f, pickerDragOffset(raw = -40f, limit = 220f), 0.01f)
        assertEquals(0f, pickerDragOffset(raw = 40f, limit = 0f), 0.01f)
        // 220 * 100 / (220 + 100)
        assertEquals(68.75f, pickerDragOffset(raw = 100f, limit = 220f), 0.01f)
        // Half the asymptote at the limit.
        assertEquals(110f, pickerDragOffset(raw = 220f, limit = 220f), 0.01f)
    }

    @Test
    fun `deformation eases in and is bounded`() {
        val rest = pickerDragDeformation(offset = 0f, limit = 220f)
        assertEquals(0f, rest.stretch, 0.0001f)
        assertEquals(0f, rest.squash, 0.0001f)
        assertEquals(0f, rest.cornerGrowth, 0.0001f)

        // At the limit the pull is fully eased: 6% taller, 3.5% narrower, corners +50%.
        val full = pickerDragDeformation(offset = 220f, limit = 220f)
        assertEquals(0.06f, full.stretch, 0.0001f)
        assertEquals(0.035f, full.squash, 0.0001f)
        assertEquals(0.50f, full.cornerGrowth, 0.0001f)

        // A quarter of the limit still gives half the sqrt easing, not a quarter.
        val quarter = pickerDragDeformation(offset = 55f, limit = 220f)
        assertEquals(0.03f, quarter.stretch, 0.0001f)
        assertEquals(0.0175f, quarter.squash, 0.0001f)
        assertEquals(0.25f, quarter.cornerGrowth, 0.0001f)
    }

    @Test
    fun `dismiss requires enough distance or a fast downward flick`() {
        assertFalse(
            pickerShouldDismiss(
                offset = 71f,
                velocityY = 200f,
                distanceThreshold = 72f,
                velocityThreshold = 900f,
            )
        )
        assertTrue(
            pickerShouldDismiss(
                offset = 72f,
                velocityY = 0f,
                distanceThreshold = 72f,
                velocityThreshold = 900f,
            )
        )
        assertTrue(
            pickerShouldDismiss(
                offset = 10f,
                velocityY = 900f,
                distanceThreshold = 72f,
                velocityThreshold = 900f,
            )
        )
        // Upward flicks never dismiss on their own.
        assertFalse(
            pickerShouldDismiss(
                offset = 10f,
                velocityY = -2000f,
                distanceThreshold = 72f,
                velocityThreshold = 900f,
            )
        )
    }
}
