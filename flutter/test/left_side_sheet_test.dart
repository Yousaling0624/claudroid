import 'package:claudroid_ui/ui/left_side_sheet.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

void main() {
  testWidgets('the left side sheet opens from the start edge', (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PrismApp(
        localizationsDelegates: const [ZhUiLocalizations.delegate],
        home: Builder(
          builder: (context) => Center(
            child: GestureDetector(
              onTap: () => showLeftSideSheet<void>(
                context: context,
                builder: (_) => const Center(child: Text('抽屉内容')),
              ),
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    expect(find.text('抽屉内容'), findsOneWidget);
    // The panel starts at x=0, is 88% wide and leaves the page visible behind.
    final panel = tester.getRect(find.byWidgetPredicate(
      (w) =>
          w is SizedBox &&
          ((w.width ?? 0) - 360 * 0.88).abs() < 0.1,
    ).first);
    expect(panel.left, 0);
    expect(panel.width, closeTo(316.8, 0.1));

    await tester.tapAt(const Offset(350, 400));
    await tester.pumpAndSettle();
    expect(find.text('抽屉内容'), findsNothing);
  });
}
