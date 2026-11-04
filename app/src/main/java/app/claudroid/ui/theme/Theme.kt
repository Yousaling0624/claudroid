package app.claudroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Visual language borrowed from Claude's own product surfaces: warm ivory paper,
 * one terracotta accent, hairline rules instead of drop shadows, and a serif voice
 * for headings. The chrome stays quiet so the conversation is the loudest thing.
 *
 * The Material scheme only models part of that vocabulary. [AppColors] carries the
 * rest: a text-safe accent (the bright terracotta is decorative, not readable at
 * small sizes), a tertiary text color that still clears 4.5:1, and the paper tint
 * used by the root gradient.
 */
private val Terracotta = Color(0xFFD97757)
private val TerracottaDeep = Color(0xFFB8573A)
private val TerracottaText = Color(0xFFA64C2F)
private val Ink = Color(0xFF1F1E1D)

@Immutable
data class AppColors(
    /** Accent for small text, links and filled controls; readable on paper. */
    val accentInk: Color,
    /** Decorative brand terracotta for marks and dots. */
    val brand: Color,
    /** Tertiary text (timestamps, placeholders, disclaimers); >= 4.5:1. */
    val faintText: Color,
    /** Slightly lifted paper used at the top of the root gradient. */
    val paperTop: Color,
    /** Raised card fill. */
    val card: Color,
)

private val LightAppColors = AppColors(
    accentInk = TerracottaText,
    brand = Color(0xFFC4623F),
    faintText = Color(0xFF746E64),
    paperTop = Color(0xFFFAF7EF),
    card = Color(0xFFFBF9F4),
)

private val DarkAppColors = AppColors(
    accentInk = Color(0xFFE89577),
    brand = Terracotta,
    faintText = Color(0xFF918C83),
    paperTop = Color(0xFF2B2B29),
    card = Color(0xFF2C2C2A),
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Tail of the Material theme: colors the scheme has no slot for. */
val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

private val Light = lightColorScheme(
    primary = TerracottaDeep,
    onPrimary = Color(0xFFFFF9F4),
    primaryContainer = Color(0xFFF3DFD3),
    onPrimaryContainer = Color(0xFF4E2414),
    secondary = Color(0xFF5E5951),
    onSecondary = Color(0xFFFFF9F4),
    secondaryContainer = Color(0xFFEDE8DC),
    onSecondaryContainer = Ink,
    background = Color(0xFFF5F2EA),
    onBackground = Ink,
    surface = Color(0xFFFBF9F4),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E3D6),
    onSurfaceVariant = Color(0xFF5F5A52),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF8F5EE),
    surfaceContainer = Color(0xFFF1EDE3),
    surfaceContainerHigh = Color(0xFFEAE4D8),
    surfaceContainerHighest = Color(0xFFE2DCCD),
    outline = Color(0xFFC9C2B3),
    outlineVariant = Color(0xFFDCD5C7),
    error = Color(0xFFA33A28),
    onError = Color(0xFFFFF8F4),
    errorContainer = Color(0xFFF7DAD3),
    onErrorContainer = Color(0xFF4A1108),
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
    outline = Color(0xFF565650),
    outlineVariant = Color(0xFF414140),
    error = Color(0xFFE9A08F),
    onError = Color(0xFF2A0D06),
    errorContainer = Color(0xFF5C2116),
    onErrorContainer = Color(0xFFFFDAD1),
)

/** Serif for display text: the closest system stand-in for Claude's Copernicus. */
val DisplayFamily = FontFamily.Serif
val MonoFamily = FontFamily.Monospace

// includeFontPadding = false tightens the vertical rhythm; Android's default font
// padding makes serif headings sit visibly low inside their line box.
private fun base(
    size: Float,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily? = null,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val AppTypography = Typography(
    displaySmall = base(31f, 38f, family = DisplayFamily),
    headlineMedium = base(24f, 31f, family = DisplayFamily),
    headlineSmall = base(20f, 26f, family = DisplayFamily),
    titleLarge = base(18f, 24f, weight = FontWeight.Medium, letterSpacing = -0.1f),
    titleMedium = base(15f, 20f, weight = FontWeight.Medium),
    titleSmall = base(13.5f, 18f, weight = FontWeight.Medium),
    bodyLarge = base(15.5f, 24f),
    bodyMedium = base(14f, 21f),
    bodySmall = base(12.5f, 18f),
    labelLarge = base(13.5f, 18f, weight = FontWeight.Medium),
    labelMedium = base(12f, 16f, weight = FontWeight.Medium),
    labelSmall = base(11f, 15f, weight = FontWeight.Medium, letterSpacing = 0.3f),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

/** Page rhythm shared by every screen. */
object Spacing {
    val page = 20.dp
    val gap = 12.dp
    val section = 20.dp
}

@Composable
fun ClaudroidTheme(themeMode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) Dark else Light
    val appColors = if (dark) DarkAppColors else LightAppColors
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
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
