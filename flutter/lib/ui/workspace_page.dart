import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import '../models/models.dart';
import 'glass_controls.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'markdown.dart';
import 'press_guard.dart';
import 'scaffold_bar.dart';
import 'workspace_logic.dart';

/// Read-only browser for the jail's `/work` directory, plus a source/rendered
/// preview and the one-off command terminal. Kotlin owns listing, path checks,
/// file reads and intents; this page only renders what the bridge returns.
class WorkspacePage extends StatefulWidget {
  const WorkspacePage({
    super.key,
    required this.bridge,
    required this.onToast,
  });

  final Bridge bridge;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<WorkspacePage> createState() => _WorkspacePageState();
}

class _WorkspacePageState extends State<WorkspacePage> {
  String _path = '';
  WorkspaceListing? _listing;
  bool _loading = true;
  String? _listingError;
  int _listingGeneration = 0;

  String? _previewPath;
  WorkspacePreview? _preview;
  bool _previewLoading = false;
  String? _previewError;
  int _previewGeneration = 0;
  int _previewReload = 0;
  bool _htmlSource = false;
  bool _codeWrap = false;

  final TextEditingController _filter = TextEditingController();
  String _query = '';
  WorkspaceSortMode? _sortMode;
  bool _sortAscending = true;

  bool _terminalOpen = false;

  bool get _atRoot => !_terminalOpen && _previewPath == null && _path.isEmpty;

  @override
  void initState() {
    super.initState();
    _loadListing();
  }

  @override
  void dispose() {
    _filter.dispose();
    super.dispose();
  }

