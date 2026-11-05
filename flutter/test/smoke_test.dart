import 'package:claudroid_ui/main.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('root app builds without a Material dependency', (tester) async {
    await tester.pumpWidget(const ClaudroidApp());
    await tester.pump();
    expect(find.text('Claudroid'), findsOneWidget);
  });
}
