import 'package:claudroid_ui/ui/palette.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('colour schemes are derived once and shared', () {
    // Engine progress rebuilds the settings pages (and the appearance preview)
    // many times per second; rebuilding the seed palette on every tick is what
    // made progress updates look like repeated freezes.
    expect(
      identical(
        claudroidColorScheme(Brightness.light),
        claudroidColorScheme(Brightness.light),
      ),
      isTrue,
    );
    expect(
      identical(
        claudroidColorScheme(Brightness.dark),
        claudroidColorScheme(Brightness.dark),
      ),
      isTrue,
    );
  });

  test('cached schemes keep the paper palette', () {
    final light = claudroidColorScheme(Brightness.light);
    final dark = claudroidColorScheme(Brightness.dark);
    expect(light.brightness, Brightness.light);
    expect(light.primary, kSeedColor);
    expect(dark.brightness, Brightness.dark);
    expect(light.surface, isNot(dark.surface));
  });
}
