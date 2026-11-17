import 'package:claudroid_ui/bridge/api.dart';
import 'package:claudroid_ui/bridge/events.dart';
import 'package:claudroid_ui/ui/settings/settings_destination.dart';
import 'package:claudroid_ui/ui/settings_page.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

Widget _harness(
  ValueNotifier<AppState> state,
  SettingsDestination destination,
) => PrismApp(
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: SettingsPage(
      bridge: const Bridge(),
      stateListenable: state,
      onUpdateSettings: (_) async {},
      onToast: (text, {bool isError = false}) {},
      destination: destination,
    ),
  ),
);

void main() {
  testWidgets('root destination keeps the grouped settings list', (
    tester,
  ) async {
    final state = ValueNotifier(const AppState());
    await tester.pumpWidget(_harness(state, SettingsDestination.root));
    await tester.pump();

    expect(find.text('模型与引擎'), findsOneWidget);
    expect(find.text('应用'), findsOneWidget);
  });

  testWidgets('service-models destination opens that page directly', (
    tester,
  ) async {
    final state = ValueNotifier(const AppState());
    await tester.pumpWidget(
      _harness(state, SettingsDestination.serviceModels),
    );
    await tester.pump();

    expect(find.text('服务与模型'), findsWidgets);
    expect(find.text('模型与引擎'), findsNothing);
  });

  testWidgets('engine destination opens the engine page directly', (
    tester,
  ) async {
    final state = ValueNotifier(const AppState());
    await tester.pumpWidget(_harness(state, SettingsDestination.engine));
    await tester.pump();

    expect(find.text('Claude Code 引擎'), findsWidgets);
    expect(find.text('模型与引擎'), findsNothing);
  });
}
