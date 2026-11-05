package app.claudroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The app's visual vocabulary: flat warm paper, one terracotta accent, hairline
 * borders instead of drop shadows, and a serif voice for the conversation.
 *
 * Material's scheme has no slot for paper tints, sand borders or a text-safe accent,
 * so those live in [AppColors]; everything else is mapped onto the M3 scheme so stock
 * components (menus, dialogs, switches) inherit the same palette.
 */
@Immutable
data class AppColors(
    /** Page background. Flat, no gradient. */
    val canvas: Color,
    /** Cards, composer, drawer surface. */
    val paper: Color,
    /** User bubbles, drawer selection, quiet fills. */
    val surface1: Color,
    /** Pressed states and disabled send button. */
    val surface2: Color,
    /** 1dp hairline borders. */
    val sand: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text. */
    val graphite: Color,
    /** Tertiary text: timestamps, captions. */
    val stone: Color,
    /** Disabled-only text; never use for content. */
    val bone: Color,
    /** Decorative brand terracotta: marks, dots, filled buttons. */
    val orange: Color,
    val orangePressed: Color,
    /** Thin warm fill for thinking pills and notices. */
    val orangeSoft: Color,
    /** Text-safe terracotta for links and small labels. */
    val accentInk: Color,
    val codeBg: Color,
    val codeFg: Color,
    val error: Color,
    val success: Color,
    val dark: Boolean,
)

private val LightAppColors = AppColors(
    canvas = Color(0xFFF8F4ED),
    paper = Color(0xFFFBF9F4),
    surface1 = Color(0xFFF0EAE0),
    surface2 = Color(0xFFE8E0D2),
    sand = Color(0xFFDDD2BD),
    ink = Color(0xFF2D2520),
    graphite = Color(0xFF5A4F44),
    stone = Color(0xFF74695E),
    bone = Color(0xFFB5AB9E),
    orange = Color(0xFFD97757),
    orangePressed = Color(0xFFBE6242),
    orangeSoft = Color(0xFFF2DDD0),
    accentInk = Color(0xFFA64C2F),
    codeBg = Color(0xFF1F1B16),
    codeFg = Color(0xFFE8E0D2),
    error = Color(0xFFA8412F),
    success = Color(0xFF5F8F53),
    dark = false,
)

private val DarkAppColors = AppColors(
    canvas = Color(0xFF1F1E1D),
    paper = Color(0xFF2C2C2A),
    surface1 = Color(0xFF2C2C2A),
    surface2 = Color(0xFF3A3936),
    sand = Color(0xFF45443F),
    ink = Color(0xFFF5F4EF),
    graphite = Color(0xFFC3C2B7),
    stone = Color(0xFF97958A),
    bone = Color(0xFF6A675F),
    orange = Color(0xFFD97757),
    orangePressed = Color(0xFFE08A6D),
    orangeSoft = Color(0xFF4A352A),
    accentInk = Color(0xFFE89577),
    codeBg = Color(0xFF14130F),
    codeFg = Color(0xFFE8E0D2),
    error = Color(0xFFE9A08F),
    success = Color(0xFF8FBF83),
    dark = true,
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Tail of the Material theme: colors the scheme has no slot for. */
val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

private val Light = lightColorScheme(
    primary = LightAppColors.orange,
    onPrimary = Color(0xFFFFFBF7),
    primaryContainer = LightAppColors.orangeSoft,
    onPrimaryContainer = Color(0xFF4E2414),
    secondary = LightAppColors.graphite,
    onSecondary = Color(0xFFFFFBF7),
    secondaryContainer = LightAppColors.surface1,
    onSecondaryContainer = LightAppColors.ink,
    tertiary = LightAppColors.accentInk,
    onTertiary = Color(0xFFFFFBF7),
    background = LightAppColors.canvas,
    onBackground = LightAppColors.ink,
    surface = LightAppColors.paper,
    onSurface = LightAppColors.ink,
    surfaceVariant = LightAppColors.surface1,
    onSurfaceVariant = LightAppColors.graphite,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = LightAppColors.paper,
    surfaceContainerLow = LightAppColors.paper,
    surfaceContainer = LightAppColors.surface1,
    surfaceContainerHigh = LightAppColors.surface2,
    surfaceContainerHighest = LightAppColors.surface2,
    outline = LightAppColors.sand,
    outlineVariant = LightAppColors.sand,
    error = LightAppColors.error,
    onError = Color(0xFFFFF8F4),
    errorContainer = Color(0xFFF6DED7),
    onErrorContainer = Color(0xFF47130A),
    inverseSurface = LightAppColors.ink,
    inverseOnSurface = LightAppColors.paper,
    inversePrimary = Color(0xFFE89577),
)

private val Dark = darkColorScheme(
    primary = DarkAppColors.orange,
    onPrimary = Color(0xFF2A1209),
    primaryContainer = DarkAppColors.orangeSoft,
    onPrimaryContainer = Color(0xFFF8DDD2),
    secondary = DarkAppColors.graphite,
    onSecondary = Color(0xFF22211F),
    secondaryContainer = DarkAppColors.surface2,
    onSecondaryContainer = DarkAppColors.ink,
    tertiary = DarkAppColors.accentInk,
    onTertiary = Color(0xFF2A1209),
    background = DarkAppColors.canvas,
    onBackground = DarkAppColors.ink,
    surface = DarkAppColors.paper,
    onSurface = DarkAppColors.ink,
    surfaceVariant = DarkAppColors.surface2,
    onSurfaceVariant = DarkAppColors.graphite,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF1A1918),
    surfaceContainerLow = Color(0xFF242422),
    surfaceContainer = DarkAppColors.surface1,
    surfaceContainerHigh = DarkAppColors.surface2,
    surfaceContainerHighest = Color(0xFF45443F),
    outline = DarkAppColors.sand,
    outlineVariant = DarkAppColors.sand,
    error = DarkAppColors.error,
    onError = Color(0xFF2A0D06),
    errorContainer = Color(0xFF5C2116),
    onErrorContainer = Color(0xFFFFDAD1),
    inverseSurface = DarkAppColors.ink,
    inverseOnSurface = DarkAppColors.canvas,
    inversePrimary = Color(0xFFA64C2F),
)

