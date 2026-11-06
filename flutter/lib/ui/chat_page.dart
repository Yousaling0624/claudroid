import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import '../models/models.dart';
import 'glyphs.dart';

/// One conversation. Mirrors `ui/chat/ChatScreen.kt`: a floating bar with the
/// title and the live status line, the transcript, and a composer pinned under
/// it. All state comes from the host; this page only folds the session stream
/// and decides where to scroll.
class ChatPage extends StatefulWidget {
  const ChatPage({
    super.key,
    required this.bridge,
    required this.sessionId,
    required this.settings,
    required this.labels,
    required this.onOpenDrawer,
    required this.onOpenSettings,
    required this.onNewChat,
    required this.onUpdateSettings,
    required this.onToast,
  });

  final Bridge bridge;
  final String sessionId;
  final AppSettings settings;
  final ModelLabels labels;
  final VoidCallback onOpenDrawer;
  final VoidCallback onOpenSettings;
  final VoidCallback onNewChat;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  late final ChatEvents _events = ChatEvents(widget.sessionId);
  StreamSubscription<ChatState>? _subscription;

  final ScrollController _scroll = ScrollController();
  final TextEditingController _input = TextEditingController();
  final FocusNode _inputFocus = FocusNode();
  final _BarTick _barTick = _BarTick();
  final ValueNotifier<bool> _loading = ValueNotifier<bool>(false);

  ChatState _chat = const ChatState();

  /// Sticks to the bottom while the answer streams, and lets go as soon as
  /// the reader scrolls up — never yanks them back.
  bool _followBottom = true;

  String? _editingId;
  String? _copiedId;
  Timer? _copiedTimer;

  @override
  void initState() {
    super.initState();
    _subscription = _events.states.listen(_applyState, onError: (_) {});
    _attach();
  }

  @override
  void dispose() {
    _copiedTimer?.cancel();
    _subscription?.cancel();
    _scroll.dispose();
    _input.dispose();
    _inputFocus.dispose();
    _barTick.dispose();
    _loading.dispose();
    // The turn keeps running on the host; only this view lets go of it.
    unawaited(widget.bridge.forget(widget.sessionId));
    super.dispose();
  }

  Future<void> _attach() async {
    try {
      final snapshot = await widget.bridge.attach(widget.sessionId);
      if (!mounted || _chat.messages.isNotEmpty) return;
      _applyState(snapshot);
    } catch (error) {
      if (!mounted) return;
      widget.onToast(
        '没能打开这段对话（$error）。点右上角新建一个对话试试。',
        isError: true,
      );
    }
  }

  void _applyState(ChatState next) {
    if (!mounted) return;
    final notice = next.titleNotice;
    setState(() => _chat = next);
    _barTick.bump();
    if (notice != null && notice.isNotEmpty) {
      widget.onToast(notice);
      unawaited(widget.bridge.consumeTitleNotice(widget.sessionId));
    }
    if (_followBottom) _pinToBottom();
  }

