import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/remote.dart';
import 'dialog_motion.dart';
import 'glyphs.dart';

/// Result of the new-chat host picker: `hostId == null` means the local engine.
typedef RemoteHostChoice = ({String? hostId});

/// Chooses between the local engine and one paired host. A remote selection
/// only records the host id; the working directory is picked separately.
Future<RemoteHostChoice?> showRemoteHostPicker(
  BuildContext context, {
  required RemoteHostsState hosts,
  required String? currentHostId,
}) {
  if (hosts.hosts.isEmpty) return Future.value(null);
  return showClaudroidDialog<RemoteHostChoice>(
    context: context,
    child: _HostPickerDialog(hosts: hosts, currentHostId: currentHostId),
  );
}

class _HostPickerDialog extends StatefulWidget {
  const _HostPickerDialog({
    required this.hosts,
    required this.currentHostId,
  });

  final RemoteHostsState hosts;
  final String? currentHostId;

  @override
  State<_HostPickerDialog> createState() => _HostPickerDialogState();
}

class _HostPickerDialogState extends State<_HostPickerDialog> {
  late String? _selected = widget.currentHostId;
  RemoteHost? get _selectedHost => widget.hosts.byId(_selected);

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final selectedHost = _selectedHost;
    return AlertDialog(
      title: '在哪儿执行？',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop((hostId: _selected)),
          child: Text(
            selectedHost == null ? '使用本机' : '使用 ${selectedHost.displayName}',
          ),
        ),
      ],
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 360,
          maxHeight: MediaQuery.sizeOf(context).height * 0.55,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DialogRowReveal(
                index: 0,
                child: _choiceRow(
                  context,
                  glyph: AppGlyphs.cpu,
                  title: '本机',
                  subtitle: '在手机里通过 PRoot 运行',
                  selected: _selected == null,
                  online: true,
                  onPressed: () => setState(() => _selected = null),
                ),
              ),
              for (final (index, host) in widget.hosts.hosts.indexed)
                DialogRowReveal(
                  index: index + 1,
                  child: _choiceRow(
                    context,
                    glyph: AppGlyphs.plug,
                    title: host.displayName,
                    subtitle: '${host.phaseLabel} · ${host.baseUrl}',
                    selected: _selected == host.id,
                    online: host.online,
                    onPressed: () => setState(() => _selected = host.id),
                  ),
                ),
              const SizedBox(height: 8),
              Text(
                '选择主机后可以再点首页的目录胶囊，指定它在主机上的工作目录。'
                '远程会话使用主机自己的登录与模型配置。',
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

  Widget _choiceRow(
    BuildContext context, {
    required Glyph glyph,
    required String title,
    required String subtitle,
    required bool selected,
    required bool online,
    required VoidCallback onPressed,
  }) {
    final scheme = context.colorScheme;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onPressed,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            GlyphIcon(
              glyph,
              size: 19,
              color: selected ? scheme.primary : scheme.onSurfaceVariant,
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: context.listTitleStyle),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.caption2.of(context).copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: online ? scheme.success : scheme.onSurfaceVariant,
                shape: BoxShape.circle,
              ),
            ),
            if (selected) ...[
              const SizedBox(width: 8),
              GlyphIcon(Glyphs.check, size: 17, color: scheme.primary),
            ],
          ],
        ),
      ),
    );
  }
}

/// Browses the host's allowed roots and returns an absolute directory.
Future<String?> showRemoteDirectoryPicker(
  BuildContext context, {
  required Bridge bridge,
  required RemoteHost host,
  String? initialPath,
  required void Function(String text, {bool isError}) onToast,
}) {
  return showClaudroidDialog<String>(
    context: context,
    child: _RemoteDirectoryDialog(
      bridge: bridge,
      host: host,
      initialPath: initialPath,
      onToast: onToast,
    ),
  );
}

class _RemoteDirectoryDialog extends StatefulWidget {
  const _RemoteDirectoryDialog({
    required this.bridge,
    required this.host,
    required this.initialPath,
    required this.onToast,
  });

  final Bridge bridge;
  final RemoteHost host;
  final String? initialPath;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<_RemoteDirectoryDialog> createState() => _RemoteDirectoryDialogState();
}

class _RemoteDirectoryDialogState extends State<_RemoteDirectoryDialog> {
  RemoteFileListing? _listing;
  String _path = '';
  String? _error;
  bool _loading = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    final initial = (widget.initialPath ?? '').trim();
    _path = initial.isEmpty ? widget.host.defaultCwd : initial;
    _load(_path);
  }

  Future<void> _load(String path) async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      if (path.isNotEmpty) _path = path;
    });
    try {
      final listing = await widget.bridge.browseHost(
        widget.host.id,
        path: path.isEmpty ? null : path,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _listing = listing;
        _path = listing.path;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _error = '$error';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final listing = _listing;
    final dirs = [
      for (final entry in listing?.entries ?? const <RemoteFileEntry>[])
        if (entry.isDir) entry,
    ]..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final parent = listing?.parent;
    return AlertDialog(
      title: '选择主机目录',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: _loading || _path.isEmpty
              ? null
              : () => Navigator.of(context).pop(_path),
          child: const Text('使用此目录'),
        ),
      ],
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 420,
          maxHeight: MediaQuery.sizeOf(context).height * 0.6,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                GlyphIcon(
                  AppGlyphs.folder,
                  size: 17,
                  color: scheme.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _path,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.caption1.of(context).copyWith(
                          fontFamily: 'monospace',
                          height: 1.35,
                        ),
                  ),
                ),
                if (_loading)
                  ActivityIndicator(size: 14, color: scheme.onSurfaceVariant)
                else if (parent != null)
                  IconButton(
                    onPressed: () => _load(parent),
                    icon: const GlyphIcon(Glyphs.arrowBack, size: 17),
                    tooltip: '上一级',
                  ),
              ],
            ),
            if ((_error ?? '').isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Text(
                  _error!,
                  style: AppTextStyle.caption2.of(context).copyWith(
                        color: scheme.error,
                        height: 1.4,
                      ),
                ),
              ),
            const SizedBox(height: 4),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (!_loading && dirs.isEmpty && (_error ?? '').isEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: Text(
                          '这个目录下没有子目录，可以直接使用当前目录。',
                          style: AppTextStyle.caption2.of(context).copyWith(
                                color: scheme.onSurfaceVariant,
                              ),
                        ),
                      ),
                    for (final (index, dir) in dirs.indexed)
                      DialogRowReveal(
                        index: index,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: _loading ? null : () => _load(dir.path),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 9),
                            child: Row(
                              children: [
                                GlyphIcon(
                                  AppGlyphs.folder,
                                  size: 18,
                                  color: scheme.onSurfaceVariant,
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: Text(
                                    dir.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: context.listTitleStyle,
                                  ),
                                ),
                                GlyphIcon(
                                  Glyphs.chevronForward,
                                  size: 15,
                                  color: scheme.onSurfaceVariant,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            Text(
              '只能进入主机配置的允许目录。',
              style: AppTextStyle.caption2.of(context).copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}
