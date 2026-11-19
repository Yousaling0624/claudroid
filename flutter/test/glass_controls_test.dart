import 'package:claudroid_ui/ui/glass_controls.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

const _offsetKey = ValueKey('liquidGlassMorphOffset');
const _scaleKey = ValueKey('liquidGlassMorphScale');

void main() {
  testWidgets('a tap reaches the child and leaves the glass at rest', (
    tester,
  ) async {
    final taps = <int>[];
    await _pumpMorph(tester, taps);

    await tester.tap(find.byType(LiquidGlassMorph));
    await tester.pumpAndSettle();

    expect(taps, hasLength(1));
    expect(_scaleOf(tester), closeTo(1, 0.0001));
    expect(_offsetOf(tester), Offset.zero);
  });

  testWidgets('holding swells, dragging stretches, release springs back', (
    tester,
  ) async {
    await _pumpMorph(tester, <int>[]);
    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(LiquidGlassMorph)),
    );

    // Let the long-press timer fire and the swell spring rise.
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump(const Duration(milliseconds: 200));
    final swollen = _scaleOf(tester);
    expect(swollen, greaterThan(1.02));

    await gesture.moveBy(const Offset(30, 0));
    // The ticker's first tick only establishes its clock; the next one moves
    // the spring.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    expect(_offsetOf(tester).dx, greaterThan(1));
    expect(_scaleOf(tester), greaterThan(swollen));

    await gesture.up();
    await tester.pumpAndSettle();
    expect(_scaleOf(tester), closeTo(1, 0.001));
    expect(_offsetOf(tester), Offset.zero);
  });

  testWidgets('a drag before the hold cancels the morph', (tester) async {
    await _pumpMorph(tester, <int>[]);
    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(LiquidGlassMorph)),
    );

    await tester.pump(const Duration(milliseconds: 100));
    await gesture.moveBy(const Offset(40, 0));
    await tester.pump(const Duration(milliseconds: 600));

    expect(_scaleOf(tester), closeTo(1, 0.0001));
    expect(_offsetOf(tester), Offset.zero);

    await gesture.up();
    await tester.pumpAndSettle();
  });

  testWidgets('reduce motion leaves the glass still', (tester) async {
    await _pumpMorph(tester, <int>[], disableAnimations: true);
    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(LiquidGlassMorph)),
    );

    await tester.pump(const Duration(milliseconds: 700));
    await gesture.moveBy(const Offset(30, 0));
    await tester.pump(const Duration(milliseconds: 200));

    expect(_scaleOf(tester), closeTo(1, 0.0001));
    expect(_offsetOf(tester), Offset.zero);

    await gesture.up();
    await tester.pumpAndSettle();
  });
}

Future<void> _pumpMorph(
  WidgetTester tester,
  List<int> taps, {
  bool disableAnimations = false,
}) async {
  await tester.pumpWidget(
    Directionality(
      textDirection: TextDirection.ltr,
      child: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: Center(
          child: LiquidGlassMorph(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => taps.add(1),
              child: const SizedBox(width: 48, height: 48),
            ),
          ),
        ),
      ),
    ),
  );
}

double _scaleOf(WidgetTester tester) => tester
    .widget<Transform>(find.byKey(_scaleKey))
    .transform
    .getMaxScaleOnAxis();

Offset _offsetOf(WidgetTester tester) {
  final translation = tester
      .widget<Transform>(find.byKey(_offsetKey))
      .transform
      .getTranslation();
  return Offset(translation.x, translation.y);
}
