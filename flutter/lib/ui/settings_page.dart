import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import 'glyphs.dart';
import 'palette.dart';
import 'settings/about_page.dart';
import 'settings/advanced_page.dart';
import 'settings/appearance_page.dart';
import 'settings/backup_page.dart';
import 'settings/engine_page.dart';
import 'settings/features_page.dart';
import 'settings/remote_hosts_page.dart';
import 'settings/service_models_page.dart';
import 'settings/settings_controller.dart';
import 'settings/settings_destination.dart';
import 'settings/settings_labels.dart';
import 'settings/settings_widgets.dart';
import 'workspace_page.dart';

/// Settings, grouped the way FlClash groups its own: a root page of icon rows
/// that open one subpage per topic. All rows read from one [SettingsController]
/// so engine progress and `settings` events update the root and every open
/// subpage together.
class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.bridge,
    required this.stateListenable,
    required this.onUpdateSettings,
    required this.onToast,
    this.destination = SettingsDestination.root,
  });

  final Bridge bridge;
  final ValueListenable<AppState> stateListenable;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  /// Opens one topic directly instead of the root list.
  final SettingsDestination destination;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late final SettingsController _controller = SettingsController(
    bridge: widget.bridge,
    stateListenable: widget.stateListenable,
    onUpdateSettings: widget.onUpdateSettings,
    onToast: widget.onToast,
  );

  @override
  void initState() {
    super.initState();
    unawaited(_controller.start());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return switch (widget.destination) {
      SettingsDestination.root => _buildRoot(context),
      SettingsDestination.serviceModels =>
        ServiceModelsPage(controller: _controller),
      SettingsDestination.engine => EnginePage(controller: _controller),
      SettingsDestination.remoteHosts =>
        RemoteHostsPage(controller: _controller),
      SettingsDestination.features => FeaturesPage(controller: _controller),
      SettingsDestination.appearance => AppearancePage(controller: _controller),
      SettingsDestination.advanced => AdvancedPage(controller: _controller),
      SettingsDestination.backup => BackupPage(controller: _controller),
      SettingsDestination.about => AboutPage(controller: _controller),
    };
  }

  Widget _buildRoot(BuildContext context) {
    return SettingsScaffold(
      title: '设置',
      listenable: _controller,
      builder: (context) => [
        SettingsGroup(
          title: '模型与引擎',
          first: true,
          children: [
            SettingsEntryRow(
              key: const ValueKey('settings-service'),
              glyph: AppGlyphs.key,
              title: '服务与模型',
              value: _controller.providerSummary,
              tone: SettingsTone.clay,
              pageBuilder: (_) => ServiceModelsPage(controller: _controller),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-engine'),
              glyph: AppGlyphs.cpu,
              title: 'Claude Code 引擎',
              value: _controller.engineSummary,
              tone: SettingsTone.clay,
              pageBuilder: (_) => EnginePage(controller: _controller),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-remote-hosts'),
              glyph: AppGlyphs.plug,
              title: '远程主机',
              value: _controller.hostsSummary,
              tone: SettingsTone.clay,
              pageBuilder: (_) => RemoteHostsPage(controller: _controller),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-features'),
              glyph: AppGlyphs.flask,
              title: '功能与实验',
              value: _controller.featuresSummary,
              tone: SettingsTone.clay,
              pageBuilder: (_) => FeaturesPage(controller: _controller),
            ),
          ],
        ),
        SettingsGroup(
          title: '应用',
          children: [
            SettingsEntryRow(
              key: const ValueKey('settings-appearance'),
              glyph: AppGlyphs.paintbrush,
              title: '外观',
              value: enumLabel(
                themeLabels,
                _controller.settings.intOr('themeMode'),
              ),
              tone: SettingsTone.honey,
              pageBuilder: (_) => AppearancePage(controller: _controller),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-advanced'),
              glyph: AppGlyphs.sliders,
              title: '高级',
              value: _controller.advancedSummary,
              tone: SettingsTone.honey,
              pageBuilder: (_) => AdvancedPage(controller: _controller),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-backup'),
              glyph: AppGlyphs.backup,
              title: '备份与恢复',
              value: _controller.backupSummary,
              tone: SettingsTone.honey,
              pageBuilder: (_) => BackupPage(controller: _controller),
            ),
          ],
        ),
        SettingsGroup(
          title: '其他',
          children: [
            SettingsEntryRow(
              key: const ValueKey('settings-workspace'),
              glyph: AppGlyphs.folder,
              title: '工作区',
              value: '文件与预览',
              tone: SettingsTone.sage,
              onTap: () => BaseNavigator.push<void>(
                context,
                WorkspacePage(bridge: widget.bridge, onToast: widget.onToast),
              ),
            ),
            SettingsEntryRow(
              key: const ValueKey('settings-about'),
              glyph: AppGlyphs.clipboardPulse,
              title: '关于与诊断',
              value: _controller.aboutSummary,
              tone: SettingsTone.sage,
              pageBuilder: (_) => AboutPage(controller: _controller),
            ),
          ],
        ),
        const ListFooter(text: '所有设置保存在本机，改动即时生效。'),
      ],
    );
  }
}
