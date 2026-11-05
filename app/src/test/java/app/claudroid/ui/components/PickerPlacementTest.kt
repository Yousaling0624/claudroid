package app.claudroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry for the anchored settings picker. Pure pixels, so these cases pin down the
 * edge behaviour (flip, cap, clamp) without a device or Compose runtime.
 */
class PickerPlacementTest {

    private fun place(
        anchor: PickerRect,
        contentHeight: Float,
        viewportWidth: Float = 360f,
        viewportHeight: Float = 800f,
        gap: Float = 6f,
        margin: Float = 12f,
        maxWidth: Float = 420f,
        maxHeightFraction: Float = 0.6f,
    ) = placePicker(
        anchor = anchor,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        contentHeight = contentHeight,
        gap = gap,
        margin = margin,
        maxWidth = maxWidth,
        maxHeightFraction = maxHeightFraction,
    )

    @Test
    fun `opens below and matches the row width when there is room`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = 200f, width = 280f, height = 56f),
            contentHeight = 180f,
        )

        assertFalse(placement.expandsUp)
        assertEquals(40f, placement.left, 0.01f)
        assertEquals(280f, placement.width, 0.01f)
        assertEquals(262f, placement.top, 0.01f) // 200 + 56 + 6
        assertEquals(180f, placement.height, 0.01f)
    }

    @Test
    fun `flips above when the content cannot fit below`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = 500f, width = 280f, height = 56f),
            contentHeight = 260f,
        )

        assertTrue(placement.expandsUp)
        assertEquals(234f, placement.top, 0.01f) // 500 - 6 - 260
        assertEquals(260f, placement.height, 0.01f)
    }

    @Test
    fun `caps a long list at the viewport fraction`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = 100f, width = 280f, height = 56f),
            contentHeight = 1000f,
        )

        assertFalse(placement.expandsUp)
        assertEquals(480f, placement.height, 0.01f) // 60% of 800
        assertEquals(162f, placement.top, 0.01f)
        assertTrue(placement.top + placement.height <= 800f - 12f)
    }

    @Test
    fun `picks the roomier side when neither side fits`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = 140f, width = 280f, height = 56f),
            contentHeight = 200f,
            viewportHeight = 300f,
        )

        assertTrue(placement.expandsUp) // 122px above vs 86px below
        assertEquals(122f, placement.height, 0.01f)
        assertEquals(12f, placement.top, 0.01f)
    }

    @Test
    fun `clamps a row above the viewport to the top margin`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = -100f, width = 280f, height = 56f),
            contentHeight = 100f,
        )

        assertEquals(12f, placement.top, 0.01f)
        assertEquals(100f, placement.height, 0.01f)
    }

    @Test
    fun `clamps a row below the viewport to the bottom margin`() {
        val placement = place(
            anchor = PickerRect(left = 40f, top = 850f, width = 280f, height = 56f),
            contentHeight = 100f,
        )

        assertTrue(placement.expandsUp)
        assertEquals(688f, placement.top, 0.01f) // 800 - 12 - 100
        assertEquals(788f, placement.top + placement.height, 0.01f)
    }

    @Test
    fun `caps width on a tablet and centres it inside the row`() {
        val placement = place(
            anchor = PickerRect(left = 300f, top = 200f, width = 900f, height = 56f),
            contentHeight = 180f,
            viewportWidth = 1200f,
        )

        assertEquals(420f, placement.width, 0.01f)
        assertEquals(540f, placement.left, 0.01f) // 300 + (900 - 420) / 2
    }

    @Test
    fun `keeps a wide row's panel inside a narrow viewport`() {
        val placement = place(
            anchor = PickerRect(left = 0f, top = 200f, width = 900f, height = 56f),
            contentHeight = 180f,
            viewportWidth = 360f,
        )

        assertEquals(336f, placement.width, 0.01f) // 360 - 2 * 12
        assertEquals(12f, placement.left, 0.01f)
        assertTrue(placement.left + placement.width <= 360f - 12f)
    }
}