  void _pinToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  bool _onScrollNotification(ScrollNotification notification) {
    if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom != _followBottom) {
        setState(() => _followBottom = atBottom);
      }
    } else if (notification is ScrollEndNotification) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom && !_followBottom) setState(() => _followBottom = true);
    }
    return false;
  }

  // -- actions -------------------------------------------------------------

  Future<void> _submit() async {
    final text = _input.text.trim();
    if (text.isEmpty || _chat.running) return;
    final editing = _editingId;
    try {
      final accepted = editing == null
          ? await widget.bridge.send(widget.sessionId, text)
          : await widget.bridge.resendFrom(widget.sessionId, editing, text);
      if (!accepted) {
        widget.onToast('这一轮还没结束。先点停止，再发送。', isError: true);
        return;
      }
      _input.clear();
      if (!mounted) return;
      setState(() {
        _editingId = null;
        _followBottom = true;
      });
      _pinToBottom();
    } catch (error) {
      // Keep the draft so nothing typed is lost.
      widget.onToast('没能发送（$error）。再点一次发送，或稍后重试。', isError: true);
    }
  }

  Future<void> _stop() async {
    try {
      await widget.bridge.stop(widget.sessionId);
    } catch (error) {
      widget.onToast('停止失败（$error）。稍等片刻再试。', isError: true);
    }
  }

  Future<void> _clear() async {
    _input.clear();
    setState(() => _editingId = null);
    try {
      await widget.bridge.clear(widget.sessionId);
    } catch (error) {
      widget.onToast('没能清空（$error）。再试一次。', isError: true);
    }
  }

  Future<void> _regenerateTitle() async {
    try {
      await widget.bridge.regenerateTitle(widget.sessionId);
    } catch (error) {
      widget.onToast('标题没能重来（$error）。再点一次。', isError: true);
    }
  }

  Future<void> _regenerate(ChatMessage message) async {
    try {
      await widget.bridge.regenerate(widget.sessionId, message.id);
    } catch (error) {
      widget.onToast('重新回答失败（$error）。再点一次。', isError: true);
    }
  }

  void _startEditing(ChatMessage message) {
    final text = message.blocks
        .whereType<TextBlock>()
        .map((b) => b.text)
        .join('\n');
    setState(() => _editingId = message.id);
    _input.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
    _inputFocus.requestFocus();
  }

  Future<void> _copy(ChatMessage message) async {
    final text = message.blocks
        .whereType<TextBlock>()
        .map((b) => b.text)
        .join('\n');
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    setState(() => _copiedId = message.id);
    _copiedTimer?.cancel();
    _copiedTimer = Timer(const Duration(milliseconds: 1400), () {
      if (mounted) setState(() => _copiedId = null);
    });
  }

  void _patch(Map<String, dynamic> patch) {
    unawaited(widget.onUpdateSettings(patch));
  }

  // -- build ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ScaffoldBar(
        isCompact: context.isInCompactSheet,
        listenable: _barTick,
        loading: _loading,
        builder: _buildBar,
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              children: [
                NotificationListener<ScrollNotification>(
                  onNotification: _onScrollNotification,
                  child: _buildTranscript(context),
                ),
                if (_chat.messages.isNotEmpty && !_followBottom)
                  Positioned(
                    right: 16,
                    bottom: 10,
                    child: _BackToBottomButton(
                      onPressed: () {
                        setState(() => _followBottom = true);
                        if (_scroll.hasClients) {
                          _scroll.animateTo(
                            _scroll.position.maxScrollExtent,
                            duration: context.motionDuration(
                              const Duration(milliseconds: 260),
                            ),
                            curve: Curves.easeOutCubic,
                          );
                        }
                      },
                    ),
                  ),
              ],
            ),
          ),
          _buildComposer(context),
        ],
      ),
    );
  }

  Widget _buildBar(BuildContext context) {
    final statusLine = _chat.running ? _chat.statusLine : '';
    return AppBar(
      leading: IconButton(
        onPressed: widget.onOpenDrawer,
        icon: const GlyphIcon(AppGlyphs.menu),
        tooltip: '会话',
      ),
      title: PopupAnchor(
        targetBuilder: (open) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: open,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(
                    child: Text(
                      _chat.title.isEmpty ? '新对话' : _chat.title,
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
              if (statusLine.isNotEmpty)
                Text(
                  statusLine,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTextStyle.caption1.of(context).copyWith(
                        color: context.colorScheme.onSurfaceVariant,
                      ),
                ),
            ],
          ),
        ),
        popupBuilder: (_) => PopupMenu(
          items: [
            PopupMenuItem(
              label: '清空对话上下文',
              glyph: AppGlyphs.trash,
              danger: true,
              onPressed: _clear,
            ),
            PopupMenuItem(
              label: _chat.titleBusy ? '正在生成标题…' : '重新生成标题',
              glyph: AppGlyphs.sparkle,
              onPressed: !_chat.running &&
                      !_chat.titleBusy &&
                      _chat.messages.isNotEmpty
                  ? _regenerateTitle
                  : null,
            ),
          ],
        ),
      ),
      actions: [
        PopupAnchor(
          targetBuilder: (open) => TextButton(
            onPressed: open,
            style: ButtonStyle.from(
              foregroundColor: context.colorScheme.onSurfaceVariant,
              textStyle: context.listTitleStyle,
              padding: const EdgeInsets.symmetric(horizontal: 8),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 120),
                  child: Text(
                    widget.labels.short,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 4),
                const GlyphIcon(Glyphs.picker, size: 16),
              ],
            ),
          ),
          popupBuilder: (_) => _modelMenu(),
        ),
        IconButton(
          onPressed: widget.onNewChat,
          icon: const GlyphIcon(AppGlyphs.plus),
          tooltip: '新对话',
        ),
      ],
    );
  }

  Widget _modelMenu() {
    final activeTier = widget.settings.str('activeTier');
    String tierLabel(String tier) =>
        widget.labels.tierMapping[tier] ?? tier;

    return PopupMenu(
      items: [
        PopupMenuItem(
          label: 'CLI 默认（不指定模型）',
          checked: activeTier.isEmpty,
          onPressed: () => _patch({'activeTier': ''}),
        ),
        PopupMenuItem(
          label: '自动（按任务路由）',
          checked: activeTier == 'AUTO',
          onPressed: () => _patch({'activeTier': 'AUTO'}),
        ),
        for (final tier in const ['OPUS', 'FABLE', 'SONNET', 'HAIKU'])
          PopupMenuItem(
            label: tierLabel(tier),
            checked: activeTier == tier,
            onPressed: () => _patch({'activeTier': tier}),
          ),
        PopupMenuItem(
          label: '接口与模型…',
          section: const PopupMenuSection(title: '设置'),
          onPressed: widget.onOpenSettings,
        ),
      ],
    );
  }

  Widget _buildTranscript(BuildContext context) {
    if (_chat.messages.isEmpty) {
      return _EmptyConversation(
        onSuggestion: (text) {
          _input.text = text;
          _inputFocus.requestFocus();
        },
      );
    }
    return ListView.separated(
      controller: _scroll,
      padding: EdgeInsets.fromLTRB(
        20,
        context.contentTopPadding,
        20,
        24,
      ),
      itemCount: _chat.messages.length,
      separatorBuilder: (_, _) => const SizedBox(height: 32),
      itemBuilder: (context, index) {
        final message = _chat.messages[index];
        final isLastAssistant = message.role == Role.assistant &&
            index == _chat.messages.length - 1;
        return _MessageView(
          message: message,
          isLastAssistant: isLastAssistant,
          running: _chat.running,
          copied: _copiedId == message.id,
          onEdit: message.role == Role.user
              ? () => _startEditing(message)
              : null,
          onRegenerate:
              isLastAssistant ? () => _regenerate(message) : null,
          onCopy: () => _copy(message),
        );
      },
    );
  }

  Widget _buildComposer(BuildContext context) {
    final editing = _editingId != null;
    return Padding(
      padding: EdgeInsets.fromLTRB(12, 8, 12, 8 + context.bottomInset),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (editing)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '正在修改这条消息',
                      style: context.listCaptionStyle,
                    ),
                  ),
                  TextButton(
                    onPressed: () {
                      _input.clear();
                      setState(() => _editingId = null);
                    },
                    child: const Text('取消'),
                  ),
                ],
              ),
            ),
          Card(
            padding: const EdgeInsets.fromLTRB(12, 6, 6, 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    focusNode: _inputFocus,
                    minLines: 1,
                    maxLines: 6,
                    keyboardType: TextInputType.multiline,
                    textInputAction: TextInputAction.newline,
                    decoration: InputDecoration(
                      hintText: editing ? '修改这条消息…' : '输入回答…',
                      border: const NoInputBorder(),
                    ),
                    onSubmitted: (_) => _submit(),
                  ),
                ),
                const SizedBox(width: 6),
                IconButton.filled(
                  onPressed: _chat.running ? _stop : _submit,
                  icon: GlyphIcon(
                    _chat.running ? AppGlyphs.stop : AppGlyphs.send,
                    size: 20,
                  ),
                  tooltip: _chat.running ? '停止' : '发送',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Empty conversation
// ---------------------------------------------------------------------------

class _EmptyConversation extends StatelessWidget {
  const _EmptyConversation({required this.onSuggestion});

  final ValueChanged<String> onSuggestion;

  static const _suggestions = [
    '读一遍这个工作区，告诉我它做什么',
    '跑一下测试，把失败的原因说清楚',
    '看看引擎装好了没，缺什么就补上',
  ];

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(28, context.contentTopPadding, 28, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 24),
          Center(
            child: Container(
              width: 78,
              height: 78,
              decoration: BoxDecoration(
                color: scheme.primary.withValues(alpha: 0.10),
                shape: BoxShape.circle,
              ),
              child: Center(
                child: GlyphIcon(
                  AppGlyphs.sparkle,
                  size: 30,
                  color: scheme.primary,
                ),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            '开始对话',
            textAlign: TextAlign.center,
            style: AppTextStyle.pageTitle.of(context),
          ),
          const SizedBox(height: 8),
          Text(
            '它跑在你手机里的 Claude Code：PRoot 免 root 就能读写工作区，root 模式下还能改系统、刷 Magisk 模块。',
            textAlign: TextAlign.center,
            style: context.listSubtitleStyle,
          ),
          const SizedBox(height: 22),
          for (final hint in _suggestions) ...[
            Card(
              onPressed: () => onSuggestion(hint),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              child: Row(
                children: [
                  Expanded(
                    child: Text(hint, style: context.listTitleStyle),
                  ),
                  const SizedBox(width: 8),
                  Text('↵', style: context.listCaptionStyle),
                ],
              ),
            ),
            const SizedBox(height: 8),
          ],
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

class _MessageView extends StatelessWidget {
  const _MessageView({
    required this.message,
    required this.isLastAssistant,
    required this.running,
    required this.copied,
    this.onEdit,
    this.onRegenerate,
    required this.onCopy,
  });

  final ChatMessage message;
  final bool isLastAssistant;
  final bool running;
  final bool copied;
  final VoidCallback? onEdit;
  final VoidCallback? onRegenerate;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    if (message.role == Role.user) {
      return _UserBubble(message: message, onEdit: onEdit);
    }
    return _AssistantMessage(
      message: message,
      showActions: isLastAssistant,
      running: running,
      copied: copied,
      onRegenerate: onRegenerate,
      onCopy: onCopy,
    );
  }
}

class _UserBubble extends StatelessWidget {
  const _UserBubble({required this.message, this.onEdit});

  final ChatMessage message;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final text = message.blocks
        .whereType<TextBlock>()
        .map((b) => b.text)
        .join('\n');
    return Align(
      alignment: AlignmentDirectional.centerEnd,
      child: GestureDetector(
        onTap: onEdit,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 320),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: scheme.primary.withValues(alpha: 0.12),
            borderRadius: AppRadius.lg,
          ),
          child: SelectableText(text, style: context.listTitleStyle),
        ),
      ),
    );
  }
}

