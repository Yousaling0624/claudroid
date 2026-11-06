/// Thin Dart wrapper over the `app.claudroid/bridge` MethodChannel.
///
/// Every call returns a `Map` with `ok` and either a payload or `error`
/// (`docs/BRIDGE_CONTRACT.md` §6). Complex values cross the channel as JSON
/// strings and are decoded here, so the rest of the UI only ever sees models.
library;

import 'dart:convert';

import 'package:flutter/services.dart';

import '../models/models.dart';

const MethodChannel _channel = MethodChannel('app.claudroid/bridge');

/// Thrown for a `ok:false` reply so call sites can `catch` one type instead of
/// branching on maps everywhere.
class BridgeException implements Exception {
  BridgeException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// One decoded answer to `app.init`, also used as the first state the app
/// renders before the event stream delivers anything.
class InitState {
  const InitState({
    required this.settings,
    required this.labels,
    required this.engine,
    required this.sessions,
  });

  final AppSettings settings;
  final ModelLabels labels;
  final EngineState engine;
  final List<ChatSession> sessions;

  factory InitState.fromMap(Map<Object?, Object?> map) => InitState(
        settings: AppSettings.fromJsonString(map['settingsJson'] as String?),
        labels: ModelLabels.fromJson(
          _decodeMap(map['labelsJson'] as String?),
        ),
        engine: EngineState.fromJson(
          _decodeMap(map['engineJson'] as String?),
        ),
        sessions: _decodeList(map['sessionsJson'] as String?)
            .map(ChatSession.fromJson)
            .toList(growable: false),
      );
}

class Bridge {
  const Bridge();

  // -- plumbing ------------------------------------------------------------

  /// Sends [method] and unwraps `ok`. Throws [BridgeException] otherwise.
  Future<Map<Object?, Object?>> _call(
    String method, [
    Map<String, Object?> args = const {},
  ]) async {
    final result = await _channel.invokeMethod<Map<Object?, Object?>>(
      method,
      args,
    );
    if (result == null) {
      throw BridgeException('桥接层没有返回结果（$method）');
    }
    if (result['ok'] != true) {
      throw BridgeException(
        (result['error'] as String?) ?? '操作失败，请稍后重试（$method）',
      );
    }
    return result;
  }

  // -- app -----------------------------------------------------------------

  Future<InitState> init() async => InitState.fromMap(await _call('app.init'));

  Future<({AppSettings settings, ModelLabels labels})> updateSettings(
    AppSettings settings,
  ) async {
    final map = await _call('settings.update', {
      'settingsJson': settings.toJsonString(),
    });
    return (
      settings: AppSettings.fromJsonString(map['settingsJson'] as String?),
      labels: ModelLabels.fromJson(_decodeMap(map['labelsJson'] as String?)),
    );
  }

  Future<List<ChatSession>> listSessions() async =>
      _decodeList((await _call('sessions.list'))['sessionsJson'] as String?)
          .map(ChatSession.fromJson)
          .toList(growable: false);

  Future<ChatSession> newSession({String? model}) async {
    final map = await _call('sessions.new', {'model': model});
    return ChatSession.fromJson(_decodeMap(map['sessionJson'] as String?));
  }

  Future<void> deleteSession(String id) =>
      _call('sessions.delete', {'id': id});

  Future<void> renameSession(String id, String title) =>
      _call('sessions.rename', {'id': id, 'title': title});

  // -- engine --------------------------------------------------------------

  Future<void> engineBootstrap() => _call('engine.bootstrap');

  Future<void> engineInstall({bool force = false}) =>
      _call('engine.install', {'force': force});

  Future<void> engineCheckUpdate({bool quiet = true}) =>
      _call('engine.checkUpdate', {'quiet': quiet});

  Future<bool> engineProbeRoot() async =>
      (await _call('engine.probeRoot'))['granted'] as bool? ?? false;

  Future<String> engineSelfTest() async =>
      (await _call('engine.selfTest'))['report'] as String? ?? '';

  Future<String> engineRootProbeReport() async =>
      (await _call('engine.rootProbeReport'))['report'] as String? ?? '';

  Future<({int jail, int workspace, int sessions})> engineStorageUsage() async {
    final map = await _call('engine.storageUsage');
    return (
      jail: (map['jailBytes'] as num?)?.toInt() ?? 0,
      workspace: (map['workspaceBytes'] as num?)?.toInt() ?? 0,
      sessions: (map['sessionBytes'] as num?)?.toInt() ?? 0,
    );
  }

  Future<void> engineUninstall({bool deleteWorkspace = false}) =>
      _call('engine.uninstall', {'deleteWorkspace': deleteWorkspace});

  Future<void> engineCancel() => _call('engine.cancel');

  // -- chat ----------------------------------------------------------------

  Future<ChatState> attach(String sessionId) async {
    final map = await _call('chat.attach', {'sessionId': sessionId});
    return ChatState.fromJson(_decodeMap(map['chatJson'] as String?));
  }

  /// `false` means a turn is already running and the text was not queued.
  Future<bool> send(String sessionId, String text) async {
    final map = await _call('chat.send', {'sessionId': sessionId, 'text': text});
    return map['accepted'] as bool? ?? false;
  }

  /// `false` means [messageId] no longer exists in the transcript.
  Future<bool> resendFrom(String sessionId, String messageId, String text) async {
    final map = await _call('chat.resendFrom', {
      'sessionId': sessionId,
      'messageId': messageId,
      'text': text,
    });
    return map['accepted'] as bool? ?? false;
  }

  Future<void> regenerate(String sessionId, String messageId) =>
      _call('chat.regenerate', {'sessionId': sessionId, 'messageId': messageId});

  Future<void> stop(String sessionId) => _call('chat.stop', {'sessionId': sessionId});

  Future<void> clear(String sessionId) =>
      _call('chat.clear', {'sessionId': sessionId});

  Future<void> consumeTitleNotice(String sessionId) =>
      _call('chat.consumeTitleNotice', {'sessionId': sessionId});

  Future<void> markTitleEdited(String sessionId, String title) =>
      _call('chat.markTitleEdited', {'sessionId': sessionId, 'title': title});

  Future<void> regenerateTitle(String sessionId) =>
      _call('chat.regenerateTitle', {'sessionId': sessionId});

  /// Drops the controller but lets the running turn finish on the host.
  Future<void> forget(String sessionId) =>
      _call('chat.forget', {'sessionId': sessionId});

  /// Stops the turn and releases the controller.
  Future<void> close(String sessionId) =>
      _call('chat.close', {'sessionId': sessionId});
}

// ---------------------------------------------------------------------------
// JSON helpers shared with events.dart
// ---------------------------------------------------------------------------

Map<String, dynamic> _decodeMap(String? source) {
  if (source == null || source.isEmpty) return const {};
  final decoded = jsonDecode(source);
  return decoded is Map<String, dynamic> ? decoded : const {};
}

List<Map<String, dynamic>> _decodeList(String? source) {
  if (source == null || source.isEmpty) return const [];
  final decoded = jsonDecode(source);
  if (decoded is! List) return const [];
  return decoded.whereType<Map<String, dynamic>>().toList(growable: false);
}

/// Public aliases so `events.dart` can reuse the same decoding rules.
Map<String, dynamic> decodeJsonMap(String? source) => _decodeMap(source);

List<Map<String, dynamic>> decodeJsonList(String? source) => _decodeList(source);
