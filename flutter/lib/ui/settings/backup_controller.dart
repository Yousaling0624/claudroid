import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../bridge/api.dart';
import '../../bridge/events.dart';
import '../../models/backup.dart';
import 'settings_labels.dart';

/// State for the "备份与恢复" page. The settings root owns one instance so the
/// root row summary and the page share a single subscription to
/// `app.claudroid/backup` — two `EventChannel` objects with the same name
/// would replace each other's platform handler.
class BackupController extends ChangeNotifier {
  BackupController({required this.bridge, required this.onToast});

  final Bridge bridge;
  final void Function(String text, {bool isError}) onToast;

  BackupConfig config = const BackupConfig();
  BackupStatus status = const BackupStatus();

  bool testing = false;
  String testMessage = '';
  bool testOk = false;

  /// True while a UI action awaits its method-channel reply; the host's
  /// `status` usually turns `Working` a moment later.
  bool busy = false;
  String pendingAction = '';

  bool _started = false;
  bool _disposed = false;
  int _saveSeq = 0;
  StreamSubscription<BackupEvent>? _events;

  /// A transport is already running; every action and field edit that could
  /// collide with it stays disabled.
  bool get active => busy || status.working;

  bool get cloudReady => config.cloudReady;

  List<String> get issues => config.issues;

  String get requirementText => backupRequirementText(config);

  /// Loads the saved config and status once, then follows status events.
  /// Failures are silent: an older host simply leaves the defaults in place.
  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    _events = BackupEvents().events.listen(_onEvent, onError: (_) {});
    await Future.wait([_loadConfig(), _loadStatus()]);
  }

  Future<void> _loadConfig() async {
    try {
      final loaded = await bridge.backupConfig();
      if (_disposed) return;
      config = loaded;
      notifyListeners();
    } catch (_) {
      // Keep the documented defaults.
    }
  }

  Future<void> _loadStatus() async {
    try {
      final loaded = await bridge.backupStatus();
      if (_disposed) return;
      status = loaded;
      notifyListeners();
    } catch (_) {
      // Keep the idle defaults.
    }
  }

  void _onEvent(BackupEvent event) {
    if (_disposed) return;
    if (event is BackupStatusEvent) {
      status = event.status;
      notifyListeners();
    }
  }

  /// Persists one edit immediately. The UI keeps the typed value even when the
  /// save fails, so the user can keep filling fields; the next edit retries.
  Future<void> saveConfig(BackupConfig next) async {
    if (_disposed) return;
    config = next;
    testMessage = '';
    final seq = ++_saveSeq;
    notifyListeners();
    try {
      final saved = await bridge.saveBackupConfig(next);
      if (_disposed || seq != _saveSeq) return;
      config = saved;
      notifyListeners();
    } catch (error) {
      if (_disposed || seq != _saveSeq) return;
      onToast(
        '备份目标没有保存成功（$error）。再改任一字段会自动重试。',
        isError: true,
      );
    }
  }

  void chooseKind(BackupTargetKind kind) {
    if (config.kind == kind) return;
    unawaited(saveConfig(config.copyWith(kind: kind)));
  }

  Future<void> testConnection() async {
    if (testing || active || !cloudReady) return;
    testing = true;
    testMessage = '';
    notifyListeners();
    try {
      final message = await bridge.testBackupTarget(config);
      if (_disposed) return;
      testOk = true;
      testMessage = message.isEmpty ? '连接成功，目标可达。' : message;
      onToast(testMessage);
    } catch (error) {
      if (_disposed) return;
      testOk = false;
      testMessage = '连接失败：$error。${backupRetryHint(config)}';
      onToast(testMessage, isError: true);
    } finally {
      if (!_disposed) {
        testing = false;
        notifyListeners();
      }
    }
  }

  Future<void> runBackup() async {
    if (active || !cloudReady) return;
    busy = true;
    pendingAction = 'backup';
    testMessage = '';
    notifyListeners();
    try {
      final result = await bridge.runBackup();
      if (_disposed) return;
      final target =
          result.target.isEmpty ? backupTargetLabel(config) : result.target;
      onToast('已备份 ${formatBytes(result.bytes)} 到 $target');
    } catch (error) {
      if (!_disposed) {
        onToast('备份失败：$error。${backupRetryHint(config)}', isError: true);
      }
    } finally {
      if (!_disposed) {
        busy = false;
        pendingAction = '';
        notifyListeners();
      }
      unawaited(_loadStatus());
    }
  }

  Future<void> restore({required bool restoreSettings}) async {
    if (active) return;
    busy = true;
    pendingAction = 'restore';
    notifyListeners();
    try {
      final result = await bridge.restoreBackup(
        restoreSettings: restoreSettings,
      );
      if (_disposed) return;
      onToast(restoreResultSummary(result));
    } catch (error) {
      if (!_disposed) {
        onToast('恢复失败：$error。${backupRetryHint(config)}', isError: true);
      }
    } finally {
      if (!_disposed) {
        busy = false;
        pendingAction = '';
        notifyListeners();
      }
      unawaited(_loadStatus());
    }
  }

  Future<void> exportFile() async {
    if (active) return;
    busy = true;
    pendingAction = 'export';
    notifyListeners();
    try {
      final result = await bridge.exportBackup();
      if (_disposed) return;
      // The user backed out of the document picker; that is not an error.
      if (result.cancelled) return;
      onToast('已导出备份文件（${formatBytes(result.bytes)}）');
    } catch (error) {
      if (!_disposed) {
        onToast('导出失败：$error。确认存储空间后重试。', isError: true);
      }
    } finally {
      if (!_disposed) {
        busy = false;
        pendingAction = '';
        notifyListeners();
      }
      unawaited(_loadStatus());
    }
  }

  Future<void> importFile() async {
    if (active) return;
    busy = true;
    pendingAction = 'import';
    notifyListeners();
    try {
      final result = await bridge.importBackup();
      if (_disposed) return;
      if (result.cancelled) return;
      onToast(restoreResultSummary(result.result));
    } catch (error) {
      if (!_disposed) {
        onToast(
          '导入失败：$error。确认选择的是 Claudroid 备份 ZIP 后重试。',
          isError: true,
        );
      }
    } finally {
      if (!_disposed) {
        busy = false;
        pendingAction = '';
        notifyListeners();
      }
      unawaited(_loadStatus());
    }
  }

  Future<void> cancel() async {
    try {
      await bridge.cancelBackup();
      if (_disposed) return;
      onToast('已请求取消');
    } catch (error) {
      if (!_disposed) {
        onToast('取消没有生效（$error），稍后再试。', isError: true);
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _events?.cancel();
    _events = null;
    super.dispose();
  }
}