class _AssistantMessage extends StatelessWidget {
  const _AssistantMessage({
    required this.message,
    required this.showActions,
    required this.running,
    required this.copied,
    this.onRegenerate,
    required this.onCopy,
  });

  final ChatMessage message;
  final bool showActions;
  final bool running;
  final bool copied;
  final VoidCallback? onRegenerate;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    final blocks = message.blocks;
    final thinking = blocks.whereType<ThinkingBlock>().toList();
    final tools = blocks.whereType<ToolBlock>().toList();
    final errors = blocks.whereType<ErrorBlock>().toList();
    final hasActivity =
        thinking.isNotEmpty || tools.isNotEmpty || errors.isNotEmpty;
    final label = _workLabel(thinking.length, tools);
    final duration = _durationLabel(message.durationMs);
    final cost = message.costUsd;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (message.streaming && blocks.isEmpty)
          const _StreamingLine()
        else ...[
          if (hasActivity)
            _ActivityRow(
              label: label,
              streaming: message.streaming,
              running: running,
            ),
          for (final (i, block) in thinking.indexed) ...[
            _ThinkingFold(
              text: block.text,
              index: i + 1,
              total: thinking.length,
              active: message.streaming && i == thinking.length - 1,
            ),
            const SizedBox(height: 10),
          ],
          for (final block in blocks.whereType<TextBlock>()) ...[
            SelectableText(
              block.text,
              style: AppTextStyle.body.of(context).copyWith(
                    color: context.colorScheme.onSurface,
                    height: 1.5,
                  ),
            ),
            const SizedBox(height: 10),
          ],
          for (final block in tools) ...[
            _ToolCard(block: block),
            const SizedBox(height: 10),
          ],
          for (final block in errors) ...[
            _ErrorNotice(text: block.text),
            const SizedBox(height: 10),
          ],
        ],
        if (message.streaming) const _StreamingLine(),
        if (showActions && !message.streaming && !running)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Row(
              children: [
                if (cost != null || duration.isNotEmpty)
                  Text(
                    [
                      if (cost != null) '≈\$${cost.toStringAsFixed(4)}',
                      if (duration.isNotEmpty) duration,
                    ].join(' · '),
                    style: context.listCaptionStyle,
                  ),
                const Spacer(),
                if (onRegenerate != null)
                  TextButton(
                    onPressed: onRegenerate,
                    child: const Text('重新回答'),
                  ),
                TextButton(
                  onPressed: onCopy,
                  child: Text(copied ? '已复制' : '复制'),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _StreamingLine extends StatelessWidget {
  const _StreamingLine();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          ActivityIndicator(
            size: 14,
            color: context.colorScheme.onSurfaceVariant,
          ),
          const SizedBox(width: 8),
          Text('正在回答…', style: context.listCaptionStyle),
        ],
      ),
    );
  }
}

