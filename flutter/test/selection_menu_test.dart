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
}
