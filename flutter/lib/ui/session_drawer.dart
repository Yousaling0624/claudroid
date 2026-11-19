import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../models/models.dart';
import 'dialog_motion.dart';
import 'glyphs.dart';
import 'settings/settings_destination.dart';
import 'settings/settings_dialogs.dart';
import 'time_format.dart';

/// The session drawer, laid out like `ui/components/SessionDrawer.kt`: brand
/// row, new-chat capsule, search, history grouped by day, and each row's own
/// rename/delete menu.
///
/// This sits inside `showLeftSideSheet`, which is not a [PrismScaffold], so
/// insets come from [MediaQuery] rather than the scaffold extensions.
class SessionDrawer extends StatefulWidget {
  const SessionDrawer({
    super.key,
    required this.sessions,
    required this.activeId,
    required this.engine,
    required this.onSelect,
    required this.onNew,
    required this.onRename,
    required this.onDelete,
    required this.onWorkspace,
    required this.onSettings,
    required this.onClose,
  });

  final List<ChatSession> sessions;
  final String? activeId;
  final EngineState engine;
  final ValueChanged<String> onSelect;
  final VoidCallback onNew;
  final void Function(String id, String title) onRename;
  final ValueChanged<String> onDelete;
  final VoidCallback onWorkspace;
  final ValueChanged<SettingsDestination> onSettings;
  final VoidCallback onClose;

  @override
  State<SessionDrawer> createState() => _SessionDrawerState();
}

class _SessionDrawerState extends State<SessionDrawer> {
  final _search = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _rename(ChatSession session) async {
    final title = await showClaudroidDialog<String>(
      context: context,
      child: SettingsTextPrompt(
        title: '重命名对话',
        initial: session.title,
        hint: '给这段对话起个名字',
        singleLine: true,
        maxLength: 80,
        validator: (value) =>
            (value ?? '').trim().isEmpty ? '名字不能为空' : null,
      ),
    );
    if (title == null) return;
    final trimmed = title.trim();
    if (trimmed.isEmpty || trimmed == session.title) return;
    widget.onRename(session.id, trimmed);
  }

  // Same as the Compose drawer: the red menu item deletes straight away, no
  // second dialog. The menu item itself is the confirmation.
  void _delete(ChatSession session) => widget.onDelete(session.id);

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final safe = MediaQuery.paddingOf(context);
    final groups = _group(widget.sessions, _query);
    final count =
        groups.fold<int>(0, (sum, group) => sum + group.sessions.length);

