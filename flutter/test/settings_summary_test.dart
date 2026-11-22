import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/settings/settings_labels.dart';
import 'package:claudroid_ui/ui/settings/settings_summary.dart';
import 'package:flutter_test/flutter_test.dart';

const _anthropic = ProviderPreset(
  id: 'anthropic',
  label: 'Anthropic',
  flavor: 'ANTHROPIC',
  baseUrl: 'https://api.anthropic.com',
  authStyle: 'API_KEY',
  model: 'claude-sonnet-5-5',
);

void main() {
  group('provider summary', () {
    test('names the active preset and the default model', () {
      final settings = AppSettings({'model': 'claude-sonnet-5-5'});
      expect(
        settingsProviderSummary(settings, const [_anthropic], 'anthropic'),
        'Anthropic · claude-sonnet-5-5',
      );
    });

    test('falls back to custom and CLI default', () {
      expect(
        settingsProviderSummary(AppSettings(), const [], null),
        '自定义 · CLI 默认',
      );
    });
  });

  group('engine summary', () {
    test('working shows the stage and percent', () {
      expect(
        settingsEngineSummary(
          const EngineState(phase: 'Working', stage: '正在解压引擎', fraction: 0.42),
        ),
        '正在解压引擎 42%',
      );
    });

    test('working without a stage still shows a label', () {
      expect(
        settingsEngineSummary(const EngineState(phase: 'Working')),
        '正在准备引擎 0%',
      );
    });

    test('installed shows the version and runtime label', () {
      expect(
        settingsEngineSummary(
          const EngineState(installedVersion: '2.1.0', engineLabel: 'PRoot'),
        ),
        '已安装 v2.1.0 · PRoot',
      );
    });

    test('missing engine reports PRoot availability', () {
      expect(
        settingsEngineSummary(const EngineState(prootAvailable: true)),
        '未安装 · PRoot 可用',
      );
      expect(settingsEngineSummary(const EngineState()), '未安装');
    });

    test('error without an install reads as abnormal', () {
      expect(
        settingsEngineSummary(const EngineState(error: '启动失败')),
        '引擎异常，点击查看',
      );
    });
  });

  group('engine primary action', () {
    test('an uninstalled engine offers install', () {
      expect(
        enginePrimaryAction(const EngineState()),
        EnginePrimaryAction.install,
      );
      expect(enginePrimaryActionLabel(const EngineState()), '安装 Claude Code');
    });

    test('a newer latest version updates only the CLI', () {
      const engine = EngineState(
        installedVersion: '2.1.320',
        latestVersion: '2.1.330',
      );
      expect(enginePrimaryAction(engine), EnginePrimaryAction.updateCli);
      expect(enginePrimaryActionLabel(engine), '仅更新 CLI 到 2.1.330');
    });

    test('a matching or unknown latest version falls back to reinstall', () {
      const same = EngineState(
        installedVersion: '2.1.330',
        latestVersion: '2.1.330',
      );
      expect(enginePrimaryAction(same), EnginePrimaryAction.reinstall);
      expect(enginePrimaryActionLabel(same), '重装 / 修复容器');

      const unchecked = EngineState(installedVersion: '2.1.330');
      expect(enginePrimaryAction(unchecked), EnginePrimaryAction.reinstall);
      expect(enginePrimaryActionLabel(unchecked), '重装 / 修复容器');
    });
  });

  group('features summary', () {
    test('defaults enable four flags and auto effort', () {
      expect(settingsFeaturesSummary(AppSettings()), '4 项开启 · 推理 自动');
    });

    test('honours stored flags and effort', () {
      final settings = AppSettings({
        'agentTeams': true,
        'workflows': false,
        'autoMemory': false,
        'backgroundTasks': false,
        'extendedThinking': false,
        'effort': 'HIGH',
      });
      expect(settingsFeaturesSummary(settings), '1 项开启 · 推理 高');
    });
  });

  group('advanced summary', () {
    test('defaults to auto update and the CLI turn limit', () {
      expect(settingsAdvancedSummary(AppSettings()), '自动更新开 · 最大轮数 默认');
    });

    test('shows a stored turn limit and disabled auto update', () {
      final settings = AppSettings({'autoUpdate': false, 'maxTurns': 12});
      expect(settingsAdvancedSummary(settings), '自动更新关 · 最大轮数 12');
    });
  });

  group('about summary', () {
    test('falls back when the host has no version yet', () {
      expect(settingsAboutSummary('', null), '版本、日志与诊断');
    });

    test('adds the release status once checked', () {
      const release = ReleaseInfo(
        tag: 'v0.1.9',
        url: 'https://example.com',
        status: ReleaseStatus.updateAvailable,
      );
      expect(
        settingsAboutSummary('Claudroid 0.1.8（8）', release),
        'Claudroid 0.1.8（8） · 有新版本可下载',
      );
    });
  });

  test('formatBytes uses binary units', () {
    expect(formatBytes(0), '0 B');
    expect(formatBytes(1023), '1023 B');
    expect(formatBytes(1024), '1.0 KB');
    expect(formatBytes(1024 * 1024 * 3), '3.0 MB');
  });
}
