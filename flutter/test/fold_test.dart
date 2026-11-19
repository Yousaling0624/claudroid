import 'package:claudroid_ui/ui/fold.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _harness({required bool open}) => MediaQuery(
  data: const MediaQueryData(),
  child: Directionality(
    textDirection: TextDirection.ltr,
    child: Align(
      alignment: Alignment.topLeft,
      child: AnimatedFold(
        open: open,
        child: const SizedBox(key: ValueKey('body'), width: 120, height: 100),
      ),
    ),
  ),
);

void main() {
  testWidgets('opening animates through intermediate heights', (tester) async {
    await tester.pumpWidget(_harness(open: false));
    expect(tester.getSize(find.byType(AnimatedFold)).height, 0);

    await tester.pumpWidget(_harness(open: true));
    // A frame into the animation the height is between closed and open.
    await tester.pump(const Duration(milliseconds: 90));
    final midway = tester.getSize(find.byType(AnimatedFold)).height;
    expect(midway, greaterThan(0));
    expect(midway, lessThan(100));

    await tester.pumpAndSettle();
    expect(tester.getSize(find.byType(AnimatedFold)).height, 100);
  });

  testWidgets('closing keeps the child mounted until the motion ends', (
    tester,
  ) async {
    await tester.pumpWidget(_harness(open: true));
    await tester.pumpAndSettle();

    await tester.pumpWidget(_harness(open: false));
    await tester.pump(const Duration(milliseconds: 40));
    expect(find.byKey(const ValueKey('body')), findsOneWidget);

    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('body')), findsNothing);
    expect(tester.getSize(find.byType(AnimatedFold)).height, 0);
  });

  testWidgets('fade can be disabled for structural swaps', (tester) async {
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: Align(
            alignment: Alignment.topLeft,
            child: AnimatedFold(
              open: true,
              fade: false,
              child: const SizedBox(width: 120, height: 100),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(FadeTransition), findsNothing);
    expect(tester.getSize(find.byType(AnimatedFold)).height, 100);
  });

  testWidgets('a narrow child stays on the leading edge', (tester) async {
    // The live thinking pill is narrower than the transcript; SizeTransition's
    // default center alignment used to put it in the middle of the line.
    await tester.pumpWidget(_harness(open: true));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.byKey(const ValueKey('body'))).dx, 0);
  });

  testWidgets('reduce motion opens instantly', (tester) async {
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: Align(
            alignment: Alignment.topLeft,
            child: AnimatedFold(
              open: true,
              child: const SizedBox(width: 120, height: 100),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(tester.getSize(find.byType(AnimatedFold)).height, 100);
  });
}
