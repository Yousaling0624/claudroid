import 'package:claudroid_ui/models/host_scope.dart';
import 'package:claudroid_ui/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

ChatSession _session(String title, String? hostId) =>
    ChatSession(id: title, title: title, hostId: hostId);

void main() {
  test('local scope keeps sessions without a host id', () {
    final sessions = [
      _session('local', null),
      _session('blank', ''),
      _session('remote-a', 'host-a'),
    ];
    expect(
      sessionsForHost(sessions, null).map((s) => s.title),
      ['local', 'blank'],
    );
    expect(sessionsForHost(sessions, '').map((s) => s.title), ['local', 'blank']);
  });

  test('a remote scope isolates each host', () {
    final sessions = [
      _session('local', null),
      _session('a', 'host-a'),
      _session('b', 'host-b'),
      _session('a2', 'host-a'),
    ];
    expect(
      sessionsForHost(sessions, 'host-a').map((s) => s.title),
      ['a', 'a2'],
    );
    expect(
      sessionsForHost(sessions, 'host-b').map((s) => s.title),
      ['b'],
    );
  });

  test('host ids are compared after trimming', () {
    expect(sameHost(' host-a ', 'host-a'), isTrue);
    expect(sameHost(null, ''), isTrue);
    expect(sameHost('host-a', 'host-b'), isFalse);
    expect(normalizeHostId('  '), isNull);
  });
}