  Future<void> _loadListing() async {
    final generation = ++_listingGeneration;
    setState(() {
      _loading = true;
      _listingError = null;
    });
    try {
      final listing = await widget.bridge.listWorkspace(_path);
      if (!mounted || generation != _listingGeneration) return;
      setState(() {
        _path = listing.path;
        _listing = listing;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || generation != _listingGeneration) return;
      setState(() {
        _loading = false;
        _listingError = '$error';
      });
    }
  }

  void _openDirectory(WorkspaceEntry entry) {
    setState(() => _path = entry.path);
    _clearFilter();
    _loadListing();
  }

  void _goUp() {
    final cut = _path.lastIndexOf('/');
    setState(() => _path = cut < 0 ? '' : _path.substring(0, cut));
    _clearFilter();
    _loadListing();
  }

  void _jumpTo(String path) {
    if (path == _path) return;
    setState(() => _path = path);
    _clearFilter();
    _loadListing();
  }

  void _clearFilter() {
    _filter.clear();
    _query = '';
  }

  Future<void> _copyPreviewText(String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    widget.onToast('内容已复制');
  }

  void _openPreview(WorkspaceEntry entry) {
    setState(() {
      _previewPath = entry.path;
      _preview = null;
      _previewError = null;
      _htmlSource = false;
      _codeWrap = false;
    });
    _loadPreview();
  }

  Future<void> _loadPreview() async {
    final path = _previewPath;
    if (path == null) return;
    final generation = ++_previewGeneration;
    setState(() {
      _previewLoading = true;
      _previewError = null;
    });
    try {
      final preview = await widget.bridge.previewWorkspace(path);
      if (!mounted || generation != _previewGeneration) return;
      setState(() {
        _preview = preview;
        _previewLoading = false;
        _previewReload++;
      });
    } catch (error) {
      if (!mounted || generation != _previewGeneration) return;
      setState(() {
        _previewLoading = false;
        _previewError = '$error';
      });
    }
  }

  void _closePreview({bool refresh = true}) {
    _previewGeneration++;
    setState(() {
      _previewPath = null;
      _preview = null;
      _previewError = null;
      _previewLoading = false;
    });
    if (refresh) _loadListing();
  }

  void _closeTerminal() {
    setState(() => _terminalOpen = false);
    _loadListing();
  }

  void _goBack() {
    if (_terminalOpen) {
      _closeTerminal();
    } else if (_previewPath != null) {
      _closePreview();
    } else if (_path.isNotEmpty) {
      _goUp();
    } else {
      Navigator.of(context).maybePop();
    }
  }

  Future<void> _shareFile(String path) async {
    try {
      await widget.bridge.shareWorkspaceFile(path);
    } catch (error) {
      widget.onToast('$error', isError: true);
    }
  }

  Future<void> _openFile(String path) async {
    try {
      await widget.bridge.openWorkspaceFile(path);
    } catch (error) {
      widget.onToast('$error', isError: true);
    }
  }

  Future<void> _deleteFile(String path, String name) async {
    final confirmed = await showFloatingDialog<bool>(
      context: context,
      child: AlertDialog(
        title: '删除文件',
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
        child: Text('将永久删除「$name」。此操作无法撤销。'),
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.bridge.deleteWorkspaceFile(path);
      if (!mounted) return;
      widget.onToast('已删除');
      _closePreview();
    } catch (error) {
      widget.onToast('$error', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _atRoot,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _goBack();
      },
      child: _terminalOpen
          ? _TerminalPage(
              bridge: widget.bridge,
              onBack: _closeTerminal,
              onToast: widget.onToast,
            )
          : _previewPath != null
              ? _buildPreviewScaffold(context)
              : _buildBrowserScaffold(context),
    );
  }

  // ---------------------------------------------------------------- browser

  Widget _buildBrowserScaffold(BuildContext context) {
    final listing = _listing;
    final entries = listing?.entries ?? const <WorkspaceEntry>[];
    final visible = sortWorkspaceEntries(
      filterWorkspaceEntries(entries, _query),
      mode: _sortMode,
      ascending: _sortAscending,
    );
    final countLabel = _query.trim().isEmpty
        ? '${entries.length}'
        : '${visible.length}/${entries.length}';
    final subtitle = _loading
        ? '读取中…'
        : '/work${_path.isEmpty ? '' : '/$_path'} · '
            '$countLabel 项 · '
            '${_humanBytes(listing?.totalBytes ?? 0)}';
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverLoading,
        leading: (context) => IconButton(
          onPressed: _goBack,
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => Text(_path.isEmpty ? '工作区' : _basename(_path)),
        actions: [
          (context) => IconButton(
            onPressed: () => setState(() => _terminalOpen = true),
            icon: const GlyphIcon(AppGlyphs.terminal, size: 19),
            tooltip: '终端',
          ),
          (context) => IconButton(
            onPressed: _loadListing,
            icon: const GlyphIcon(AppGlyphs.refresh, size: 19),
            tooltip: '刷新',
          ),
          (context) => PopupAnchor(
            targetBuilder: (open) => IconButton(
              onPressed: open,
              icon: const GlyphIcon(Glyphs.more, size: 20),
              tooltip: '更多',
            ),
            popupBuilder: (_) => PopupMenu(
              items: [
                if (_path.isNotEmpty)
                  PopupMenuItem(
                    label: '回到 /work',
                    glyph: AppGlyphs.folder,
                    onPressed: () => _jumpTo(''),
                  ),
                PopupMenuItem(
                  label: '刷新列表',
                  glyph: AppGlyphs.refresh,
                  onPressed: _loadListing,
                ),
              ],
            ),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(
              18,
              context.contentTopPadding,
              18,
              2,
            ),
            child: _buildBreadcrumb(context),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 0, 18, 4),
            child: Text(
              subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote.of(context).copyWith(
                    color: context.colorScheme.onSurfaceVariant,
                  ),
            ),
          ),
          if (!_loading && _listingError == null && entries.isNotEmpty)
            _buildListToolbar(context),
          Expanded(
            child: ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              child: _buildBrowserBody(context, listing, visible),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBreadcrumb(BuildContext context) {
    final segments = breadcrumbSegments(_path);
    final scheme = context.colorScheme;
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: segments.length,
        separatorBuilder: (_, _) => GlyphIcon(
          Glyphs.chevronForward,
          size: 13,
          color: scheme.onSurfaceVariant,
        ),
        itemBuilder: (context, index) {
          final segment = segments[index];
          final current = index == segments.length - 1;
          return Semantics(
            button: !current,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: current ? null : () => _jumpTo(segment.path),
              child: SizedBox(
                height: 40,
                child: Center(
                  child: Container(
                    height: 28,
                    padding: const EdgeInsets.symmetric(horizontal: 9),
                    decoration: BoxDecoration(
                      color: current
                          ? scheme.surfaceContainerHigh
                          : const Color(0x00000000),
                      borderRadius: AppRadius.full,
                    ),
                    child: Center(
                      child: Text(
                        segment.label,
                        style: AppTextStyle.caption1.of(context).copyWith(
                              color: current
                                  ? scheme.onSurface
                                  : scheme.onSurfaceVariant,
                              fontWeight:
                                  current ? FontWeight.w600 : FontWeight.w400,
                            ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildListToolbar(BuildContext context) {
    final scheme = context.colorScheme;
    final label = _sortMode == null
        ? '默认顺序'
        : '${_sortMode!.label} ${_sortAscending ? '升序' : '降序'}';
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 2, 12, 6),
      child: Row(
        children: [
          Expanded(
            child: SearchField(
              controller: _filter,
              hintText: '筛选文件',
              height: 40,
              onChanged: (value) => setState(() => _query = value),
            ),
          ),
          PopupAnchor(
            targetBuilder: (open) => TextButton(
              onPressed: open,
              style: ButtonStyle.from(
                foregroundColor: scheme.onSurfaceVariant,
                minimumSize: const Size(44, 40),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const GlyphIcon(Glyphs.sort, size: 16),
                  const SizedBox(width: 4),
                  Text(label),
                ],
              ),
            ),
            popupBuilder: (_) => PopupMenu(
              items: [
                PopupMenuItem(
                  label: '默认顺序',
                  checked: _sortMode == null,
                  onPressed: () => setState(() => _sortMode = null),
                ),
                for (final mode in WorkspaceSortMode.values) ...[
                  PopupMenuItem(
                    label: '${mode.label} · 升序',
                    checked: _sortMode == mode && _sortAscending,
                    onPressed: () => setState(() {
                      _sortMode = mode;
                      _sortAscending = true;
                    }),
                  ),
                  PopupMenuItem(
                    label: '${mode.label} · 降序',
                    checked: _sortMode == mode && !_sortAscending,
                    onPressed: () => setState(() {
                      _sortMode = mode;
                      _sortAscending = false;
                    }),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBrowserBody(
    BuildContext context,
    WorkspaceListing? listing,
    List<WorkspaceEntry> visible,
  ) {
    if (_loading) {
      return const _Loading();
    }
    if (_listingError != null) {
      return _ErrorState(
        message: _listingError!,
        onRetry: _loadListing,
      );
    }
    final entries = listing?.entries ?? const <WorkspaceEntry>[];
    if (entries.isEmpty) {
      return const _EmptyWorkspace();
    }
    if (visible.isEmpty) {
      return _NoMatches(query: _query);
    }
    return ListView.separated(
      padding: EdgeInsets.fromLTRB(
        12,
        4,
        12,
        24 + context.bottomInset,
      ),
      itemCount: visible.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        final entry = visible[index];
        return _EntryRow(
          entry: entry,
          onPressed: () => entry.isDirectory
              ? _openDirectory(entry)
              : _openPreview(entry),
        );
      },
    );
  }

  // ---------------------------------------------------------------- preview

  Widget _buildPreviewScaffold(BuildContext context) {
    final preview = _preview;
    final scheme = context.colorScheme;
    final isHtml = preview?.kind == WorkspaceKind.html && preview?.text != null;
    final text = preview?.text;
    final canWrap = preview != null &&
        preview.text != null &&
        (preview.kind == WorkspaceKind.text ||
            (preview.kind == WorkspaceKind.html && _htmlSource));
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverLoading,
        leading: (context) => IconButton(
          onPressed: _goBack,
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => Text(
          preview?.name ?? _basename(_previewPath ?? ''),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          if (isHtml)
            (context) => TextButton(
              onPressed: () => setState(() => _htmlSource = !_htmlSource),
              child: Text(_htmlSource ? '预览' : '源码'),
            ),
          if (text != null)
            (context) => IconButton(
              onPressed: () => unawaited(_copyPreviewText(text)),
              icon: const GlyphIcon(Glyphs.copy, size: 19),
              tooltip: '复制内容',
            ),
          (context) => PopupAnchor(
            targetBuilder: (open) => IconButton(
              onPressed: open,
              icon: const GlyphIcon(Glyphs.more, size: 20),
              tooltip: '更多',
            ),
            popupBuilder: (_) => PopupMenu(
              items: [
                if (canWrap)
                  PopupMenuItem(
                    label: _codeWrap ? '不自动换行' : '自动换行',
                    glyph: AppGlyphs.code,
                    onPressed: () => setState(() => _codeWrap = !_codeWrap),
                  ),
                PopupMenuItem(
                  label: '分享文件',
                  onPressed: () {
                    final path = _previewPath;
                    if (path != null) unawaited(_shareFile(path));
                  },
                ),
                PopupMenuItem(
                  label: '用其他应用打开',
                  onPressed: () {
                    final path = _previewPath;
                    if (path != null) unawaited(_openFile(path));
                  },
                ),
                PopupMenuItem(
                  label: '重新加载',
                  glyph: AppGlyphs.refresh,
                  onPressed: _loadPreview,
                ),
                PopupMenuItem(
                  label: '删除文件',
                  glyph: AppGlyphs.trash,
                  danger: true,
                  onPressed: () {
                    final path = _previewPath;
                    if (path != null) {
                      unawaited(_deleteFile(path, preview?.name ?? ''));
                    }
                  },
                ),
              ],
            ),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(
              18,
              context.contentTopPadding,
              18,
              6,
            ),
            child: Text(
              preview == null
                  ? '/work/${_previewPath ?? ''}'
                  : '/work/${preview.path} · ${_humanBytes(preview.size)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote.of(context).copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
          ),
          Expanded(
            child: ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              child: _previewLoading
                  ? const _Loading()
                  : _previewError != null
                      ? _ErrorState(
                          message: _previewError!,
                          onRetry: _loadPreview,
                        )
                      : preview == null
                          ? const SizedBox.shrink()
                          : SizedBox.expand(
                              child: _buildPreviewBody(context, preview),
                            ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPreviewBody(BuildContext context, WorkspacePreview preview) {
    if (preview.kind == WorkspaceKind.image) {
      final bytes = preview.bytes;
      if (bytes == null) {
        return _BinaryPreview(
          preview: preview,
          onOpen: () => unawaited(_openFile(preview.path)),
          onShare: () => unawaited(_shareFile(preview.path)),
        );
      }
      return ColoredBox(
        color: context.colorScheme.surfaceContainerLowest,
        child: InteractiveViewer(
          minScale: 1,
          maxScale: 8,
          child: SizedBox.expand(
            child: Image.memory(
              bytes,
              fit: BoxFit.contain,
              cacheWidth: 1600,
              errorBuilder: (_, _, _) => _BinaryPreview(
                preview: preview,
                onOpen: () => unawaited(_openFile(preview.path)),
                onShare: () => unawaited(_shareFile(preview.path)),
              ),
            ),
          ),
        ),
      );
    }
    final text = preview.text;
    if (text == null) {
      return _BinaryPreview(
        preview: preview,
        onOpen: () => unawaited(_openFile(preview.path)),
        onShare: () => unawaited(_shareFile(preview.path)),
      );
    }
    if (preview.kind == WorkspaceKind.html && !_htmlSource) {
      return _HtmlPreviewHtml(
        key: ValueKey('${preview.path}#$_previewReload'),
        html: text,
      );
    }
    if (preview.kind == WorkspaceKind.markdown) {
      return _withPreviewNotice(
        preview,
        SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(18, 2, 18, 28 + context.bottomInset),
          child: MarkdownText(
            text,
            onLinkTap: (url) => unawaited(_openUrl(url)),
          ),
        ),
      );
    }
    final style = AppTextStyle.caption1.of(context).copyWith(
          fontFamily: 'monospace',
          height: 1.45,
        );
    final selection = SelectionArea(
      child: Text(text.isEmpty ? '（空文件）' : text, style: style),
    );
    final body = _codeWrap
        ? SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(18, 2, 18, 28 + context.bottomInset),
            child: selection,
          )
        : SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(18, 2, 18, 28 + context.bottomInset),
              // prism 的 SelectableText 内部是只读 EditableText，在横向滚动视图的
              // 无界宽度里会布局失败并画不出字；Text + SelectionArea 既可选择又能量出
              // 真实的独占宽度。
              child: selection,
            ),
          );
    return _withPreviewNotice(preview, body);
  }

  Widget _withPreviewNotice(WorkspacePreview preview, Widget child) {
    if (!preview.truncated) return child;
    final scheme = context.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 4, 18, 8),
          child: Text(
            '内容较大，仅显示开头部分（共 ${_humanBytes(preview.size)}）。',
            style: AppTextStyle.caption2.of(context).copyWith(
                  color: scheme.onSurfaceVariant,
                ),
          ),
        ),
        Expanded(child: child),
      ],
    );
  }

  Future<void> _openUrl(String url) async {
    try {
      await widget.bridge.openUrl(url);
    } catch (error) {
      widget.onToast('$error', isError: true);
    }
  }
}

// ---------------------------------------------------------------------------
// Browser rows
// ---------------------------------------------------------------------------

class _EntryRow extends StatelessWidget {
  const _EntryRow({required this.entry, required this.onPressed});

  final WorkspaceEntry entry;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final (glyph, tint) = _iconFor(entry, scheme);
    return Card(
      onPressed: onPressed,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHigh,
              borderRadius: AppRadius.sm,
            ),
            child: Center(
              child: GlyphIcon(glyph, size: 19, color: tint),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: context.listTitleStyle,
                ),
                const SizedBox(height: 2),
                Text(
                  entry.isDirectory
                      ? '文件夹'
                      : '${_humanBytes(entry.size)} · ${_fileTime(entry.modifiedAt)}',
                  maxLines: 1,
                  style: AppTextStyle.caption1.of(context).copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                ),
              ],
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
    );
  }
}

(Glyph, Color) _iconFor(WorkspaceEntry entry, ColorScheme scheme) {
  if (entry.isDirectory) return (AppGlyphs.folder, scheme.primary);
  return switch (entry.kind) {
    WorkspaceKind.image => (AppGlyphs.image, scheme.onSurfaceVariant),
    WorkspaceKind.markdown => (AppGlyphs.file, scheme.primary),
    WorkspaceKind.html => (AppGlyphs.globe, scheme.primary),
    WorkspaceKind.text => _isCodeName(entry.name)
        ? (AppGlyphs.code, scheme.tertiary)
        : (AppGlyphs.file, scheme.onSurfaceVariant),
    WorkspaceKind.archive => (AppGlyphs.folder, scheme.onSurfaceVariant),
    WorkspaceKind.directory => (AppGlyphs.folder, scheme.primary),
    WorkspaceKind.other => (AppGlyphs.file, scheme.onSurfaceVariant),
  };
}

bool _isCodeName(String name) {
  const extensions = {
    'kt', 'kts', 'java', 'py', 'rb', 'go', 'rs', 'c', 'h', 'cpp', 'hpp',
    'js', 'mjs', 'ts', 'jsx', 'tsx', 'sh', 'bash', 'zsh', 'sql', 'css',
    'scss', 'json', 'jsonl', 'xml', 'yaml', 'yml', 'toml', 'gradle',
  };
  final extension = name.toLowerCase().split('.').last;
  return name.contains('.') && extensions.contains(extension);
}

class _EmptyWorkspace extends StatelessWidget {
  const _EmptyWorkspace();

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GlyphIcon(AppGlyphs.folder, size: 34, color: scheme.onSurfaceVariant),
            const SizedBox(height: 14),
            Text(
              '工作区还是空的',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 8),
            Text(
              'Claude 写到 /work 的文件会出现在这里。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
          ],
        ),
      ),
    );
  }
}

class _NoMatches extends StatelessWidget {
  const _NoMatches({required this.query});

  final String query;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GlyphIcon(Glyphs.search, size: 30, color: scheme.onSurfaceVariant),
            const SizedBox(height: 12),
            Text(
              query.isEmpty ? '没有匹配的文件' : '没有匹配「$query」的文件',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 6),
            Text(
              '换个关键词，或清空筛选。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
          ],
        ),
      ),
    );
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle.copyWith(color: scheme.error),
            ),
            const SizedBox(height: 16),
            TextButton(onPressed: onRetry, child: const Text('重试')),
          ],
        ),
      ),
    );
  }
}

class _BinaryPreview extends StatelessWidget {
  const _BinaryPreview({
    required this.preview,
    required this.onOpen,
    required this.onShare,
  });

