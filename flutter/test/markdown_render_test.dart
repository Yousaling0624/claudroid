import 'package:claudroid_ui/ui/markdown.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

Widget _harness(Widget child) => PrismApp(
      home: ViewScope(
        size: const Size(400, 800),
        isMobile: true,
        child: Builder(
          builder: (context) => IconTheme(
            data: IconThemeData(color: context.colorScheme.onSurface, size: 24),
            child: Surface(
              color: context.colorScheme.surface,
              child: SelectionArea(child: child),
            ),
          ),
        ),
      ),
    );

void main() {
  testWidgets('one SelectionArea covers every markdown block', (tester) async {
    await tester.pumpWidget(
      _harness(
        const Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            MarkdownText('第一段'),
            MarkdownText('## 小标题\n\n第二段'),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    // 每个段落/表格格都是普通 Text，选择域由外层 SelectionArea 统一提供。
    expect(find.byType(SelectableText), findsNothing);
    expect(find.byType(Text), findsNWidgets(3));
    expect(find.text('第一段'), findsOneWidget);
    expect(find.text('第二段'), findsOneWidget);
  });

  testWidgets('markdown links keep their tap callback', (tester) async {
    String? opened;
    await tester.pumpWidget(
      _harness(
        MarkdownText(
          'https://example.com',
          onLinkTap: (url) => opened = url,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('https://example.com'));
    await tester.pump();

    expect(opened, 'https://example.com');
  });
}
