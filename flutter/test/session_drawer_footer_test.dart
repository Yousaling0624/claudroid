import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/models/remote.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:claudroid_ui/ui/session_drawer.dart';
import 'package:claudroid_ui/ui/settings/settings_destination.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

/// The drawer's footer capsule is the quick executor switch: a tap drops an
/// anchored menu of local + paired hosts (like the model menu), while a long
/// press keeps the shortcut into the current executor's settings page.
void main() {
  testWidgets('tapping the local footer opens the host menu', (tester) async {
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

    expect(find.byType(PopupMenu), findsOneWidget);
    expect(find.text('开发机'), findsOneWidget);
    expect(picks, 0);
    expect(settings, 0);
  });

  testWidgets('choosing a host in the local footer menu switches to it', (
    tester,
  ) async {
    String? selected = 'unset';
    await tester.pumpWidget(
      _harness(onSelectHost: (hostId) => selected = hostId),
    );

    await tester.tap(find.text('引擎状态'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开发机'));
    await tester.pumpAndSettle();

    expect(selected, _host.id);
  });

  testWidgets('long-pressing the local footer opens engine settings', (
    tester,
  ) async {
    SettingsDestination? destination;
    var picks = 0;
    await tester.pumpWidget(
      _harness(
        onChooseHost: () => picks++,
        onSettings: (value) => destination = value,
      ),
    );

    await tester.longPress(find.text('引擎状态'));
    await tester.pumpAndSettle();

    expect(destination, SettingsDestination.engine);
    expect(find.byType(PopupMenu), findsNothing);
    expect(picks, 0);
  });

  testWidgets('the remote footer menu offers the local engine too', (
    tester,
  ) async {
    String? selected = 'unset';
    await tester.pumpWidget(
      _harness(
        activeHost: _host,
        onSelectHost: (hostId) => selected = hostId,
      ),
    );

    await tester.tap(find.text('远程执行 · 离线'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('本机'));
    await tester.pumpAndSettle();

    expect(selected, isNull);
  });

  testWidgets('long-pressing a remote footer opens that host settings', (
    tester,
  ) async {
    String? opened;
    var picks = 0;
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
    expect(find.byType(PopupMenu), findsNothing);
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
  ValueChanged<String?>? onSelectHost,
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
      hosts: const RemoteHostsState(hosts: [_host]),
      onSelect: (_) {},
      onNew: () {},
      onRename: (_, _) {},
      onDelete: (_) {},
      onChooseHost: onChooseHost ?? () {},
      onSelectHost: onSelectHost ?? (_) {},
      onWorkspace: () {},
      onOpenHostSettings: onOpenHostSettings ?? (_) {},
      onSettings: onSettings ?? (_) {},
      onClose: () {},
    ),
  );
}
