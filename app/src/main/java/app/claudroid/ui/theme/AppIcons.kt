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
 * Hand-drawn line icons on a 24dp grid with 1.6dp round strokes. Material's filled
 * set is heavier and reads as stock Android; these keep the same hairline language
 * as the sun mark and pick up the current tint.
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

    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
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
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17.5f); lineTo(20f, 17.5f)
            }
        }
    }

    /** Three sliders read lighter than a filled gear and suit the settings affordance. */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(20f, 17f)
            }
            solid {
                circle(9f, 7f, 2.1f)
                circle(15f, 12f, 2.1f)
                circle(9f, 17f, 2.1f)
            }
        }
    }

    val Workspace: ImageVector by lazy {
        icon("Workspace") {
            stroke {
                moveTo(3.5f, 7.5f)
                lineTo(9f, 7.5f)
                lineTo(11f, 9.5f)
                lineTo(20.5f, 9.5f)
                lineTo(20.5f, 18.5f)
                lineTo(3.5f, 18.5f)
                close()
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            solid {
                circle(12f, 5f, 1.6f)
                circle(12f, 12f, 1.6f)
                circle(12f, 19f, 1.6f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            stroke {
                circle(10.5f, 10.5f, 6f)
                moveTo(15.2f, 15.2f); lineTo(20f, 20f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            stroke {
                moveTo(19f, 12f); lineTo(5.5f, 12f)
                moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
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

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            stroke {
                moveTo(6.5f, 9.5f); lineTo(12f, 15f); lineTo(17.5f, 9.5f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            stroke {
                moveTo(19.5f, 8.5f)
                curveTo(18.2f, 5.6f, 15.4f, 3.7f, 12f, 3.7f)
                curveTo(7.4f, 3.7f, 3.7f, 7.4f, 3.7f, 12f)
                curveTo(3.7f, 16.6f, 7.4f, 20.3f, 12f, 20.3f)
                curveTo(16f, 20.3f, 19.3f, 17.7f, 20.2f, 14f)
                moveTo(19.5f, 4.5f); lineTo(19.5f, 8.5f); lineTo(15.5f, 8.5f)
            }
        }
    }

    val Share: ImageVector by lazy {
        icon("Share") {
            stroke {
                moveTo(8f, 13.1f); lineTo(16f, 17.4f)
                moveTo(8f, 10.9f); lineTo(16f, 6.6f)
            }
            solid {
                circle(18f, 5.5f, 2.4f)
                circle(6f, 12f, 2.4f)
                circle(18f, 18.5f, 2.4f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke {
                moveTo(4.5f, 7f); lineTo(19.5f, 7f)
                moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
                moveTo(6.5f, 7f); lineTo(7.4f, 19.5f); lineTo(16.6f, 19.5f); lineTo(17.5f, 7f)
                moveTo(10.2f, 10.8f); lineTo(10.2f, 16.2f)
                moveTo(13.8f, 10.8f); lineTo(13.8f, 16.2f)
            }
        }
    }
}