/** Serif for the assistant voice and greetings; system CJK falls back to Noto Serif. */
val DisplayFamily = FontFamily.Serif
val SansFamily = FontFamily.SansSerif
val MonoFamily = FontFamily.Monospace

// includeFontPadding = false tightens the vertical rhythm; Android's default font
// padding makes serif headings sit visibly low inside their line box.
internal fun base(
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

/**
 * Typography for the transcript. Kept out of Material's [Typography] so the settings
 * and workspace screens do not accidentally become serif.
 */
object AppType {
    val greeting = base(32f, 38f, family = DisplayFamily, letterSpacing = -0.4f)
    val assistantBody = base(16f, 25f, family = DisplayFamily)
    val assistantHeadingLarge = base(24f, 31f, family = DisplayFamily, letterSpacing = -0.2f)
    val assistantHeading = base(20f, 26f, family = DisplayFamily)
    val assistantHeadingSmall = base(17f, 23f, family = DisplayFamily, weight = FontWeight.Medium)
    val assistantHeadingTiny = base(16f, 22f, family = DisplayFamily, weight = FontWeight.Medium)
    val userBody = base(16f, 24f, family = SansFamily)
    val caption = base(11f, 14f, family = SansFamily)
    val captionMedium = base(11f, 14f, family = SansFamily, weight = FontWeight.Medium)
    val code = base(14f, 21f, family = MonoFamily)
}

private val AppTypography = Typography(
    displaySmall = AppType.greeting,
    headlineMedium = base(24f, 31f, family = DisplayFamily, letterSpacing = -0.2f),
    headlineSmall = base(20f, 26f, family = DisplayFamily),
    titleLarge = base(17f, 24f, family = SansFamily, weight = FontWeight.SemiBold, letterSpacing = -0.1f),
    titleMedium = base(15f, 20f, family = SansFamily, weight = FontWeight.Medium),
    titleSmall = base(13f, 18f, family = SansFamily, weight = FontWeight.Medium),
    bodyLarge = AppType.userBody,
    bodyMedium = base(14f, 21f, family = SansFamily),
    bodySmall = base(12.5f, 18f, family = SansFamily),
    labelLarge = base(14f, 18f, family = SansFamily, weight = FontWeight.Medium),
    labelMedium = base(12f, 16f, family = SansFamily, weight = FontWeight.Medium),
    labelSmall = AppType.captionMedium,
)

/** 10 / 12 / 16 / 18 / 24: soft paper corners, never full-screen pills. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
