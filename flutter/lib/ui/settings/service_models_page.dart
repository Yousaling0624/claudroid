import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/models.dart';
import '../glass_controls.dart';
import '../model_menu.dart';
import '../press_guard.dart';
import 'model_picker.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

class ServiceModelsPage extends StatelessWidget {
  const ServiceModelsPage({super.key, required this.controller});

  final SettingsController controller;

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '服务与模型',
      listenable: controller,
      builder: (context) => [
        if (controller.providers.isNotEmpty) _providerSection(context),
        _apiSection(context),
        _modelSection(context),
        _tierSection(context),
      ],
    );
  }

  Widget _providerSection(BuildContext context) {
    var activeLabel = '自定义';
    ProviderPreset? active;
    for (final preset in controller.providers) {
      if (preset.id == controller.activeProviderId) {
        activeLabel = preset.label;
        active = preset;
        break;
      }
    }
    final hint = active?.hint.trim() ?? '';
    return generateSection(
      title: '服务商预设',
      footer: hint.isNotEmpty
          ? hint
          : '应用后会自动填好 Base URL、鉴权方式和默认模型；API Key 保持不变。',
      items: [
        SettingsPickerRow<String>(
          label: '服务商',
          value: controller.providersLoading ? '应用中…' : activeLabel,
          options: [
            for (final preset in controller.providers)
              PopupMenuItem(
                label: preset.label,
                checked: preset.id == controller.activeProviderId,
                onPressed: () => controller.applyProvider(preset),
              ),
          ],
        ),
      ],
    );
  }

  Widget _apiSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '接口',
      items: [
        SettingsPickerRow<String>(
          label: '协议',
          value: enumLabel(
            apiFlavorLabels,
            settings.str('apiFlavor', 'ANTHROPIC'),
          ),
          options: [
            for (final entry in apiFlavorLabels.entries)
              PopupMenuItem(
                label: entry.value,
                checked: settings.str('apiFlavor') == entry.key,
                onPressed: () => controller.patch({'apiFlavor': entry.key}),
              ),
          ],
        ),
        SettingsValueRow(
          label: 'Base URL',
          value: settings.str('baseUrl'),
          onPressed: () => editSettingText(
            context,
            controller,
            title: 'Base URL',
            key: 'baseUrl',
            current: settings.str('baseUrl'),
            hint: 'https://api.anthropic.com',
            keyboardType: TextInputType.url,
            singleLine: true,
            validator: (value) {
              final text = value?.trim() ?? '';
              if (text.isEmpty) return null;
              final scheme = Uri.tryParse(text)?.scheme;
              if (scheme != 'http' && scheme != 'https') {
                return '以 http:// 或 https:// 开头';
              }
              return null;
            },
          ),
        ),
        SettingsValueRow(
          label: 'API Key',
          value: settings.str('apiKey').isEmpty ? '未设置' : '已设置',
          onPressed: () => editSettingText(
            context,
            controller,
            title: 'API Key',
            key: 'apiKey',
            current: settings.str('apiKey'),
            hint: '本地网关可以留空',
            obscure: true,
            emptyLabel: '清空',
            maxLength: 4096,
          ),
        ),
        SettingsPickerRow<String>(
          label: '认证方式',
          value: enumLabel(
            authStyleLabels,
            settings.str('authStyle', 'API_KEY'),
          ),
          options: [
            for (final entry in authStyleLabels.entries)
              PopupMenuItem(
                label: entry.value,
                checked: settings.str('authStyle') == entry.key,
                onPressed: () => controller.patch({'authStyle': entry.key}),
              ),
          ],
        ),
      ],
    );
  }

  Widget _modelSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '模型',
      items: [
        SettingsValueRow(
          label: '默认模型',
          value: settings.str('model').isEmpty
              ? 'CLI 默认'
              : settings.str('model'),
          trailingGlyph: Glyphs.picker,
          onPressed: () => pickModelSetting(
            context,
            controller,
            title: '默认模型',
            key: 'model',
            current: settings.str('model'),
            hint: '留空则用 CLI 默认',
            clearActiveTier: true,
          ),
        ),
        SettingsValueRow(
          label: '小模型',
          value: settings.str('smallModel').isEmpty
              ? 'CLI 默认'
              : settings.str('smallModel'),
          trailingGlyph: Glyphs.picker,
          onPressed: () => pickModelSetting(
            context,
            controller,
            title: '小模型',
            key: 'smallModel',
            current: settings.str('smallModel'),
            hint: '留空则用 CLI 默认',
          ),
        ),
        DecorationListItem(
          title: const Text('模型列表'),
          subtitle: Text(
            controller.modelList.isEmpty
                ? '从接口拉取后可搜索选择'
                : '已拉取 ${controller.modelList.length} 个模型',
          ),
          minVerticalPadding: 12,
          trailing: Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.end,
            children: [
              DeferredDisable(
                onPressed: controller.modelsLoading
                    ? null
                    : () => pickModelSetting(
                        context,
                        controller,
                        title: '默认模型',
                        key: 'model',
                        current: settings.str('model'),
                        hint: '留空则用 CLI 默认',
                        clearActiveTier: true,
                        forceRefresh: true,
                      ),
                builder: (context, onPressed) => GlassTonalButton(
                  size: TonalButtonSize.compact,
                  label: controller.modelsLoading ? '拉取中…' : '拉取模型列表',
                  onPressed: onPressed,
                ),
              ),
              DeferredDisable(
                onPressed:
                    controller.modelsLoading || controller.testingConnection
                    ? null
                    : controller.testConnection,
                builder: (context, onPressed) => GlassTonalButton(
                  size: TonalButtonSize.compact,
                  label: controller.testingConnection ? '连接中…' : '测试连接',
                  onPressed: onPressed,
                ),
              ),
            ],
          ),
        ),
        if (controller.modelError != null)
          SettingsNote(text: controller.modelError!, isError: true),
        if (controller.connectionMessage.isNotEmpty)
          SettingsNote(
            text: controller.connectionMessage,
            isError: !controller.connectionOk,
          ),
      ],
    );
  }

  Widget _tierSection(BuildContext context) {
    final settings = controller.settings;
    return generateSection(
      title: '模型档位',
      footer: controller.labels.tierSummary.isEmpty
          ? null
          : controller.labels.tierSummary,
      items: [
        SettingsPickerRow<String>(
          label: '当前档位',
          value: tierLabel(settings.str('activeTier')),
          options: [
            for (final (value, label) in modelTierChoices())
              PopupMenuItem(
                label: label,
                checked: settings.str('activeTier') == value,
                onPressed: () => controller.patch({'activeTier': value}),
              ),
          ],
        ),
        for (final MapEntry(key: tier, value: tierName)
            in modelTierLabels.entries)
          SettingsValueRow(
            label: '$tierName · ${tier.toLowerCase()}',
            value: controller.labels.tierMapping[tier] ?? 'CLI 默认',
            trailingGlyph: Glyphs.picker,
            onPressed: () => pickModelSetting(
              context,
              controller,
              title: '$tierName 档位映射',
              key: tierField(tier),
              current: settings.str(tierField(tier)),
              hint: '留空则用官方默认',
            ),
          ),
      ],
    );
  }
}
