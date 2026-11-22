import 'dart:math' as math;

import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/localizations.dart';
import 'package:claudroid_ui/ui/reply_share.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

ChatMessage _reply(List<Block> blocks) =>
    ChatMessage(id: 'm1', role: Role.assistant, blocks: blocks);

Widget _harness(Widget child) => PrismApp(
  localizationsDelegates: const [ZhUiLocalizations.delegate],
  supportedLocales: const [Locale('zh'), Locale('en')],
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: Builder(
      builder: (context) =>
          Surface(color: context.colorScheme.surface, child: child),
    ),
  ),
);

void main() {
  group('replyPlainText', () {
    test('joins visible text blocks and drops sources and attachments', () {
      final text = replyPlainText(
        _reply(const [
          TextBlock('第一段'),
          ThinkingBlock('思考过程'),
          TextBlock('   '),
          TextBlock('第二段\n\nSources:\n- [A](https://example.com/a)\n'),
          TextBlock('附件 @attachments/a.png'),
        ]),
      );

      expect(text, '第一段\n\n第二段\n\n附件');
    });

    test('returns an empty string when nothing visible remains', () {
      expect(
        replyPlainText(
          _reply(const [
            TextBlock(''),
            TextBlock(' \n '),
            ThinkingBlock('只有思考'),
            TextBlock('@attachments/a.png'),
          ]),
        ),
        '',
      );
    });

    test('keeps a plain answer unchanged', () {
      expect(replyPlainText(_reply(const [TextBlock('只有正文')])), '只有正文');
    });
  });

  group('replyImagePixelRatio', () {
    test('caps the device pixel ratio at 2', () {
      expect(replyImagePixelRatio(const Size(400, 800), 3), 2.0);
      expect(replyImagePixelRatio(const Size(400, 800), 2.5), 2.0);
    });

    test('keeps a lower device pixel ratio', () {
      expect(replyImagePixelRatio(const Size(400, 800), 1.5), 1.5);
    });

    test('downsamples a tall reply to the 8192 px limit', () {
      final ratio = replyImagePixelRatio(const Size(100, 10000), 3);
      expect(ratio, closeTo(8192 / 10040, 1e-9));
      expect(ratio, lessThan(1));
    });

    test('downsamples a wide reply to the 8192 px limit', () {
      final ratio = replyImagePixelRatio(const Size(10000, 100), 3);
      expect(ratio, closeTo(8192 / 10040, 1e-9));
    });

    test('caps the total pixels of a large reply', () {
      const width = 2000 + 2 * kReplyImagePadding;
      const height = 4000 + 2 * kReplyImagePadding;
      final ratio = replyImagePixelRatio(const Size(2000, 4000), 3);
      expect(
        ratio,
        closeTo(math.sqrt(12 * 1000 * 1000 / (width * height)), 1e-9),
      );
    });

    test('returns null when even 0.5x would exceed the limit', () {
      expect(replyImagePixelRatio(const Size(100, 20000), 3), isNull);
    });

    test('returns null for an empty size', () {
      expect(replyImagePixelRatio(Size.zero, 3), isNull);
      expect(replyImagePixelRatio(const Size(0, 800), 3), isNull);
      expect(replyImagePixelRatio(const Size(400, 0), 3), isNull);
    });

    test('falls back to 1.0 for an invalid device pixel ratio', () {
      for (final dpr in [
        double.nan,
        double.infinity,
        double.negativeInfinity,
        0.0,
        -2.0,
      ]) {
        expect(replyImagePixelRatio(const Size(400, 800), dpr), 1.0);
      }
    });
  });

  testWidgets('the share dialog returns the picked format', (tester) async {
    ReplyShareChoice? choice;
    await tester.pumpWidget(
      _harness(
        Center(
          child: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                choice = await showReplyShareDialog(context);
              },
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    expect(find.text('分享回复'), findsOneWidget);
    expect(find.text('分享文字'), findsOneWidget);
    expect(find.text('分享图片'), findsOneWidget);

    await tester.tap(find.text('分享图片'));
    await tester.pumpAndSettle();

    expect(choice, ReplyShareChoice.image);
    expect(find.text('分享回复'), findsNothing);
  });

  testWidgets('the share dialog can be cancelled', (tester) async {
    ReplyShareChoice? choice = ReplyShareChoice.text;
    await tester.pumpWidget(
      _harness(
        Center(
          child: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                choice = await showReplyShareDialog(context);
              },
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();

    expect(choice, isNull);
  });
}
