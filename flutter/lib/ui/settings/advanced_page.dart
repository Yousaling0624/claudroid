import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_widgets.dart';

class AdvancedPage extends StatelessWidget {
  const AdvancedPage({super.key, required this.controller});

  final SettingsController controller;

  String _envSummary() {
    final raw = controller.settings.str('extraEnv');
    final lines = raw
        .split('\n')
        .map((line) => line.trim())
        .where((line) => line.isNotEmpty)
        .length;
    return lines == 0 ? '未设置' : '已设置 $lines 条';
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '高级',
      listenable: controller,
      builder: (context) => [_runSection(context), _promptSection(context)],
    );
  }

  Widget _runSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '更新与运行',
      items: [
        SettingsSwitchRow(
          label: '启动时自动检查更新',
          value: settings.flag('autoUpdate', true),
          onChanged: (value) => controller.patch({'autoUpdate': value}),
        ),
        SettingsValueRow(
          label: '最大轮数',
          value: settings.intOr('maxTurns') == 0
              ? '默认'
              : '${settings.intOr('maxTurns')}',
          onPressed: () => editSettingNumber(
            context,
            controller,
            title: '最大轮数',
            key: 'maxTurns',
            current: settings.intOr('maxTurns'),
            hint: '0 = 默认',
          ),
        ),
      ],
    );
  }

  Widget _promptSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '提示词',
      footer: '额外环境变量每行一条 KEY=VALUE，只对之后的 CLI 进程生效。',
      items: [
        SettingsValueRow(
          label: '系统提示词',
          value: settings.str('extraSystemPrompt').isEmpty ? '未设置' : '已设置',
          onPressed: () => editSettingText(
            context,
            controller,
            title: '系统提示词',
            key: 'extraSystemPrompt',
            current: settings.str('extraSystemPrompt'),
            hint: '附加给 CLI 的系统提示词',
            emptyLabel: '清空',
          ),
        ),
        SettingsValueRow(
          label: '额外环境变量',
          value: _envSummary(),
          onPressed: () => editSettingText(
            context,
            controller,
            title: '额外环境变量',
            key: 'extraEnv',
            current: settings.str('extraEnv'),
            hint: '每行一条 KEY=VALUE',
            emptyLabel: '清空',
          ),
        ),
      ],
    );
  }
}
