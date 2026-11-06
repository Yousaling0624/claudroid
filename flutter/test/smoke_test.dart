import 'package:claudroid_ui/ui/app.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('the shell renders before the host answers', (tester) async {
    // No platform channels are registered here, so `Bridge.init` throws and the
    // app falls into its empty state with the startup notice. That is exactly
    // the path a cold start takes when the host is not attached yet.
    await tester.pumpWidget(const ClaudroidApp());
    await tester.pump();

    expect(find.text('还没有对话'), findsOneWidget);
    expect(find.text('新建对话'), findsOneWidget);
    expect(find.text('设置'), findsOneWidget);
  });
}
