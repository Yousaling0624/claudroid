package app.claudroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Visual language borrowed from Claude's own product surfaces: warm ivory paper,
 * one terracotta accent, hairline rules instead of drop shadows, and a serif voice
 * for headings. Deliberately low-contrast chrome so the conversation is the loudest
 * thing on screen.
 */
private val Terracotta = Color(0xFFD97757)
private val TerracottaDeep = Color(0xFFBD5D3F)
private val Ivory = Color(0xFFF5F2EA)
private val IvoryDeep = Color(0xFFEDE8DC)
private val Ink = Color(0xFF1F1E1D)

private val Light = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFFFFFCF7),
    primaryContainer = Color(0xFFF6E3DA),
    onPrimaryContainer = Color(0xFF5A2A18),
    secondary = Color(0xFF5E5B54),
    onSecondary = Color(0xFFFFFCF7),
    secondaryContainer = IvoryDeep,
    onSecondaryContainer = Ink,
    background = Ivory,
    onBackground = Ink,
    surface = Color(0xFFFBF9F4),
    onSurface = Ink,
    surfaceVariant = IvoryDeep,
    onSurfaceVariant = Color(0xFF6B665F),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF8F5EE),
    surfaceContainer = Color(0xFFF1EDE3),
    surfaceContainerHigh = Color(0xFFEBE6DA),
    surfaceContainerHighest = Color(0xFFE3DDCF),
    outline = Color(0xFFD8D1C2),
    outlineVariant = Color(0xFFE7E1D4),
    error = Color(0xFFAF3B2A),
    onError = Color(0xFFFFF8F4),
)

private val Dark = darkColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFF2A1209),
    primaryContainer = Color(0xFF4A2416),
    onPrimaryContainer = Color(0xFFF8DDD2),
    secondary = Color(0xFFC9C4B8),
    onSecondary = Color(0xFF22211F),
    secondaryContainer = Color(0xFF3A3936),
    onSecondaryContainer = Color(0xFFF0EEE6),
    background = Color(0xFF262624),
    onBackground = Color(0xFFF0EEE6),
    surface = Color(0xFF2C2C2A),
    onSurface = Color(0xFFF0EEE6),
    surfaceVariant = Color(0xFF3A3936),
    onSurfaceVariant = Color(0xFFA5A199),
    surfaceContainerLowest = Color(0xFF1F1F1D),
    surfaceContainerLow = Color(0xFF292927),
    surfaceContainer = Color(0xFF30302E),
    surfaceContainerHigh = Color(0xFF383836),
    surfaceContainerHighest = Color(0xFF424240),
    outline = Color(0xFF4A4A46),
    outlineVariant = Color(0xFF3A3A37),
    error = Color(0xFFE9A08F),
    onError = Color(0xFF2A0D06),
)

/** Serif for display text: the closest system stand-in for Claude's Copernicus. */
val DisplayFamily = FontFamily.Serif
val MonoFamily = FontFamily.Monospace

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFamily, fontSize = 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily, fontSize = 25.sp, lineHeight = 31.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily, fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)

@Composable
fun ClaudroidTheme(themeMode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
