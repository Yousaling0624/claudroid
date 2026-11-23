import 'package:claudroid_ui/models/remote.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:claudroid_ui/ui/remote_host_picker.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

Widget _harness({
  required RemoteHostsState hosts,
  required ValueChanged<RemoteHostChoice?> onResult,
}) {
  return PrismApp(
    localizationsDelegates: const [ZhUiLocalizations.delegate],
    supportedLocales: const [Locale('zh'), Locale('en')],
    home: ViewScope(
      size: const Size(400, 800),
      isMobile: true,
      child: Builder(
        builder: (context) => Center(
          child: TextButton(
            onPressed: () async {
              onResult(
                await showRemoteHostPicker(
                  context,
                  hosts: hosts,
                  currentHostId: null,
                ),
              );
            },
            child: const Text('打开主机选择'),
          ),
        ),
      ),
    ),
  );
}

void main() {
  testWidgets('an empty host list still offers local and add-device', (
    tester,
  ) async {
    await tester.pumpWidget(
      _harness(hosts: const RemoteHostsState(), onResult: (_) {}),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    expect(find.text('在哪儿执行？'), findsOneWidget);
    expect(find.text('本机'), findsOneWidget);
    expect(find.text('添加设备'), findsOneWidget);
    expect(find.text('使用本机'), findsOneWidget);
  });

  testWidgets('the local settings shortcut returns configureLocal', (
    tester,
  ) async {
    RemoteHostChoice? choice;
    var opened = false;
    await tester.pumpWidget(
      _harness(
        hosts: const RemoteHostsState(),
        onResult: (value) {
          opened = true;
          choice = value;
        },
      ),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('host-picker-settings-local')),
    );
    await tester.pumpAndSettle();

    expect(opened, isTrue);
    expect(choice?.intent, RemoteHostPickerIntent.configureLocal);
    expect(choice?.hostId, isNull);
  });

  testWidgets('a host settings shortcut returns configureHost and its id', (
    tester,
  ) async {
    const hosts = RemoteHostsState(
      hosts: [
        RemoteHost(id: 'h1', name: '开发机', baseUrl: 'https://claude.example'),
        RemoteHost(id: 'h2', name: '家里', baseUrl: 'http://10.0.0.8:8787'),
      ],
    );
    RemoteHostChoice? choice;
    await tester.pumpWidget(
      _harness(hosts: hosts, onResult: (value) => choice = value),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('host-picker-settings-h2')),
    );
    await tester.pumpAndSettle();

    expect(choice?.intent, RemoteHostPickerIntent.configureHost);
    expect(choice?.hostId, 'h2');
  });

  testWidgets('the add-device row returns addHost without selecting', (
    tester,
  ) async {
    const hosts = RemoteHostsState(
      hosts: [
        RemoteHost(id: 'h1', name: '开发机', baseUrl: 'https://claude.example'),
      ],
    );
    RemoteHostChoice? choice;
    await tester.pumpWidget(
      _harness(hosts: hosts, onResult: (value) => choice = value),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('host-picker-add')));
    await tester.pumpAndSettle();

    expect(choice?.intent, RemoteHostPickerIntent.addHost);
    expect(choice?.hostId, isNull);
  });

  testWidgets('a long host name stays on one ellipsized line', (tester) async {
    const longName = 'PC-20260824HJFC-WORKSTATION-WITH-A-LONG-NAME';
    const hosts = RemoteHostsState(
      hosts: [
        RemoteHost(
          id: 'h1',
          name: longName,
          baseUrl: 'https://claude.example',
        ),
      ],
    );
    await tester.pumpWidget(
      _harness(hosts: hosts, onResult: (_) {}),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    final title = tester.widget<Text>(find.text(longName));
    expect(title.maxLines, 1);
    expect(title.overflow, TextOverflow.ellipsis);
  });

  testWidgets('a normal row tap still returns a plain selection', (
    tester,
  ) async {
    const hosts = RemoteHostsState(
      hosts: [
        RemoteHost(id: 'h1', name: '开发机', baseUrl: 'https://claude.example'),
      ],
    );
    RemoteHostChoice? choice;
    await tester.pumpWidget(
      _harness(hosts: hosts, onResult: (value) => choice = value),
    );
    await tester.tap(find.text('打开主机选择'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('开发机'));
    await tester.pump();
    await tester.tap(find.text('使用 开发机'));
    await tester.pumpAndSettle();

    expect(choice?.intent, isNull);
    expect(choice?.hostId, 'h1');
  });
}
