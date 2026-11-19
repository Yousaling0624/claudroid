import 'dart:math' as math;

import 'package:claudroid_ui/ui/sparkle_motion.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

// Must match the keys `ThinkingSparkle` puts on its transforms.
const _rotationKey = ValueKey('thinkingSparkleRotation');
const _scaleKey = ValueKey('thinkingSparkleScale');
const _color = Color(0xFFD97757);

Widget _harness({required bool active, bool reduceMotion = false}) =>
    MediaQuery(
      data: MediaQueryData(disableAnimations: reduceMotion),
      child: Directionality(
        textDirection: TextDirection.ltr,
        child: Center(
          child: ThinkingSparkle(active: active, color: _color),
        ),
      ),
    );

double _rotationOf(WidgetTester tester) {
  final matrix = tester.widget<Transform>(find.byKey(_rotationKey)).transform;
  return math.atan2(matrix.entry(1, 0), matrix.entry(0, 0));
}

double _scaleOf(WidgetTester tester) {
  final matrix = tester.widget<Transform>(find.byKey(_scaleKey)).transform;
  return matrix.entry(0, 0);
}

void main() {
  test('rotation advances linearly and wraps by the four-fold symmetry', () {
    expect(thinkingSparklePose(0).angle, 0);
    expect(thinkingSparklePose(0.25).angle, closeTo(math.pi / 4, 1e-9));
    expect(thinkingSparklePose(0.5).angle, closeTo(math.pi / 2, 1e-9));
    // Half a turn later the four-point star looks exactly like t = 0.
    expect(thinkingSparklePose(1).angle, 0);
  });

  test('the pulse peaks in 1.10–1.20 and rests at the seam', () {
    expect(thinkingSparklePose(0).scale, 1);
    expect(thinkingSparklePose(1).scale, 1);

    var peak = 0.0;
    for (var i = 0; i < 1000; i++) {
      peak = math.max(peak, thinkingSparklePose(i / 1000).scale);
    }
    expect(peak, inInclusiveRange(1.10, 1.20));
    expect(peak, greaterThan(1.14));
  });

  testWidgets('an active turn rotates and pulses without moving its box', (
    tester,
  ) async {
    await tester.pumpWidget(_harness(active: true));
    await tester.pump();

    expect(_rotationOf(tester), 0);
    expect(_scaleOf(tester), 1);
    final size = tester.getSize(find.byType(ThinkingSparkle));
    expect(size, const Size(14, 14));

    await tester.pump(const Duration(milliseconds: 400));
    final turned = _rotationOf(tester);
    final pulsed = _scaleOf(tester);
    expect(turned, greaterThan(0));
    expect(pulsed, greaterThan(1.10));
    // Transforms paint outside the box; the layout must stay 14 dp.
    expect(tester.getSize(find.byType(ThinkingSparkle)), size);

    await tester.pump(const Duration(milliseconds: 400));
    expect(_rotationOf(tester), greaterThan(turned));
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduce motion keeps the still glyph and starts nothing', (
    tester,
  ) async {
    await tester.pumpWidget(_harness(active: true, reduceMotion: true));
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.byKey(_rotationKey), findsNothing);
    expect(find.byKey(_scaleKey), findsNothing);
    expect(tester.getSize(find.byType(ThinkingSparkle)), const Size(14, 14));
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('an idle turn keeps the still glyph', (tester) async {
    await tester.pumpWidget(_harness(active: false));
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.byKey(_rotationKey), findsNothing);
    expect(find.byKey(_scaleKey), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('ending the turn stops the loop and parks the glyph', (
    tester,
  ) async {
    await tester.pumpWidget(_harness(active: true));
    await tester.pump(const Duration(milliseconds: 400));
    expect(tester.binding.transientCallbackCount, greaterThan(0));

    await tester.pumpWidget(_harness(active: false));
    await tester.pump();

    expect(find.byKey(_rotationKey), findsNothing);
    expect(find.byKey(_scaleKey), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
  });
}
