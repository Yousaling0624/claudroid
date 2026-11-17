/// The compact timestamp used by the session drawer and the home "continue"
/// card: clock for today, 昨天 for yesterday, then month/day (with year when
/// it is not the current one). [now] is injectable for tests.
String sessionTimeLabel(int millis, {DateTime? now}) {
  if (millis <= 0) return '';
  final current = now ?? DateTime.now();
  final time = DateTime.fromMillisecondsSinceEpoch(millis);
  final days = _dayOf(current).difference(_dayOf(time)).inDays;
  final clock = '${_two(time.hour)}:${_two(time.minute)}';
  if (days <= 0) return clock;
  if (days == 1) return '昨天 $clock';
  if (days < 7) return '${time.month}月${time.day}日';
  if (time.year != current.year) {
    return '${time.year}年${time.month}月${time.day}日';
  }
  return '${time.month}月${time.day}日';
}

DateTime _dayOf(DateTime value) => DateTime(value.year, value.month, value.day);

String _two(int value) => value.toString().padLeft(2, '0');
