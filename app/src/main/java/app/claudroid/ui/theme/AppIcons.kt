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
 * Hand-drawn line icons on a 24dp grid. Strokes stay at 1.7dp with round caps so the
 * whole chrome reads as one hairline language alongside the brand starburst, instead
 * of the heavier filled Material icon set.
 */
object AppIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(
        width: Float = 1.7f,
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

    val Menu: ImageVector by lazy {
        icon("Menu") {
            stroke {
                moveTo(4.5f, 6.8f); lineTo(19.5f, 6.8f)
                moveTo(4.5f, 12f); lineTo(19.5f, 12f)
                moveTo(4.5f, 17.2f); lineTo(19.5f, 17.2f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            stroke {
                moveTo(12f, 5.5f); lineTo(12f, 18.5f)
                moveTo(5.5f, 12f); lineTo(18.5f, 12f)
            }
        }
    }

    /** Send arrow, drawn slimmer than a filled Material arrow. */
    val ArrowUp: ImageVector by lazy {
        icon("ArrowUp") {
            stroke(width = 1.9f) {
                moveTo(12f, 19.5f); lineTo(12f, 5.2f)
                moveTo(5.4f, 11.8f); lineTo(12f, 5.2f); lineTo(18.6f, 11.8f)
            }
        }
    }

    /** Stop square: rounded enough to belong to the family, still read as "halt". */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            stroke(width = 2.6f) {
                moveTo(7.8f, 7.8f); lineTo(16.2f, 7.8f); lineTo(16.2f, 16.2f); lineTo(7.8f, 16.2f)
                close()
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            solid {
                circle(5f, 12f, 1.55f)
                circle(12f, 12f, 1.55f)
                circle(19f, 12f, 1.55f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            stroke {
                moveTo(6.9f, 9.7f); lineTo(12f, 14.8f); lineTo(17.1f, 9.7f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            stroke {
                moveTo(9.7f, 6.8f); lineTo(14.9f, 12f); lineTo(9.7f, 17.2f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            stroke {
                circle(10.6f, 10.6f, 5.9f)
                moveTo(15.1f, 15.1f); lineTo(19.8f, 19.8f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                moveTo(6.4f, 6.4f); lineTo(17.6f, 17.6f)
                moveTo(17.6f, 6.4f); lineTo(6.4f, 17.6f)
            }
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            stroke {
                moveTo(19f, 12f); lineTo(5.6f, 12f)
                moveTo(11f, 6.4f); lineTo(5.4f, 12f); lineTo(11f, 17.6f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroke {
                moveTo(9f, 8.6f); lineTo(9f, 4.6f); lineTo(19.4f, 4.6f); lineTo(19.4f, 15f); lineTo(15f, 15f)
                moveTo(4.6f, 9f); lineTo(15f, 9f); lineTo(15f, 19.4f); lineTo(4.6f, 19.4f)
                close()
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            stroke(width = 1.8f) {
                moveTo(5f, 12.6f); lineTo(9.8f, 17.4f); lineTo(19.2f, 6.6f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                moveTo(3.6f, 7.6f)
                lineTo(9.1f, 7.6f)
                lineTo(11f, 9.5f)
                lineTo(20.4f, 9.5f)
                lineTo(20.4f, 18.2f)
                lineTo(3.6f, 18.2f)
                close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke {
                moveTo(4.5f, 7f); lineTo(19.5f, 7f)
                moveTo(4.5f, 12f); lineTo(19.5f, 12f)
                moveTo(4.5f, 17f); lineTo(19.5f, 17f)
            }
            solid {
                circle(9f, 7f, 2f)
                circle(15f, 12f, 2f)
                circle(9f, 17f, 2f)
            }
        }
    }

    /** Workspace alias so older screens can keep their name. */
    val Workspace: ImageVector get() = Folder

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke {
                moveTo(4.6f, 7.2f); lineTo(19.4f, 7.2f)
                moveTo(9.2f, 7.2f); lineTo(9.2f, 4.6f); lineTo(14.8f, 4.6f); lineTo(14.8f, 7.2f)
                moveTo(6.6f, 7.2f); lineTo(7.5f, 19.4f); lineTo(16.5f, 19.4f); lineTo(17.4f, 7.2f)
                moveTo(10.3f, 10.8f); lineTo(10.3f, 16.1f)
                moveTo(13.7f, 10.8f); lineTo(13.7f, 16.1f)
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
                moveTo(19.6f, 4.6f); lineTo(19.6f, 8.6f); lineTo(15.6f, 8.6f)
            }
        }
    }

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

    /** Source/code file: angle brackets around a slash. */
    val Code: ImageVector by lazy {
        icon("Code") {
            stroke {
                moveTo(8.6f, 7.2f); lineTo(4.4f, 12f); lineTo(8.6f, 16.8f)
                moveTo(15.4f, 7.2f); lineTo(19.6f, 12f); lineTo(15.4f, 16.8f)
                moveTo(13.3f, 5.4f); lineTo(10.7f, 18.6f)
            }
        }
    }

    /** Plain text: page with folded corner and ruled lines. */
    val TextFile: ImageVector by lazy {
        icon("TextFile") {
            stroke {
                moveTo(6f, 3.6f); lineTo(14.2f, 3.6f); lineTo(18.4f, 7.8f); lineTo(18.4f, 20.4f)
                lineTo(6f, 20.4f); close()
                moveTo(14f, 3.8f); lineTo(14f, 8f); lineTo(18.2f, 8f)
                moveTo(9f, 12.2f); lineTo(15.2f, 12.2f)
                moveTo(9f, 15.6f); lineTo(15.2f, 15.6f)
            }
        }
    }

    /** Image file: frame with a sun and a mountain ridge. */
    val Image: ImageVector by lazy {
        icon("Image") {
            stroke {
                moveTo(4.2f, 5.2f); lineTo(19.8f, 5.2f); lineTo(19.8f, 18.8f); lineTo(4.2f, 18.8f); close()
                circle(9.1f, 9.9f, 1.7f)
                moveTo(4.8f, 17.2f); lineTo(9.9f, 12.5f); lineTo(13.6f, 15.7f)
                moveTo(13.6f, 15.7f); lineTo(15.7f, 13.8f); lineTo(19.1f, 17.1f)
            }
        }
    }

    /** Generic/binary file. */
    val File: ImageVector by lazy {
        icon("File") {
            stroke {
                moveTo(6f, 3.6f); lineTo(14.2f, 3.6f); lineTo(18.4f, 7.8f); lineTo(18.4f, 20.4f)
                lineTo(6f, 20.4f); close()
                moveTo(14f, 3.8f); lineTo(14f, 8f); lineTo(18.2f, 8f)
            }
        }
    }

    // ---------------------------------------------------------------- tool icons
    // Same 24dp grid / 1.7dp hairline / round caps as the chrome icons above. These
    // are restrained line-object silhouettes (Claude's quiet sketch language), not
    // filled Material glyphs, so a transcript full of tool rows stays calm.

    /** Terminal: rounded frame, a `>_` prompt. Maps Bash / shell tools. */
    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            stroke {
                moveTo(3.8f, 5.2f); lineTo(20.2f, 5.2f); lineTo(20.2f, 18.8f); lineTo(3.8f, 18.8f); close()
                moveTo(7.4f, 9.6f); lineTo(10f, 12f); lineTo(7.4f, 14.4f)
                moveTo(11.6f, 14.6f); lineTo(16.4f, 14.6f)
            }
        }
    }

    /** Read: open page with an eye; distinct from a plain file. */
    val ReadFile: ImageVector by lazy {
        icon("ReadFile") {
            stroke {
                moveTo(6f, 3.6f); lineTo(14.2f, 3.6f); lineTo(18.4f, 7.8f); lineTo(18.4f, 20.4f)
                lineTo(6f, 20.4f); close()
                moveTo(14f, 3.8f); lineTo(14f, 8f); lineTo(18.2f, 8f)
                moveTo(9f, 13.4f)
                curveTo(10f, 11.9f, 14f, 11.9f, 15f, 13.4f)
                curveTo(14f, 14.9f, 10f, 14.9f, 9f, 13.4f); close()
                circle(12f, 13.4f, 0.9f)
            }
        }
    }

    /** Write / Edit: page with a pencil. */
    val WriteFile: ImageVector by lazy {
        icon("WriteFile") {
            stroke {
                moveTo(6f, 3.6f); lineTo(12.6f, 3.6f); lineTo(16.8f, 7.8f); lineTo(16.8f, 10.4f)
                moveTo(6f, 3.6f); lineTo(6f, 20.4f); lineTo(13.4f, 20.4f)
                moveTo(12.4f, 3.8f); lineTo(12.4f, 8f); lineTo(16.6f, 8f)
                moveTo(14.6f, 13.2f); lineTo(20.2f, 18.8f); lineTo(17.6f, 21.4f); lineTo(12f, 15.8f)
                close()
                moveTo(13.4f, 14.4f); lineTo(16.8f, 17.8f)
            }
        }
    }

    /** Globe: circle with meridian + latitude arcs. Maps WebFetch / WebSearch. */
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

    /** Agents / Task: two small heads over a shared shoulder line. */
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

    /** Checklist / Todo: three rules with a leading check. */
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

    /** Plug / MCP: cable with a two-prong head. */
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

    /** Wrench / generic tool: open jaw + handle. */
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

    /** Sparkle: four-point star with small satellites; maps "think"/generate tools. */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            stroke {
                moveTo(12f, 3.4f)
                curveTo(12.6f, 8.2f, 15.8f, 11.4f, 20.6f, 12f)
                curveTo(15.8f, 12.6f, 12.6f, 15.8f, 12f, 20.6f)
                curveTo(11.4f, 15.8f, 8.2f, 12.6f, 3.4f, 12f)
                curveTo(8.2f, 11.4f, 11.4f, 8.2f, 12f, 3.4f); close()
                moveTo(18.6f, 4.2f); lineTo(18.6f, 6.6f)
                moveTo(17.4f, 5.4f); lineTo(19.8f, 5.4f)
            }
        }
    }

    /** Generic tool: a quiet hex nut, used for any unrecognised name. */
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
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            stroke {
                moveTo(4.2f, 7f); lineTo(19.8f, 7f)
                moveTo(4.2f, 12f); lineTo(19.8f, 12f)
                moveTo(4.2f, 17f); lineTo(19.8f, 17f)
            }
            solid {
                circle(9f, 7f, 2.1f)
                circle(15f, 12f, 2.1f)
                circle(8f, 17f, 2.1f)
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
            solid { circle(12f, 8f, 0.95f) }
        }
    }
}