class _ActivityRow extends StatelessWidget {
  const _ActivityRow({
    required this.label,
    required this.streaming,
    required this.running,
  });

  final String label;
  final bool streaming;
  final bool running;

  @override
  Widget build(BuildContext context) {
    if (label.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          if (streaming && running) ...[
            ActivityIndicator(
              size: 13,
              color: context.colorScheme.onSurfaceVariant,
            ),
            const SizedBox(width: 6),
          ],
          Text(label, style: context.listCaptionStyle),
        ],
      ),
    );
  }
}

class _ThinkingFold extends StatefulWidget {
  const _ThinkingFold({
    required this.text,
    required this.index,
    required this.total,
    required this.active,
  });

  final String text;
  final int index;
  final int total;
  final bool active;

  @override
  State<_ThinkingFold> createState() => _ThinkingFoldState();
}

class _ThinkingFoldState extends State<_ThinkingFold> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final header = widget.active && widget.text.isEmpty
        ? '思考中…'
        : '思考过程 ${widget.index}/${widget.total} · ${widget.text.length} 字';
    return Surface(
      color: scheme.surfaceContainerHigh,
      shape: AppShape.md,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _open = !_open),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(header, style: context.listCaptionStyle),
                  ),
                  GlyphIcon(
                    _open ? Glyphs.chevronDown : Glyphs.chevronForward,
                    size: 16,
                    color: scheme.onSurfaceVariant,
                  ),
                ],
              ),
              if (_open && widget.text.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  widget.text,
                  style: AppTextStyle.caption1.of(context).copyWith(
                        color: scheme.onSurfaceVariant,
                        height: 1.45,
                      ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _ToolCard extends StatelessWidget {
  const _ToolCard({required this.block});

  final ToolBlock block;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final status = block.running
        ? '运行中'
        : block.isError
            ? '失败'
            : '完成';
    final statusColor = block.isError
        ? scheme.error
        : block.running
            ? scheme.onSurfaceVariant
            : scheme.success;
    return Card(
      padding: const EdgeInsets.all(12),
      isError: block.isError,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  block.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTextStyle.footnote.of(context, emphasized: true),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                status,
                style: AppTextStyle.caption2.of(context).copyWith(
                      color: statusColor,
                    ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          _MonoBlock(label: '入参', text: block.input),
          if (block.output != null && block.output!.isNotEmpty) ...[
            const SizedBox(height: 6),
            _MonoBlock(label: '输出', text: block.output!),
          ],
        ],
      ),
    );
  }
}

