import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../bridge/api.dart';
import '../app_search_field.dart';
import '../glyphs.dart';
import '../layout.dart';
import '../press_guard.dart';
import '../selection_menu.dart';
import 'log_filter.dart';
import 'settings_widgets.dart';

class LogsPage extends StatefulWidget {
  const LogsPage({super.key, required this.bridge, required this.onToast});

  final Bridge bridge;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<LogsPage> createState() => _LogsPageState();
}

class _LogsPageState extends State<LogsPage> {
  final TextEditingController _search = TextEditingController();
  List<String> _lines = const [];
  String _query = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final lines = await widget.bridge.readLogs();
      if (!mounted) return;
      setState(() {
        _lines = lines;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loading = false);
      widget.onToast('读取日志失败（$error）', isError: true);
    }
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _lines.join('\n')));
    if (!mounted) return;
    widget.onToast('日志已复制');
  }

  Future<void> _clear() async {
    try {
      await widget.bridge.clearLogs();
      if (!mounted) return;
      _search.clear();
      setState(() {
        _lines = const [];
        _query = '';
      });
    } catch (error) {
      widget.onToast('清空日志失败（$error）', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '引擎日志',
      listenable: settingsNeverBar,
      actions: [
        IconButton(
          onPressed: _lines.isEmpty ? null : _copy,
          icon: const GlyphIcon(Glyphs.copy),
          tooltip: '复制全部',
        ),
        DeferredDisable(
          onPressed: _lines.isEmpty ? null : _clear,
          builder: (context, onPressed) => IconButton(
            onPressed: onPressed,
            icon: const GlyphIcon(AppGlyphs.trash),
            tooltip: '清空日志',
          ),
        ),
      ],
      body: _loading
          ? Center(
              child: ActivityIndicator(
                size: 22,
                color: context.colorScheme.onSurfaceVariant,
              ),
            )
          : _body(context),
    );
  }

  Widget _body(BuildContext context) {
    final visible = filterLogLines(_lines, _query);
    final hasQuery = _query.trim().isNotEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_lines.isNotEmpty) ...[
          Padding(
            padding: EdgeInsets.fromLTRB(
              kPageInset,
              context.contentTopPadding,
              kPageInset,
              6,
            ),
            child: AppSearchField(
              controller: _search,
              hintText: '过滤日志',
              height: 44,
              onChanged: (value) => setState(() => _query = value),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(kPageInset, 0, kPageInset, 6),
            child: Text(
              hasQuery ? '${visible.length} / ${_lines.length} 行' : '${_lines.length} 行',
              style: AppTextStyle.caption2.of(context).copyWith(
                    color: context.colorScheme.onSurfaceVariant,
                  ),
            ),
          ),
        ],
        Expanded(
          child: SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(
              kPageInset,
              _lines.isEmpty ? context.contentTopPadding : 0,
              kPageInset,
              24 + context.bottomInset,
            ),
            child: SelectableText(
              _lines.isEmpty
                  ? '暂无日志'
                  : visible.isEmpty
                      ? '没有匹配的日志'
                      : visible.join('\n'),
              contextMenuBuilder: appEditableTextMenu,
              style: AppTextStyle.caption1
                  .of(context)
                  .copyWith(fontFamily: 'monospace', height: 1.45),
            ),
          ),
        ),
      ],
    );
  }
}
