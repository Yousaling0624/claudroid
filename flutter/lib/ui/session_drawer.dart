import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../models/models.dart';
import 'glyphs.dart';

/// The session drawer, laid out like `ui/components/SessionDrawer.kt`: brand
/// row, new-chat capsule, search, history grouped by day, and each row's own
/// rename/delete menu.
///
/// This sits inside `showModalSideSheet`, which is not a [PrismScaffold], so
/// insets come from [MediaQuery] rather than the scaffold extensions.
class SessionDrawer extends StatefulWidget {
  const SessionDrawer({
    super.key,
    required this.sessions,
    required this.activeId,
    required this.onSelect,
    required this.onNew,
    required this.onRename,
    required this.onDelete,
    required this.onClose,
  });

  final List<ChatSession> sessions;
  final String? activeId;
  final ValueChanged<String> onSelect;
  final VoidCallback onNew;
  final void Function(String id, String title) onRename;
  final ValueChanged<String> onDelete;
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
    final title = await showFloatingDialog<String>(
      context: context,
      child: _RenameDialog(title: session.title),
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
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 8),
              child: SearchField(
                controller: _search,
                hintText: '搜索对话',
                height: 44,
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
            Expanded(
              child: groups.isEmpty
                  ? _empty(context)
                  : ListView(
                      padding: EdgeInsets.only(bottom: 8 + safe.bottom),
                      children: [
                        for (final group in groups)
                          generateSection(
                            title: group.label,
                            items: [
                              for (final session in group.sessions)
                                _SessionRow(
                                  session: session,
                                  selected: session.id == widget.activeId,
                                  onPressed: () => widget.onSelect(session.id),
                                  onRename: () => _rename(session),
                                  onDelete: () => _delete(session),
                                ),
                            ],
                          ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final scheme = context.colorScheme;
    return SizedBox(
      height: 56,
      child: Padding(
        padding: const EdgeInsets.only(left: 18, right: 8),
        child: Row(
          children: [
            GlyphIcon(AppGlyphs.sparkle, size: 17, color: scheme.primary),
            const SizedBox(width: 7),
            Text(
              'Claudroid',
              style: AppTextStyle.headline
                  .of(context)
                  .copyWith(color: scheme.onSurface),
            ),
            const Spacer(),
            IconButton(
              onPressed: widget.onClose,
              icon: const GlyphIcon(Glyphs.close, size: 18),
              tooltip: '收起',
            ),
          ],
        ),
      ),
    );
  }

  Widget _newChat(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14),
      child: Card(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        onPressed: widget.onNew,
        child: SizedBox(
          height: 52,
          child: Row(
            children: [
              GlyphIcon(AppGlyphs.plus, size: 18, color: scheme.primary),
              const SizedBox(width: 8),
              Text(
                '新对话',
                style: AppTextStyle.callout.of(context, emphasized: true),
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
    return DecorationListItem(
      isSelected: widget.selected,
      onPressed: widget.onPressed,
      title: Text(
        session.title.isEmpty ? '新对话' : session.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(_timeLabel(session.updatedAt)),
      trailing: PopupAnchor(
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
    );
  }
}

class _RenameDialog extends StatefulWidget {
  const _RenameDialog({required this.title});

  final String title;

  @override
  State<_RenameDialog> createState() => _RenameDialogState();
}

class _RenameDialogState extends State<_RenameDialog> {
  late final TextEditingController _controller =
      TextEditingController(text: widget.title);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: '重命名对话',
      hasFields: true,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(_controller.text),
          child: const Text('保存'),
        ),
      ],
      child: TextField(
        controller: _controller,
        autofocus: true,
        maxLines: 1,
        decoration: const InputDecoration(hintText: '给这段对话起个名字'),
        onSubmitted: (value) => Navigator.of(context).pop(value),
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
/// The bridge sends summaries only, so search matches titles; the Compose drawer
/// also greps message text because it has the full session on hand.
List<_Group> _group(List<ChatSession> sessions, String query) {
  final needle = query.trim().toLowerCase();
  final visible = [
    for (final session in sessions)
      if (needle.isEmpty || session.title.toLowerCase().contains(needle))
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

String _timeLabel(int millis) {
  if (millis <= 0) return '';
  final now = DateTime.now();
  final time = DateTime.fromMillisecondsSinceEpoch(millis);
  final days = _dayOf(now).difference(_dayOf(time)).inDays;
  final clock =
      '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
  if (days <= 0) return clock;
  if (days == 1) return '昨天 $clock';
  if (days < 7) return '${time.month}月${time.day}日';
  if (time.year != now.year) {
    return '${time.year}年${time.month}月${time.day}日';
  }
  return '${time.month}月${time.day}日';
}
