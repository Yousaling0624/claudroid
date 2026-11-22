/// Thin Dart wrapper over the `app.claudroid/bridge` MethodChannel.
///
/// Every call returns a `Map` with `ok` and either a payload or `error`
/// (`docs/BRIDGE_CONTRACT.md` §6). Complex values cross the channel as JSON
/// strings and are decoded here, so the rest of the UI only ever sees models.
library;

import 'dart:convert';

import 'package:flutter/services.dart';

import '../models/backup.dart';
import '../models/model_info.dart';
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

  Future<List<String>> suggestions({int count = 3, String context = ''}) async {
    final map = await _call('app.suggestions', {
      'count': count,
      'context': context,
    });
    return _decodeStringList(map['itemsJson'] as String?);
  }

  Future<AppInfo> appInfo() async {
    final map = await _call('app.info');
    return AppInfo(
      versionName: map['versionName'] as String? ?? '',
      versionCode: (map['versionCode'] as num?)?.toInt() ?? 0,
    );
  }

  Future<ReleaseInfo> checkRelease() async {
    final map = await _call('app.release');
    return ReleaseInfo.fromJson(_decodeMap(map['releaseJson'] as String?));
  }

  Future<void> openUrl(String url) => _call('app.openUrl', {'url': url});

  /// Opens the system share sheet with plain text, e.g. into a note app.
  Future<void> shareText(String text) => _call('share.text', {'text': text});

  /// Opens the system share sheet with a PNG the UI rendered.
  Future<void> shareImage(Uint8List bytes) =>
      _call('share.image', {'bytes': bytes});

  Future<({List<ProviderPreset> presets, String? activeId})>
      listProviders() async {
    final map = await _call('providers.list');
    return (
      presets: _decodeList(map['presetsJson'] as String?)
          .map(ProviderPreset.fromJson)
          .toList(growable: false),
      activeId: map['activeId'] as String?,
    );
  }

  Future<({AppSettings settings, ModelLabels labels})> applyProvider(
    String presetId,
  ) async {
    final map = await _call('providers.apply', {'presetId': presetId});
    return (
      settings: AppSettings.fromJsonString(map['settingsJson'] as String?),
      labels: ModelLabels.fromJson(_decodeMap(map['labelsJson'] as String?)),
    );
  }

  Future<({List<String> models, List<ModelInfo> details, String endpoint})>
      fetchModels() async {
    final map = await _call('models.list');
    final models = _decodeStringList(map['modelsJson'] as String?);
    final details = _decodeList(
      map['modelDetailsJson'] as String?,
    ).map(ModelInfo.fromJson).toList(growable: false);
    return (
      models: models,
      // An older host sends only `modelsJson`; keep the picker usable by
      // synthesising one ID-only entry per model.
      details: details.isEmpty
          ? [for (final id in models) ModelInfo.idOnly(id)]
          : details,
      endpoint: map['endpoint'] as String? ?? '',
    );
  }

  Future<({String endpoint, int count})> testConnection() async {
    final map = await _call('models.test');
    return (
      endpoint: map['endpoint'] as String? ?? '',
      count: (map['count'] as num?)?.toInt() ?? 0,
    );
  }

  /// Opens the system document picker in the host Activity. Returns workspace
  /// relative paths (e.g. `attachments/foo.zip`); empty when cancelled.
  Future<List<String>> pickAttachments() async {
    final map = await _call('attachments.pick');
    if (map['cancelled'] == true) return const [];
    return _decodeStringList(map['relativePathsJson'] as String?);
  }

  Future<List<String>> readLogs() async =>
      _decodeStringList((await _call('logs.read'))['linesJson'] as String?);

  Future<void> clearLogs() => _call('logs.clear');

  // -- workspace -----------------------------------------------------------

  Future<WorkspaceListing> listWorkspace([String path = '']) async {
    final map = await _call('workspace.list', {'path': path});
    return WorkspaceListing(
      path: map['path'] as String? ?? '',
      entries: _decodeList(map['entriesJson'] as String?)
          .map(WorkspaceEntry.fromJson)
          .toList(growable: false),
      totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
    );
  }

  Future<WorkspacePreview> previewWorkspace(String path) async {
    final map = await _call('workspace.preview', {'path': path});
    final raw = map['bytes'];
    return WorkspacePreview(
      path: map['path'] as String? ?? path,
      name: map['name'] as String? ?? '',
      kind: WorkspaceKind.fromWire(map['kind'] as String?),
      mime: map['mime'] as String? ?? '',
      size: (map['size'] as num?)?.toInt() ?? 0,
      modifiedAt: (map['modifiedAt'] as num?)?.toInt() ?? 0,
      text: map['text'] as String?,
      bytes: raw is Uint8List ? raw : null,
      truncated: map['truncated'] as bool? ?? false,
      tooLarge: map['tooLarge'] as bool? ?? false,
    );
  }

  Future<void> deleteWorkspaceFile(String path) =>
      _call('workspace.delete', {'path': path});

  Future<void> openWorkspaceFile(String path) =>
      _call('workspace.open', {'path': path});

  Future<void> shareWorkspaceFile(String path) =>
      _call('workspace.share', {'path': path});

  /// The switchable workspaces and which one the CLI currently sees as `/work`.
  Future<WorkspaceSpaces> listWorkspaces() async => WorkspaceSpaces.fromJsonString(
        (await _call('workspace.spaces'))['workspacesJson'] as String?,
      );

  /// Creates a workspace and switches to it.
  Future<WorkspaceSpaces> createWorkspace(String name) async =>
      WorkspaceSpaces.fromJsonString(
        (await _call('workspace.create', {'name': name}))['workspacesJson']
            as String?,
      );

  Future<WorkspaceSpaces> selectWorkspace(String id) async =>
      WorkspaceSpaces.fromJsonString(
        (await _call('workspace.select', {'id': id}))['workspacesJson']
            as String?,
      );

  Future<WorkspaceSpaces> renameWorkspace(String id, String name) async =>
      WorkspaceSpaces.fromJsonString(
        (await _call('workspace.rename', {'id': id, 'name': name}))[
            'workspacesJson'] as String?,
      );

  Future<WorkspaceSpaces> removeWorkspace(String id) async =>
      WorkspaceSpaces.fromJsonString(
        (await _call('workspace.remove', {'id': id}))['workspacesJson']
            as String?,
      );

  // -- terminal ------------------------------------------------------------

  Future<TerminalInfo> terminalInfo() async {
    final map = await _call('terminal.info');
    return TerminalInfo(
      label: map['label'] as String?,
      issue: map['issue'] as String?,
    );
  }

  /// Starts (or replaces) the persistent interactive shell.
  Future<void> terminalStart({required int rows, required int cols}) =>
      _call('terminal.start', {'rows': rows, 'cols': cols});

  /// Writes raw terminal input; ordering is preserved by the platform channel.
  Future<void> terminalWrite(String data) =>
      _call('terminal.write', {'data': data});

  Future<void> terminalResize({required int rows, required int cols}) =>
      _call('terminal.resize', {'rows': rows, 'cols': cols});

  Future<void> terminalRun(String command, {String cwdRel = ''}) =>
      _call('terminal.run', {'command': command, 'cwdRel': cwdRel});

  Future<void> terminalStop() => _call('terminal.stop');

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

  Future<void> engineUpdateCli() => _call('engine.updateCli');

  Future<void> engineCheckUpdate({bool quiet = true}) =>
      _call('engine.checkUpdate', {'quiet': quiet});

  Future<bool> engineProbeRoot() async =>
      (await _call('engine.probeRoot'))['granted'] as bool? ?? false;

  Future<String> engineSelfTest() async =>
      (await _call('engine.selfTest'))['report'] as String? ?? '';

  Future<String> engineRootProbeReport() async =>
      (await _call('engine.rootProbeReport'))['report'] as String? ?? '';

  Future<({bool changed, bool skipped, String dns, String message})>
      refreshNetwork() async {
    final map = await _call('engine.refreshNetwork');
    return (
      changed: map['changed'] as bool? ?? false,
      skipped: map['skipped'] as bool? ?? false,
      dns: map['dns'] as String? ?? '',
      message: map['message'] as String? ?? '',
    );
  }

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

  // -- backup --------------------------------------------------------------

  Future<BackupConfig> backupConfig() async => BackupConfig.fromJsonString(
        (await _call('backup.config'))['configJson'] as String?,
      );

  Future<BackupConfig> saveBackupConfig(BackupConfig config) async =>
      BackupConfig.fromJsonString(
        (await _call('backup.saveConfig', {
          'configJson': config.toJsonString(),
        }))['configJson'] as String?,
      );

  Future<BackupStatus> backupStatus() async => BackupStatus.fromJsonString(
        (await _call('backup.status'))['statusJson'] as String?,
      );

  /// Probes the target (HEAD / GET depending on the kind). The host returns a
  /// ready-to-show Chinese message; [config] tests the current edits even
  /// before the next save round-trips.
  Future<String> testBackupTarget([BackupConfig? config]) async {
    final map = await _call('backup.test', {
      if (config != null) 'configJson': config.toJsonString(),
    });
    return map['message'] as String? ?? '';
  }

  Future<BackupResult> runBackup() async => BackupResult.fromJsonString(
        (await _call('backup.run'))['resultJson'] as String?,
      );

  Future<RestoreResult> restoreBackup({bool restoreSettings = false}) async =>
      RestoreResult.fromJsonString(
        (await _call('backup.restore', {
          'restoreSettings': restoreSettings,
        }))['resultJson'] as String?,
      );

  /// The host returns `cancelled: true` when the user backs out of the system
  /// document picker; that is not an error.
  Future<({bool cancelled, int bytes})> exportBackup() async {
    final map = await _call('backup.export');
    return (
      cancelled: map['cancelled'] as bool? ?? false,
      bytes: (map['bytes'] as num?)?.toInt() ?? 0,
    );
  }

  Future<({bool cancelled, RestoreResult result})> importBackup() async {
    final map = await _call('backup.import');
    return (
      cancelled: map['cancelled'] as bool? ?? false,
      result: RestoreResult.fromJsonString(map['resultJson'] as String?),
    );
  }

  Future<void> cancelBackup() => _call('backup.cancel');

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

  /// Branches the conversation at [messageId]; returns the new session id.
  Future<String> forkChat(String sessionId, String messageId) async {
    final map = await _call('chat.fork', {
      'sessionId': sessionId,
      'messageId': messageId,
    });
    return map['sessionId'] as String? ?? '';
  }

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

List<String> _decodeStringList(String? source) {
  if (source == null || source.isEmpty) return const [];
  final decoded = jsonDecode(source);
  if (decoded is! List) return const [];
  return decoded.map((e) => '$e').toList(growable: false);
}
