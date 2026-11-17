import 'package:claudroid_ui/ui/settings/log_filter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const lines = [
    '12:00 Starting engine',
    '',
    '12:01 proot: mount /work',
    '12:02 ERROR: permission denied',
  ];

  test('a blank query keeps every line, including blank ones', () {
    expect(filterLogLines(lines, ''), same(lines));
    expect(filterLogLines(lines, '   '), same(lines));
  });

  test('matches case-insensitively and trims the query', () {
    expect(filterLogLines(lines, 'error'), ['12:02 ERROR: permission denied']);
    expect(filterLogLines(lines, '  PROOT '), ['12:01 proot: mount /work']);
  });

  test('returns an empty list when nothing matches', () {
    expect(filterLogLines(lines, 'daemon'), isEmpty);
  });
}
