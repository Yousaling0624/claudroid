import 'dart:convert';

import 'package:claudroid_ui/bridge/events.dart';
import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/models/remote.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('hosts decode runtime phases, tunnel and the default id', () {
    final hosts = RemoteHostsState.fromJsonString(
      jsonEncode({
        'defaultHostId': 'h1',
        'hosts': [
          {
            'id': 'h1',
            'name': '开发机',
            'baseUrl': 'https://claude.example.com',
            'hostName': 'devbox',
            'platform': 'linux',
            'protocol': 1,
            'defaultCwd': '/home/me',
            'workspaceRoots': ['/home/me', '/srv'],
            'lastSeenAt': 1700000000000,
            'phase': 'online',
            'hasAccessToken': true,
            'tunnel': {
              'mode': 'named',
              'state': 'up',
              'url': 'https://claude.example.com',
              'changedAt': 1,
            },
          },
          {
            'id': 'h2',
            'name': '',
            'baseUrl': 'http://127.0.0.1:8787',
            'hostName': 'laptop',
            'phase': 'error',
            'error': '连接被拒绝',
            'tunnel': {},
          },
        ],
      }),
    );

    expect(hosts.hosts, hasLength(2));
    final host = hosts.byId('h1')!;
    expect(host.displayName, '开发机');
    expect(host.online, isTrue);
    expect(host.defaultCwd, '/home/me');
    expect(host.workspaceRoots, ['/home/me', '/srv']);
    expect(host.tunnel.up, isTrue);
    expect(host.tunnel.url, 'https://claude.example.com');
    expect(hosts.defaultHost?.id, 'h1');

    final fallback = hosts.byId('h2')!;
    expect(fallback.displayName, 'laptop');
    expect(fallback.failed, isTrue);
    expect(fallback.error, '连接被拒绝');
    expect(fallback.tunnel.state, 'off');
  });

  test('a corrupt hosts document degrades to an empty list', () {
    expect(RemoteHostsState.fromJsonString('{oops').hosts, isEmpty);
    expect(RemoteHostsState.fromJsonString('').defaultHostId, isNull);
  });

  test('hosts decode the permission mode and bypass capability', () {
    final host = RemoteHost.fromJson({
      'id': 'h1',
      'name': '开发机',
      'baseUrl': 'https://x',
      'permissionMode': 'bypassPermissions',
      'allowBypassPermissions': true,
    });
    expect(host.permissionMode, 'bypassPermissions');
    expect(host.allowBypassPermissions, isTrue);

    final roundTrip = RemoteHost.fromJson(host.toJson());
    expect(roundTrip.permissionMode, 'bypassPermissions');
    expect(roundTrip.allowBypassPermissions, isTrue);
    expect(host.toJson()['permissionMode'], 'bypassPermissions');
    expect(host.toJson()['allowBypassPermissions'], isTrue);
  });

  test('hosts accept the Claude Auto permission mode', () {
    final host = RemoteHost.fromJson({
      'id': 'h1',
      'name': '开发机',
      'baseUrl': 'https://x',
      'permissionMode': 'auto',
    });
    expect(host.permissionMode, 'auto');

    final roundTrip = RemoteHost.fromJson(host.toJson());
    expect(roundTrip.permissionMode, 'auto');
    expect(host.toJson()['permissionMode'], 'auto');
  });

  test('unknown permission modes and missing fields fall back safely', () {
    final unknown = RemoteHost.fromJson({
      'id': 'h1',
      'baseUrl': 'https://x',
      'permissionMode': 'somethingElse',
      'allowBypassPermissions': 'yes',
    });
    expect(unknown.permissionMode, 'default');
    expect(unknown.allowBypassPermissions, isFalse);

    // Hosts paired before these fields existed keep rendering.
    final legacy = RemoteHost.fromJson({'id': 'h2', 'baseUrl': 'https://x'});
    expect(legacy.permissionMode, 'default');
    expect(legacy.allowBypassPermissions, isFalse);
    expect(legacy.toJson()['permissionMode'], 'default');
    expect(legacy.toJson()['allowBypassPermissions'], isFalse);
  });

  test('sessions carry the host binding across the wire', () {
    final session = ChatSession.fromJson({
      'id': 's1',
      'title': '远端会话',
      'hostId': 'h1',
      'remoteCwd': '/srv/app',
      'hasContent': true,
    });
    expect(session.isRemote, isTrue);
    expect(session.hostId, 'h1');
    expect(session.remoteCwd, '/srv/app');

    final legacy = ChatSession.fromJson({'id': 's2', 'title': '本机'});
    expect(legacy.isRemote, isFalse);
  });

  test('the hosts global event patches AppState', () {
    final event = AppEvent.fromMap({
      'type': 'hosts',
      'hostsJson': jsonEncode({
        'defaultHostId': 'h1',
        'hosts': [
          {'id': 'h1', 'name': 'dev', 'baseUrl': 'https://x', 'phase': 'online'},
        ],
      }),
    });
    expect(event, isA<AppHostsEvent>());
    final next = event!.apply(const AppState());
    expect(next.hosts.hosts.single.displayName, 'dev');
    expect(next.hosts.defaultHostId, 'h1');
  });

  test('pending permissions fold, update and resolve', () {
    final requested = ChatEvent.fromMap({
      'type': 'permission',
      'requestId': 'req-1',
      'toolName': 'Bash',
      'inputJson': '{"command":"rm -rf build"}',
      'title': '运行清理命令',
      'expiresAt': 1700000000000,
    })!;
    final withOne = requested.apply(const ChatState())!;
    expect(withOne.pendingPermissions, hasLength(1));
    expect(withOne.pendingPermissions.single.summary, 'rm -rf build');
    expect(withOne.pendingPermissions.single.heading, '运行清理命令');

    final repeated = requested.apply(withOne)!;
    expect(repeated.pendingPermissions, hasLength(1));

    final resolved = ChatEvent.fromMap({
      'type': 'permissionResolved',
      'requestId': 'req-1',
      'allowed': true,
    })!;
    final cleared = resolved.apply(repeated)!;
    expect(cleared.pendingPermissions, isEmpty);
  });

  test('chat snapshots decode the permissions array', () {
    final state = ChatState.fromJson({
      'permissions': [
        {
          'requestId': 'r1',
          'toolName': 'Edit',
          'inputJson': '{"file_path":"/srv/a.ts"}',
          'expiresAt': 1,
        },
      ],
    });
    expect(state.pendingPermissions.single.summary, '/srv/a.ts');
  });

  test('remote listings compute the parent without leaving root', () {
    expect(
      const RemoteFileListing(path: '/home/me/src', entries: []).parent,
      '/home/me',
    );
    expect(const RemoteFileListing(path: '/', entries: []).parent, isNull);
    expect(const RemoteFileListing(path: '/home', entries: []).parent, '/');
  });
}
