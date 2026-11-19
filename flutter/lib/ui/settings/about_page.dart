import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/models.dart';
import '../glass_controls.dart';
import '../glyphs.dart';
import '../press_guard.dart';
import 'logs_page.dart';
import 'settings_controller.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

class AboutPage extends StatelessWidget {
  const AboutPage({super.key, required this.controller});

  final SettingsController controller;

  Future<void> _openLogs(BuildContext context) async {
    await BaseNavigator.push<void>(
      context,
      LogsPage(bridge: controller.bridge, onToast: controller.onToast),
    );
    await controller.refreshLogs();
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '关于与诊断',
      listenable: controller,
      builder: (context) => [
        _aboutSection(context),
        _diagnosticsSection(context),
      ],
    );
  }

  Widget _aboutSection(BuildContext context) {
    final release = controller.release;
    final releaseStatus = release?.status;
    return generateSection(
      title: '应用',
      items: [
        if (controller.appVersion.isNotEmpty)
          SettingsInfoRow(label: '应用版本', value: controller.appVersion),
        const SettingsInfoRow(
          label: '后端',
          value: 'Claude Code CLI（官方 npm 发布物）',
        ),
        DecorationListItem(
          title: const Text('最新发布'),
          subtitle: Text(
            release == null
                ? (controller.checkingRelease ? '正在检查…' : '检查 GitHub 上的最新版本')
                : 'v${release.tag} · ${releaseStatusLabel(release.status)}',
          ),
          onPressed: release == null
              ? (controller.checkingRelease ? null : controller.checkRelease)
              : () => controller.bridge.openUrl(release.url),
          trailing: release == null
              ? DeferredDisable(
                  onPressed: controller.checkingRelease
                      ? null
                      : controller.checkRelease,
                  builder: (context, onPressed) => GlassTonalButton(
                    size: TonalButtonSize.compact,
                    label: controller.checkingRelease ? '检查中…' : '检查',
                    glyph: AppGlyphs.refresh,
                    onPressed: onPressed,
                  ),
                )
              : GlassTonalButton(
                  size: TonalButtonSize.compact,
                  label: releaseStatus == ReleaseStatus.updateAvailable
                      ? '下载'
                      : '发布页',
                  onPressed: () => controller.bridge.openUrl(release.url),
                ),
        ),
        if (controller.releaseError != null)
          SettingsNote(
            text:
                '无法连接 GitHub：${controller.releaseError}。可以重试，或直接打开项目的 Release 页。',
          ),
      ],
      footer: '接口、模型与引擎都在这台设备上；改动即时保存。',
    );
  }

  Widget _diagnosticsSection(BuildContext context) {
    return generateSection(
      title: '诊断',
      items: [
        SettingsValueRow(
          label: '引擎日志（${controller.logs.length}）',
          value: '完整日志、复制与排查',
          trailingGlyph: Glyphs.chevronForward,
          onPressed: () => _openLogs(context),
        ),
        SettingsValueRow(
          label: '存储占用',
          value: controller.storage.isEmpty ? '点击读取' : controller.storage,
          trailingGlyph: AppGlyphs.refresh,
          onPressed: controller.loadStorage,
        ),
      ],
    );
  }
}
