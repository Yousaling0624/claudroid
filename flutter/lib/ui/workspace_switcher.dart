import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'dialog_motion.dart';
import 'glyphs.dart';
import 'settings/settings_dialogs.dart';

/// Opens the workspace picker. Returns `true` when the active workspace changed,
/// so a page that lists files can reset to the new root.
Future<bool> showWorkspaceSwitcher(
  BuildContext context, {
  required Bridge bridge,
  required void Function(String text, {bool isError}) onToast,
}) async {
  final WorkspaceSpaces initial;
  try {
    initial = await bridge.listWorkspaces();
  } catch (error) {
    onToast('没能读取工作区（$error）。再试一次。', isError: true);
    return false;
  }
  if (!context.mounted) return false;
  final changed = await showFloatingDialog<bool>(
    context: context,
    child: _WorkspaceSwitcherDialog(
      bridge: bridge,
      initial: initial,
      onToast: onToast,
    ),
  );
  return changed == true;
}

class _WorkspaceSwitcherDialog extends StatefulWidget {
  const _WorkspaceSwitcherDialog({
    required this.bridge,
    required this.initial,
    required this.onToast,
  });

  final Bridge bridge;
  final WorkspaceSpaces initial;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<_WorkspaceSwitcherDialog> createState() =>
      _WorkspaceSwitcherDialogState();
}

class _WorkspaceSwitcherDialogState extends State<_WorkspaceSwitcherDialog> {
  late WorkspaceSpaces _spaces = widget.initial;
  String? _busyId;

  bool get _busy => _busyId != null;

  Future<void> _select(WorkspaceSpace space) async {
    if (_busy) return;
    if (space.id == _spaces.activeId) {
      Navigator.of(context).pop(true);
      return;
    }
    setState(() => _busyId = space.id);
    try {
      final next = await widget.bridge.selectWorkspace(space.id);
      if (!mounted) return;
      widget.onToast('已切换到「${next.active?.name ?? space.name}」');
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() => _busyId = null);
      widget.onToast('$error', isError: true);
    }
  }

  Future<void> _create() async {
    if (_busy) return;
    final name = await _promptName(title: '新建工作区', hint: '给这个工作区起个名字');
    if (name == null || !mounted) return;
    setState(() => _busyId = 'create');
    try {
      final next = await widget.bridge.createWorkspace(name);
      if (!mounted) return;
      widget.onToast('已创建并切换到「${next.active?.name ?? name}」');
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() => _busyId = null);
      widget.onToast('$error', isError: true);
    }
  }

  Future<void> _rename(WorkspaceSpace space) async {
    if (_busy) return;
    final name = await _promptName(
      title: '重命名工作区',
      hint: '给这个工作区起个名字',
      initial: space.name,
    );
    if (name == null || !mounted || name == space.name) return;
    setState(() => _busyId = space.id);
    try {
      final next = await widget.bridge.renameWorkspace(space.id, name);
      if (!mounted) return;
      setState(() {
        _spaces = next;
        _busyId = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _busyId = null);
      widget.onToast('$error', isError: true);
    }
  }

  Future<void> _remove(WorkspaceSpace space) async {
    if (_busy) return;
    final confirmed = await showFloatingDialog<bool>(
      context: context,
      child: AlertDialog(
        title: '删除工作区？',
        destructive: true,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
        child: Text('「${space.name}」里的所有文件都会被永久删除，此操作无法撤销。'),
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busyId = space.id);
    try {
      final next = await widget.bridge.removeWorkspace(space.id);
      if (!mounted) return;
      setState(() {
        _spaces = next;
        _busyId = null;
      });
      widget.onToast('已删除「${space.name}」');
    } catch (error) {
      if (!mounted) return;
      setState(() => _busyId = null);
      widget.onToast('$error', isError: true);
    }
  }

  Future<String?> _promptName({
    required String title,
    required String hint,
    String initial = '',
  }) async {
    final value = await showFloatingDialog<String>(
      context: context,
      child: SettingsTextPrompt(
        title: title,
        initial: initial,
        hint: hint,
        singleLine: true,
        maxLength: 24,
        validator: (value) =>
            (value ?? '').trim().isEmpty ? '名字不能为空' : null,
      ),
    );
    final trimmed = value?.trim() ?? '';
    return trimmed.isEmpty ? null : trimmed;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return AlertDialog(
      title: '切换工作区',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('取消'),
        ),
      ],
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 340,
          maxHeight: MediaQuery.sizeOf(context).height * 0.5,
        ),
        child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final (i, space) in _spaces.spaces.indexed)
                  DialogRowReveal(
                    index: i,
                    child: _spaceRow(context, space),
                  ),
                Container(
                  height: 1,
                  margin: const EdgeInsets.symmetric(vertical: 6),
                  color: scheme.outlineVariant.withValues(alpha: 0.5),
                ),
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _busy ? null : _create,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    child: Row(
                      children: [
                        _busyId == 'create'
                            ? ActivityIndicator(
                                size: 16,
                                color: scheme.onSurfaceVariant,
                              )
                            : GlyphIcon(
                                AppGlyphs.plus,
                                size: 17,
                                color: scheme.onSurfaceVariant,
                              ),
                        const SizedBox(width: 11),
                        Text(
                          '新建工作区…',
                          style: context.listTitleStyle,
                        ),
                      ],
                    ),
                  ),
                ),
                Text(
                  '切换后，新回合会在这个工作区的 /work 下执行。',
                  style: AppTextStyle.caption2.of(context).copyWith(
                        color: scheme.onSurfaceVariant,
                        height: 1.4,
                      ),
                ),
              ],
            ),
          ),
      ),
    );
  }

  Widget _spaceRow(BuildContext context, WorkspaceSpace space) {
    final scheme = context.colorScheme;
    final active = space.id == _spaces.activeId;
    final busy = _busyId == space.id;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _busy ? null : () => _select(space),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            GlyphIcon(
              AppGlyphs.folder,
              size: 18,
              color: active ? scheme.primary : scheme.onSurfaceVariant,
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Text(
                space.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.listTitleStyle,
              ),
            ),
            if (busy)
              ActivityIndicator(size: 14, color: scheme.onSurfaceVariant)
            else if (active)
              GlyphIcon(Glyphs.check, size: 17, color: scheme.primary),
            if (!space.isDefault) ...[
              IconButton(
                onPressed: _busy ? null : () => _rename(space),
                icon: const GlyphIcon(AppGlyphs.edit, size: 16),
                iconSize: 16,
                padding: const EdgeInsets.all(6),
                tooltip: '重命名',
              ),
              IconButton(
                onPressed: _busy ? null : () => _remove(space),
                icon: const GlyphIcon(AppGlyphs.trash, size: 16),
                iconSize: 16,
                padding: const EdgeInsets.all(6),
                tooltip: '删除',
              ),
            ],
          ],
        ),
      ),
    );
  }
}
