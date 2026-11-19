import 'dart:convert';

import 'package:claudroid_ui/bridge/api.dart';
import 'package:claudroid_ui/ui/claude_dir_dialog.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

const _channel = MethodChannel('app.claudroid/bridge');

Map<String, Object?> _listing(String path) {
  final entries = switch (path) {
    '' => [
        _entry('notes', 'notes'),
        _entry('src', 'src'),
        _file('README.md', 'README.md'),
      ],
    'src' => [_entry('ui', 'src/ui')],
    _ => <Map<String, Object?>>[],
  };
  return {
    'ok': true,
    'path': path,
    'entriesJson': jsonEncode(entries),
    'totalBytes': 0,
  };
}

Map<String, Object?> _entry(String name, String path) => {
      'name': name,
      'path': path,
      'kind': 'DIRECTORY',
      'size': 0,
      'modifiedAt': 0,
      'isDirectory': true,
    };

Map<String, Object?> _file(String name, String path) => {
      'name': name,
      'path': path,
      'kind': 'MARKDOWN',
      'size': 10,
      'modifiedAt': 0,
      'isDirectory': false,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(_channel, (call) async {
      if (call.method != 'workspace.list') {
        return <Object?, Object?>{'ok': false, 'error': 'unexpected call'};
      }
      final path = (call.arguments as Map<Object?, Object?>)['path'] as String?;
      if (path == 'gone') {
        return <Object?, Object?>{'ok': false, 'error': '目录不存在'};
      }
      return _listing(path ?? '');
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(_channel, null);
  });

  Widget harness(Future<void> Function(BuildContext) open) => PrismApp(
        builder: (context, child) => ViewScope(
          size: const Size(400, 800),
          isMobile: true,
          child: child!,
        ),
        localizationsDelegates: const [ZhUiLocalizations.delegate],
        supportedLocales: const [Locale('zh'), Locale('en')],
        home: Builder(
          builder: (context) => IconTheme(
            data: IconThemeData(color: context.colorScheme.onSurface, size: 24),
            child: Surface(
              color: context.colorScheme.surface,
              child: Center(
                child: TextButton(
                  onPressed: () => open(context),
                  child: const Text('打开'),
                ),
              ),
            ),
          ),
        ),
      );

  testWidgets('browses directories and returns the chosen path', (
    tester,
  ) async {
    String? chosen;
    await tester.pumpWidget(
      harness((context) async {
        chosen = await showClaudeDirDialog(
          context,
          bridge: const Bridge(),
          current: 'src',
          onToast: (_, {bool isError = false}) {},
        );
      }),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    expect(find.text('Claude 工作目录'), findsOneWidget);
    expect(find.text('/work/src'), findsOneWidget);
    expect(find.text('ui'), findsOneWidget);
    expect(find.text('.. 上一级'), findsOneWidget);

    await tester.tap(find.text('ui'));
    await tester.pumpAndSettle();
    expect(find.text('/work/src/ui'), findsOneWidget);
    expect(find.text('没有子目录'), findsOneWidget);

    await tester.tap(find.text('.. 上一级'));
    await tester.pumpAndSettle();
    expect(find.text('/work/src'), findsOneWidget);

    await tester.tap(find.text('选择此目录'));
    await tester.pumpAndSettle();

    expect(chosen, 'src');
  });

  testWidgets('a deleted starting directory falls back to the root', (
    tester,
  ) async {
    final toasts = <String>[];
    await tester.pumpWidget(
      harness((context) async {
        await showClaudeDirDialog(
          context,
          bridge: const Bridge(),
          current: 'gone',
          onToast: (text, {bool isError = false}) => toasts.add(text),
        );
      }),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    expect(toasts, contains('原工作目录已不存在，已回到工作区根目录'));
    expect(find.text('/work'), findsOneWidget);
    expect(find.text('src'), findsOneWidget);
    expect(find.text('.. 上一级'), findsNothing);
  });
}
