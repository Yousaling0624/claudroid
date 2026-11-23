import 'package:claudroid_ui/ui/localizations.dart';
import 'package:claudroid_ui/ui/selection_menu.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

Widget _harness() => PrismApp(
  localizationsDelegates: const [ZhUiLocalizations.delegate],
  supportedLocales: const [Locale('zh'), Locale('en')],
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: Builder(
      builder: (context) => Surface(
        color: context.colorScheme.surface,
        child: const Padding(
          padding: EdgeInsets.all(24),
          child: SelectionArea(
            contextMenuBuilder: appSelectionMenu,
            child: Text('hello world', style: TextStyle(fontSize: 18)),
          ),
        ),
      ),
    ),
  ),
);

Widget _editableHarness() => PrismApp(
  localizationsDelegates: const [ZhUiLocalizations.delegate],
  supportedLocales: const [Locale('zh'), Locale('en')],
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: Builder(
      builder: (context) => Surface(
        color: context.colorScheme.surface,
        child: const Padding(
          padding: EdgeInsets.all(24),
          // A narrow field keeps its centre over the text; the widget would
          // otherwise stretch to the page width and the long press would land
          // past the last character.
          child: SizedBox(
            width: 160,
            child: SelectableText(
              'hello world',
              contextMenuBuilder: appEditableTextMenu,
              style: TextStyle(fontSize: 18),
            ),
          ),
        ),
      ),
    ),
  ),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'selection menu offers copy, share and select all',
    (tester) async {
      await tester.pumpWidget(_harness());
      await tester.longPress(find.text('hello world'));
      await tester.pumpAndSettle();

      expect(find.text('复制'), findsOneWidget);
      expect(find.text('分享'), findsOneWidget);
      expect(find.text('全选'), findsOneWidget);

      // 用户反馈：点「全选」后菜单直接消失，没法接着分享。全选后必须重新
      // 弹出，并且此时仍能分享。
      await tester.tap(find.text('全选'));
      await tester.pumpAndSettle();

      expect(find.text('复制'), findsOneWidget);
      expect(find.text('分享'), findsOneWidget);
      expect(find.text('全选'), findsOneWidget);
    },
    // The framework only adds the share item on Android; the widget test host
    // is the desktop platform otherwise.
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'editable selectable text keeps copy and share after select all',
    (tester) async {
      await tester.pumpWidget(_editableHarness());
      await tester.longPressAt(
        tester.getTopLeft(find.byType(SelectableText)) + const Offset(30, 12),
      );
      await tester.pumpAndSettle();

      expect(find.text('复制'), findsOneWidget);
      expect(find.text('分享'), findsOneWidget);
      expect(find.text('全选'), findsOneWidget);

      await tester.tap(find.text('全选'));
      await tester.pumpAndSettle();

      // The menu must reopen on the fresh full selection: copy and share stay
      // available, and a second "全选" is no longer offered because the whole
      // text is already selected.
      expect(find.text('复制'), findsOneWidget);
      expect(find.text('分享'), findsOneWidget);
      expect(find.text('全选'), findsNothing);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );
}
