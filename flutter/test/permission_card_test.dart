import 'package:claudroid_ui/models/remote.dart';
import 'package:claudroid_ui/ui/permission_card.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

PermissionCard _card({
  required Future<void> Function({required bool allow, required bool always})
      onRespond,
  int expiresAt = 0,
}) =>
    PermissionCard(
      permission: PendingPermission(
        requestId: 'req-1',
        toolName: 'Bash',
        inputJson: '{"command":"npm test"}',
        expiresAt: expiresAt,
      ),
      onRespond: onRespond,
    );

Widget _harness(Widget child) => PrismApp(
      home: ViewScope(
        size: const Size(400, 800),
        isMobile: true,
        child: Center(child: child),
      ),
    );

void main() {
  testWidgets('allow once answers without always', (tester) async {
    bool? gotAllow;
    bool? gotAlways;
    await tester.pumpWidget(
      _harness(
        _card(
          onRespond: ({required bool allow, required bool always}) async {
            gotAllow = allow;
            gotAlways = always;
          },
        ),
      ),
    );
    await tester.tap(find.text('允许一次'));
    await tester.pump();
    expect(gotAllow, isTrue);
    expect(gotAlways, isFalse);
  });

  testWidgets('session-wide allow sets both flags', (tester) async {
    bool? gotAllow;
    bool? gotAlways;
    await tester.pumpWidget(
      _harness(
        _card(
          onRespond: ({required bool allow, required bool always}) async {
            gotAllow = allow;
            gotAlways = always;
          },
        ),
      ),
    );
    await tester.tap(find.text('本会话总是允许'));
    await tester.pump();
    expect(gotAllow, isTrue);
    expect(gotAlways, isTrue);
  });

  testWidgets('deny sends allow=false', (tester) async {
    bool? gotAllow;
    await tester.pumpWidget(
      _harness(
        _card(
          onRespond: ({required bool allow, required bool always}) async {
            gotAllow = allow;
          },
        ),
      ),
    );
    await tester.tap(find.text('拒绝'));
    await tester.pump();
    expect(gotAllow, isFalse);
  });

  testWidgets('a countdown is shown when the host set a deadline', (
    tester,
  ) async {
    final expiresAt = DateTime.now()
        .add(const Duration(minutes: 2))
        .millisecondsSinceEpoch;
    await tester.pumpWidget(
      _harness(
        _card(
          expiresAt: expiresAt,
          onRespond: ({required bool allow, required bool always}) async {},
        ),
      ),
    );
    expect(find.textContaining('后自动拒绝'), findsOneWidget);
    expect(find.text('Bash'), findsOneWidget);
    expect(find.textContaining('npm test'), findsOneWidget);
  });
}
