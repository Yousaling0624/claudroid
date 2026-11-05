package app.claudroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry for the picker's bubble-split opening. The curves are pure pixels, so these
 * cases pin the start bubble, the landing rect, the delayed corners and the metaball
 * bridge without a device or a Compose runtime.
 */
class PickerMorphTest {

    private fun assertClose(expected: Float, actual: Float, delta: Float = 0.01f) {
        assertEquals(expected, actual, delta)
    }

    @Test
    fun `platter starts as a round bubble at the row centre`() {
        val anchor = PickerRect(left = 40f, top = 200f, width = 280f, height = 56f)
        val card = PickerRect(left = 40f, top = 300f, width = 280f, height = 180f)

        val frame = platterMorph(anchor, card, progress = 0f, cardRadius = 33f, corners = 0f)

        val side = 0.41f * 56f
        assertClose(side, frame.width)
        assertClose(side, frame.height)
        assertClose(anchor.centerX, frame.centerX)
        assertClose(anchor.centerY, frame.centerY)
        assertClose(side / 2f, frame.cornerRadius)
    }

    @Test
    fun `platter lands exactly on the card`() {
        val anchor = PickerRect(left = 40f, top = 200f, width = 280f, height = 56f)
        val card = PickerRect(left = 40f, top = 300f, width = 280f, height = 180f)

        val frame = platterMorph(anchor, card, progress = 1f, cardRadius = 33f, corners = 1f)

        assertClose(card.left, frame.left)
        assertClose(card.top, frame.top)
        assertClose(card.width, frame.width)
        assertClose(card.height, frame.height)
        assertClose(33f, frame.cornerRadius)
    }

    @Test
    fun `corners stay a capsule until the delayed spring squares them off`() {
        val anchor = PickerRect(left = 40f, top = 200f, width = 280f, height = 56f)
        val card = PickerRect(left = 40f, top = 300f, width = 280f, height = 180f)

        val capsule = platterMorph(anchor, card, progress = 0.5f, cardRadius = 33f, corners = 0f)
        val squared = platterMorph(anchor, card, progress = 0.5f, cardRadius = 33f, corners = 1f)

        assertClose(minOf(capsule.width, capsule.height) / 2f, capsule.cornerRadius)
        assertClose(33f, squared.cornerRadius)
        assertTrue(capsule.cornerRadius > squared.cornerRadius)
    }

    @Test
    fun `droplet starts as a row-height bubble at the row centre`() {
        val anchor = PickerRect(left = 40f, top = 200f, width = 280f, height = 56f)
        val card = PickerRect(left = 40f, top = 300f, width = 280f, height = 180f)

        val droplet = dropletMorph(anchor, card, progress = 0f, blend = 28f, rimDepth = 8f)

        // A full-width settings row would read as a bar in landscape; the bubble is
        // capped to twice the row height and stays centred on the row.
        assertClose(112f, droplet.width)
        assertClose(anchor.height, droplet.height)
        assertClose(anchor.centerX, droplet.centerX)
        assertClose(anchor.centerY, droplet.centerY)
    }

    @Test
    fun `droplet ends at a quarter of the row bubble inside the card`() {
        val anchor = PickerRect(left = 40f, top = 100f, width = 300f, height = 60f)
        val card = PickerRect(left = 0f, top = 300f, width = 600f, height = 400f)

        val droplet = dropletMorph(anchor, card, progress = 1f, blend = 28f, rimDepth = 8f)

        assertClose(120f * 0.25f, droplet.width)
        assertClose(anchor.height * 0.25f, droplet.height)
        assertTrue(droplet.centerX in card.left..card.right)
        assertTrue(droplet.centerY in card.top..card.bottom)
        assertTrue(droplet.left >= card.left && droplet.right <= card.right)
        assertTrue(droplet.top >= card.top && droplet.bottom <= card.bottom)
    }

    @Test
    fun `droplet centre is clamped when the anchor is far from the card`() {
        val anchor = PickerRect(left = 1000f, top = 1000f, width = 80f, height = 40f)
        val card = PickerRect(left = 100f, top = 100f, width = 200f, height = 200f)

        val droplet = dropletMorph(anchor, card, progress = 1f, blend = 28f, rimDepth = 8f)

        assertTrue(droplet.centerX in card.left..card.right)
        assertTrue(droplet.centerY in card.top..card.bottom)
    }

    @Test
    fun `no bridge when the shapes are too far apart`() {
        val platter = PickerRect(left = 100f, top = 100f, width = 200f, height = 100f)
        val droplet = PickerRect(left = 100f, top = 260f, width = 200f, height = 60f)

        assertNull(gooBridge(platter, 30f, droplet, blend = 28f))
    }

    @Test
    fun `bridge fills the gap between two close shapes`() {
        val platter = PickerRect(left = 100f, top = 100f, width = 200f, height = 100f)
        val droplet = PickerRect(left = 100f, top = 210f, width = 200f, height = 60f)

        val bridge = gooBridge(platter, 30f, droplet, blend = 28f)
        assertNotNull(bridge)
        val polygons = bridge!!
        assertTrue(polygons.isNotEmpty())
        assertTrue(polygons.all { it.size >= 3 })
        // The fan starts at the droplet centre and reaches through the 10px gap, so the
        // metaball neck actually paints a pixel neither rounded rect covers.
        assertClose(droplet.centerX, polygons.first().first().x)
        assertClose(droplet.centerY, polygons.first().first().y)
        assertTrue(polygons.any { contains(it, x = 200f, y = 205f) })
    }

    @Test
    fun `no bridge once the droplet is inside the platter`() {
        val platter = PickerRect(left = 0f, top = 0f, width = 400f, height = 300f)
        val droplet = PickerRect(left = 150f, top = 100f, width = 100f, height = 60f)

        assertNull(gooBridge(platter, 40f, droplet, blend = 28f))
    }

    @Test
    fun `no bridge while the platter is inside the droplet`() {
        val platter = PickerRect(left = 180f, top = 100f, width = 40f, height = 40f)
        val droplet = PickerRect(left = 0f, top = 0f, width = 400f, height = 200f)

        assertNull(gooBridge(platter, 20f, droplet, blend = 28f))
    }

    @Test
    fun `bridge needs a positive blend`() {
        val platter = PickerRect(left = 100f, top = 100f, width = 200f, height = 100f)
        val droplet = PickerRect(left = 100f, top = 210f, width = 200f, height = 60f)

        assertNull(gooBridge(platter, 30f, droplet, blend = 0f))
    }

    @Test
    fun `smooth min pinches the midpoint of the field`() {
        assertClose(-2f, smoothMin(5f, 5f, blend = 28f))
        assertClose(5f, smoothMin(5f, 5f, blend = 0f))
        assertClose(5f, smoothMin(5f, 20f, blend = 0f))
    }

    @Test
    fun `rounded rect distance is negative inside and zero on the edge`() {
        val rect = PickerRect(left = 0f, top = 0f, width = 200f, height = 100f)

        assertClose(-50f, roundedRectDistance(rect, radius = 10f, x = 100f, y = 50f))
        assertClose(0f, roundedRectDistance(rect, radius = 10f, x = 200f, y = 50f))
        assertClose(0f, roundedRectDistance(rect, radius = 10f, x = 100f, y = 100f))
    }

    /** Ray-casting point-in-polygon; proves the fan actually covers the neck gap. */
    private fun contains(polygon: List<MorphPoint>, x: Float, y: Float): Boolean {
        var inside = false
        var previous = polygon.lastIndex
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[previous]
            if ((a.y > y) != (b.y > y) &&
                x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            previous = index
        }
        return inside
    }
}
