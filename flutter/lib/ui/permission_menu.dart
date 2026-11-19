import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'dialog_motion.dart';
import 'settings/settings_labels.dart';

/// Dialog form of the permission choice, shared by the `/plan` companion
/// command (`/permissions`). The settings page uses the same enum labels.
Future<void> showPermissionModeDialog(
  BuildContext context, {
  required String current,
  required void Function(Map<String, dynamic> patch) onPatch,
}) async {
  final choice = await showFloatingDialog<String>(
    context: context,
    child: _PermissionModeDialog(current: current),
  );
  if (choice == null || choice == current) return;
  onPatch({'permissionMode': choice});
}

class _PermissionModeDialog extends StatelessWidget {
  const _PermissionModeDialog({required this.current});

  final String current;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return AlertDialog(
      title: '权限模式',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (i, entry) in permissionLabels.entries.indexed)
            DialogRowReveal(
              index: i,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => Navigator.of(context).pop(entry.key),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(entry.value, style: context.listTitleStyle),
                      ),
                      if (entry.key == current)
                        GlyphIcon(
                          Glyphs.check,
                          size: 17,
                          color: scheme.primary,
                        ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
