import 'package:claudroid_ui/ui/settings/settings_labels.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('bypass mode spells out that commands run directly', () {
    final footnote = permissionFootnote('BYPASS');
    expect(footnote, contains('完全放行'));
    expect(footnote, contains('直接执行'));
  });

  test('other modes use the standard caution', () {
    final footnote = permissionFootnote('AUTO');
    expect(footnote, contains('请确认'));
    expect(footnote, isNot(contains('完全放行')));
  });
}
