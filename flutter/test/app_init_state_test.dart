import 'package:claudroid_ui/bridge/api.dart';
import 'package:claudroid_ui/bridge/events.dart';
import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/models/remote.dart';
import 'package:claudroid_ui/ui/app.dart';
import 'package:flutter_test/flutter_test.dart';

const _init = InitState(
  settings: AppSettings(),
  labels: ModelLabels(),
  engine: EngineState(),
  sessions: <ChatSession>[],
);

void main() {
  test('app.init keeps hosts that arrived on the event channel first', () {
    // The event channel sends `ready` (with hosts) before `app.init` returns;
    // the old code replaced the whole state and the host list stayed empty
    // until the next manager change.
    const current = AppState(
      hosts: RemoteHostsState(
        hosts: [
          RemoteHost(id: 'h1', name: '开发机', baseUrl: 'https://claude.example'),
        ],
      ),
    );

    final merged = mergeInitState(current, _init);

    expect(merged.hosts.hosts, hasLength(1));
    expect(merged.hosts.hosts.single.id, 'h1');
    expect(merged.ready, isTrue);
  });

  test('app.init still becomes the first state when no event arrived', () {
    final merged = mergeInitState(const AppState(), _init);

    expect(merged.hosts.hosts, isEmpty);
    expect(merged.ready, isTrue);
    expect(merged.sessions, isEmpty);
  });
}
