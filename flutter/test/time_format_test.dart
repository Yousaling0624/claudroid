import 'package:claudroid_ui/ui/time_format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final now = DateTime(2026, 11, 17, 9, 30);

  test('a missing timestamp renders as empty', () {
    expect(sessionTimeLabel(0, now: now), '');
    expect(sessionTimeLabel(-1, now: now), '');
  });

  test('today shows the clock', () {
    final morning = DateTime(2026, 11, 17, 8, 5);
    expect(sessionTimeLabel(morning.millisecondsSinceEpoch, now: now), '08:05');
  });

  test('yesterday is prefixed', () {
    final yesterday = DateTime(2026, 11, 16, 22, 5);
    expect(
      sessionTimeLabel(yesterday.millisecondsSinceEpoch, now: now),
      '昨天 22:05',
    );
  });

  test('earlier this week falls back to month and day', () {
    final tuesday = DateTime(2026, 11, 13, 18, 40);
    expect(
      sessionTimeLabel(tuesday.millisecondsSinceEpoch, now: now),
      '11月13日',
    );
  });

  test('another year includes the year', () {
    final lastYear = DateTime(2025, 12, 30, 18, 0);
    expect(
      sessionTimeLabel(lastYear.millisecondsSinceEpoch, now: now),
      '2025年12月30日',
    );
  });
}