    return Surface(
      color: SurfaceLevel.page.colorOf(scheme),
      child: Padding(
        padding: EdgeInsets.only(top: safe.top),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _header(context),
            _newChat(context),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 0),
              child: Container(
                height: 50,
                padding: const EdgeInsets.symmetric(horizontal: 14),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHigh,
                  borderRadius: AppRadius.full,
                ),
                child: SearchField(
                  controller: _search,
                  hintText: '搜索对话',
                  height: 50,
                  onChanged: (value) => setState(() => _query = value),
                  trailing: _query.isEmpty
                      ? null
                      : IconButton(
                          onPressed: () {
                            _search.clear();
                            setState(() => _query = '');
                          },
                          icon: const GlyphIcon(Glyphs.close, size: 16),
                          tooltip: '清空搜索',
                        ),
                ),
              ),
            ),
            Expanded(
              child: groups.isEmpty
                  ? _empty(context)
                  : ListView(
                      padding: const EdgeInsets.only(bottom: 8),
                      children: [
                        _allConversations(context, count),
                        for (final group in groups) ...[
                          Padding(
                            padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
                            child: Text(
                              group.label,
                              style: AppTextStyle.footnote
                                  .of(context, emphasized: true)
                                  .copyWith(color: scheme.onSurfaceVariant),
                            ),
                          ),
                          for (final session in group.sessions)
                            Padding(
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 10),
                              child: _SessionRow(
                                session: session,
                                selected: session.id == widget.activeId,
                                onPressed: () => widget.onSelect(session.id),
                                onRename: () => _rename(session),
                                onDelete: () => _delete(session),
                              ),
                            ),
                        ],
                      ],
                    ),
            ),
            _footer(context),
          ],
        ),
      ),
    );
  }

  /// The reference drawer's "all conversations" row: a quiet list title with
  /// the live count, so the grouping below reads as a drill-down not a flat
  /// log. There is no filter menu yet, so no chevron advertises one.
  Widget _allConversations(BuildContext context, int count) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 0),
      child: Row(
        children: [
          Text(
            '全部对话',
            style: AppTextStyle.callout.of(context, emphasized: true),
          ),
          const Spacer(),
          Text(
            '$count 条',
            style: AppTextStyle.caption2
                .of(context)
                .copyWith(color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }

  /// Pinned footer, the same shape as `DrawerEnginePill` in the Compose drawer:
  /// a two-line status capsule that opens settings, then workspace and settings
  /// glass buttons.
  Widget _footer(BuildContext context) {
    final scheme = context.colorScheme;
    final engine = widget.engine;
    final updateAvailable = engine.installedVersion != null &&
        engine.latestVersion != null &&
        engine.latestVersion != engine.installedVersion;
    final title = engine.engineLabel.isEmpty ? '引擎状态' : engine.engineLabel;
    final String status;
    final Color dot;
    if (engine.phase == 'Failed') {
      status = '不可用';
      dot = scheme.error;
    } else if (engine.phase == 'Working') {
      final stage = engine.stage.isEmpty ? '正在准备' : engine.stage;
      status = '$stage ${(engine.fraction * 100).round()}%';
      dot = scheme.primary;
    } else if (updateAvailable) {
      status = '可更新';
      dot = scheme.primary;
    } else if (engine.ready) {
      status = '运行中';
      dot = scheme.success;
    } else {
      status = '未就绪';
      dot = scheme.onSurfaceVariant;
    }
    return Padding(
      padding: EdgeInsets.fromLTRB(
        12,
        8,
        12,
        8 + MediaQuery.paddingOf(context).bottom,
      ),
      child: Row(
        children: [
          Expanded(
            child: Semantics(
              button: true,
              label: '引擎状态：$title · $status，打开引擎设置',
              child: PressSurface(
                fill: scheme.surfaceContainerHigh,
                shape: AppShape.full,
                onPressed: () =>
                    widget.onSettings(SettingsDestination.engine),
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: SizedBox(
                  height: 56,
                  child: Row(
                    children: [
                      Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          color: scheme.surfaceBright,
                          shape: BoxShape.circle,
                        ),
                        child: Center(
                          child: GlyphIcon(
                            AppGlyphs.cpu,
                            size: 18,
                            color: scheme.primary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: AppTextStyle.footnote
                                  .of(context, emphasized: true)
                                  .copyWith(color: scheme.onSurface),
                            ),
                            const SizedBox(height: 2),
                            Row(
                              children: [
                                Container(
                                  width: 6,
                                  height: 6,
                                  decoration: BoxDecoration(
                                    color: dot,
                                    shape: BoxShape.circle,
                                  ),
                                ),
                                const SizedBox(width: 5),
                                Expanded(
                                  child: Text(
                                    status,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: AppTextStyle.caption2
                                        .of(context)
                                        .copyWith(
                                          color: scheme.onSurfaceVariant,
                                        ),
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 4),
                      GlyphIcon(
                        Glyphs.picker,
                        size: 14,
                        color: scheme.onSurfaceVariant,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          _GlassIconButton(
            onPressed: widget.onWorkspace,
            glyph: AppGlyphs.folder,
            tooltip: '工作区',
          ),
          const SizedBox(width: 8),
          _GlassIconButton(
            onPressed: () => widget.onSettings(SettingsDestination.root),
            glyph: AppGlyphs.settings,
            tooltip: '设置',
          ),
        ],
      ),
    );
  }

  Widget _header(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 10, 0),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: scheme.primaryContainer,
              shape: BoxShape.circle,
            ),
            child: Center(
              child: GlyphIcon(
                AppGlyphs.sparkle,
                size: 20,
                color: scheme.primary,
              ),
            ),
          ),
          const SizedBox(width: 10),
          Text(
            'Claudroid',
            style: AppTextStyle.headline
                .of(context)
                .copyWith(color: scheme.onSurface),
          ),
          const Spacer(),
          _GlassIconButton(
            onPressed: widget.onClose,
            glyph: Glyphs.close,
            size: 18,
            tooltip: '收起',
          ),
        ],
      ),
    );
  }

  Widget _newChat(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 14, 14, 0),
      child: PressSurface(
        fill: scheme.primaryContainer,
        shape: AppShape.full,
        onPressed: widget.onNew,
        padding: EdgeInsets.zero,
        child: SizedBox(
          height: 50,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              GlyphIcon(
                AppGlyphs.plus,
                size: 18,
                color: scheme.onPrimaryContainer,
              ),
              const SizedBox(width: 8),
              Text(
                '新对话',
                style: AppTextStyle.callout
                    .of(context, emphasized: true)
                    .copyWith(color: scheme.onPrimaryContainer),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _empty(BuildContext context) {
    final scheme = context.colorScheme;
    final searching = _query.trim().isNotEmpty;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 36),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          GlyphIcon(AppGlyphs.sparkle, size: 26, color: scheme.onSurfaceVariant),
          const SizedBox(height: 12),
          Text(
            searching ? '没有找到相关对话' : '还没有对话',
            style: AppTextStyle.subheadline
                .of(context)
                .copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(height: 4),
          Text(
            searching ? '试试标题里的关键词。' : '从上方「新对话」开始。',
            textAlign: TextAlign.center,
            style: AppTextStyle.footnote
                .of(context)
                .copyWith(color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class _SessionRow extends StatefulWidget {
  const _SessionRow({
    required this.session,
    required this.selected,
    required this.onPressed,
    required this.onRename,
    required this.onDelete,
  });

  final ChatSession session;
  final bool selected;
  final VoidCallback onPressed;
  final VoidCallback onRename;
  final VoidCallback onDelete;

  @override
  State<_SessionRow> createState() => _SessionRowState();
}

class _SessionRowState extends State<_SessionRow> {
  @override
  Widget build(BuildContext context) {
    final session = widget.session;
    final scheme = context.colorScheme;
    return PressSurface(
      fill: widget.selected
          ? scheme.surfaceContainerHigh
          : const Color(0x00000000),
      shape: AppShape.all(AppCorner.md),
      onPressed: widget.onPressed,
      padding: EdgeInsets.zero,
      child: SizedBox(
        height: 52,
        child: Row(
          children: [
            const SizedBox(width: 14),
            Expanded(
              child: Text(
                session.title.isEmpty ? '新对话' : session.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyle.callout
                    .of(context, emphasized: widget.selected)
                    .copyWith(color: scheme.onSurface),
              ),
            ),
            const SizedBox(width: 8),
            Text(
              sessionTimeLabel(session.updatedAt),
              style: AppTextStyle.caption2
                  .of(context)
                  .copyWith(color: scheme.onSurfaceVariant),
            ),
            PopupAnchor(
              targetBuilder: (open) => IconButton(
                onPressed: open,
                icon: const GlyphIcon(Glyphs.more, size: 18),
                tooltip: '对话选项',
              ),
              popupBuilder: (_) => PopupMenu(
                items: [
                  PopupMenuItem(
                    label: '重命名',
                    glyph: AppGlyphs.edit,
                    onPressed: widget.onRename,
                  ),
                  PopupMenuItem(
                    label: '删除对话',
                    glyph: AppGlyphs.trash,
                    danger: true,
                    onPressed: widget.onDelete,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A circular glass icon key, the same material as the bar's icon buttons.
class _GlassIconButton extends StatelessWidget {
  const _GlassIconButton({
    required this.onPressed,
    required this.glyph,
    required this.tooltip,
    this.size = 19,
  });

  final VoidCallback onPressed;
  final Glyph glyph;
  final String tooltip;
  final double size;

  @override
  Widget build(BuildContext context) {
    return TonalButtonTheme(
      glass: true,
      grouped: false,
      size: TonalButtonSize.bar,
      child: IconButton(
        onPressed: onPressed,
        icon: GlyphIcon(glyph, size: size),
        tooltip: tooltip,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Grouping
// ---------------------------------------------------------------------------

class _Group {
  const _Group(this.label, this.sessions);

  final String label;
  final List<ChatSession> sessions;
}

/// "今天 / 昨天 / 过去 7 天 / 更早", the same buckets as `SessionGrouping.kt`.
///
/// Sessions created but never used are hidden, matching the Compose drawer.
/// Search matches the title and the truncated transcript text the host sends
/// alongside each summary.
List<_Group> _group(List<ChatSession> sessions, String query) {
  final needle = query.trim().toLowerCase();
  final visible = [
    for (final session in sessions)
      if (session.hasContent &&
          (needle.isEmpty ||
              session.title.toLowerCase().contains(needle) ||
              session.searchText.toLowerCase().contains(needle)))
        session,
  ]..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

  final today = _dayOf(DateTime.now());
  final buckets = <String, List<ChatSession>>{};
  for (final session in visible) {
    final day = _dayOf(DateTime.fromMillisecondsSinceEpoch(
      session.updatedAt == 0 ? session.createdAt : session.updatedAt,
    ));
    // Clock skew can date a transcript "tomorrow"; count it as today rather
    // than dropping it into a bucket nobody can reason about.
    final days = today.difference(day).inDays;
    final label = days <= 0
        ? '今天'
        : days == 1
            ? '昨天'
            : days < 7
                ? '过去 7 天'
                : '更早';
    buckets.putIfAbsent(label, () => []).add(session);
  }
  return [
    for (final label in const ['今天', '昨天', '过去 7 天', '更早'])
      if (buckets[label]?.isNotEmpty ?? false) _Group(label, buckets[label]!),
  ];
}

DateTime _dayOf(DateTime value) => DateTime(value.year, value.month, value.day);
