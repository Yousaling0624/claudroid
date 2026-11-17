import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'model_picker.dart';
import 'settings_controller.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

const _featureLabels = {
  'agentTeams': 'Agent teams（实验）',
  'workflows': 'Workflows',
  'autoMemory': 'Auto memory',
  'backgroundTasks': 'Background tasks',
  'extendedThinking': 'Extended thinking',
};

class FeaturesPage extends StatelessWidget {
  const FeaturesPage({super.key, required this.controller});

  final SettingsController controller;

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '功能与实验',
      listenable: controller,
      builder: (context) => [
        _switchesSection(context),
        _modelsSection(context),
      ],
    );
  }

  Widget _switchesSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '功能开关',
      footer: '实验功能可能随时变动；切换后对新会话生效。',
      items: [
        for (final entry in featureFlagDefaults.entries)
          SettingsSwitchRow(
            label: _featureLabels[entry.key] ?? entry.key,
            value: settings.flag(entry.key, entry.value),
            onChanged: (value) => controller.patch({entry.key: value}),
          ),
      ],
    );
  }

  Widget _modelsSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '模型与推理',
      items: [
        SettingsPickerRow<String>(
          label: '推理强度',
          value: enumLabel(effortLabels, settings.str('effort', 'AUTO')),
          options: [
            for (final entry in effortLabels.entries)
              PopupMenuItem(
                label: entry.value,
                checked: settings.str('effort') == entry.key,
                onPressed: () => controller.patch({'effort': entry.key}),
              ),
          ],
        ),
        SettingsValueRow(
          label: '子代理模型',
          value: settings.str('subagentModel').isEmpty
              ? '继承主模型'
              : settings.str('subagentModel'),
          trailingGlyph: Glyphs.picker,
          onPressed: () => pickModelSetting(
            context,
            controller,
            title: '子代理模型',
            key: 'subagentModel',
            current: settings.str('subagentModel'),
            hint: '留空则继承主模型',
          ),
        ),
        SettingsValueRow(
          label: 'Auto 权限分类器模型',
          value: settings.str('autoModeModel').isEmpty
              ? '官方默认'
              : settings.str('autoModeModel'),
          trailingGlyph: Glyphs.picker,
          onPressed: () => pickModelSetting(
            context,
            controller,
            title: 'Auto 权限分类器模型',
            key: 'autoModeModel',
            current: settings.str('autoModeModel'),
            hint: '留空则用官方默认',
          ),
        ),
      ],
    );
  }
}
