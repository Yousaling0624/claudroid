import 'package:claudroid_ui/ui/settings/settings_widgets.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

/// The settings title lives in the fixed bar; the body padding is what clears
/// it. A regression that moves the title into the list would scroll it away.
Widget _harness() => PrismApp(
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: SettingsScaffold(
      title: '服务与模型',
      listenable: ChangeNotifier(),
      body: ListView(
        children: [
          for (var i = 0; i < 40; i++)
            SizedBox(height: 60, child: Text('设置行 $i')),
        ],
      ),
    ),
  ),
);

void main() {
  testWidgets('the settings title does not move when the list scrolls', (
    tester,
  ) async {
    await tester.pumpWidget(_harness());
    await tester.pump();

    final before = tester.getTopLeft(find.text('服务与模型'));
    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();
    final after = tester.getTopLeft(find.text('服务与模型'));

    expect(after, before);
  });
}
