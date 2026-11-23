import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/models/remote.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:claudroid_ui/ui/session_drawer.dart';
import 'package:claudroid_ui/ui/settings/settings_destination.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

/// The drawer's footer capsule is the quick executor switch (same picker as
/// the host bar above); the settings shortcut moved to a long press.
void main() {
  testWidgets('tapping the local footer opens the host picker', (tester) async {
    var picks = 0;
    var settings = 0;
    await tester.pumpWidget(
      _harness(
        onChooseHost: () => picks++,
        onSettings: (_) => settings++,
      ),
    );

    await tester.tap(find.text('引擎状态'));
    await tester.pumpAndSettle();

    expect(picks, 1);
    expect(settings, 0);
  });

  testWidgets('long-pressing the local footer opens engine settings', (
    tester,
  ) async {
    var picks = 0;
    SettingsDestination? destination;
    await tester.pumpWidget(
      _harness(
        onChooseHost: () => picks++,
        onSettings: (value) => destination = value,
      ),
    );

    await tester.longPress(find.text('引擎状态'));
    await tester.pumpAndSettle();

    expect(destination, SettingsDestination.engine);
    expect(picks, 0);
  });

  testWidgets('tapping a remote footer opens the host picker', (tester) async {
    var picks = 0;
    var hostSettings = 0;
    await tester.pumpWidget(
      _harness(
        activeHost: _host,
        onChooseHost: () => picks++,
        onOpenHostSettings: (_) => hostSettings++,
      ),
    );

    await tester.tap(find.text('远程执行 · 离线'));
    await tester.pumpAndSettle();

    expect(picks, 1);
    expect(hostSettings, 0);
  });

  testWidgets('long-pressing a remote footer opens that host settings', (
    tester,
  ) async {
    var picks = 0;
    String? opened;
    await tester.pumpWidget(
      _harness(
        activeHost: _host,
        onChooseHost: () => picks++,
        onOpenHostSettings: (id) => opened = id,
      ),
    );

    await tester.longPress(find.text('远程执行 · 离线'));
    await tester.pumpAndSettle();

    expect(opened, _host.id);
    expect(picks, 0);
  });
}

const _host = RemoteHost(
  id: 'h1',
  name: '开发机',
  baseUrl: 'https://claude.example',
);

Widget _harness({
  RemoteHost? activeHost,
  VoidCallback? onChooseHost,
  ValueChanged<String>? onOpenHostSettings,
  ValueChanged<SettingsDestination>? onSettings,
}) {
  return PrismApp(
    localizationsDelegates: const [ZhUiLocalizations.delegate],
    supportedLocales: const [Locale('zh'), Locale('en')],
    home: SessionDrawer(
      sessions: const [],
      activeId: null,
      engine: const EngineState(),
      activeHost: activeHost,
      onSelect: (_) {},
      onNew: () {},
      onRename: (_, _) {},
      onDelete: (_) {},
      onChooseHost: onChooseHost ?? () {},
      onWorkspace: () {},
      onOpenHostSettings: onOpenHostSettings ?? (_) {},
      onSettings: onSettings ?? (_) {},
      onClose: () {},
    ),
  );
}