  final WorkspacePreview preview;
  final VoidCallback onOpen;
  final VoidCallback onShare;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 34),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: AppRadius.md,
              ),
              child: Center(
                child: GlyphIcon(
                  AppGlyphs.file,
                  size: 24,
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text(
              '无法在应用内预览',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 8),
            Text(
              preview.tooLarge
                  ? '图片超过 6 MB，交给其他应用打开或直接分享。'
                  : '二进制或超大文件可以交给其他应用打开，或直接分享。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
            const SizedBox(height: 22),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                GlassTonalButton(label: '打开', onPressed: onOpen),
                const SizedBox(width: 10),
                TextButton(onPressed: onShare, child: const Text('分享')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// HTML preview
// ---------------------------------------------------------------------------

/// Hosts the Kotlin-registered WebView platform view and waits for its `ready`
/// callback, so the first slow paint is not mistaken for an empty artifact.
class _HtmlPreviewHtml extends StatefulWidget {
  const _HtmlPreviewHtml({super.key, required this.html});

  final String html;

  @override
  State<_HtmlPreviewHtml> createState() => _HtmlPreviewHtmlState();
}

class _HtmlPreviewHtmlState extends State<_HtmlPreviewHtml> {
  static const _viewType = 'app.claudroid/html_preview';

  MethodChannel? _channel;
  bool _ready = false;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        AndroidView(
          viewType: _viewType,
          creationParams: <String, Object>{'html': widget.html},
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
        ),
        if (!_ready) const _Loading(),
      ],
    );
  }

  void _onPlatformViewCreated(int id) {
    final channel = MethodChannel('$_viewType/$id', const StandardMethodCodec());
    _channel = channel;
    channel.setMethodCallHandler((call) async {
      if (call.method == 'ready' && mounted) {
        setState(() => _ready = true);
      }
    });
  }

  @override
  void dispose() {
    _channel?.setMethodCallHandler(null);
    super.dispose();
  }
}

// ---------------------------------------------------------------------------
// Terminal
// ---------------------------------------------------------------------------

const _maxTerminalLines = 300;
const _maxLiveChars = 200000;

enum _TerminalKind { command, output, error, info }

class _TerminalLine {
  const _TerminalLine(this.text, this.kind);

  final String text;
  final _TerminalKind kind;
}

class _TerminalPage extends StatefulWidget {
  const _TerminalPage({
    required this.bridge,
    required this.onBack,
    required this.onToast,
  });

  final Bridge bridge;
  final VoidCallback onBack;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<_TerminalPage> createState() => _TerminalPageState();
}

class _TerminalPageState extends State<_TerminalPage> {
  final TerminalEvents _events = TerminalEvents();
  final TextEditingController _input = TextEditingController();
  final FocusNode _inputFocus = FocusNode();
  final ScrollController _scroll = ScrollController();
  final List<_TerminalLine> _lines = [];

  StreamSubscription<TerminalEvent>? _subscription;
  TerminalInfo? _info;
  bool _infoLoading = true;
  bool _running = false;
  String _live = '';
  bool _liveTruncated = false;
  String _cwdRel = '';
  bool _followBottom = true;
  final List<String> _history = [];
  int _historyIndex = 0;
  String _historyDraft = '';

  @override
  void initState() {
    super.initState();
    _subscription = _events.events.listen(_onEvent, onError: (_) {});
    _loadInfo();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    // Leaving the pane must kill the child process, like the Compose terminal.
    unawaited(widget.bridge.terminalStop());
    _input.dispose();
    _inputFocus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadInfo() async {
    try {
      final info = await widget.bridge.terminalInfo();
      if (!mounted) return;
      setState(() {
        _info = info;
        _infoLoading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _infoLoading = false);
      widget.onToast('$error', isError: true);
    }
  }

  void _onEvent(TerminalEvent event) {
    if (!mounted) return;
    switch (event) {
      case TerminalChunkEvent(:final text):
        setState(() {
          _live += text;
          if (_live.length > _maxLiveChars) {
            _live = _live.substring(_live.length - _maxLiveChars);
            _liveTruncated = true;
          }
        });
        _pinToBottom();
      case final TerminalDoneEvent done:
        setState(() => _commitOutput(done));
        _pinToBottom();
      case TerminalStoppedEvent():
        setState(() {
          _appendLive();
          _lines.add(const _TerminalLine('已停止', _TerminalKind.info));
          _trimLines();
          _running = false;
        });
        _pinToBottom();
      case final TerminalErrorEvent error:
        setState(() {
          _appendLive();
          _lines.add(_TerminalLine(error.message, _TerminalKind.error));
          _trimLines();
          _running = false;
        });
        _pinToBottom();
    }
  }

  void _commitOutput(TerminalDoneEvent event) {
    _appendLive();
    if (event.truncated) {
      _lines.add(const _TerminalLine('输出过长，已截断', _TerminalKind.info));
    }
    final error = event.error;
    if (error != null && error.isNotEmpty) {
      _lines.add(_TerminalLine(error, _TerminalKind.error));
    } else if (event.exitCode != 0) {
      _lines.add(_TerminalLine('退出码 ${event.exitCode}', _TerminalKind.info));
    }
    _trimLines();
    _cwdRel = event.cwdRel;
    _running = false;
  }

  void _appendLive() {
    final text = _live.trimRight();
    if (text.isNotEmpty) {
      _lines.add(_TerminalLine(text, _TerminalKind.output));
    }
    if (_liveTruncated) {
      _lines.add(const _TerminalLine('输出过长，已截断', _TerminalKind.info));
    }
    _live = '';
    _liveTruncated = false;
  }

  void _trimLines() {
    if (_lines.length <= _maxTerminalLines) return;
    _lines.removeRange(0, _lines.length - _maxTerminalLines);
  }

  Future<void> _submit() async {
    if (_running || !(_info?.available ?? false)) return;
    final command = _input.text.trim();
    if (command.isEmpty) return;
    if (command == 'clear') {
      _input.clear();
      setState(() => _lines.clear());
      return;
    }
    _input.clear();
    setState(() {
      _lines.add(_TerminalLine('\$ $command', _TerminalKind.command));
      _trimLines();
      _running = true;
      _live = '';
      _liveTruncated = false;
    });
    if (_history.isEmpty || _history.last != command) {
      _history.add(command);
      if (_history.length > 50) _history.removeAt(0);
    }
    _historyIndex = _history.length;
    _historyDraft = '';
    _pinToBottom();
    try {
      await widget.bridge.terminalRun(command, cwdRel: _cwdRel);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _lines.add(_TerminalLine('$error', _TerminalKind.error));
        _running = false;
      });
    }
  }

  Future<void> _stop() async {
    try {
      await widget.bridge.terminalStop();
    } catch (error) {
      if (!mounted) return;
      widget.onToast('$error', isError: true);
    }
  }

  KeyEventResult _onTerminalKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
      if (_history.isEmpty) return KeyEventResult.handled;
      if (_historyIndex == _history.length) {
        _historyDraft = _input.text;
      }
      if (_historyIndex > 0) _historyIndex--;
      _setInput(_history[_historyIndex]);
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
      if (_historyIndex >= _history.length) return KeyEventResult.ignored;
      _historyIndex++;
      _setInput(
        _historyIndex == _history.length
            ? _historyDraft
            : _history[_historyIndex],
      );
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  void _setInput(String text) {
    _input.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }

  Future<void> _copyOutput() async {
    final text = [
      for (final line in _lines) line.text,
      if (_live.trim().isNotEmpty) _live.trimRight(),
    ].join('\n');
    if (text.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    widget.onToast('终端输出已复制');
  }

  void _pinToBottom() {
    if (!_followBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  bool _onScroll(ScrollNotification notification) {
    if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom != _followBottom) {
        setState(() => _followBottom = atBottom);
      }
    } else if (notification is ScrollEndNotification) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom && !_followBottom) {
        setState(() => _followBottom = true);
      }
    }
    return false;
  }

  String get _subtitle {
    if (_infoLoading) return '/work';
    final label = _info?.label;
    final rel = _cwdRel.trim().replaceAll(RegExp(r'/+$'), '');
    final path = rel.isEmpty ? '/work' : '/work/$rel';
    return label == null || label.isEmpty ? path : '$label · $path';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final available = _info?.available ?? false;
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverLoading,
        leading: (context) => IconButton(
          onPressed: widget.onBack,
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => const Text('终端'),
        actions: [
          (context) => IconButton(
            onPressed: _lines.isEmpty && _live.isEmpty
                ? null
                : () => unawaited(_copyOutput()),
            icon: const GlyphIcon(Glyphs.copy, size: 19),
            tooltip: '复制输出',
          ),
          (context) => DeferredDisable(
            onPressed: _lines.isEmpty
                ? null
                : () => setState(() => _lines.clear()),
            builder: (context, onPressed) =>
                TextButton(onPressed: onPressed, child: const Text('清空')),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(
              18,
              context.contentTopPadding,
              18,
              6,
            ),
            child: Text(
              _subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote.of(context).copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
          ),
          Expanded(
            child: ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              child: _infoLoading
                  ? const _Loading()
                  : !available
                      ? _TerminalUnavailable(reason: _info?.issue)
                      : _buildOutput(context),
            ),
          ),
          if (!_infoLoading && available)
            ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              heightFactor: 1,
              alignment: Alignment.center,
              child: _buildInput(context),
            ),
        ],
      ),
    );
  }

  Widget _buildOutput(BuildContext context) {
    final scheme = context.colorScheme;
    final liveVisible = _running && (_live.isNotEmpty || _liveTruncated);
    final rows = _lines.length + (liveVisible ? 1 : 0);
    if (rows == 0) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(
            '在 /work 中执行命令；cd 切换目录；clear 清屏。',
            textAlign: TextAlign.center,
            style: context.listSubtitleStyle,
          ),
        ),
      );
    }
    return NotificationListener<ScrollNotification>(
      onNotification: _onScroll,
      child: ListView.builder(
        controller: _scroll,
        padding: EdgeInsets.fromLTRB(14, 4, 14, 14),
        itemCount: rows,
        itemBuilder: (context, index) {
          if (index == _lines.length) {
            return Padding(
              padding: const EdgeInsets.only(top: 2),
              child: SelectableText(
                _live.trimRight(),
                style: _terminalStyle(context).copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            );
          }
          final line = _lines[index];
          return Padding(
            padding: const EdgeInsets.only(top: 2),
            child: SelectableText(
              line.text,
              style: _terminalStyle(context).copyWith(
                color: switch (line.kind) {
                  _TerminalKind.command => scheme.primary,
                  _TerminalKind.error => scheme.error,
                  _TerminalKind.info => scheme.onSurfaceVariant,
                  _TerminalKind.output => scheme.onSurface,
                },
              ),
            ),
          );
        },
      ),
    );
  }

  TextStyle _terminalStyle(BuildContext context) =>
      AppTextStyle.caption1.of(context).copyWith(
            fontFamily: 'monospace',
            height: 1.45,
          );

  Widget _buildInput(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(12, 6, 12, 8 + context.bottomInset),
      child: Container(
        padding: const EdgeInsets.only(left: 14, right: 4),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHigh,
          borderRadius: AppRadius.full,
        ),
        child: Row(
          children: [
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Text(
                '\$',
                style: AppTextStyle.caption1.of(context).copyWith(
                      fontFamily: 'monospace',
                      color: scheme.onSurfaceVariant,
                    ),
              ),
            ),
            Expanded(
              child: Focus(
                onKeyEvent: _onTerminalKey,
                child: TextField(
                  controller: _input,
                  focusNode: _inputFocus,
                  minLines: 1,
                  maxLines: 4,
                  keyboardType: TextInputType.text,
                  textInputAction: TextInputAction.send,
                  onSubmitted: (_) => _submit(),
                  decoration: const InputDecoration(
                    hintText: '在 /work 中执行命令…',
                    border: NoInputBorder(),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 4),
            IconButton.filled(
              onPressed: _running ? _stop : _submit,
              icon: GlyphIcon(
                _running ? AppGlyphs.stop : AppGlyphs.send,
                size: 20,
              ),
              tooltip: _running ? '停止' : '运行',
            ),
          ],
        ),
      ),
    );
  }
}

