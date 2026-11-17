import '../../models/models.dart';
import 'settings_labels.dart';

/// One-line status for the "服务与模型" row.
String settingsProviderSummary(
  AppSettings settings,
  List<ProviderPreset> providers,
  String? activeProviderId,
) {
  var provider = '自定义';
  for (final preset in providers) {
    if (preset.id == activeProviderId) {
      provider = preset.label;
      break;
    }
  }
  final model = settings.str('model');
  return '$provider · ${model.isEmpty ? 'CLI 默认' : model}';
}

/// One-line status for the "Claude Code 引擎" row.
String settingsEngineSummary(EngineState engine) {
  if (engine.phase == 'Working') {
    final stage = engine.stage.isEmpty ? '正在准备引擎' : engine.stage;
    return '$stage ${(engine.fraction * 100).round()}%';
  }
  if (engine.error != null && engine.error!.isNotEmpty) {
    return '引擎异常，点击查看';
  }
  final installed = engine.installedVersion;
  if (installed != null && installed.isNotEmpty) {
    final label = engine.engineLabel.isEmpty ? '' : ' · ${engine.engineLabel}';
    return '已安装 v$installed$label';
  }
  return engine.prootAvailable ? '未安装 · PRoot 可用' : '未安装';
}

/// One-line status for the "功能与实验" row.
String settingsFeaturesSummary(AppSettings settings) {
  final enabled = featureFlagDefaults.entries
      .where((entry) => settings.flag(entry.key, entry.value))
      .length;
  final effort = enumLabel(effortLabels, settings.str('effort', 'AUTO'));
  return '$enabled 项开启 · 推理 $effort';
}

/// One-line status for the "高级" row.
String settingsAdvancedSummary(AppSettings settings) {
  final autoUpdate = settings.flag('autoUpdate', true);
  final maxTurns = settings.intOr('maxTurns');
  return '自动更新${autoUpdate ? '开' : '关'} · '
      '最大轮数 ${maxTurns == 0 ? '默认' : '$maxTurns'}';
}

/// One-line status for the "关于与诊断" row.
String settingsAboutSummary(String appVersion, ReleaseInfo? release) {
  if (appVersion.isEmpty) return '版本、日志与诊断';
  if (release == null) return appVersion;
  return '$appVersion · ${releaseStatusLabel(release.status)}';
}
