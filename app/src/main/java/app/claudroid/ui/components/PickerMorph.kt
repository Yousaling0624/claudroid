package app.claudroid.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry for the settings picker's bubble-split morph, in overlay-local pixels.
 *
 * The panel is laid out once at its final rect. Opening draws it as a platter that
 * starts as a small round bubble at the tapped row, stretches and travels to the final
 * card while a second curve squares off its corners. A bubble the height of the row
 * splits away, shrinks and flows into the platter; a smooth-min bridge keeps the two
 * silhouettes joined whenever they are apart.
 *
 * The bridge is ported from chen08209/prism_ui (`lib/goo.dart`:
 * `gooPairOutline`, `_bridge`, `_smoothMin`, `_roundedDistance`), GPL-3.0.
 * Only the pure geometry is ported; no Dart runtime or Flutter types are involved.
 *
 * No Compose or Android types here: the curves and the edge cases are pinned down by
 * [PickerMorphTest] on the JVM.
 */

/** A point in overlay-local pixels. */
internal data class MorphPoint(val x: Float, val y: Float)

/** A rounded silhouette: the platter and the droplet share these bounds. */
internal interface MorphSilhouette {
    val left: Float
    val top: Float
    val width: Float
    val height: Float
    val cornerRadius: Float
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/** The visible platter at one instant. */
internal data class PlatterMorph(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
    override val cornerRadius: Float,
) : MorphSilhouette

/** The droplet that splits from the row and is later absorbed by the platter. */
internal data class DropletMorph(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
) : MorphSilhouette {
    override val cornerRadius: Float get() = min(width, height) / 2f
}

/** How much of the platter the anchor controls at the start of the morph. */
internal const val PICKER_BUBBLE_IN_CONTROL = 0.41f

/** The droplet's final size, relative to the bubble it split from. */
internal const val PICKER_DROPLET_SHARE = 0.25f

/**
 * Settings rows run the full page width, so a literal row-sized droplet would be a
 * horizontal bar in landscape. The droplet keeps the row's height and centre but its
 * start width is capped to this multiple of the height, which reads as a bubble
 * lifting out of the row rather than the whole row stretching.
 */
internal const val PICKER_DROPLET_MAX_ASPECT = 2f

/**
 * The platter silhouette at [progress].
 *
 * At zero it is a round bubble of `0.41 * anchor.shortestSide` centred in the row; at
 * one it is exactly [card]. Width and height are interpolated separately so the bubble
 * stretches into a platter instead of uniformly scaling a finished card. [corners] is
 * the delayed corner curve: zero keeps the capsule (height / 2), one reaches the card's
 * radius. All values may overshoot slightly with the opening spring.
 */
internal fun platterMorph(
    anchor: PickerRect,
    card: PickerRect,
    progress: Float,
    cardRadius: Float,
    corners: Float,
): PlatterMorph {
    val cardWidth = card.width.coerceAtLeast(1f)
    val scale = lerp(PICKER_BUBBLE_IN_CONTROL * anchor.shortestSide / cardWidth, 1f, progress)
    val width = (cardWidth * scale).coerceAtLeast(0f)
    val height = (lerp(cardWidth, card.height.coerceAtLeast(0f), progress) * scale).coerceAtLeast(0f)
    val centerX = lerp(anchor.centerX, card.centerX, progress)
    val centerY = lerp(anchor.centerY, card.centerY, progress)
    val capsule = min(width, height) / 2f
    val radius = lerp(capsule, cardRadius.coerceAtLeast(0f), corners.coerceIn(0f, 1f))
        .coerceIn(0f, capsule)
    return PlatterMorph(centerX - width / 2f, centerY - height / 2f, width, height, radius)
}

/**
 * The droplet silhouette at [progress].
 *
 * It starts as a row-height bubble (width capped by [PICKER_DROPLET_MAX_ASPECT]),
 * shrinks to [share] of it and travels toward the platter centre. The destination is the
 * point a quarter of the way from the row to the card, clamped into the card's inner
 * rect (so it always lands inside the panel). When that inner rect is too small — a wide
 * row on a narrow card — it falls back to the card centre.
 */
internal fun dropletMorph(
    anchor: PickerRect,
    card: PickerRect,
    progress: Float,
    blend: Float,
    rimDepth: Float,
    share: Float = PICKER_DROPLET_SHARE,
): DropletMorph {
    val extent = lerp(1f, share, progress)
    val startWidth = min(anchor.width, anchor.height * PICKER_DROPLET_MAX_ASPECT)
    val width = (startWidth * extent).coerceAtLeast(0f)
    val height = (anchor.height * extent).coerceAtLeast(0f)
    // The final droplet must clear the card's edge by its own half size plus the goo
    // blend; using the row's longest side for both axes over-insets narrow cards.
    val insetX = startWidth * share / 2f + blend + rimDepth
    val insetY = anchor.height * share / 2f + blend + rimDepth
    val innerWidth = card.width - insetX * 2f
    val innerHeight = card.height - insetY * 2f
    val sunkX = lerp(anchor.centerX, card.centerX, share)
    val sunkY = lerp(anchor.centerY, card.centerY, share)
    val targetX = if (innerWidth <= 0f) card.centerX else sunkX.coerceIn(card.left + insetX, card.right - insetX)
    val targetY = if (innerHeight <= 0f) card.centerY else sunkY.coerceIn(card.top + insetY, card.bottom - insetY)
    val centerX = lerp(anchor.centerX, targetX, progress)
    val centerY = lerp(anchor.centerY, targetY, progress)
    return DropletMorph(centerX - width / 2f, centerY - height / 2f, width, height)
}

/**
 * How much of [droplet] the [platter] has swallowed, from 0 (still separate) to 1
 * (fully covered).
 *
 * The droplet is painted above the glass so its fill stays readable on the warm page;
 * that also means it would otherwise sit on the finished list. The last points to
 * disappear are the capsule's four extremes: until all of them are inside the platter
 * the droplet is still visibly its own bubble. Once they are, the platter has swallowed
 * it and the drawing layer fades it out. [blend] is the depth over which that fade runs.
 */
internal fun dropletAbsorption(
    platter: PickerRect,
    platterRadius: Float,
    droplet: PickerRect,
    blend: Float,
): Float {
    if (blend <= 0f || droplet.width <= 0f || droplet.height <= 0f) return 1f
    val extremes = arrayOf(
        MorphPoint(droplet.left, droplet.centerY),
        MorphPoint(droplet.right, droplet.centerY),
        MorphPoint(droplet.centerX, droplet.top),
        MorphPoint(droplet.centerX, droplet.bottom),
    )
    var least = Float.MAX_VALUE
    for (point in extremes) {
        least = min(least, -roundedRectDistance(platter, platterRadius, point.x, point.y))
    }
    return (least / blend).coerceIn(0f, 1f)
}

/**
 * True once any of the platter's four extremes has left the droplet.
 *
 * While the platter is still entirely inside the droplet the whole droplet is painted;
 * subtracting the platter then would punch a hole through the bubble. As soon as the
 * platter pokes out, the droplet layer clips itself against the platter so the two
 * silhouettes read as one liquid instead of two overlapping outlines.
 */
internal fun platterPokesOut(platter: PickerRect, droplet: PickerRect): Boolean {
    if (droplet.width <= 0f || droplet.height <= 0f) return true
    val dropletRadius = droplet.shortestSide / 2f
    val extremes = arrayOf(
        MorphPoint(platter.left, platter.centerY),
        MorphPoint(platter.right, platter.centerY),
        MorphPoint(platter.centerX, platter.top),
        MorphPoint(platter.centerX, platter.bottom),
    )
    return extremes.any { roundedRectDistance(droplet, dropletRadius, it.x, it.y) > 0f }
}

/**
 * Signed distance from [a]'s rounded rect to the farthest corner of [b], minus [blend].
 * Negative while the blend still swells [a] toward [b]; non-negative once [b] lies deep
 * enough inside [a] that the two silhouettes read as one.
 */
internal fun gooDepth(
    aLeft: Float,
    aTop: Float,
    aWidth: Float,
    aHeight: Float,
    aRadius: Float,
    bLeft: Float,
    bTop: Float,
    bWidth: Float,
    bHeight: Float,
    blend: Float,
): Float {
    val a = PickerRect(aLeft, aTop, aWidth, aHeight)
    val outermost = maxOf(
        roundedRectDistance(a, aRadius, bLeft, bTop),
        roundedRectDistance(a, aRadius, bLeft + bWidth, bTop),
        roundedRectDistance(a, aRadius, bLeft, bTop + bHeight),
        roundedRectDistance(a, aRadius, bLeft + bWidth, bTop + bHeight),
    )
    return -outermost - blend
}

/** Standard rounded-box signed distance. Negative inside, positive outside. */
internal fun roundedRectDistance(rect: PickerRect, radius: Float, x: Float, y: Float): Float {
    val round = min(radius.coerceAtLeast(0f), rect.shortestSide / 2f)
    val qx = abs(x - rect.centerX) - rect.width / 2f + round
    val qy = abs(y - rect.centerY) - rect.height / 2f + round
    val outside = hypot(max(qx, 0f), max(qy, 0f))
    return outside + min(max(qx, qy), 0f) - round
}

/**
 * The fan of polygons that fills the goo bridge between [a] (the platter, with
 * [aRadius]) and [b] (the droplet), or null when the two already read as one shape.
 *
 * Ported from prism_ui `_bridge`: rays fan out of the droplet's centre and stop where
 * the smooth-min field of the two rounded rects crosses zero, or deep inside the
 * platter. The union of the two rects with this fan is the metaball silhouette; unlike a
 * straight neck it also bridges overlapping shapes, which is what makes the split read
 * as liquid instead of two cards sliding past each other.
 *
 * `turns`/`tolerance` are the reference's coarse setting; the fine one is 128/0.05.
 */
internal fun gooBridge(
    a: PickerRect,
    aRadius: Float,
    b: PickerRect,
    blend: Float,
    turns: Int = 64,
    tolerance: Float = 0.15f,
): List<List<MorphPoint>>? {
    if (blend <= 0f || b.width <= 0f || b.height <= 0f || turns < 4) return null
    if (gooDepth(a.left, a.top, a.width, a.height, aRadius, b.left, b.top, b.width, b.height, blend) >= 0f) {
        return null
    }
    val bRadius = b.shortestSide / 2f
    if (gooDepth(b.left, b.top, b.width, b.height, bRadius, a.left, a.top, a.width, a.height, blend) >= 0f) {
        return null
    }

    fun toA(point: MorphPoint): Float = roundedRectDistance(a, aRadius, point.x, point.y)
    fun toB(point: MorphPoint): Float = roundedRectDistance(b, bRadius, point.x, point.y)
    fun field(point: MorphPoint): Float = smoothMin(toA(point), toB(point), blend)

    val center = MorphPoint(b.centerX, b.centerY)
    val limit = b.longestSide / 2f + a.longestSide + blend

    fun reach(direction: MorphPoint): MorphPoint {
        var inside = 0f
        var distance = 0f
        while (distance < limit) {
            val point = center + direction * distance
            val platter = toA(point)
            if (platter < -blend) return point
            val depth = smoothMin(platter, toB(point), blend)
            if (depth >= 0f) {
                var outside = distance
                repeat(4) {
                    val middle = (inside + outside) / 2f
                    if (field(center + direction * middle) < 0f) {
                        inside = middle
                    } else {
                        outside = middle
                    }
                }
                break
            }
            inside = distance
            distance += max(0.5f, min(-depth, platter + blend))
        }
        return center + direction * inside
    }

    val ends = arrayOfNulls<MorphPoint>(turns)
    val depths = FloatArray(turns)

    fun reachOf(turn: Int): GooReach {
        val depth = depths[turn % turns]
        return when {
            depth < -blend -> GooReach.Deep
            depth < blend -> GooReach.Flowing
            else -> GooReach.Clear
        }
    }

    fun cast(turn: Int) {
        val angle = turn.toFloat() / turns * (2f * PI.toFloat())
        val end = reach(MorphPoint(cos(angle), sin(angle)))
        ends[turn] = end
        depths[turn] = toA(end)
    }

    fun skip(from: Int, to: Int) {
        for (turn in from + 1 until to) {
            if (ends[turn] == null) depths[turn] = depths[from]
        }
    }

    fun refine(from: Int, to: Int) {
        if (to - from < 2) return
        val alike = reachOf(from) == reachOf(to)
        if (alike && reachOf(from) != GooReach.Flowing) {
            skip(from, to)
            return
        }
        val middle = (from + to) / 2
        cast(middle)
        if (alike &&
            reachOf(middle) == reachOf(from) &&
            offChord(ends[from]!!, ends[to % turns]!!, ends[middle]!!) <= tolerance
        ) {
            skip(from, to)
            return
        }
        refine(from, middle)
        refine(middle, to)
    }

    for (turn in 0 until turns step 4) cast(turn)
    for (turn in 0 until turns step 4) refine(turn, turn + 4)

    val flows = BooleanArray(turns) { depths[it] < blend }
    if (!flows.any { it }) return null

    fun deep(turn: Int): Boolean = depths[turn] < -blend
    val first = flows.indexOfFirst { !it }.let { if (it >= 0) it else 0 }
    val polygons = ArrayList<List<MorphPoint>>()
    var fan = ArrayList<Int>()

    fun closeFan() {
        if (fan.size > 1) {
            val rim = fan.mapIndexedNotNull { index, turn ->
                val point = ends[turn]
                if (point != null &&
                    (index == 0 ||
                        index == fan.lastIndex ||
                        !deep(fan[index - 1]) ||
                        !deep(turn) ||
                        !deep(fan[index + 1]))
                ) {
                    point
                } else {
                    null
                }
            }
            if (rim.size > 1) {
                polygons += listOf(center) + douglasPeucker(rim, tolerance)
            }
        }
        fan = ArrayList()
    }

    for (index in 0..turns) {
        val turn = (first + index) % turns
        if (flows[turn]) fan.add(turn) else closeFan()
    }
    closeFan()
    return polygons.ifEmpty { null }
}

private enum class GooReach { Deep, Flowing, Clear }

private fun offChord(from: MorphPoint, to: MorphPoint, point: MorphPoint): Float {
    val chord = to - from
    val length = chord.length
    val offset = point - from
    return if (length == 0f) {
        offset.length
    } else {
        abs(offset.x * chord.y - offset.y * chord.x) / length
    }
}

private fun douglasPeucker(points: List<MorphPoint>, tolerance: Float): List<MorphPoint> {
    if (points.size < 3) return points
    val kept = BooleanArray(points.size)
    kept[0] = true
    kept[points.lastIndex] = true

    fun split(from: Int, to: Int) {
        var farthest = 0f
        var at = -1
        for (index in from + 1 until to) {
            val distance = offChord(points[from], points[to], points[index])
            if (distance > farthest) {
                farthest = distance
                at = index
            }
        }
        if (at >= 0 && farthest > tolerance) {
            kept[at] = true
            split(from, at)
            split(at, to)
        }
    }

    split(0, points.lastIndex)
    return points.filterIndexed { index, _ -> kept[index] }
}

private operator fun MorphPoint.plus(other: MorphPoint): MorphPoint =
    MorphPoint(x + other.x, y + other.y)

private operator fun MorphPoint.minus(other: MorphPoint): MorphPoint =
    MorphPoint(x - other.x, y - other.y)

private operator fun MorphPoint.times(scale: Float): MorphPoint =
    MorphPoint(x * scale, y * scale)

private val MorphPoint.length: Float get() = hypot(x, y)

/** Polynomial smooth minimum; the same field used by metaball shaders. */
internal fun smoothMin(a: Float, b: Float, blend: Float): Float {
    if (blend <= 0f) return min(a, b)
    val h = (0.5f + 0.5f * (b - a) / blend).coerceIn(0f, 1f)
    return lerp(b, a, h) - blend * h * (1f - h)
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