class _MonoBlock extends StatelessWidget {
  const _MonoBlock({required this.label, required this.text});

  final String label;
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: context.listCaptionStyle),
        const SizedBox(height: 2),
        Surface(
          color: scheme.surfaceContainerHighest,
          shape: AppShape.sm,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            child: Text(
              text,
              maxLines: 10,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.caption1.of(context).copyWith(
                    fontFamily: 'monospace',
                    height: 1.4,
                  ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ErrorNotice extends StatelessWidget {
  const _ErrorNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: scheme.error.withValues(alpha: 0.10),
        borderRadius: AppRadius.md,
      ),
      child: Text(
        text,
        style: AppTextStyle.caption1.of(context).copyWith(
              color: scheme.error,
              height: 1.45,
            ),
      ),
    );
  }
}

class _BackToBottomButton extends StatelessWidget {
  const _BackToBottomButton({required this.onPressed});

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Semantics(
      label: '回到底部',
      button: true,
      child: GestureDetector(
        onTap: onPressed,
        child: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: scheme.surfaceContainer,
            shape: BoxShape.circle,
            border: Border.all(color: scheme.outlineVariant),
          ),
          child: Center(
            child: GlyphIcon(
              Glyphs.chevronDown,
              size: 19,
              color: scheme.onSurfaceVariant,
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Labels and formatting
// ---------------------------------------------------------------------------

String _workLabel(int thinkingCount, List<ToolBlock> tools) {
  final parts = <String>[];
  if (thinkingCount > 0) parts.add('思考过程 $thinkingCount 段');
  if (tools.isNotEmpty) {
    final errors = tools.where((t) => t.isError).length;
    parts.add(errors > 0
        ? '工具调用 ${tools.length} · $errors 个错误'
        : '工具调用 ${tools.length}');
  }
  return parts.join(' · ');
}

String _durationLabel(int? durationMs) {
  if (durationMs == null || durationMs <= 0) return '';
  final seconds = durationMs / 1000;
  if (seconds >= 60) {
    final m = seconds ~/ 60;
    final s = seconds.round() % 60;
    return '${m}m ${s}s';
  }
  return '${seconds.toStringAsFixed(1)}s';
}

/// A [ChangeNotifier] the bar listens to; the page bumps it whenever the
/// folded chat state changes, since the bar is rebuilt outside `setState`.
class _BarTick extends ChangeNotifier {
  void bump() => notifyListeners();
}

// ---------------------------------------------------------------------------
// Empty shell (no session yet)
// ---------------------------------------------------------------------------

class EmptyChatPage extends StatelessWidget {
  const EmptyChatPage({
    super.key,
    this.notice,
    required this.onOpenDrawer,
    required this.onOpenSettings,
    required this.onNewChat,
  });

  final String? notice;
  final VoidCallback onOpenDrawer;
  final VoidCallback onOpenSettings;
  final VoidCallback onNewChat;

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ScaffoldBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverValue,
        builder: (context) => AppBar(
          leading: IconButton(
            onPressed: onOpenDrawer,
            icon: const GlyphIcon(AppGlyphs.menu),
            tooltip: '会话',
          ),
          title: const Text('Claudroid'),
          actions: [
            IconButton(
              onPressed: onNewChat,
              icon: const GlyphIcon(AppGlyphs.plus),
              tooltip: '新对话',
            ),
          ],
        ),
      ),
      body: _EmptyBody(
        notice: notice,
        onOpenSettings: onOpenSettings,
        onNewChat: onNewChat,
      ),
    );
  }
}

class _EmptyBody extends StatelessWidget {
  const _EmptyBody({
    this.notice,
    required this.onOpenSettings,
    required this.onNewChat,
  });

