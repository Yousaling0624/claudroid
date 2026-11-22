import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../bridge/api.dart';
import '../../bridge/events.dart';
import '../../models/model_info.dart';
import '../../models/models.dart';
import '../../models/remote.dart';
import 'backup_controller.dart';
import 'settings_labels.dart';
import 'settings_summary.dart';

/// Shared state for the settings root and every subpage. The root owns one
/// instance; subpages receive it instead of keeping their own snapshots, so a
/// `settings` event or an in-flight engine action updates every open page.
class SettingsController extends ChangeNotifier {
  SettingsController({
    required this.bridge,
    required this.stateListenable,
    required this.onUpdateSettings,
    required this.onToast,
  }) {
    final state = stateListenable.value;
    settings = state.settings;
    labels = state.labels;
    engine = state.engine;
    hosts = state.hosts;
    stateListenable.addListener(_sync);
    backup.addListener(_onBackupChanged);
  }

  final Bridge bridge;
  final ValueListenable<AppState> stateListenable;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  /// Owned here so the backup page and the root row share one event-channel
  /// subscription; the page receives this controller like every other subpage.
  late final BackupController backup = BackupController(
    bridge: bridge,
    onToast: onToast,
  );

  late AppSettings settings;
  late ModelLabels labels;
  late EngineState engine;
  late RemoteHostsState hosts;

  bool busy = false;
  String storage = '';
  String selfTest = '';

  List<ProviderPreset> providers = const [];
  String? activeProviderId;
  bool providersLoading = false;

  bool modelsLoading = false;
  List<ModelInfo> models = const [];
  String? modelError;
  bool testingConnection = false;
  String connectionMessage = '';
  bool connectionOk = false;

  String _modelsCacheKey = '';
  String _modelsEndpoint = '';

  List<String> get modelList => [for (final model in models) model.id];

  String get modelsEndpoint => _modelsEndpoint;

  String appVersion = '';
  ReleaseInfo? release;
  bool checkingRelease = false;
  String? releaseError;

  bool rootProbing = false;
  String rootReport = '';

  bool networkRefreshing = false;
  String networkStatus = '';

  List<String> logs = const [];

  bool _disposed = false;

  String get providerSummary =>
      settingsProviderSummary(settings, providers, activeProviderId);
  String get engineSummary => settingsEngineSummary(engine);
  String get featuresSummary => settingsFeaturesSummary(settings);
  String get advancedSummary => settingsAdvancedSummary(settings);
  String get backupSummary =>
      settingsBackupSummary(backup.status, backup.config);
  String get hostsSummary => settingsRemoteSummary(hosts);
  String get aboutSummary => settingsAboutSummary(appVersion, release);

  /// Loads the pieces the root page shows as subtitles. Failures are silent:
  /// custom endpoints simply keep the provider picker hidden, and a missing
  /// version just leaves the about subtitle generic.
  Future<void> start() async {
    await Future.wait([
      loadProviders(),
      loadAppInfo(),
      refreshLogs(),
      backup.start(),
    ]);
  }

  Future<void> loadProviders() async {
    try {
      final result = await bridge.listProviders();
      if (_disposed) return;
      providers = result.presets;
      activeProviderId = result.activeId;
      notifyListeners();
    } catch (_) {
      // Custom endpoints keep the picker hidden.
    }
  }

  Future<void> applyProvider(ProviderPreset preset) async {
    if (providersLoading) return;
    providersLoading = true;
    notifyListeners();
    try {
      final result = await bridge.applyProvider(preset.id);
      if (_disposed) return;
      _dropModelsIfEndpointChanged(result.settings);
      settings = result.settings;
      labels = result.labels;
      activeProviderId = preset.id;
      notifyListeners();
    } catch (error) {
      if (!_disposed) {
        onToast('没能应用服务商预设（$error）', isError: true);
      }
    } finally {
      if (!_disposed) {
        providersLoading = false;
        notifyListeners();
      }
    }
  }

  Future<void> loadAppInfo() async {
    try {
      final info = await bridge.appInfo();
      if (_disposed) return;
      appVersion = 'Claudroid ${info.versionName}（${info.versionCode}）';
      notifyListeners();
    } catch (_) {
      // The about row stays generic when the host cannot report a version.
    }
  }

