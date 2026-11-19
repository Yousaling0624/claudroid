import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'dialog_motion.dart';
import 'glass_controls.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'markdown.dart';
import 'scaffold_bar.dart';
import 'terminal_page.dart';
import 'workspace_logic.dart';
import 'workspace_switcher.dart';

/// Read-only browser for the jail's `/work` directory, plus a source/rendered
/// preview and the persistent shell. Kotlin owns listing, path checks, file
/// reads, the PTY and intents; this page only renders what the bridge returns.
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

  /// Active workspace display name; blank until the first `workspace.spaces`.
  String _workspaceName = '';

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
    _loadWorkspaceName();
    _loadListing();
  }

  Future<void> _loadWorkspaceName() async {
    try {
      final spaces = await widget.bridge.listWorkspaces();
      if (!mounted) return;
      setState(() => _workspaceName = spaces.active?.name ?? '');
    } catch (_) {
      // The title falls back to 工作区; the picker retries when opened.
    }
  }

  Future<void> _switchWorkspace() async {
    final changed = await showWorkspaceSwitcher(
      context,
      bridge: widget.bridge,
      onToast: widget.onToast,
    );
    if (!changed || !mounted) return;
    _previewGeneration++;
    _listingGeneration++;
    _clearFilter();
    setState(() {
      _path = '';
      _listing = null;
      _loading = true;
      _listingError = null;
      _previewPath = null;
      _preview = null;
      _previewError = null;
      _previewLoading = false;
      _terminalOpen = false;
    });
    await _loadWorkspaceName();
    await _loadListing();
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
    final confirmed = await showClaudroidDialog<bool>(
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
          ? TerminalPage(
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
        title: (context) {
          if (_path.isNotEmpty) return Text(_basename(_path));
          final label = _workspaceName.isEmpty ? '工作区' : _workspaceName;
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _switchWorkspace,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 4),
                GlyphIcon(
                  Glyphs.chevronDown,
                  size: 16,
                  color: context.colorScheme.onSurfaceVariant,
                ),
              ],
            ),
          );
        },
        actions: [
          (context) => IconButton(
            onPressed: _switchWorkspace,
            icon: const GlyphIcon(AppGlyphs.folder, size: 19),
            tooltip: '切换工作区',
          ),
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
                PopupMenuItem(
                  label: '切换工作区…',
                  glyph: AppGlyphs.folder,
                  onPressed: _switchWorkspace,
                ),
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
            TextButton.icon(
              onPressed: onRetry,
              icon: const GlyphIcon(AppGlyphs.refresh, size: 16),
              label: const Text('重试'),
            ),
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
