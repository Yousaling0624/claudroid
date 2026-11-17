import 'package:claudroid_ui/ui/app.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('the shell renders before the host answers', (tester) async {
    // No platform channels are registered here, so `Bridge.init` throws and the
    // app falls into its home state with the startup notice. That is exactly
    // the path a cold start takes when the host is not attached yet.
    await tester.pumpWidget(const ClaudroidApp());
    await tester.pump();

    expect(find.text('Claudroid'), findsOneWidget);
    expect(find.text('今天想让 Claude 在这台手机上做什么？'), findsOneWidget);
    expect(find.text('有什么可以帮你的？'), findsOneWidget);
  });
}
