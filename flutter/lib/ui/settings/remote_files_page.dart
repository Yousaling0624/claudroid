import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../bridge/api.dart';
import '../../models/remote.dart';
import '../glyphs.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

/// Read-only browser over a host's allowed roots. Opening or sharing downloads
/// through the daemon; the file itself never enters the app's main storage.
class RemoteFilesPage extends StatefulWidget {
  const RemoteFilesPage({
    super.key,
    required this.bridge,
    required this.host,
    required this.onToast,
  });

  final Bridge bridge;
  final RemoteHost host;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<RemoteFilesPage> createState() => _RemoteFilesPageState();
}

class _RemoteFilesPageState extends State<RemoteFilesPage> {
  RemoteFileListing? _listing;
  String _path = '';
  bool _loading = false;
  String? _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load(widget.host.defaultCwd);
  }

  Future<void> _load([String? path]) async {
    final target = (path ?? _path).trim();
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      if (target.isNotEmpty) _path = target;
    });
    try {
      final listing = await widget.bridge.browseHost(
        widget.host.id,
        path: target.isEmpty ? null : target,
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

  Future<void> _open(String path) async {
    try {
      await widget.bridge.openRemoteFile(widget.host.id, path);
    } catch (error) {
      if (mounted) widget.onToast('$error', isError: true);
    }
  }

  Future<void> _share(String path) async {
    try {
      await widget.bridge.shareRemoteFile(widget.host.id, path);
    } catch (error) {
      if (mounted) widget.onToast('$error', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final listing = _listing;
    final entries = [...?listing?.entries]
      ..sort((a, b) {
        if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
        return a.name.toLowerCase().compareTo(b.name.toLowerCase());
      });
    final parent = listing?.parent;
    return SettingsScaffold(
      title: '主机文件 · ${widget.host.displayName}',
      listenable: settingsNeverValue,
      body: ListView(
        padding: EdgeInsets.only(
          top: context.contentTopPadding,
          bottom: 32 + context.bottomInset,
        ),
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: context.colorScheme.surfaceBright,
                borderRadius: BorderRadius.circular(22),
                border: Border.all(
                  color: context.colorScheme.outlineVariant
                      .withValues(alpha: 0.6),
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
                child: Row(
                  children: [
                    GlyphIcon(
                      AppGlyphs.folder,
                      size: 18,
                      color: context.colorScheme.primary,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: SelectableText(
                        _path.isEmpty ? widget.host.defaultCwd : _path,
                        maxLines: 2,
                        style: AppTextStyle.caption1
                            .of(context)
                            .copyWith(fontFamily: 'monospace', height: 1.35),
                      ),
                    ),
                    IconButton(
                      onPressed: _loading ? null : () => _load(),
                      icon: const GlyphIcon(AppGlyphs.refresh, size: 18),
                      tooltip: '刷新',
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (_loading)
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 10, 24, 0),
              child: LinearProgressIndicator(
                minHeight: 2,
              ),
            ),
          if ((_error ?? '').isNotEmpty)
            SettingsNote(text: _error!, isError: true),
          if (parent != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: context.colorScheme.surfaceBright,
                  borderRadius: BorderRadius.circular(22),
                  border: Border.all(
                    color: context.colorScheme.outlineVariant
                        .withValues(alpha: 0.6),
                  ),
                ),
                child: SettingsActionRow(
                  label: '上一级',
                  glyph: Glyphs.arrowBack,
                  onPressed: _loading ? null : () => _load(parent),
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (!_loading && listing != null && entries.isEmpty)
                  const SettingsNote(text: '这个目录是空的。')
                else
                  for (final entry in entries)
                    _RemoteFileRow(
                      entry: entry,
                      onOpen: () => entry.isDir
                          ? _load(entry.path)
                          : _open(entry.path),
                      onShare: () => _share(entry.path),
                    ),
              ],
            ),
          ),
          const SettingsNote(
            text: '只能浏览主机配置里允许的目录。打开或分享会先下载到本机缓存。',
          ),
        ],
      ),
    );
  }
}

class _RemoteFileRow extends StatelessWidget {
  const _RemoteFileRow({
    required this.entry,
    required this.onOpen,
    required this.onShare,
  });

  final RemoteFileEntry entry;
  final VoidCallback onOpen;
  final VoidCallback onShare;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return DecorationListItem(
      title: Row(
        children: [
          GlyphIcon(
            entry.isDir ? AppGlyphs.folder : AppGlyphs.file,
            size: 20,
            color: entry.isDir ? scheme.primary : scheme.onSurfaceVariant,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              entry.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      onPressed: onOpen,
      subtitle: entry.isDir
          ? null
          : Text(entry.size <= 0 ? '空文件' : formatBytes(entry.size)),
      trailing: entry.isDir
          ? GlyphIcon(
              Glyphs.chevronForward,
              size: 16,
              color: scheme.onSurfaceVariant,
            )
          : PopupAnchor(
              targetBuilder: (open) => IconButton(
                onPressed: open,
                icon: const GlyphIcon(Glyphs.more, size: 18),
                tooltip: '文件操作',
              ),
              popupBuilder: (_) => PopupMenu(
                items: [
                  PopupMenuItem(
                    label: '打开',
                    glyph: AppGlyphs.file,
                    onPressed: onOpen,
                  ),
                  PopupMenuItem(
                    label: '分享',
                    glyph: AppGlyphs.share,
                    onPressed: onShare,
                  ),
                ],
              ),
            ),
    );
  }
}
