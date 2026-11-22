import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../glass_controls.dart';
import '../glyphs.dart';
import '../press_guard.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_labels.dart';
import 'settings_summary.dart';
import 'settings_widgets.dart';

class EnginePage extends StatelessWidget {
  const EnginePage({super.key, required this.controller});

  final SettingsController controller;

  Future<void> _uninstall(BuildContext context) async {
    final deleteWorkspace = await confirmEngineUninstall(context);
    if (deleteWorkspace == null) return;
    await controller.uninstall(deleteWorkspace: deleteWorkspace);
  }

  Future<void> _copyPath(String path) async {
    await Clipboard.setData(ClipboardData(text: path));
    controller.onToast('安装位置已复制');
  }

  /// One maintenance action. Disabling is deferred so prism_ui never cancels
  /// a press animation inside the update that greys the button out
  /// (see [DeferredDisable]); the label already tells what is running.
  Widget _action({
    required String label,
    required bool enabled,
    required VoidCallback onPressed,
  }) {
    return DeferredDisable(
      onPressed: enabled ? onPressed : null,
      builder: (context, onPressed) => GlassTonalButton(
        size: TonalButtonSize.compact,
        label: label,
        onPressed: onPressed,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: 'Claude Code 引擎',
      listenable: controller,
      builder: (context) => [
        _statusSection(context),
        _runOptionsSection(context),
        _networkSection(context),
        _maintenanceSection(context),
        _dangerSection(context),
        _reportsSection(context),
      ],
    );
  }

  Widget _statusSection(BuildContext context) {
    final engine = controller.engine;
    final working = engine.phase == 'Working';
    return generateSection(
      title: '状态',
      items: [
        if (working)
          DecorationListItem(
            title: Text(engine.stage.isEmpty ? '正在准备引擎…' : engine.stage),
            subtitle: engine.detail.isEmpty ? null : Text(engine.detail),
            trailing: Text(
              '${(engine.fraction * 100).round()}%',
              style: context.listCaptionStyle,
            ),
          ),
        if (working)
          DecorationListItem(
            title: LinearProgressIndicator(value: engine.fraction),
            minVerticalPadding: 4,
          ),
        SettingsInfoRow(label: '已安装', value: engine.installedVersion ?? '未安装'),
        SettingsInfoRow(label: '官方最新', value: engine.latestVersion ?? '未知'),
        if (engine.installedVersion != null &&
            engine.latestVersion != null &&
            engine.latestVersion != engine.installedVersion)
          DecorationListItem(
            title: const Text('有可用更新'),
            trailing: TonalChip(
              label: '可更新',
              color: context.colorScheme.primaryContainer,
              foregroundColor: context.colorScheme.onPrimaryContainer,
            ),
          ),
        SettingsInfoRow(label: 'CPU 架构', value: engine.arch ?? '未知'),
        if ((engine.jailPath ?? '').isEmpty)
          const SettingsInfoRow(label: '安装位置', value: '未设置')
        else
          SettingsCopyRow(
            label: '安装位置',
            value: engine.jailPath!,
            onPressed: () => _copyPath(engine.jailPath!),
          ),
        SettingsInfoRow(label: '运行方式', value: engine.engineLabel),
        SettingsInfoRow(
          label: 'PRoot 组件',
          value: engine.prootAvailable ? '可用' : '缺失',
        ),
        SettingsInfoRow(
          label: 'Root 授权',
          value: engine.rootGranted ? '已授权' : '未授权',
        ),
      ],
    );
  }

  Widget _runOptionsSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '运行选项',
      items: [
        SettingsPickerRow<String>(
          label: '引擎模式',
          value: enumLabel(
            engineModeLabels,
            settings.str('engineMode', 'AUTO'),
          ),
          options: [
            for (final entry in engineModeLabels.entries)
              PopupMenuItem(
                label: entry.value,
                checked: settings.str('engineMode') == entry.key,
                onPressed: () => controller.patch({'engineMode': entry.key}),
              ),
          ],
        ),
        SettingsPickerRow<String>(
          label: '权限模式',
          value: enumLabel(
            permissionLabels,
            settings.str('permissionMode', 'BYPASS'),
          ),
          options: [
            for (final entry in permissionLabels.entries)
              PopupMenuItem(
                label: entry.value,
                checked: settings.str('permissionMode') == entry.key,
                onPressed: () =>
                    controller.patch({'permissionMode': entry.key}),
              ),
          ],
        ),
      ],
    );
  }

  Widget _networkSection(BuildContext context) {
    final proxy = controller.settings.str('networkProxy');
    return generateSection(
      title: '网络',
      footer: '留空自动使用系统代理。Clash 等按应用分流的 VPN 不会覆盖本应用：'
          '把本应用加入 VPN 的分流列表，或在这里填它的 HTTP 代理端口'
          '（Clash 默认 7890）。仅支持 HTTP/HTTPS。',
      items: [
        SettingsValueRow(
          label: '网络代理',
          value: networkProxyLabel(proxy),
          onPressed: () => editSettingText(
            context,
            controller,
            title: '网络代理',
            key: 'networkProxy',
            current: proxy,
            hint: 'http://127.0.0.1:7890',
            emptyLabel: '自动',
            keyboardType: TextInputType.url,
            singleLine: true,
            validator: validateNetworkProxy,
          ),
        ),
        SettingsActionRow(
          label: controller.networkRefreshing ? '正在刷新网络配置…' : '刷新网络配置',
          glyph: AppGlyphs.refresh,
          busy: controller.networkRefreshing,
          onPressed: controller.busy || controller.networkRefreshing
              ? null
              : controller.refreshNetwork,
        ),
      ],
    );
  }

  Widget _maintenanceSection(BuildContext context) {
    final engine = controller.engine;
    final working = engine.phase == 'Working';
    final busy = controller.busy;
    final action = enginePrimaryAction(engine);
    return generateSection(
      title: '维护',
      items: [
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 8),
          child: Row(
            children: [
              Expanded(
                child: DeferredDisable(
                  onPressed: busy
                      ? null
                      : () => switch (action) {
                            EnginePrimaryAction.install =>
                              controller.installEngine(force: false),
                            EnginePrimaryAction.updateCli =>
                              controller.updateCli(),
                            EnginePrimaryAction.reinstall =>
                              controller.installEngine(force: true),
                          },
                  builder: (context, onPressed) => GlassTonalButton(
                    size: TonalButtonSize.section,
                    label: enginePrimaryActionLabel(engine),
                    onPressed: onPressed,
                  ),
                ),
              ),
            ],
          ),
        ),
        // Only the CLI moves between versions; keep the full reinstall within
        // reach without competing with the primary button.
        if (action == EnginePrimaryAction.updateCli)
          SettingsActionRow(
            label: '重装 / 修复容器（完整）',
            glyph: AppGlyphs.refresh,
            onPressed:
                busy ? null : () => controller.installEngine(force: true),
          ),
        SettingsActionRow(
          label: '检查更新',
          glyph: AppGlyphs.refresh,
          onPressed: busy
              ? null
              : () => controller.checkEngineUpdate(quiet: false),
        ),
        SettingsActionRow(
          label: '运行自检',
          glyph: AppGlyphs.checklist,
          onPressed: busy ? null : controller.runSelfTest,
        ),
        SettingsActionRow(
          label: controller.rootProbing ? 'Root 探测中…' : 'Root 探测',
          glyph: AppGlyphs.cpu,
          busy: controller.rootProbing,
          onPressed: busy || controller.rootProbing
              ? null
              : controller.probeRoot,
        ),
        if (working)
          SettingsActionRow(
            label: '取消当前操作',
            glyph: Glyphs.close,
            onPressed: busy ? null : controller.cancelEngine,
          ),
      ],
    );
  }

  Widget _dangerSection(BuildContext context) {
    final busy = controller.busy;
    return generateSection(
      title: '危险操作',
      items: [
        DecorationListItem(
          title: const Text('卸载引擎'),
          subtitle: const Text('删除 CLI 与运行时；可选择是否同时删除工作区。'),
          trailing: TonalButton(
            size: TonalButtonSize.compact,
            label: '卸载',
            onPressed: busy ? null : () => _uninstall(context),
          ),
        ),
      ],
    );
  }

  Widget _reportsSection(BuildContext context) {
    final engine = controller.engine;
    final selfTest = controller.selfTest.isNotEmpty
        ? controller.selfTest
        : (engine.selfTest ?? '');
    return generateSection(
      title: '报告',
      items: [
        if (controller.networkStatus.isNotEmpty)
          SettingsNote(text: controller.networkStatus),
        if (engine.error != null && engine.error!.isNotEmpty)
          DecorationListItem(
            invalid: true,
            title: const Text('引擎错误'),
            subtitle: Text(engine.error!),
            trailing: _action(
              label: '重装',
              enabled: !controller.busy,
              onPressed: () => controller.installEngine(force: true),
            ),
          ),
        if (controller.rootReport.isNotEmpty)
          DecorationListItem(
            title: const Text('Root 探测报告'),
            subtitle: SelectableText(
              controller.rootReport,
              style: context.listCaptionStyle,
            ),
          ),
        if (selfTest.isNotEmpty)
          DecorationListItem(
            title: const Text('自检报告'),
            subtitle: SelectableText(selfTest, style: context.listCaptionStyle),
          ),
      ],
    );
  }
}