class _TerminalUnavailable extends StatelessWidget {
  const _TerminalUnavailable({this.reason});

  final String? reason;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GlyphIcon(
              AppGlyphs.sparkle,
              size: 34,
              color: context.colorScheme.primary,
            ),
            const SizedBox(height: 16),
            Text(
              reason ?? '引擎未安装，请先在设置中安装 Claude Code。',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 8),
            Text(
              '请在设置中安装引擎或切换引擎模式，然后重新打开终端。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
          ],
        ),
      ),
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: SizedBox(
        width: 44,
        child: LinearProgressIndicator(minHeight: 2),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

String _basename(String path) {
  final cut = path.lastIndexOf('/');
  return cut < 0 ? path : path.substring(cut + 1);
}

String _humanBytes(int bytes) {
  if (bytes >= 1 << 30) return '${(bytes / (1 << 30)).toStringAsFixed(1)} GB';
  if (bytes >= 1 << 20) return '${(bytes / (1 << 20)).toStringAsFixed(1)} MB';
  if (bytes >= 10 << 10) return '${(bytes / (1 << 10)).toStringAsFixed(0)} KB';
  if (bytes >= 1 << 10) return '${(bytes / (1 << 10)).toStringAsFixed(1)} KB';
  return '$bytes B';
}

String _fileTime(int timestamp) {
  final time = DateTime.fromMillisecondsSinceEpoch(timestamp);
  final now = DateTime.now();
  final diff = now.difference(time);
  if (!diff.isNegative && diff.inMinutes < 1) return '刚刚';
  if (!diff.isNegative && diff.inMinutes < 60) return '${diff.inMinutes} 分钟前';
  final sameDay =
      time.year == now.year && time.month == now.month && time.day == now.day;
  final hm = '${time.hour.toString().padLeft(2, '0')}:'
      '${time.minute.toString().padLeft(2, '0')}';
  if (sameDay) return hm;
  return '${time.month.toString().padLeft(2, '0')}-'
      '${time.day.toString().padLeft(2, '0')} $hm';
}

final Listenable _neverBar = _NeverNotifier();
final ValueListenable<bool> _neverLoading = _NeverValue();

class _NeverNotifier extends ChangeNotifier {}

class _NeverValue implements ValueListenable<bool> {
  const _NeverValue();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
