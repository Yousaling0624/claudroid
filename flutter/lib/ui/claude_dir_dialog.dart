import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'dialog_motion.dart';
import 'glyphs.dart';

/// Container path shown for a workspace-relative Claude directory.
String claudeDirLabel(String rel) {
  final path = _cleanClaudeDir(rel);
  return path.isEmpty ? '/work' : '/work/$path';
}

/// Parent of [rel] inside the workspace; the root has no parent, so `''`.
String parentClaudeDir(String rel) {
  final path = _cleanClaudeDir(rel);
  final cut = path.lastIndexOf('/');
  return cut < 0 ? '' : path.substring(0, cut);
}

/// Directories under [listing], sorted by name so the picker stays stable.
List<WorkspaceEntry> claudeDirChildren(WorkspaceListing listing) {
  final children = [
    for (final entry in listing.entries)
      if (entry.isDirectory) entry,
  ];
  children.sort((a, b) {
    final folded = a.name.toLowerCase().compareTo(b.name.toLowerCase());
    return folded != 0 ? folded : a.name.compareTo(b.name);
  });
  return children;
}

/// Opens the Claude working-directory picker. Returns the chosen workspace
/// relative path (empty string for the workspace root) or `null` on cancel.
///
/// The setting is consumed by the host on the next turn (the CLI's cwd), so
/// nothing here needs to restart the engine.
Future<String?> showClaudeDirDialog(
  BuildContext context, {
  required Bridge bridge,
  required String current,
  required void Function(String text, {bool isError}) onToast,
}) {
  return showClaudroidDialog<String>(
    context: context,
    child: _ClaudeDirDialog(
      bridge: bridge,
      current: current,
      onToast: onToast,
    ),
  );
}

/// Trims to the relative form the settings key stores: no leading, trailing or
/// doubled slashes, and no surrounding whitespace.
String _cleanClaudeDir(String rel) => rel
    .trim()
    .split('/')
    .where((segment) => segment.isNotEmpty)
    .join('/');

class _ClaudeDirDialog extends StatefulWidget {
  const _ClaudeDirDialog({
    required this.bridge,
    required this.current,
    required this.onToast,
  });

  final Bridge bridge;
  final String current;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<_ClaudeDirDialog> createState() => _ClaudeDirDialogState();
}

class _ClaudeDirDialogState extends State<_ClaudeDirDialog> {
  late String _path = _cleanClaudeDir(widget.current);
  late String _loadedPath = _path;
  WorkspaceListing? _listing;
  bool _loading = true;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _fetch(_path, initial: true);
  }

  Future<void> _load(String path) {
    final target = _cleanClaudeDir(path);
    setState(() {
      _path = target;
      _loading = true;
    });
    return _fetch(target, initial: false);
  }

  Future<void> _fetch(String path, {required bool initial}) async {
    final generation = ++_generation;
    try {
      final listing = await widget.bridge.listWorkspace(path);
      if (!mounted || generation != _generation) return;
      setState(() {
        _path = listing.path;
        _loadedPath = listing.path;
        _listing = listing;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || generation != _generation) return;
      if (initial && path.isNotEmpty) {
        widget.onToast('原工作目录已不存在，已回到工作区根目录');
        _loadedPath = '';
        await _load('');
        return;
      }
      setState(() {
        _path = _loadedPath;
        _loading = false;
      });
      widget.onToast('没能读取目录（$error）。再试一次。', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final listing = _listing;
    final children = listing == null
        ? const <WorkspaceEntry>[]
        : claudeDirChildren(listing);
    return AlertDialog(
      title: 'Claude 工作目录',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(''),
          child: const Text('工作区根目录'),
        ),
        TextButton(
          onPressed: _loading ? null : () => Navigator.of(context).pop(_path),
          child: const Text('选择此目录'),
        ),
      ],
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 340,
          maxHeight: MediaQuery.sizeOf(context).height * 0.6,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Claude Code 会把这个目录当作项目根目录（容器内路径 /work/…）',
                style: AppTextStyle.caption1.of(context).copyWith(
                      color: scheme.onSurfaceVariant,
                      height: 1.4,
                    ),
              ),
              const SizedBox(height: 8),
              Text(
                claudeDirLabel(_path),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyle.caption2.of(context).copyWith(
                      fontFamily: 'monospace',
                      color: scheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 4),
              if (_path.isNotEmpty) _upRow(context),
              if (_loading)
                SizedBox(
                  height: 88,
                  child: Center(
                    child: ActivityIndicator(
                      size: 22,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                )
              else if (listing == null)
                _errorRow(context)
              else if (children.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 18),
                  child: Text(
                    '没有子目录',
                    textAlign: TextAlign.center,
                    style: AppTextStyle.caption1.of(context).copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                  ),
                )
              else
                for (final (index, entry) in children.indexed)
                  DialogRowReveal(
                    index: index,
                    child: _dirRow(context, entry),
                  ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _upRow(BuildContext context) {
    final scheme = context.colorScheme;
    return Semantics(
      button: true,
      label: '上一级目录',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _loading ? null : () => _load(parentClaudeDir(_path)),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            children: [
              GlyphIcon(
                Glyphs.chevronBack,
                size: 18,
                color: scheme.onSurfaceVariant,
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Text('.. 上一级', style: context.listTitleStyle),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _dirRow(BuildContext context, WorkspaceEntry entry) {
    final scheme = context.colorScheme;
    return Semantics(
      button: true,
      label: '进入 ${entry.name}',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _loading ? null : () => _load(entry.path),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            children: [
              GlyphIcon(AppGlyphs.folder, size: 18, color: scheme.primary),
              const SizedBox(width: 11),
              Expanded(
                child: Text(
                  entry.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: context.listTitleStyle,
                ),
              ),
              const SizedBox(width: 8),
              GlyphIcon(
                Glyphs.chevronForward,
                size: 15,
                color: scheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _errorRow(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Column(
        children: [
          Text(
            '没能读取这个目录',
            textAlign: TextAlign.center,
            style: AppTextStyle.caption1.of(context).copyWith(
                  color: scheme.error,
                ),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => _load(_path),
            child: const Text('重试'),
          ),
        ],
      ),
    );
  }
}
