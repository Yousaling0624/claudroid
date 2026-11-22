import '../../models/backup.dart';
import '../../models/models.dart';
import '../../models/remote.dart';
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

/// The one action the engine page offers as its primary button. Updating the
/// CLI alone is the cheap path; a full reinstall stays reachable as a
/// secondary row on the same section.
enum EnginePrimaryAction { install, updateCli, reinstall }

/// Picks the primary action from the engine snapshot. `latestVersion` is only
/// trusted once the host has checked updates, so an empty value keeps the
/// reinstall path instead of inventing an upgrade.
EnginePrimaryAction enginePrimaryAction(EngineState engine) {
  final installed = engine.installedVersion;
  if (installed == null || installed.isEmpty) {
    return EnginePrimaryAction.install;
  }
  final latest = engine.latestVersion;
  if (latest != null && latest.isNotEmpty && latest != installed) {
    return EnginePrimaryAction.updateCli;
  }
  return EnginePrimaryAction.reinstall;
}

/// Label for [enginePrimaryAction]; names the target version when updating so
/// the button says what will change.
String enginePrimaryActionLabel(EngineState engine) {
  switch (enginePrimaryAction(engine)) {
    case EnginePrimaryAction.install:
      return '安装 Claude Code';
    case EnginePrimaryAction.updateCli:
      return '仅更新 CLI 到 ${engine.latestVersion}';
    case EnginePrimaryAction.reinstall:
      return '重装 / 修复容器';
  }
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

/// One-line status for the "备份与恢复" row. [now] is injectable for tests.
///
/// Priority: a running task, then a live error, then the auto-backup state
/// (auto needs a complete cloud target), then the last operation. With auto
/// off — or left on a local target — the row reads exactly as before.
String settingsBackupSummary(
  BackupStatus status,
  BackupConfig config, {
  DateTime? now,
}) {
  if (status.working) {
    final stage = status.stage.isEmpty
        ? '正在${backupActionLabel(status.action)}'
        : status.stage;
    return '$stage ${(status.fraction * 100).round()}%';
  }
  if ((status.error ?? '').isNotEmpty) return '备份异常，点击查看';
  final auto = config.auto;
  if (auto.enabled && config.kind != BackupTargetKind.local) {
    if (!config.cloudReady) return '自动备份待配置';
    final interval = autoIntervalLabel(auto.intervalHours);
    final lastWasBackup =
        status.lastAction.isEmpty || status.lastAction == 'backup';
    if (status.lastAt > 0 && lastWasBackup) {
      if (status.lastOk == false) return '自动备份异常，点击查看';
      final relative = backupRelativeTime(status.lastAt, now: now);
      if (relative.isNotEmpty) {
        return '自动备份 · $interval · $relative';
      }
    }
    return '自动备份 · $interval';
  }
  if (status.lastAt > 0) {
    final relative = backupRelativeTime(status.lastAt, now: now);
    if (relative.isNotEmpty) {
      if (config.kind == BackupTargetKind.local) {
        return '上次${backupActionLabel(status.lastAction)} · $relative';
      }
      return '${config.kind.label} · $relative';
    }
  }
  if (config.kind == BackupTargetKind.local) return '未配置';
  return '${config.kind.label} · 未备份';
}

/// One-line status for the "远程主机" row.
String settingsRemoteSummary(RemoteHostsState hosts) {
  if (hosts.hosts.isEmpty) return '未添加';
  final online = hosts.hosts.where((host) => host.online).length;
  final failed = hosts.hosts.where((host) => host.failed).length;
  if (failed > 0) return '$online/${hosts.hosts.length} 在线 · 有异常';
  final defaultHost = hosts.defaultHost;
  if (defaultHost != null) {
    return '${hosts.hosts.length} 台 · 默认 ${defaultHost.displayName}';
  }
  return '${hosts.hosts.length} 台 · $online 在线';
}

/// One-line status for the "关于与诊断" row.
String settingsAboutSummary(String appVersion, ReleaseInfo? release) {
  if (appVersion.isEmpty) return '版本、日志与诊断';
  if (release == null) return appVersion;
  return '$appVersion · ${releaseStatusLabel(release.status)}';
}