  Future<void> refreshLogs() async {
    try {
      final lines = await bridge.readLogs();
      if (_disposed) return;
      logs = lines;
      notifyListeners();
    } catch (_) {
      // A log read failure is not worth a banner on page open.
    }
  }

  void patch(Map<String, dynamic> patch) {
    if (_disposed) return;
    settings = settings.merge(patch);
    notifyListeners();
    unawaited(onUpdateSettings(patch));
  }

  // -- async engine actions ------------------------------------------------

  Future<void> _run(Future<void> Function() action, String failureHint) async {
    if (busy) return;
    busy = true;
    notifyListeners();
    try {
      await action();
    } catch (error) {
      if (!_disposed) onToast('$failureHint（$error）', isError: true);
    } finally {
      if (!_disposed) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> loadStorage() => _run(() async {
    final usage = await bridge.engineStorageUsage();
    if (_disposed) return;
    storage =
        '引擎 ${formatBytes(usage.jail)} · '
        '工作区 ${formatBytes(usage.workspace)} · '
        '会话 ${formatBytes(usage.sessions)}';
    notifyListeners();
  }, '读取存储占用失败，再点一次');

  Future<void> runSelfTest() => _run(() async {
    final report = await bridge.engineSelfTest();
    if (_disposed) return;
    selfTest = report;
    notifyListeners();
  }, '自检没能完成，稍后再试');

  Future<void> probeRoot() async {
    if (rootProbing) return;
    rootProbing = true;
    notifyListeners();
    try {
      final granted = await bridge.engineProbeRoot();
      final report = await bridge.engineRootProbeReport();
      if (_disposed) return;
      rootReport = [
        granted ? '已获得 root 授权' : '未获得 root 授权（PRoot 模式不受影响）',
        if (report.trim().isNotEmpty) report.trim(),
      ].join('\n\n');
      notifyListeners();
    } catch (error) {
      if (!_disposed) onToast('Root 探测失败（$error）', isError: true);
    } finally {
      if (!_disposed) {
        rootProbing = false;
        notifyListeners();
      }
    }
  }

  /// Returns the cached or freshly fetched details for the picker; `null`
  /// means the request failed (the error is in [modelError]).
  ///
  /// The cache follows the endpoint and credentials, so switching provider or
  /// editing the key never shows another endpoint's catalogue.
  Future<({List<ModelInfo> models, String endpoint})?> fetchModels({
    bool force = false,
  }) async {
    if (modelsLoading) return null;
    final key = _modelsCacheKeyNow();
    if (!force && models.isNotEmpty && key == _modelsCacheKey) {
      return (models: models, endpoint: _modelsEndpoint);
    }
    modelsLoading = true;
    modelError = null;
    connectionMessage = '';
    notifyListeners();
    try {
      final result = await bridge.fetchModels();
      if (_disposed) return null;
      models = result.details;
      _modelsEndpoint = result.endpoint;
      _modelsCacheKey = key;
      if (result.details.isEmpty) {
        connectionOk = false;
        connectionMessage = '已连接，但模型列表为空：${result.endpoint}。请确认端点与协议是否匹配。';
      }
      notifyListeners();
      if (result.details.isNotEmpty) {
        onToast('已拉取 ${result.details.length} 个模型');
      }
      return (models: models, endpoint: _modelsEndpoint);
    } catch (error) {
      if (_disposed) return null;
      // Keep the previous catalogue visible; only the error line changes.
      modelError = '拉取模型失败：$error';
      notifyListeners();
      return null;
    } finally {
      if (!_disposed) {
        modelsLoading = false;
        notifyListeners();
      }
    }
  }

  /// Writes a model field and, for the default model, clears the active tier
  /// so the raw model ID actually reaches the CLI.
  void selectModel(
    String key,
    String value, {
    bool clearActiveTier = false,
  }) {
    if (_disposed) return;
    final patch = <String, dynamic>{key: value};
    if (clearActiveTier) patch['activeTier'] = '';
    this.patch(patch);
    onToast('已选择 $value');
  }

  String _modelsCacheKeyNow() => _modelsKey(settings);

  String _modelsKey(AppSettings value) {
    final base = value.str('baseUrl');
    final auth = value.str('authStyle');
    final flavor = value.str('apiFlavor');
    final key = value.str('apiKey');
    return '$base|$auth|$flavor|${key.length}:${key.hashCode}';
  }

  void _dropModelsIfEndpointChanged(AppSettings next) {
    if (_modelsCacheKey.isEmpty || _modelsCacheKey == _modelsKey(next)) return;
    _modelsCacheKey = '';
    _modelsEndpoint = '';
    models = const [];
    modelError = null;
    connectionMessage = '';
  }

  Future<void> testConnection() async {
    if (testingConnection) return;
    testingConnection = true;
    modelError = null;
    connectionMessage = '';
    notifyListeners();
    try {
      final result = await bridge.testConnection();
      if (_disposed) return;
      connectionOk = result.count > 0;
      connectionMessage = result.count > 0
          ? '连接成功：${result.endpoint}，${result.count} 个模型'
          : '已连接，但模型列表为空：${result.endpoint}。请确认端点与协议是否匹配。';
      notifyListeners();
    } catch (error) {
      if (_disposed) return;
      connectionOk = false;
      connectionMessage = '连接失败：$error';
      notifyListeners();
    } finally {
      if (!_disposed) {
        testingConnection = false;
        notifyListeners();
      }
    }
  }

  Future<void> refreshNetwork() async {
    if (networkRefreshing) return;
    networkRefreshing = true;
    networkStatus = '';
    notifyListeners();
    try {
      final result = await bridge.refreshNetwork();
      if (_disposed) return;
      networkStatus = [
        result.message,
        if (result.skipped && result.dns.isNotEmpty) '当前 DNS：${result.dns}',
      ].join('\n');
      onToast(result.message, isError: result.skipped);
    } catch (error) {
      if (_disposed) return;
      networkStatus = '刷新网络配置失败：$error';
      onToast(networkStatus, isError: true);
    } finally {
      if (!_disposed) {
        networkRefreshing = false;
        notifyListeners();
      }
    }
  }

  Future<void> checkRelease() async {
    if (checkingRelease) return;
    checkingRelease = true;
    releaseError = null;
    notifyListeners();
    try {
      final result = await bridge.checkRelease();
      if (_disposed) return;
      release = result;
      notifyListeners();
    } catch (error) {
      if (_disposed) return;
      releaseError = '$error';
      notifyListeners();
    } finally {
      if (!_disposed) {
        checkingRelease = false;
        notifyListeners();
      }
    }
  }

  Future<void> installEngine({required bool force}) =>
      _run(() => bridge.engineInstall(force: force), '安装没能开始');

  Future<void> updateCli() =>
      _run(() => bridge.engineUpdateCli(), '更新 CLI 没能开始');

  Future<void> checkEngineUpdate({bool quiet = false}) =>
      _run(() => bridge.engineCheckUpdate(quiet: quiet), '检查更新失败');

  Future<void> cancelEngine() => _run(() => bridge.engineCancel(), '取消没能生效');

  Future<void> uninstall({required bool deleteWorkspace}) async {
    await _run(
      () => bridge.engineUninstall(deleteWorkspace: deleteWorkspace),
      '卸载失败，先检查引擎日志',
    );
    if (_disposed) return;
    storage = '';
    selfTest = '';
    notifyListeners();
  }

  void _sync() {
    if (_disposed) return;
    final state = stateListenable.value;
    _dropModelsIfEndpointChanged(state.settings);
    settings = state.settings;
    labels = state.labels;
    engine = state.engine;
    hosts = state.hosts;
    notifyListeners();
  }

  void _onBackupChanged() {
    if (!_disposed) notifyListeners();
  }

  /// Re-reads the host list after the page mutates it. The global `hosts` event
  /// also arrives, but awaiting this gives the page a fresh snapshot even when
  /// the event is delayed behind a reconnect.
  Future<void> refreshHosts() async {
    try {
      final next = await bridge.listHosts();
      if (_disposed) return;
      hosts = next;
      notifyListeners();
    } catch (_) {
      // The event channel will catch up; the page keeps its current list.
    }
  }

  @override
  void dispose() {
    _disposed = true;
    stateListenable.removeListener(_sync);
    backup.removeListener(_onBackupChanged);
    backup.dispose();
    super.dispose();
  }
}
