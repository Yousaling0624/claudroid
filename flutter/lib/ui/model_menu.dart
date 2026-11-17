import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../models/models.dart';

/// The model-tier choices, in display order. One list feeds the chat header
/// popup, the `/model` dialog and the settings picker so their wording can
/// never drift apart.
List<(String, String)> modelTierChoices() => [
  ('', 'CLI 默认（不指定模型）'),
  ('AUTO', '自动（按任务路由）'),
  for (final MapEntry(key: tier, value: label) in modelTierLabels.entries)
    (tier, label),
];

/// The shared model-tier menu used by the chat header and the home header.
///
/// The tiers map to nothing in Dart: the host resolves `activeTier` itself, so
/// this only sends the selected enum name back through `settings.update`.
PopupMenu buildModelTierMenu({
  required AppSettings settings,
  required ValueChanged<Map<String, dynamic>> onPatch,
  required VoidCallback onOpenSettings,
}) {
  final activeTier = settings.str('activeTier');

  return PopupMenu(
    // Seven rows plus the `设置` divider and title. prism_ui's cap counts rows
    // only, so the default 7 leaves the title and last row clipped; one extra
    // row of budget covers both at any text scale.
    maxVisibleItems: 8,
    items: [
      for (final (value, label) in modelTierChoices())
        PopupMenuItem(
          label: label,
          checked: activeTier == value,
          onPressed: () => onPatch({'activeTier': value}),
        ),
      PopupMenuItem(
        label: '接口与模型…',
        section: const PopupMenuSection(title: '设置'),
        onPressed: onOpenSettings,
      ),
    ],
  );
}

/// The current model label plus a picker glyph, opening [buildModelTierMenu].
/// Both the chat and home headers use this so the trigger is identical.
class ModelTierButton extends StatelessWidget {
  const ModelTierButton({
    super.key,
    required this.labels,
    required this.settings,
    required this.onPatch,
    required this.onOpenSettings,
  });

  final ModelLabels labels;
  final AppSettings settings;
  final ValueChanged<Map<String, dynamic>> onPatch;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    return PopupAnchor(
      targetBuilder: (open) => TextButton(
        onPressed: open,
        style: ButtonStyle.from(
          foregroundColor: context.colorScheme.onSurfaceVariant,
          textStyle: context.listTitleStyle,
          padding: const EdgeInsets.symmetric(horizontal: 8),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 120),
              child: Text(
                labels.short,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 4),
            const GlyphIcon(Glyphs.picker, size: 16),
          ],
        ),
      ),
      popupBuilder: (_) => buildModelTierMenu(
        settings: settings,
        onPatch: onPatch,
        onOpenSettings: onOpenSettings,
      ),
    );
  }
}

/// Dialog form of the same choice, for the `/model` slash action where there is
/// no anchor to hang a [PopupMenu] from.
Future<void> showModelTierDialog(
  BuildContext context, {
  required AppSettings settings,
  required ValueChanged<Map<String, dynamic>> onPatch,
  required VoidCallback onOpenSettings,
}) async {
  final choice = await showFloatingDialog<String>(
    context: context,
    child: _ModelTierDialog(settings: settings),
  );
  if (choice == null) return;
  if (choice == _ModelTierDialog.settingsChoice) {
    onOpenSettings();
    return;
  }
  onPatch({'activeTier': choice});
}

class _ModelTierDialog extends StatelessWidget {
  const _ModelTierDialog({required this.settings});

  static const settingsChoice = '\u0000settings';

  final AppSettings settings;

  @override
  Widget build(BuildContext context) {
    final active = settings.str('activeTier');
    return AlertDialog(
      title: '选择模型',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(settingsChoice),
          child: const Text('接口与模型…'),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (value, label) in modelTierChoices())
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => Navigator.of(context).pop(value),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        label,
                        style: context.listTitleStyle,
                      ),
                    ),
                    if (value == active)
                      GlyphIcon(
                        Glyphs.check,
                        size: 17,
                        color: context.colorScheme.primary,
                      ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
