import 'package:flutter/widgets.dart';
import 'package:prism_ui/color.dart';
import 'package:prism_ui/color_scheme.dart';

/// Seed for the whole palette: Claudroid's terracotta, the same value as
/// `ui/theme/Theme.kt`'s `orange`, so the derived surfaces stay on the warm
/// paper side instead of drifting blue.
///
/// Lives outside `app.dart` so the settings preview can rebuild the light and
/// dark schemes without importing the shell (which imports the settings page).
const kSeedColor = Color(0xFFD97757);

// Warm paper neutrals. They replace the seed-derived surfaces, which read as
// rosy peach once prism_ui lifts their chroma; the seed still drives primary,
// containers and the liquid-glass tints.
const _paperLight = Color(0xFFF8F4ED);
const _paperBrightLight = Color(0xFFFBF9F4);
const _paperLowestLight = Color(0xFFFFFDF9);
const _paperHighLight = Color(0xFFF0EAE0);
const _paperHighestLight = Color(0xFFE8E0D2);
const _inkLight = Color(0xFF2D2520);
const _graphiteLight = Color(0xFF5A4F44);
const _outlineLight = Color(0xFFB5AB9E);
const _sandLight = Color(0xFFDDD2BD);
const _accentInkLight = Color(0xFFA64C2F);

const _paperDark = Color(0xFF1B1815);
const _paperBrightDark = Color(0xFF24201C);
const _paperLowestDark = Color(0xFF151310);
const _paperHighDark = Color(0xFF2A251F);
const _paperHighestDark = Color(0xFF332D26);
const _inkDark = Color(0xFFF2EAE0);
const _graphiteDark = Color(0xFFC9BFB3);
const _outlineDark = Color(0xFF8A7D6E);
const _sandDark = Color(0xFF443C34);
const _accentInkDark = Color(0xFFE8A184);

/// The app's colour scheme: FlClash's generated structure and liquid-glass
/// tints, with Claude's warm neutral paper swapped in for every surface role.
ColorScheme claudroidColorScheme(Brightness brightness) {
  final base = seededColorScheme(seedColor: kSeedColor, brightness: brightness);
  if (brightness == Brightness.dark) {
    return base.copyWith(
      surface: _paperDark,
      surfaceDim: _paperDark,
      surfaceBright: _paperBrightDark,
      surfaceContainerLowest: _paperLowestDark,
      surfaceContainerLow: _paperDark,
      surfaceContainer: _paperDark,
      surfaceContainerHigh: _paperHighDark,
      surfaceContainerHighest: _paperHighestDark,
      onSurface: _inkDark,
      onSurfaceVariant: _graphiteDark,
      outline: _outlineDark,
      outlineVariant: _sandDark,
      primary: const Color(0xFFE08B6C),
      onPrimary: const Color(0xFF33170E),
      primaryContainer: const Color(0xFF5A2E1E),
      onPrimaryContainer: const Color(0xFFF6D8C9),
      inverseSurface: _inkDark,
      onInverseSurface: _paperDark,
      inversePrimary: const Color(0xFFA64C2F),
    );
  }
  return base.copyWith(
    surface: _paperLight,
    surfaceDim: _paperHighLight,
    surfaceBright: _paperBrightLight,
    surfaceContainerLowest: _paperLowestLight,
    surfaceContainerLow: _paperBrightLight,
    surfaceContainer: _paperLight,
    surfaceContainerHigh: _paperHighLight,
    surfaceContainerHighest: _paperHighestLight,
    onSurface: _inkLight,
    onSurfaceVariant: _graphiteLight,
    outline: _outlineLight,
    outlineVariant: _sandLight,
    primary: kSeedColor,
    onPrimary: const Color(0xFFFFFFFF),
    primaryContainer: const Color(0xFFF2DDD0),
    onPrimaryContainer: _accentInkLight,
    inverseSurface: _inkLight,
    onInverseSurface: _paperBrightLight,
    inversePrimary: const Color(0xFFE8A184),
  );
}

/// Text-safe terracotta for links and small accent labels. `primary` is tuned
/// for fills and icons; it is too light for body-size text on paper.
Color linkColor(ColorScheme scheme) =>
    scheme.brightness == Brightness.dark ? _accentInkDark : _accentInkLight;