  final String? notice;
  final VoidCallback onOpenSettings;
  final VoidCallback onNewChat;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(28, context.contentTopPadding, 28, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 32),
          Center(
            child: Container(
              width: 78,
              height: 78,
              decoration: BoxDecoration(
                color: scheme.primary.withValues(alpha: 0.10),
                shape: BoxShape.circle,
              ),
              child: Center(
                child: GlyphIcon(
                  AppGlyphs.sparkle,
                  size: 30,
                  color: scheme.primary,
                ),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            '还没有对话',
            textAlign: TextAlign.center,
            style: AppTextStyle.pageTitle.of(context),
          ),
          const SizedBox(height: 8),
          Text(
            '新建一个对话，或从左侧抽屉里挑一段继续。',
            textAlign: TextAlign.center,
            style: context.listSubtitleStyle,
          ),
          if (notice != null && notice!.isNotEmpty) ...[
            const SizedBox(height: 18),
            _ErrorNotice(
              text: '启动时没能连上原生桥接：$notice\n'
                  '先重新打开应用；仍然失败的话，在「设置 → 关于」里看引擎日志。',
            ),
          ],
          const SizedBox(height: 22),
          TonalButton(
            label: '新建对话',
            glyph: AppGlyphs.plus,
            onPressed: onNewChat,
          ),
          const SizedBox(height: 10),
          TonalButton(
            label: '设置',
            glyph: AppGlyphs.edit,
            onPressed: onOpenSettings,
          ),
        ],
      ),
    );
  }
}

/// Stand-in for the bar's `listenable`/`loading` when nothing ever changes.
/// `ChangeNotifier` has no const constructor, so these are cached instances.
final Listenable _neverBar = _Never();
final ValueListenable<bool> _neverValue = _NeverValue();

class _Never extends ChangeNotifier {}

class _NeverValue implements ValueListenable<bool> {
  const _NeverValue();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
