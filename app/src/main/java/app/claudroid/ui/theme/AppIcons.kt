package app.claudroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive-adjacent icon set on a 24dp grid.
 *
 * The old set was a 1.7dp hand-drawn hairline; it read thin and slightly skeuomorphic
 * next to the new rounded chrome. These use Material Symbols' rounded language:
 * 2dp strokes, round caps/joins, 2.4dp corner radii, fewer interior details, and
 * filled forms only where a solid shape is the clearest reading (stop, sparkle,
 * status dots). Geometry is simplified rather than literal — the terminal is a soft
 * rounded frame, the folder a single rounded silhouette.
 */
object AppIcons {

    private const val STROKE = 2f
    private const val CORNER = 2.4f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(
        width: Float = STROKE,
        block: PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        close()
    }

    /** Rounded rectangle with a single soft corner radius, the base of the new set. */
    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float = CORNER) {
        val rad = minOf(radius, (r - l) / 2f, (b - t) / 2f)
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        quadTo(r, t, r, t + rad)
        lineTo(r, b - rad)
        quadTo(r, b, r - rad, b)
        lineTo(l + rad, b)
        quadTo(l, b, l, b - rad)
        lineTo(l, t + rad)
        quadTo(l, t, l + rad, t)
        close()
    }

    /** Rounded page silhouette; the old folded corner read as skeuomorphic at 18dp. */
    private fun PathBuilder.documentOutline() {
        roundRect(6.2f, 3.6f, 17.8f, 20.4f, CORNER)
    }

    val Menu: ImageVector by lazy {
        icon("Menu") {
            stroke {
                moveTo(4.6f, 6.8f); lineTo(19.4f, 6.8f)
                moveTo(4.6f, 12f); lineTo(19.4f, 12f)
                moveTo(4.6f, 17.2f); lineTo(19.4f, 17.2f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            stroke {
                moveTo(12f, 5.2f); lineTo(12f, 18.8f)
                moveTo(5.2f, 12f); lineTo(18.8f, 12f)
            }
        }
    }

    /** Send arrow; slightly heavier than the chrome so it holds inside the filled circle. */
    val ArrowUp: ImageVector by lazy {
        icon("ArrowUp") {
            stroke(width = 2.2f) {
                moveTo(12f, 19.2f); lineTo(12f, 5.4f)
                moveTo(5.8f, 11.4f); lineTo(12f, 5.2f); lineTo(18.2f, 11.4f)
            }
        }
    }

    /** Filled rounded square: the clearest "halt" at 16dp. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            solid { roundRect(7.2f, 7.2f, 16.8f, 16.8f, 2.6f) }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            solid {
                circle(5f, 12f, 1.7f)
                circle(12f, 12f, 1.7f)
                circle(19f, 12f, 1.7f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            stroke {
                moveTo(6.8f, 9.6f); lineTo(12f, 14.8f); lineTo(17.2f, 9.6f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            stroke {
                moveTo(9.6f, 6.8f); lineTo(14.8f, 12f); lineTo(9.6f, 17.2f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            stroke {
                circle(10.6f, 10.6f, 6f)
                moveTo(15.1f, 15.1f); lineTo(19.4f, 19.4f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                moveTo(6.6f, 6.6f); lineTo(17.4f, 17.4f)
                moveTo(17.4f, 6.6f); lineTo(6.6f, 17.4f)
            }
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            stroke {
                moveTo(19.2f, 12f); lineTo(5.2f, 12f)
                moveTo(11.2f, 5.8f); lineTo(5f, 12f); lineTo(11.2f, 18.2f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroke {
                roundRect(9f, 4.8f, 19.2f, 15f, 2.4f)
                roundRect(4.8f, 9f, 15f, 19.2f, 2.4f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            stroke(width = 2.2f) {
                moveTo(5.2f, 12.4f); lineTo(9.8f, 17f); lineTo(18.8f, 7.2f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                // One soft silhouette with a small tab; no sharp fold or hanging lip.
                moveTo(3.8f, 17.4f)
                lineTo(3.8f, 7.0f)
                quadTo(3.8f, 5.2f, 5.6f, 5.2f)
                lineTo(11.4f, 5.2f)
                quadTo(12.2f, 5.2f, 12.8f, 5.8f)
                lineTo(14.0f, 7.2f)
                lineTo(18.2f, 7.2f)
                quadTo(20.2f, 7.2f, 20.2f, 9.2f)
                lineTo(20.2f, 17.4f)
                quadTo(20.2f, 19.2f, 18.4f, 19.2f)
                lineTo(5.6f, 19.2f)
                quadTo(3.8f, 19.2f, 3.8f, 17.4f)
                close()
            }
        }
    }

    /** Workspace alias so older screens can keep their name. */
    val Workspace: ImageVector get() = Folder

    /**
     * Rounded gear: one soft outer ring, eight short teeth and a centre hole. The old
     * slider-with-dots glyph was indistinguishable from the "advanced" settings icon.
     */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke {
                circle(12f, 12f, 6.1f)
                circle(12f, 12f, 2.3f)
            }
            stroke(width = 2.4f) {
                moveTo(18.6f, 12f); lineTo(20.9f, 12f)
                moveTo(16.6f, 16.6f); lineTo(18.3f, 18.3f)
                moveTo(12f, 18.6f); lineTo(12f, 20.9f)
                moveTo(7.4f, 16.6f); lineTo(5.7f, 18.3f)
                moveTo(5.4f, 12f); lineTo(3.1f, 12f)
                moveTo(7.4f, 7.4f); lineTo(5.7f, 5.7f)
                moveTo(12f, 5.4f); lineTo(12f, 3.1f)
                moveTo(16.6f, 7.4f); lineTo(18.3f, 5.7f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke {
                moveTo(4.8f, 7.2f); lineTo(19.2f, 7.2f)
                moveTo(9.2f, 7.2f); lineTo(9.2f, 5.2f)
                quadTo(9.2f, 4.6f, 9.8f, 4.6f)
                lineTo(14.2f, 4.6f)
                quadTo(14.8f, 4.6f, 14.8f, 5.2f)
                lineTo(14.8f, 7.2f)
                moveTo(6.6f, 7.2f); lineTo(7.4f, 18.2f)
                quadTo(7.5f, 19.2f, 8.5f, 19.2f)
                lineTo(15.5f, 19.2f)
                quadTo(16.5f, 19.2f, 16.6f, 18.2f)
                lineTo(17.4f, 7.2f)
                moveTo(10.2f, 10.8f); lineTo(10.2f, 16f)
                moveTo(13.8f, 10.8f); lineTo(13.8f, 16f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            stroke {
                moveTo(19.4f, 8.6f)
                curveTo(18.1f, 5.7f, 15.3f, 3.8f, 12f, 3.8f)
                curveTo(7.5f, 3.8f, 3.8f, 7.5f, 3.8f, 12f)
                curveTo(3.8f, 16.5f, 7.5f, 20.2f, 12f, 20.2f)
                curveTo(15.9f, 20.2f, 19.2f, 17.6f, 20.1f, 14f)
                moveTo(19.8f, 4.8f); lineTo(19.8f, 8.8f); lineTo(15.8f, 8.8f)
            }
        }
    }

    /** Regenerate reuses the circular arrow; kept as a semantic alias. */
    val Regenerate: ImageVector get() = Refresh

    val Share: ImageVector by lazy {
        icon("Share") {
            stroke {
                moveTo(8.2f, 13f); lineTo(15.8f, 17.2f)
                moveTo(8.2f, 11f); lineTo(15.8f, 6.8f)
            }
            solid {
                circle(18f, 5.6f, 2.3f)
                circle(6f, 12f, 2.3f)
                circle(18f, 18.4f, 2.3f)
            }
        }
    }

    val Attachment: ImageVector by lazy {
        icon("Attachment") {
            stroke {
                moveTo(19.3f, 10.3f)
                lineTo(11.2f, 18.4f)
                curveTo(9.5f, 20.1f, 6.8f, 20.1f, 5.1f, 18.4f)
                curveTo(3.4f, 16.7f, 3.4f, 14f, 5.1f, 12.3f)
                lineTo(13.2f, 4.2f)
                curveTo(14.4f, 3.1f, 16.3f, 3.1f, 17.5f, 4.2f)
                curveTo(18.6f, 5.4f, 18.6f, 7.3f, 17.5f, 8.4f)
                lineTo(9.6f, 16.3f)
                curveTo(9f, 16.9f, 8f, 16.9f, 7.4f, 16.3f)
                curveTo(6.8f, 15.7f, 6.8f, 14.7f, 7.4f, 14.1f)
                lineTo(14.7f, 6.8f)
            }
        }
    }

    /** Edit: pencil with a rounded shoulder, used by the user-message action. */
    val Edit: ImageVector by lazy {
        icon("Edit") {
            stroke {
                moveTo(4.8f, 19.2f)
                lineTo(4.8f, 15.4f)
                lineTo(15f, 5.2f)
                quadTo(15.8f, 4.4f, 16.6f, 5.2f)
                lineTo(18.8f, 7.4f)
                quadTo(19.6f, 8.2f, 18.8f, 9f)
                lineTo(8.6f, 19.2f)
                close()
                moveTo(13.6f, 6.6f); lineTo(17.4f, 10.4f)
            }
        }
    }

    val Code: ImageVector by lazy {
        icon("Code") {
            stroke {
                moveTo(8.8f, 7.4f); lineTo(4.6f, 12f); lineTo(8.8f, 16.6f)
                moveTo(15.2f, 7.4f); lineTo(19.4f, 12f); lineTo(15.2f, 16.6f)
                moveTo(13.2f, 5.4f); lineTo(10.8f, 18.6f)
            }
        }
    }

    val TextFile: ImageVector by lazy {
        icon("TextFile") {
            stroke {
                documentOutline()
                moveTo(9.2f, 9.4f); lineTo(14.8f, 9.4f)
                moveTo(9.2f, 13.0f); lineTo(14.8f, 13.0f)
                moveTo(9.2f, 16.6f); lineTo(12.8f, 16.6f)
            }
        }
    }

    val Image: ImageVector by lazy {
        icon("Image") {
            stroke {
                roundRect(4.2f, 5.2f, 19.8f, 18.8f, 2.8f)
                circle(8.8f, 9.6f, 1.5f)
                // A single mountain line instead of the old two-peak zigzag.
                moveTo(5.4f, 16.6f)
                lineTo(9.6f, 12.4f)
                lineTo(13.0f, 15.8f)
                lineTo(15.4f, 13.4f)
                lineTo(18.6f, 16.6f)
            }
        }
    }

    val File: ImageVector by lazy {
        icon("File") {
            stroke { documentOutline() }
        }
    }

    // ---------------------------------------------------------------- tool icons
    // Same 24dp grid and 2dp rounded language as the chrome. Simplified silhouettes
    // keep a transcript full of tool rows calm while still reading as objects.

    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            stroke {
                roundRect(3.8f, 5.2f, 20.2f, 18.8f, 2.8f)
                moveTo(7.4f, 9.8f); lineTo(10.0f, 12f); lineTo(7.4f, 14.2f)
                moveTo(11.6f, 14.4f); lineTo(16.0f, 14.4f)
            }
        }
    }

    val ReadFile: ImageVector by lazy {
        icon("ReadFile") {
            stroke {
                documentOutline()
                moveTo(8.8f, 13.4f)
                quadTo(10.2f, 11.4f, 12f, 11.4f)
                quadTo(13.8f, 11.4f, 15.2f, 13.4f)
                quadTo(13.8f, 15.4f, 12f, 15.4f)
                quadTo(10.2f, 15.4f, 8.8f, 13.4f)
                close()
            }
            solid { circle(12f, 13.4f, 1f) }
        }
    }

    val WriteFile: ImageVector by lazy {
        icon("WriteFile") {
            stroke {
                documentOutline()
                // Pencil overlaps the page's lower-right corner; the tip stays outside
                // so the glyph still reads at 17dp in the workspace list.
                moveTo(11.8f, 20.8f)
                lineTo(12.6f, 17.4f)
                lineTo(17.6f, 12.4f)
                quadTo(18.4f, 11.6f, 19.2f, 12.4f)
                lineTo(20.0f, 13.2f)
                quadTo(20.8f, 14.0f, 20.0f, 14.8f)
                lineTo(15.0f, 19.8f)
                close()
                moveTo(12.6f, 17.4f); lineTo(15.0f, 19.8f)
            }
        }
    }

    val Globe: ImageVector by lazy {
        icon("Globe") {
            stroke {
                circle(12f, 12f, 8.2f)
                moveTo(3.8f, 12f); lineTo(20.2f, 12f)
                moveTo(12f, 3.8f)
                curveTo(8.6f, 7.4f, 8.6f, 16.6f, 12f, 20.2f)
                moveTo(12f, 3.8f)
                curveTo(15.4f, 7.4f, 15.4f, 16.6f, 12f, 20.2f)
            }
        }
    }

    val Agents: ImageVector by lazy {
        icon("Agents") {
            stroke {
                circle(9f, 8.4f, 2.6f)
                circle(16.4f, 9f, 2.1f)
                moveTo(4.2f, 18.6f)
                curveTo(4.2f, 14.7f, 6.4f, 13.2f, 9f, 13.2f)
                curveTo(11.6f, 13.2f, 13.6f, 14.7f, 13.6f, 17.6f)
                moveTo(14.2f, 14.2f)
                curveTo(17.2f, 13.4f, 20f, 14.9f, 20f, 18.6f)
            }
        }
    }

    val Checklist: ImageVector by lazy {
        icon("Checklist") {
            stroke {
                moveTo(3.8f, 6.6f); lineTo(5.2f, 8f); lineTo(8f, 5.2f)
                moveTo(10.6f, 6.8f); lineTo(20.2f, 6.8f)
                moveTo(3.8f, 12f); lineTo(20.2f, 12f)
                moveTo(3.8f, 17.2f); lineTo(20.2f, 17.2f)
            }
        }
    }

    val Plug: ImageVector by lazy {
        icon("Plug") {
            stroke {
                moveTo(9f, 3.6f); lineTo(9f, 7.4f)
                moveTo(15f, 3.6f); lineTo(15f, 7.4f)
                moveTo(6.6f, 7.4f); lineTo(17.4f, 7.4f); lineTo(17.4f, 11.2f)
                curveTo(17.4f, 14.2f, 15.4f, 15.8f, 12f, 15.8f)
                curveTo(8.6f, 15.8f, 6.6f, 14.2f, 6.6f, 11.2f); close()
                moveTo(12f, 15.8f); lineTo(12f, 20.4f)
            }
        }
    }

    val Wrench: ImageVector by lazy {
        icon("Wrench") {
            stroke {
                moveTo(20f, 5.2f)
                curveTo(17.2f, 3.6f, 13.8f, 4.8f, 12.8f, 7.6f)
                curveTo(12.2f, 9.2f, 12.6f, 10.8f, 13.6f, 12f)
                lineTo(5.2f, 20.4f)
                curveTo(4.4f, 21.2f, 3f, 21.2f, 2.2f, 20.4f)
                lineTo(11.6f, 10.8f)
                moveTo(20f, 5.2f)
                curveTo(20.6f, 6.8f, 20.2f, 8.6f, 18.9f, 9.7f)
                curveTo(17.6f, 10.8f, 15.8f, 10.8f, 14.6f, 9.8f)
            }
        }
    }

    /** Filled four-point star: the one expressive solid in the tool family. */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            solid {
                moveTo(12f, 3.2f)
                curveTo(12.7f, 8f, 16f, 11.3f, 20.8f, 12f)
                curveTo(16f, 12.7f, 12.7f, 16f, 12f, 20.8f)
                curveTo(11.3f, 16f, 8f, 12.7f, 3.2f, 12f)
                curveTo(8f, 11.3f, 11.3f, 8f, 12f, 3.2f)
                close()
                moveTo(18.8f, 3.8f)
                lineTo(19.3f, 4.9f); lineTo(20.4f, 5.4f); lineTo(19.3f, 5.9f)
                lineTo(18.8f, 7f); lineTo(18.3f, 5.9f); lineTo(17.2f, 5.4f)
                lineTo(18.3f, 4.9f)
                close()
            }
        }
    }

    val Tool: ImageVector by lazy {
        icon("Tool") {
            stroke {
                moveTo(12f, 3.6f); lineTo(19.2f, 7.8f); lineTo(19.2f, 16.2f)
                lineTo(12f, 20.4f); lineTo(4.8f, 16.2f); lineTo(4.8f, 7.8f); close()
                circle(12f, 12f, 2.8f)
            }
        }
    }

    // ---------------------------------------------------------------- settings IA
    /** Vertical faders; visually distinct from the gear used for the settings root. */
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            stroke {
                moveTo(6.6f, 4.6f); lineTo(6.6f, 19.4f)
                moveTo(12f, 4.6f); lineTo(12f, 19.4f)
                moveTo(17.4f, 4.6f); lineTo(17.4f, 19.4f)
            }
            solid {
                circle(6.6f, 15.2f, 2.2f)
                circle(12f, 8.8f, 2.2f)
                circle(17.4f, 13.2f, 2.2f)
            }
        }
    }

    val Layers: ImageVector by lazy {
        icon("Layers") {
            stroke {
                moveTo(12f, 3.6f); lineTo(20.2f, 8f); lineTo(12f, 12.4f); lineTo(3.8f, 8f); close()
                moveTo(3.8f, 12f); lineTo(12f, 16.4f); lineTo(20.2f, 12f)
                moveTo(3.8f, 16f); lineTo(12f, 20.4f); lineTo(20.2f, 16f)
            }
        }
    }

    val Shield: ImageVector by lazy {
        icon("Shield") {
            stroke {
                moveTo(12f, 3.4f); lineTo(19.4f, 6.2f); lineTo(19.4f, 12.2f)
                curveTo(19.4f, 16.6f, 16.2f, 19.6f, 12f, 20.8f)
                curveTo(7.8f, 19.6f, 4.6f, 16.6f, 4.6f, 12.2f)
                lineTo(4.6f, 6.2f); close()
                moveTo(8.8f, 12f); lineTo(11.2f, 14.4f); lineTo(15.6f, 9.6f)
            }
        }
    }

    val Flask: ImageVector by lazy {
        icon("Flask") {
            stroke {
                moveTo(9.4f, 3.6f); lineTo(14.6f, 3.6f)
                moveTo(10.6f, 3.6f); lineTo(10.6f, 9.2f); lineTo(5.6f, 18.2f)
                lineTo(18.4f, 18.2f); lineTo(13.4f, 9.2f); lineTo(13.4f, 3.6f)
                moveTo(7.9f, 14.2f); lineTo(16.1f, 14.2f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        icon("Palette") {
            stroke {
                moveTo(12f, 3.6f)
                curveTo(7.3f, 3.6f, 3.8f, 7.1f, 3.8f, 11.6f)
                curveTo(3.8f, 16f, 7.4f, 19.6f, 11.8f, 19.6f)
                curveTo(13.8f, 19.6f, 14.8f, 18.4f, 14.8f, 17.2f)
                curveTo(14.8f, 16f, 14f, 15.2f, 14.8f, 14.2f)
                curveTo(15.6f, 13.2f, 17.4f, 13f, 18.4f, 11.6f)
                curveTo(20f, 9.4f, 20.2f, 3.6f, 12f, 3.6f); close()
                circle(7.4f, 11.2f, 1f)
                circle(9.6f, 7.6f, 1f)
                circle(14f, 7.6f, 1f)
            }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            stroke {
                circle(12f, 12f, 8.4f)
                moveTo(12f, 11f); lineTo(12f, 16.4f)
            }
            solid { circle(12f, 8f, 1.05f) }
        }
    }
}
