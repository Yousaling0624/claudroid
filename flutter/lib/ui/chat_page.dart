import 'dart:async';

import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import '../models/models.dart';
import '../models/remote.dart';
import 'attachments.dart';
import 'chat_search.dart';
import 'claude_dir_dialog.dart';
import 'composer.dart';
import 'conversation_export.dart';
import 'drafts.dart';
import 'fold.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'markdown.dart';
import 'model_menu.dart';
import 'permission_card.dart';
import 'permission_menu.dart';
import 'reply_share.dart';
import 'scaffold_bar.dart';
import 'selection_menu.dart';
import 'settings/settings_destination.dart';
import 'settings/settings_labels.dart';
import 'sparkle_motion.dart';
import 'tool_presentation.dart';
import 'typography.dart';
import 'web_sources.dart';
import 'workspace_switcher.dart';

/// One conversation. Mirrors `ui/chat/ChatScreen.kt`: a floating bar with the
/// title and the live status line, the transcript, and a composer pinned under
/// it. All state comes from the host; this page only folds the session stream
/// and decides where to scroll.
class ChatPage extends StatefulWidget {
  const ChatPage({
    super.key,
    required this.bridge,
    required this.sessionId,
    this.session,
    this.hosts = const RemoteHostsState(),
    required this.settings,
    required this.labels,
    required this.onOpenDrawer,
    required this.onOpenSettings,
    required this.onOpenWorkspace,
    required this.onNewChat,
    required this.onOpenSession,
    required this.onUpdateSettings,
    required this.onToast,
  });

  final Bridge bridge;
  final String sessionId;

  /// Drawer summary for this session, used for the remote badge and banner.
  /// Null for a just-created session before the `sessions` event lands.
  final ChatSession? session;
  final RemoteHostsState hosts;
  final AppSettings settings;
  final ModelLabels labels;
  final VoidCallback onOpenDrawer;
  final ValueChanged<SettingsDestination> onOpenSettings;
  final VoidCallback onOpenWorkspace;
  final VoidCallback onNewChat;

  /// Opens a session created here (a fork), so the shell swaps the page.
  final ValueChanged<String> onOpenSession;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  late final ChatEvents _events = ChatEvents(widget.sessionId);
  StreamSubscription<ChatEvent>? _subscription;
  Timer? _resyncTimer;
  bool _resyncing = false;

  final ScrollController _scroll = ScrollController();
  final TextEditingController _input = TextEditingController();
  final FocusNode _inputFocus = FocusNode();
  final GlobalKey<ChatComposerState> _composerKey =
      GlobalKey<ChatComposerState>();
  final _BarTick _barTick = _BarTick();
  final ValueNotifier<bool> _loading = ValueNotifier<bool>(false);

  ChatState _chat = const ChatState();
  ChatState? _pendingChat;
  bool _flushScheduled = false;
  Timer? _deltaFlushTimer;
  String _barSignature = '';

  /// Deltas arrive faster than the screen can draw. Coalescing them into one
  /// rebuild every ~32 ms (about 31 fps) keeps the transcript readable while
  /// leaving frames for scrolling; a Timer (rather than a per-frame callback)
  /// guarantees the trailing delta is still committed when the stream pauses.
  static const Duration _deltaFlushInterval = Duration(milliseconds: 32);

  /// Sticks to the bottom while the answer streams, and lets go as soon as
  /// the reader scrolls up — never yanks them back.
  bool _followBottom = true;

  /// True between a finger-driven [ScrollStartNotification] and its
  /// [ScrollEndNotification]. Auto-follow must not call `jumpTo` in this
  /// window: `jumpTo` calls `goIdle()`, which cancels the drag activity.
  bool _userScrolling = false;

  /// Cached rows keyed by message id. A delta only changes one message, so
  /// handing Flutter the *same* widget instance lets it skip every other
  /// visible row instead of re-running the source regex and rebuilding its
  /// Markdown tree on each flush.
  final Map<String, _RowCacheEntry> _rowCache = {};
  int _rowEpoch = 0;

  /// Expand state for every fold, keyed by message and block. The page owns
  /// it so a row scrolled out of the list keeps the reader's choice.
  final Map<String, bool> _folds = {};

  bool _foldOpen(String key) => _folds[key] ?? false;

  void _toggleFold(String key) {
    setState(() {
      _folds[key] = !_foldOpen(key);
      _invalidateRows();
    });
  }

  String? _editingId;
  String? _copiedId;
  Timer? _copiedTimer;
  bool _searching = false;
  final TextEditingController _search = TextEditingController();
  final FocusNode _searchFocus = FocusNode();

  bool get _remote => widget.session?.isRemote ?? false;

  RemoteHost? get _remoteHost => widget.hosts.byId(widget.session?.hostId);

  List<ChatMessage> get _visibleMessages => _searching
      ? searchMessages(_chat.messages, _search.text)
      : _chat.messages;

  @override
  void initState() {
    super.initState();
    _input.text = chatDrafts[widget.sessionId] ?? '';
    _subscription = _events.events.listen(_onChatEvent, onError: (_) {});
    _attach();
  }

  @override
  void dispose() {
    _copiedTimer?.cancel();
    _resyncTimer?.cancel();
    _deltaFlushTimer?.cancel();
    _subscription?.cancel();
    _scroll.dispose();
    _input.dispose();
    _inputFocus.dispose();
    _search.dispose();
    _searchFocus.dispose();
    _barTick.dispose();
    _loading.dispose();
    // Deliberately no `chat.forget` here: switching pages only drops this
    // listener. Releasing the controller is the host's call (`chat.close` /
    // `sessions.delete`), otherwise a running turn becomes unreachable.
    super.dispose();
  }

  Future<void> _attach() async {
    try {
      final snapshot = await widget.bridge.attach(widget.sessionId);
      if (!mounted || _chat.messages.isNotEmpty) return;
      _applyState(snapshot);
    } catch (error) {
      if (!mounted) return;
      widget.onToast('没能打开这段对话（$error）。点右上角新建一个对话试试。', isError: true);
    }
  }

  void _onChatEvent(ChatEvent event) {
    // Fold against the newest queued state, not the last committed frame:
    // several deltas can arrive before the next flush.
    final next = event.apply(_pendingChat ?? _chat);
    if (next == null) {
      // A delta or tool result whose target is gone: the transcript drifted.
      // Debounced so a burst of stale deltas only asks the host once.
      _resyncTimer?.cancel();
      _resyncTimer = Timer(const Duration(milliseconds: 150), _resync);
      return;
    }
    _applyState(next, coalesce: event is ChatDeltaEvent);
  }

  Future<void> _resync() async {
    if (_resyncing || !mounted) return;
    _resyncing = true;
    try {
      final snapshot = await widget.bridge.attach(widget.sessionId);
      if (mounted) _applyState(snapshot);
    } catch (_) {
      // The next event or the user reopening the page will retry.
    } finally {
      _resyncing = false;
    }
  }

  void _applyState(ChatState next, {bool coalesce = false}) {
    if (!mounted) return;
    final notice = next.titleNotice;
    // The newest folded state wins; whichever flush fires next commits it.
    _pendingChat = next;
    if (coalesce) {
      // Streaming text: draw at most every ~32 ms. The timer's flush commits
      // the newest pending state, so a burst of deltas costs one rebuild and
      // still cannot lose the tail.
      _deltaFlushTimer ??= Timer(_deltaFlushInterval, () {
        _deltaFlushTimer = null;
        _flushState();
      });
    } else {
      // Running/status/tool/snapshot events are user-visible state changes;
      // commit them on the next frame instead of waiting out the throttle.
      _deltaFlushTimer?.cancel();
      _deltaFlushTimer = null;
      if (!_flushScheduled) {
        _flushScheduled = true;
        WidgetsBinding.instance.addPostFrameCallback((_) => _flushState());
        WidgetsBinding.instance.scheduleFrame();
      }
    }
    if (notice != null && notice.isNotEmpty) {
      widget.onToast(notice);
      unawaited(widget.bridge.consumeTitleNotice(widget.sessionId));
    }
  }

  void _flushState() {
    _flushScheduled = false;
    _deltaFlushTimer?.cancel();
    _deltaFlushTimer = null;
    final pending = _pendingChat;
    if (!mounted || pending == null) return;
    _pendingChat = null;
    setState(() => _chat = pending);
    _pruneRowCache(pending.messages);
    // Only the bar-visible fields warrant rebuilding the bar; streaming text
    // changes must not tick it on every frame.
    final signature =
        '${pending.title}\u0000${pending.running}\u0000'
        '${pending.statusLine}\u0000${pending.titleBusy}';
    if (signature != _barSignature) {
      _barSignature = signature;
      _barTick.bump();
    }
    if (_followBottom && !_searching) _pinToBottom();
  }

  void _pruneRowCache(List<ChatMessage> messages) {
    if (_rowCache.isEmpty) return;
    final ids = {for (final message in messages) message.id};
    _rowCache.removeWhere((id, _) => !ids.contains(id));
  }

  /// Bumps the row epoch so cached rows re-read page-owned interaction state
  /// (folds, copy feedback, edit mode). Rare, user-driven changes only.
  void _invalidateRows() => _rowEpoch++;

  void _pinToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _searching || !_followBottom || _userScrolling) return;
      if (!_scroll.hasClients) return;
      final position = _scroll.position;
      // `jumpTo` ends the current scroll activity; doing that while a drag or
      // fling is live would steal the gesture, so wait for it to settle.
      if (position.isScrollingNotifier.value) return;
      // `jumpTo` is not free: even a no-op reassigns the activity. Once the
      // bottom is already under the viewport, leave the position alone.
      if ((position.pixels - position.maxScrollExtent).abs() <= 0.5) return;
      _scroll.jumpTo(position.maxScrollExtent);
    });
  }

  void _jumpToBottom() {
    setState(() => _followBottom = true);
    if (!_scroll.hasClients) return;
    _scroll.animateTo(
      _scroll.position.maxScrollExtent,
      duration: context.motionDuration(const Duration(milliseconds: 260)),
      curve: Curves.easeOutCubic,
    );
  }

  bool _onScrollNotification(ScrollNotification notification) {
    if (notification is ScrollStartNotification &&
        notification.dragDetails != null) {
      // A finger is down. Stop following immediately, before the first drag
      // update: a post-frame `jumpTo` from a streaming flush would otherwise
      // call `goIdle()` and end the drag, which is why the page felt locked
      // while the model was answering.
      _userScrolling = true;
      if (_followBottom) setState(() => _followBottom = false);
    } else if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom != _followBottom) {
        setState(() => _followBottom = atBottom);
      }
    } else if (notification is ScrollEndNotification) {
      _userScrolling = false;
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom && !_followBottom) setState(() => _followBottom = true);
    }
    return false;
  }

  /// Folds change the transcript's height without a scroll notification. While
  /// the reader is pinned to the bottom, follow every metric change so the
  /// expansion grows the viewport instead of pushing the revealed text below
  /// the composer; a manual "back to bottom" animation is left alone.
  ///
  /// A fold that collapses above the viewport can also shrink the list until it
  /// ends exactly at the viewport while [followBottom] is still false from an
  /// earlier scroll-up. Treat that boundary as "pinned" again so the next
  /// expansion follows instead of revealing its content off-screen.
  bool _onScrollMetrics(ScrollMetricsNotification notification) {
    if (_searching || _userScrolling || !_scroll.hasClients) return false;
    if (_scroll.position.isScrollingNotifier.value) return false;
    final atBottom = notification.metrics.extentAfter < 2;
    if (!_followBottom && !atBottom) return false;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _searching || _userScrolling || !_scroll.hasClients) {
        return;
      }
      final position = _scroll.position;
      if (position.isScrollingNotifier.value) return;
      final bottom = position.extentAfter < 2;
      if (!_followBottom && !bottom) return;
      if (!_followBottom && bottom) {
        setState(() => _followBottom = true);
      }
      if ((position.pixels - position.maxScrollExtent).abs() > 0.5) {
        _scroll.jumpTo(position.maxScrollExtent);
      }
    });
    return false;
  }

  // -- actions -------------------------------------------------------------

  Future<void> _submit() async {
    final composer = _composerKey.currentState;
    final paths = composer?.attachments ?? const <String>[];
    final text = _input.text.trim();
    if ((text.isEmpty && paths.isEmpty) || _chat.running) {
      return;
    }
    final editing = _editingId;
    final prompt = composeChatPrompt(text, paths);
    try {
      final accepted = editing == null
          ? await widget.bridge.send(widget.sessionId, prompt)
          : await widget.bridge.resendFrom(widget.sessionId, editing, prompt);
      if (!accepted) {
        widget.onToast('这一轮还没结束。先点停止，再发送。', isError: true);
        return;
      }
      chatDrafts.remove(widget.sessionId);
      _input.clear();
      composer?.clearAttachments();
      if (!mounted) return;
      setState(() {
        _editingId = null;
        _followBottom = true;
        _invalidateRows();
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
    _composerKey.currentState?.clearAttachments();
    setState(() {
      _editingId = null;
      _invalidateRows();
    });
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

  /// Branches the conversation at [message]: the host copies everything up to
  /// and including that answer into a new session and we open it.
  Future<void> _fork(ChatMessage message) async {
    try {
      final sessionId = await widget.bridge.forkChat(
        widget.sessionId,
        message.id,
      );
      if (!mounted) return;
      widget.onOpenSession(sessionId);
      widget.onToast('已从这条回答分出新对话');
    } catch (error) {
      widget.onToast('分叉失败（$error）。再点一次。', isError: true);
    }
  }

  void _startEditing(ChatMessage message) {
    final joined = message.blocks
        .whereType<TextBlock>()
        .map((b) => b.text)
        .join('\n');
    // Attachment tokens belong to the composer's thumbnail row, not its text
    // field; moving them back keeps edit-and-resend lossless.
    final split = splitAttachmentRefs(joined);
    setState(() {
      _editingId = message.id;
      _invalidateRows();
    });
    _input.value = TextEditingValue(
      text: split.text,
      selection: TextSelection.collapsed(offset: split.text.length),
    );
    _composerKey.currentState?.setAttachments(split.paths);
    _inputFocus.requestFocus();
  }

  void _cancelEdit() {
    _input.clear();
    _composerKey.currentState?.clearAttachments();
    setState(() {
      _editingId = null;
      _invalidateRows();
    });
  }

  void _openSearch() {
    setState(() {
      _searching = true;
      _invalidateRows();
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (_scroll.hasClients) _scroll.jumpTo(0);
      _searchFocus.requestFocus();
    });
  }

  void _closeSearch() {
    _search.clear();
    _searchFocus.unfocus();
    setState(() {
      _searching = false;
      _invalidateRows();
    });
    if (_followBottom) _pinToBottom();
  }

  Future<void> _copyConversation() async {
    final text = conversationToText(_chat.messages);
    if (text.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    widget.onToast('对话已复制');
  }

  Future<void> _copy(ChatMessage message) async {
    final text = replyPlainText(message);
    if (text.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    setState(() {
      _copiedId = message.id;
      _invalidateRows();
    });
    _copiedTimer?.cancel();
    _copiedTimer = Timer(const Duration(milliseconds: 1400), () {
      if (mounted) {
        setState(() {
          _copiedId = null;
          _invalidateRows();
        });
      }
    });
  }

  Future<void> _shareText(ChatMessage message) async {
    final text = replyPlainText(message);
    if (text.isEmpty) return;
    try {
      await widget.bridge.shareText(text);
    } catch (error) {
      widget.onToast('分享失败（$error）。', isError: true);
    }
  }

  Future<void> _shareImage(Uint8List bytes) async {
    try {
      await widget.bridge.shareImage(bytes);
    } catch (error) {
      widget.onToast('分享失败（$error）。', isError: true);
    }
  }

  Future<void> _openUrl(String url) async {
    try {
      await widget.bridge.openUrl(url);
    } catch (error) {
      widget.onToast('没能打开链接（$error）。', isError: true);
    }
  }

  void _patch(Map<String, dynamic> patch) {
    unawaited(widget.onUpdateSettings(patch));
  }

  /// Picks the directory Claude Code runs in; the host applies it to the next
  /// turn's CLI cwd, so a running engine does not need a restart.
  Future<void> _chooseClaudeDir() async {
    final current = widget.settings.str('claudeWorkDir');
    final chosen = await showClaudeDirDialog(
      context,
      bridge: widget.bridge,
      current: current,
      onToast: widget.onToast,
    );
    if (chosen == null || !mounted) return;
    if (claudeDirLabel(chosen) == claudeDirLabel(current)) {
      widget.onToast('未变');
      return;
    }
    _patch({'claudeWorkDir': chosen});
    widget.onToast(
      chosen.isEmpty
          ? 'Claude 工作目录已重置为 /work（工作区根目录）'
          : 'Claude 工作目录已切换到 ${claudeDirLabel(chosen)}',
    );
  }

  ChatMessage? get _lastAssistant {
    for (final message in _chat.messages.reversed) {
      if (message.role == Role.assistant) return message;
    }
    return null;
  }

  Future<void> _respondPermission(
    PendingPermission permission, {
    required bool allow,
    required bool always,
  }) async {
    try {
      await widget.bridge.respondPermission(
        widget.sessionId,
        permission.requestId,
        allow: allow,
        always: always,
      );
    } catch (error) {
      if (mounted) widget.onToast('$error', isError: true);
      rethrow;
    }
  }

  List<SlashAction> _slashActions() {
    final last = _lastAssistant;
    final hasMessages = _chat.messages.isNotEmpty;
    final remote = _remote;
    return [
      SlashAction(
        command: 'new',
        label: '新对话',
        glyph: AppGlyphs.plus,
        onPressed: widget.onNewChat,
      ),
      if (!remote)
        SlashAction(
          command: 'model',
          label: '切换模型',
          glyph: AppGlyphs.sparkle,
          onPressed: () => showModelTierDialog(
            context,
            settings: widget.settings,
            onPatch: _patch,
            onOpenSettings: () =>
                widget.onOpenSettings(SettingsDestination.serviceModels),
          ),
        ),
      SlashAction(
        command: 'plan',
        label: '规划模式（只读）',
        glyph: AppGlyphs.checklist,
        onPressed: () {
          if (widget.settings.str('permissionMode', 'BYPASS') == 'PLAN') {
            widget.onToast('当前已经是只读规划模式');
            return;
          }
          _patch({'permissionMode': 'PLAN'});
          widget.onToast('已切换到只读规划；用 /permissions 更换模式');
        },
      ),
      SlashAction(
        command: 'permissions',
        label: '权限模式',
        glyph: AppGlyphs.key,
        onPressed: () => showPermissionModeDialog(
          context,
          current: widget.settings.str('permissionMode', 'BYPASS'),
          onPatch: _patch,
        ),
      ),
      SlashAction(
        command: 'attach',
        label: '添加附件',
        glyph: AppGlyphs.attachment,
        onPressed: () => _composerKey.currentState?.pickAttachments(),
      ),
      if (!remote) ...[
        SlashAction(
          command: 'workspace',
          label: '工作区',
          glyph: AppGlyphs.folder,
          onPressed: widget.onOpenWorkspace,
        ),
        SlashAction(
          command: 'switch',
          label: '切换工作区',
          glyph: AppGlyphs.folder,
          onPressed: () => showWorkspaceSwitcher(
            context,
            bridge: widget.bridge,
            onToast: widget.onToast,
          ),
        ),
        SlashAction(
          command: 'cwd',
          label: 'Claude 工作目录',
          glyph: AppGlyphs.folder,
          onPressed: _chooseClaudeDir,
        ),
      ] else
        SlashAction(
          command: 'host',
          label: '远程主机设置',
          glyph: AppGlyphs.plug,
          onPressed: () =>
              widget.onOpenSettings(SettingsDestination.remoteHosts),
        ),
      SlashAction(
        command: 'search',
        label: '搜索当前对话',
        glyph: AppGlyphs.search,
        enabled: hasMessages,
        onPressed: _openSearch,
      ),
      SlashAction(
        command: 'copy',
        label: '复制整段对话',
        glyph: Glyphs.copy,
        enabled: hasMessages,
        onPressed: _copyConversation,
      ),
      SlashAction(
        command: 'title',
        label: '重新生成标题',
        glyph: AppGlyphs.sparkle,
        enabled: hasMessages && !_chat.running && !_chat.titleBusy,
        onPressed: _regenerateTitle,
      ),
      SlashAction(
        command: 'clear',
        label: '清空对话上下文',
        glyph: AppGlyphs.trash,
        danger: true,
        onPressed: _clear,
      ),
      if (_chat.running)
        SlashAction(
          command: 'stop',
          label: '停止这一轮',
          glyph: AppGlyphs.stop,
          danger: true,
          onPressed: _stop,
        )
      else
        SlashAction(
          command: 'retry',
          label: '重新回答',
          glyph: AppGlyphs.refresh,
          enabled: last != null,
          onPressed: () {
            if (last != null) unawaited(_regenerate(last));
          },
        ),
      SlashAction(
        command: 'settings',
        label: '设置',
        glyph: AppGlyphs.settings,
        onPressed: () => widget.onOpenSettings(SettingsDestination.root),
      ),
      SlashAction(
        command: 'help',
        label: '命令帮助',
        glyph: AppGlyphs.sliders,
        onPressed: () => showSlashHelpDialog(context, _slashActions()),
      ),
    ];
  }

  // -- build ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _barTick,
        loading: _loading,
        leading: (context) => IconButton(
          onPressed: widget.onOpenDrawer,
          icon: const GlyphIcon(AppGlyphs.menu),
          tooltip: '会话',
        ),
        title: (context) {
          final remote = _remote;
          final host = _remoteHost;
          final remoteLabel = remote
              ? '远程 · ${host?.displayName ?? '主机已移除'}'
              : '';
          final runningStatus = _chat.running ? _chat.statusLine : '';
          final statusLine = [
            if (remoteLabel.isNotEmpty) remoteLabel,
            if (runningStatus.isNotEmpty) runningStatus,
          ].join(' · ');
          return PopupAnchor(
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
                      style: AppTextStyle.caption1
                          .of(context)
                          .copyWith(
                            color: context.colorScheme.onSurfaceVariant,
                          ),
                    ),
                ],
              ),
            ),
            popupBuilder: (_) => PopupMenu(
              items: [
                if (_chat.messages.isNotEmpty)
                  PopupMenuItem(
                    label: _searching ? '退出搜索' : '搜索当前对话',
                    glyph: _searching ? Glyphs.close : Glyphs.search,
                    onPressed: _searching ? _closeSearch : _openSearch,
                  ),
                if (_chat.messages.isNotEmpty)
                  PopupMenuItem(
                    label: '复制整段对话',
                    glyph: Glyphs.copy,
                    onPressed: _copyConversation,
                  ),
                PopupMenuItem(
                  label: 'Claude 工作目录 · '
                      '${claudeDirLabel(widget.settings.str('claudeWorkDir'))}',
                  glyph: AppGlyphs.folder,
                  onPressed: _chooseClaudeDir,
                ),
                PopupMenuItem(
                  label: '清空对话上下文',
                  glyph: AppGlyphs.trash,
                  danger: true,
                  onPressed: _clear,
                ),
                PopupMenuItem(
                  label: _chat.titleBusy ? '正在生成标题…' : '重新生成标题',
                  glyph: AppGlyphs.sparkle,
                  onPressed:
                      !_chat.running &&
                          !_chat.titleBusy &&
                          _chat.messages.isNotEmpty
                      ? _regenerateTitle
                      : null,
                ),
              ],
            ),
          );
        },
        actions: [
          if (!_remote)
            (context) => ModelTierButton(
              labels: widget.labels,
              settings: widget.settings,
              onPatch: _patch,
              onOpenSettings: () =>
                  widget.onOpenSettings(SettingsDestination.serviceModels),
            ),
          (context) => IconButton(
            onPressed: widget.onNewChat,
            icon: const GlyphIcon(AppGlyphs.plus),
            tooltip: '新对话',
          ),
        ],
      ),
      body: Column(
        children: [
          if (_searching) _buildSearchBar(context),
          if (_remote && _hostIssue != null)
            _RemoteHostBanner(
              message: _hostIssue!,
              onOpenSettings: () =>
                  widget.onOpenSettings(SettingsDestination.remoteHosts),
            ),
          Expanded(
            child: Stack(
              children: [
                NotificationListener<ScrollMetricsNotification>(
                  onNotification: _onScrollMetrics,
                  child: NotificationListener<ScrollNotification>(
                    onNotification: _onScrollNotification,
                    child: _buildTranscript(context),
                  ),
                ),
                if (_chat.messages.isNotEmpty && !_searching)
                  Positioned(
                    right: 16,
                    bottom: 10,
                    child: context.disableAnimations
                        ? (_followBottom
                              ? const SizedBox.shrink()
                              : _BackToBottomButton(onPressed: _jumpToBottom))
                        : AnimatedVisibility.bottomNavigation(
                            visible: !_followBottom,
                            child: _BackToBottomButton(
                              onPressed: _jumpToBottom,
                            ),
                          ),
                  ),
              ],
            ),
          ),
          _buildPendingPermissions(context),
          _buildComposer(context),
        ],
      ),
    );
  }

  String? get _hostIssue {
    if (!_remote) return null;
    final host = _remoteHost;
    if (host == null) return '绑定的远程主机已被删除，请到「设置 → 远程主机」重新添加。';
    if (host.failed) {
      final reason = host.error?.trim() ?? '';
      return reason.isEmpty
          ? '远程主机连接失败，正在自动重试。'
          : '远程主机连接失败：$reason。正在自动重试。';
    }
    if (host.phase == 'offline') {
      return '远程主机「${host.displayName}」当前离线，正在自动重连。';
    }
    return null;
  }

  Widget _buildPendingPermissions(BuildContext context) {
    final pending = _chat.pendingPermissions;
    if (pending.isEmpty) return const SizedBox.shrink();
    final permission = pending.first;
    return ContentWidth(
      maxWidth: kChatContentWidth,
      heightFactor: 1,
      alignment: Alignment.center,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            PermissionCard(
              key: ValueKey(permission.requestId),
              permission: permission,
              onRespond: ({required allow, required always}) =>
                  _respondPermission(
                    permission,
                    allow: allow,
                    always: always,
                  ),
            ),
            if (pending.length > 1)
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 5, 4, 0),
                child: Text(
                  '还有 ${pending.length - 1} 个工具调用在排队确认',
                  style: AppTextStyle.caption2.of(context).copyWith(
                        color: context.colorScheme.onSurfaceVariant,
                      ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildSearchBar(BuildContext context) {
    final scheme = context.colorScheme;
    final query = _search.text.trim();
    final count = _visibleMessages.length;
    return ContentWidth(
      maxWidth: kChatContentWidth,
      heightFactor: 1,
      alignment: Alignment.center,
      child: Padding(
        padding: EdgeInsets.fromLTRB(16, context.contentTopPadding + 4, 6, 4),
        child: Row(
          children: [
            Expanded(
              child: SearchField(
                controller: _search,
                focusNode: _searchFocus,
                hintText: '搜索当前对话',
                height: 42,
                onChanged: (_) {
                  setState(() {});
                  if (_scroll.hasClients) _scroll.jumpTo(0);
                },
              ),
            ),
            const SizedBox(width: 8),
            Text(
              query.isEmpty ? '$count 条' : '$count 条匹配',
              style: AppTextStyle.caption2
                  .of(context)
                  .copyWith(color: scheme.onSurfaceVariant),
            ),
            IconButton(
              onPressed: _closeSearch,
              icon: const GlyphIcon(Glyphs.close, size: 18),
              tooltip: '退出搜索',
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTranscript(BuildContext context) {
    if (_chat.messages.isEmpty) {
      return ContentWidth(
        maxWidth: kChatContentWidth,
        child: _EmptyConversation(
          bridge: widget.bridge,
          onSuggestion: (text) {
            _input.text = text;
            _inputFocus.requestFocus();
          },
        ),
      );
    }
    final messages = _visibleMessages;
    if (messages.isEmpty) {
      return ContentWidth(
        maxWidth: kChatContentWidth,
        child: _NoSearchMatches(query: _search.text),
      );
    }
    return ContentWidth(
      maxWidth: kChatContentWidth,
      child: ListView.separated(
        controller: _scroll,
        padding: EdgeInsets.fromLTRB(
          20,
          _searching ? 8 : context.contentTopPadding,
          20,
          56,
        ),
        itemCount: messages.length,
        separatorBuilder: (_, _) => const SizedBox(height: 32),
        itemBuilder: (context, index) {
          final message = messages[index];
          final isLastAssistant =
              message.role == Role.assistant && index == messages.length - 1;
          return _buildMessageRow(message, isLastAssistant);
        },
      ),
    );
  }

  /// One transcript row, cached while nothing it renders has changed.
  ///
  /// Returning the same widget instance makes `Element.updateChild` bail out,
  /// so a streaming delta only rebuilds (and re-parses Markdown for) the one
  /// answer that actually grew. Search results are never cached: they depend
  /// on the query and their row indexes change as matches are filtered.
  Widget _buildMessageRow(ChatMessage message, bool isLastAssistant) {
    final model = widget.labels.chat;
    final running = _chat.running;
    final streaming = message.streaming;
    final statusLine = _chat.statusLine;
    final cached = _searching ? null : _rowCache[message.id];
    if (cached != null &&
        identical(cached.message, message) &&
        cached.isLastAssistant == isLastAssistant &&
        cached.running == running &&
        cached.model == model &&
        cached.epoch == _rowEpoch &&
        (!streaming || cached.statusLine == statusLine)) {
      return cached.widget;
    }
    final row = RepaintBoundary(
      key: ValueKey(message.id),
      child: _MessageView(
        bridge: widget.bridge,
        message: message,
        model: model,
        statusLine: statusLine,
        running: running,
        copied: _copiedId == message.id,
        foldOpen: _foldOpen,
        onToggleFold: _toggleFold,
        onEdit: message.role == Role.user && !running && !_searching
            ? () => _startEditing(message)
            : null,
        onRegenerate: isLastAssistant ? () => _regenerate(message) : null,
        onFork: () => _fork(message),
        onCopy: () => _copy(message),
        onShareText: () => _shareText(message),
        onShareImage: _shareImage,
        onToast: widget.onToast,
        onOpenUrl: _openUrl,
      ),
    );
    if (!_searching) {
      _rowCache[message.id] = _RowCacheEntry(
        widget: row,
        message: message,
        isLastAssistant: isLastAssistant,
        running: running,
        model: model,
        statusLine: streaming ? statusLine : '',
        epoch: _rowEpoch,
      );
    }
    return row;
  }

  Widget _buildComposer(BuildContext context) {
    return ContentWidth(
      maxWidth: kChatContentWidth,
      heightFactor: 1,
      alignment: Alignment.center,
      child: ChatComposer(
        key: _composerKey,
        bridge: widget.bridge,
        controller: _input,
        focusNode: _inputFocus,
        running: _chat.running,
        onSubmit: _submit,
        onStop: _stop,
        onToast: widget.onToast,
        editing: _editingId != null,
        onCancelEdit: _cancelEdit,
        actions: _slashActions(),
        onChanged: (value) {
          if (_editingId == null) chatDrafts[widget.sessionId] = value;
        },
        footnote: _remote
            ? remotePermissionFootnote(_remoteHost?.permissionMode ?? 'default')
            : permissionFootnote(
                widget.settings.str('permissionMode', 'BYPASS'),
              ),
        bottomInset: context.bottomInset,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Empty conversation
// ---------------------------------------------------------------------------

class _EmptyConversation extends StatefulWidget {
  const _EmptyConversation({required this.bridge, required this.onSuggestion});

  final Bridge bridge;
  final ValueChanged<String> onSuggestion;

  @override
  State<_EmptyConversation> createState() => _EmptyConversationState();
}

class _EmptyConversationState extends State<_EmptyConversation> {
  List<String> _suggestions = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final items = await widget.bridge.suggestions(count: 3);
      if (!mounted) return;
      setState(() {
        _suggestions = items;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

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
            style: claudroidSerif(AppTextStyle.pageTitle.of(context)),
          ),
          const SizedBox(height: 8),
          Text(
            '它跑在你手机里的 Claude Code：PRoot 免 root 就能读写工作区，root 模式下还能改系统、刷 Magisk 模块。',
            textAlign: TextAlign.center,
            style: context.listSubtitleStyle,
          ),
          const SizedBox(height: 22),
          if (_loading)
            for (final width in const [1.0, 0.86, 0.94]) ...[
              FractionallySizedBox(
                alignment: Alignment.centerLeft,
                widthFactor: width,
                child: Container(
                  height: 46,
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHigh,
                    borderRadius: AppRadius.md,
                  ),
                ),
              ),
              const SizedBox(height: 8),
            ]
          else
            for (final hint in _suggestions) ...[
              Card(
                onPressed: () => widget.onSuggestion(hint),
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
                child: Row(
                  children: [
                    Expanded(child: Text(hint, style: context.listTitleStyle)),
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
    required this.bridge,
    required this.message,
    required this.model,
    required this.statusLine,
    required this.running,
    required this.copied,
    required this.foldOpen,
    required this.onToggleFold,
    this.onEdit,
    this.onRegenerate,
    required this.onFork,
    required this.onCopy,
    required this.onShareText,
    required this.onShareImage,
    required this.onToast,
    required this.onOpenUrl,
  });

  final Bridge bridge;
  final ChatMessage message;
  final String model;
  final String statusLine;
  final bool running;
  final bool copied;
  final bool Function(String key) foldOpen;
  final ValueChanged<String> onToggleFold;
  final VoidCallback? onEdit;
  final VoidCallback? onRegenerate;
  final VoidCallback onFork;
  final VoidCallback onCopy;
  final Future<void> Function() onShareText;
  final Future<void> Function(Uint8List bytes) onShareImage;
  final void Function(String text, {bool isError}) onToast;
  final ValueChanged<String> onOpenUrl;

  @override
  Widget build(BuildContext context) {
    if (message.role == Role.user) {
      return _UserBubble(
        bridge: bridge,
        message: message,
        onEdit: onEdit,
      );
    }
    return _AssistantMessage(
      message: message,
      model: model,
      statusLine: statusLine,
      showActions: true,
      running: running,
      copied: copied,
      foldOpen: foldOpen,
      onToggleFold: onToggleFold,
      onRegenerate: onRegenerate,
      onFork: onFork,
      onCopy: onCopy,
      onShareText: onShareText,
      onShareImage: onShareImage,
      onToast: onToast,
      onOpenUrl: onOpenUrl,
    );
  }
}

class _UserBubble extends StatelessWidget {
  const _UserBubble({
    required this.bridge,
    required this.message,
    this.onEdit,
  });

  final Bridge bridge;
  final ChatMessage message;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final raw = message.blocks
        .whereType<TextBlock>()
        .map((b) => b.text)
        .join('\n');
    final split = splitAttachmentRefs(raw);
    final text = split.text;
    final paths = split.paths;
    final hasBody = text.trim().isNotEmpty;
    return LayoutBuilder(
      builder: (context, constraints) => Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Align(
            alignment: AlignmentDirectional.centerEnd,
            child: Semantics(
              button: onEdit != null,
              label: onEdit == null ? null : '你的消息，点击编辑',
              child: GestureDetector(
                onTap: onEdit,
                child: Container(
                  constraints: BoxConstraints(
                    maxWidth: constraints.maxWidth * 0.8,
                  ),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 10,
                  ),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHigh,
                    borderRadius: AppRadius.lg,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (paths.isNotEmpty)
                        AttachmentPreviewStrip(
                          bridge: bridge,
                          paths: paths,
                          wrap: true,
                          size: 54,
                          padding: EdgeInsets.only(bottom: hasBody ? 8 : 0),
                        ),
                      if (hasBody)
                        SelectableText(
                          text,
                          style: context.listTitleStyle.copyWith(
                            color: scheme.onSurface,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          if (onEdit != null)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: TextButton(
                onPressed: onEdit,
                style: ButtonStyle.from(
                  foregroundColor: scheme.onSurfaceVariant,
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: const Size(44, 44),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    GlyphIcon(
                      AppGlyphs.edit,
                      size: 13,
                      color: scheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      '编辑',
                      style: AppTextStyle.caption1
                          .of(context)
                          .copyWith(color: scheme.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _NoSearchMatches extends StatelessWidget {
  const _NoSearchMatches({required this.query});

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
              '没有匹配的消息',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 6),
            Text(
              query.trim().isEmpty ? '输入关键词开始搜索。' : '换个关键词试试。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
          ],
        ),
      ),
    );
  }
}

/// `stripTrailingSources` walks the whole answer and its code fences. Earlier
/// text blocks keep their identity across deltas, so the result is memoised per
/// block; the block being written gets a fresh entry on every delta and the
/// old one is dropped with it (Expando holds keys weakly).
final Expando<String> _visibleTextCache = Expando<String>('visibleText');

String _visibleText(TextBlock block) =>
    _visibleTextCache[block] ??= WebSources.stripTrailingSources(block.text);

class _AssistantMessage extends StatefulWidget {
  const _AssistantMessage({
    required this.message,
    required this.model,
    required this.statusLine,
    required this.showActions,
    required this.running,
    required this.copied,
    required this.foldOpen,
    required this.onToggleFold,
    this.onRegenerate,
    required this.onFork,
    required this.onCopy,
    required this.onShareText,
    required this.onShareImage,
    required this.onToast,
    required this.onOpenUrl,
  });

  final ChatMessage message;
  final String model;
  final String statusLine;
  final bool showActions;
  final bool running;
  final bool copied;
  final bool Function(String key) foldOpen;
  final ValueChanged<String> onToggleFold;
  final VoidCallback? onRegenerate;
  final VoidCallback onFork;
  final VoidCallback onCopy;
  final Future<void> Function() onShareText;
  final Future<void> Function(Uint8List bytes) onShareImage;
  final void Function(String text, {bool isError}) onToast;
  final ValueChanged<String> onOpenUrl;

  @override
  State<_AssistantMessage> createState() => _AssistantMessageState();
}

class _AssistantMessageState extends State<_AssistantMessage> {
  /// The snapshot region for "share as image": the header, folds and body in
  /// one boundary, while the action row stays outside it.
  final GlobalKey _captureKey = GlobalKey();

  Future<void> _share() async {
    final choice = await showReplyShareDialog(context);
    if (choice == null || !mounted) return;
    switch (choice) {
      case ReplyShareChoice.text:
        await widget.onShareText();
      case ReplyShareChoice.image:
        await _shareImage();
    }
  }

  Future<void> _shareImage() async {
    final boundary =
        _captureKey.currentContext?.findRenderObject()
            as RenderRepaintBoundary?;
    if (boundary == null) {
      widget.onToast('暂时无法生成图片，请重试。');
      return;
    }
    final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
    if (replyImagePixelRatio(boundary.size, devicePixelRatio) == null) {
      widget.onToast('这条回复太长，长图会超过上限。请改用「分享文字」。', isError: true);
      return;
    }
    widget.onToast('正在生成图片…');
    try {
      final bytes = await captureReplyImage(
        boundary: boundary,
        devicePixelRatio: devicePixelRatio,
        background: SurfaceLevel.page.colorOf(context.colorScheme),
      );
      if (!mounted) return;
      await widget.onShareImage(bytes);
    } catch (error) {
      if (!mounted) return;
      widget.onToast('生成图片失败（$error）。改用「分享文字」试试。', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final message = widget.message;
    final blocks = message.blocks;
    final thinkingSegments = [
      for (final block in blocks)
        if (block is ThinkingBlock && block.text.trim().isNotEmpty) block.text,
    ];
    final tools = blocks.whereType<ToolBlock>().toList(growable: false);
    final errorCount = tools.where((tool) => tool.isError).length;
    final visibleTexts = [
      for (final block in blocks)
        if (block is TextBlock) _visibleText(block) else null,
    ];
    final hasOutput = visibleTexts.any(
      (text) => text != null && text.trim().isNotEmpty,
    );
    final copyText = visibleTexts
        .whereType<String>()
        .where((text) => text.trim().isNotEmpty)
        .join('\n\n')
        .trim();
    final lastTextIndex = visibleTexts.lastIndexWhere(
      (text) => text != null && text.trim().isNotEmpty,
    );
    final duration = _durationLabel(message.durationMs);
    final cost = message.costUsd;
    // The label frozen by Kotlin when this turn started wins over the live
    // settings label; old records have none and keep the previous behavior.
    final storedModel = message.model?.trim() ?? '';
    final signature = message.streaming
        ? widget.statusLine
        : (storedModel.isNotEmpty ? storedModel : widget.model);
    final meta = [
      if (cost != null && cost > 0) '≈\$${cost.toStringAsFixed(4)}',
      if (duration.isNotEmpty) duration,
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        RepaintBoundary(
          key: _captureKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  GlyphIcon(AppGlyphs.sparkle, size: 18, color: scheme.primary),
                  const SizedBox(width: 8),
                  Text(
                    'Claude',
                    style: AppTextStyle.footnote.of(context, emphasized: true),
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      signature,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTextStyle.footnote
                          .of(context)
                          .copyWith(color: scheme.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              // Thinking and tool calls stay out of the body until the first
              // real answer text arrives: a live pill plus one activity line
              // per tool.
              AnimatedFold(
                open: !hasOutput && message.streaming,
                fade: false,
                animate: message.streaming,
                child: Padding(
                  padding: EdgeInsets.zero,
                  child: _ThinkingPill(
                    text: thinkingSegments.join('\n'),
                    active: true,
                    placeholder: thinkingSegments.isEmpty,
                    open: widget.foldOpen('pill:${message.id}'),
                    onToggle: () => widget.onToggleFold('pill:${message.id}'),
                  ),
                ),
              ),
              AnimatedFold(
                open: !hasOutput && message.streaming && tools.isNotEmpty,
                fade: false,
                animate: message.streaming,
                child: Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Column(
                    children: [for (final tool in tools) _ActivityLine(tool)],
                  ),
                ),
              ),
              AnimatedFold(
                open:
                    (hasOutput || !message.streaming) &&
                    (thinkingSegments.isNotEmpty || tools.isNotEmpty),
                fade: false,
                animate: message.streaming,
                child: _WorkDisclosure(
                  messageId: message.id,
                  blocks: blocks,
                  thinkingCount: thinkingSegments.length,
                  toolCount: tools.length,
                  errorCount: errorCount,
                  open: widget.foldOpen('work:${message.id}'),
                  onToggle: () => widget.onToggleFold('work:${message.id}'),
                  foldOpen: widget.foldOpen,
                  onToggleFold: widget.onToggleFold,
                  onOpenUrl: widget.onOpenUrl,
                ),
              ),
              // 一条回复的全部正文共用一个选择域：长按“全选”才能选中整段回答，
              // 而不是每个段落各选一段。标题行、思考过程与工具卡留在这个区域外，
              // 它们内部已有自己的选择域，嵌套进来反而会把全选截断。
              SelectionArea(
                contextMenuBuilder: appSelectionMenu,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (var index = 0; index < blocks.length; index++)
                      _contentBlock(
                        context,
                        blocks[index],
                        visibleTexts[index],
                        message.streaming && index == lastTextIndex,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        if (!message.streaming && (meta.isNotEmpty || widget.showActions))
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Row(
              children: [
                if (meta.isNotEmpty)
                  Expanded(
                    child: Text(
                      meta.join(' · '),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: context.listCaptionStyle,
                    ),
                  )
                else
                  const Spacer(),
                if (widget.showActions &&
                    !widget.running &&
                    widget.onRegenerate != null)
                  IconButton(
                    onPressed: widget.onRegenerate,
                    icon: const GlyphIcon(AppGlyphs.refresh, size: 18),
                    tooltip: '重新回答',
                  ),
                if (widget.showActions && copyText.isNotEmpty)
                  IconButton(
                    onPressed: widget.onCopy,
                    icon: GlyphIcon(
                      widget.copied ? Glyphs.check : Glyphs.copy,
                      size: 18,
                    ),
                    tooltip: widget.copied ? '已复制' : '复制',
                  ),
                if (widget.showActions && copyText.isNotEmpty)
                  IconButton(
                    onPressed: _share,
                    icon: const GlyphIcon(AppGlyphs.share, size: 18),
                    tooltip: '分享',
                  ),
                if (widget.showActions)
                  IconButton(
                    onPressed: widget.onFork,
                    icon: const GlyphIcon(AppGlyphs.fork, size: 18),
                    tooltip: '分出新对话',
                  ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _contentBlock(
    BuildContext context,
    Block block,
    String? visibleText,
    bool streaming,
  ) {
    if (block is TextBlock) {
      final display = visibleText ?? '';
      if (display.trim().isEmpty) return const SizedBox.shrink();
      return Padding(
        padding: const EdgeInsets.only(top: 10),
        child: MarkdownText(
          display,
          streaming: streaming,
          // 正文用系统无衬线：中文衬线的粗体在 Android 上会回退到黑体，
          // 正文出现「细宋体 + 粗黑体」混排；无衬线能保证中英文与字重一致。
          serif: false,
          onLinkTap: widget.onOpenUrl,
        ),
      );
    }
    if (block is ErrorBlock) {
      return Padding(
        padding: const EdgeInsets.only(top: 10),
        child: _ErrorNotice(text: block.text),
      );
    }
    return const SizedBox.shrink();
  }
}

class _ThinkingPill extends StatelessWidget {
  const _ThinkingPill({
    required this.text,
    required this.active,
    required this.placeholder,
    required this.open,
    required this.onToggle,
  });

  final String text;
  final bool active;
  final bool placeholder;
  final bool open;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: placeholder ? null : onToggle,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHigh,
              borderRadius: AppRadius.full,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ThinkingSparkle(
                  active: active,
                  color: active ? scheme.primary : scheme.onSurfaceVariant,
                ),
                const SizedBox(width: 6),
                Text(
                  active ? '思考中…' : '思考过程 · ${text.length} 字',
                  style: AppTextStyle.caption1
                      .of(context)
                      .copyWith(color: scheme.onSurfaceVariant),
                ),
                if (!placeholder) ...[
                  const SizedBox(width: 3),
                  FoldChevron(open: open, size: 14),
                ],
              ],
            ),
          ),
        ),
        AnimatedFold(
          open: open && !placeholder,
          child: Padding(
            padding: const EdgeInsets.only(top: 8, left: 13, right: 4),
            child: Container(
              padding: const EdgeInsets.only(left: 10),
              decoration: BoxDecoration(
                border: Border(
                  left: BorderSide(color: scheme.outlineVariant, width: 2),
                ),
              ),
              child: SelectableText(
                text,
                style: AppTextStyle.caption1
                    .of(context)
                    .copyWith(color: scheme.onSurfaceVariant, height: 20 / 13),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ActivityLine extends StatelessWidget {
  const _ActivityLine(this.tool);

  final ToolBlock tool;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final detail = toolCallDetail(tool.input);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          GlyphIcon(
            ToolPresentation.icon(tool.name),
            size: 16,
            color: tool.isError ? scheme.error : scheme.onSurfaceVariant,
          ),
          const SizedBox(width: 9),
          Text(
            ToolPresentation.verb(tool.name),
            style: AppTextStyle.caption1
                .of(context)
                .copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(width: 8),
          if (detail.isNotEmpty)
            Expanded(
              child: Text(
                detail,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyle.caption2
                    .of(context)
                    .copyWith(
                      fontFamily: 'monospace',
                      color: scheme.onSurfaceVariant.withValues(alpha: 0.75),
                    ),
              ),
            )
          else
            const Spacer(),
          const SizedBox(width: 8),
          if (tool.running)
            _PulseDot(color: scheme.primary)
          else if (tool.isError)
            Text(
              '失败',
              style: AppTextStyle.caption2
                  .of(context)
                  .copyWith(color: scheme.error),
            )
          else
            GlyphIcon(Glyphs.check, size: 12, color: scheme.onSurfaceVariant),
        ],
      ),
    );
  }
}

/// A quiet strip above the transcript when the bound host is offline or gone.
/// Reconnection is automatic; the action only opens the host settings.
class _RemoteHostBanner extends StatelessWidget {
  const _RemoteHostBanner({
    required this.message,
    required this.onOpenSettings,
  });

  final String message;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return ContentWidth(
      maxWidth: kChatContentWidth,
      heightFactor: 1,
      alignment: Alignment.center,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
        child: Container(
          padding: const EdgeInsets.fromLTRB(12, 9, 6, 9),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHigh,
            borderRadius: AppRadius.md,
            border: Border.all(
              color: scheme.outlineVariant.withValues(alpha: 0.6),
            ),
          ),
          child: Row(
            children: [
              GlyphIcon(
                AppGlyphs.plug,
                size: 16,
                color: scheme.onSurfaceVariant,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  message,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: AppTextStyle.caption1.of(context).copyWith(
                        color: scheme.onSurfaceVariant,
                        height: 1.35,
                      ),
                ),
              ),
              TextButton(
                onPressed: onOpenSettings,
                child: const Text('主机设置'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PulseDot extends StatefulWidget {  const _PulseDot({required this.color, this.size = 4});

  final Color color;
  final double size;

  @override
  State<_PulseDot> createState() => _PulseDotState();
}

class _PulseDotState extends State<_PulseDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
    value: 1,
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (context.disableAnimations) {
      _controller.stop();
      _controller.value = 1;
    } else if (!_controller.isAnimating) {
      _controller.repeat(reverse: true);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: widget.size,
      height: widget.size,
      decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle),
    );
    if (context.disableAnimations) return dot;
    return FadeTransition(
      opacity: Tween<double>(begin: 1, end: 0.3).animate(_controller),
      child: dot,
    );
  }
}

class _WorkDisclosure extends StatelessWidget {
  const _WorkDisclosure({
    required this.messageId,
    required this.blocks,
    required this.thinkingCount,
    required this.toolCount,
    required this.errorCount,
    required this.open,
    required this.onToggle,
    required this.foldOpen,
    required this.onToggleFold,
    required this.onOpenUrl,
  });

  final String messageId;
  final List<Block> blocks;
  final int thinkingCount;
  final int toolCount;
  final int errorCount;
  final bool open;
  final VoidCallback onToggle;
  final bool Function(String key) foldOpen;
  final ValueChanged<String> onToggleFold;
  final ValueChanged<String> onOpenUrl;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final children = <Widget>[];
    var thinkingIndex = 0;
    var first = true;
    for (var index = 0; index < blocks.length; index++) {
      final block = blocks[index];
      final isWork =
          (block is ThinkingBlock && block.text.trim().isNotEmpty) ||
          block is ToolBlock;
      if (!isWork) continue;
      if (!first) {
        children.add(Container(height: 1, color: scheme.outlineVariant));
      }
      first = false;
      if (block is ThinkingBlock) {
        thinkingIndex++;
        final key = 'think:$messageId:$index';
        children.add(
          _ThinkingFold(
            key: ValueKey('$messageId:$index'),
            text: block.text,
            index: thinkingIndex,
            total: thinkingCount,
            open: foldOpen(key),
            onToggle: () => onToggleFold(key),
          ),
        );
      } else if (block is ToolBlock) {
        final key = 'tool:${block.id}';
        children.add(
          _ToolCard(
            key: ValueKey(block.id),
            block: block,
            open: foldOpen(key),
            onToggle: () => onToggleFold(key),
            onOpenUrl: onOpenUrl,
          ),
        );
      }
    }

    return Container(
      margin: const EdgeInsets.only(top: 2, bottom: 12),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
        borderRadius: AppRadius.md,
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onToggle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
              child: Row(
                children: [
                  GlyphIcon(
                    AppGlyphs.sparkle,
                    size: 15,
                    color: errorCount > 0
                        ? scheme.error
                        : scheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 9),
                  Expanded(
                    child: Text(
                      workLabel(thinkingCount, toolCount, errorCount),
                      style: AppTextStyle.caption1
                          .of(context)
                          .copyWith(
                            color: errorCount > 0
                                ? scheme.error
                                : scheme.onSurfaceVariant,
                          ),
                    ),
                  ),
                  FoldChevron(open: open),
                ],
              ),
            ),
          ),
          AnimatedFold(
            open: open,
            child: Column(children: children),
          ),
        ],
      ),
    );
  }
}

class _ThinkingFold extends StatelessWidget {
  const _ThinkingFold({
    super.key,
    required this.text,
    required this.index,
    required this.total,
    required this.open,
    required this.onToggle,
  });

  final String text;
  final int index;
  final int total;
  final bool open;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onToggle,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            child: Row(
              children: [
                Text('思考过程 $index/$total', style: context.listCaptionStyle),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    '· ${text.length} 字',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: context.listCaptionStyle,
                  ),
                ),
                FoldChevron(open: open, size: 14),
              ],
            ),
          ),
        ),
        AnimatedFold(
          open: open,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
            child: Container(
              padding: const EdgeInsets.only(left: 10),
              decoration: BoxDecoration(
                border: Border(
                  left: BorderSide(color: scheme.outlineVariant, width: 2),
                ),
              ),
              child: SelectableText(
                text,
                style: AppTextStyle.caption1
                    .of(context)
                    .copyWith(color: scheme.onSurfaceVariant, height: 20 / 13),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ToolCard extends StatelessWidget {
  const _ToolCard({
    super.key,
    required this.block,
    required this.open,
    required this.onToggle,
    required this.onOpenUrl,
  });

  final ToolBlock block;
  final bool open;
  final VoidCallback onToggle;
  final ValueChanged<String> onOpenUrl;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final output = block.output;
    final isSearch =
        block.name.toLowerCase() == 'websearch' ||
        (output?.toLowerCase().contains('web search results') ?? false);
    final sources = isSearch
        ? WebSources.parse(output ?? '')
        : const <WebSource>[];
    final hint = block.isError ? webFetchFailureHint(block.name, output) : null;

    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: AppRadius.md,
        border: Border.all(
          color: block.isError
              ? scheme.error.withValues(alpha: 0.45)
              : scheme.outlineVariant.withValues(alpha: 0.7),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onToggle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(
                children: [
                  GlyphIcon(
                    ToolPresentation.icon(block.name),
                    size: 18,
                    color: block.isError
                        ? scheme.error
                        : scheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${ToolPresentation.verb(block.name)} · ${block.name}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTextStyle.footnote.of(
                            context,
                            emphasized: true,
                          ),
                        ),
                        if (toolCallDetail(block.input).isNotEmpty)
                          Text(
                            toolCallDetail(block.input),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: AppTextStyle.caption2
                                .of(context)
                                .copyWith(
                                  fontFamily: 'monospace',
                                  color: scheme.onSurfaceVariant,
                                ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  if (block.running) ...[
                    _PulseDot(color: scheme.primary, size: 5),
                    const SizedBox(width: 5),
                    Text(
                      '运行中',
                      style: AppTextStyle.caption2
                          .of(context)
                          .copyWith(color: scheme.primary),
                    ),
                  ] else if (block.isError)
                    Text(
                      '失败',
                      style: AppTextStyle.caption2
                          .of(context)
                          .copyWith(color: scheme.error),
                    )
                  else
                    Semantics(
                      label: '完成',
                      child: GlyphIcon(
                        Glyphs.check,
                        size: 14,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  const SizedBox(width: 5),
                  FoldChevron(open: open),
                ],
              ),
            ),
          ),
          if (hint != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  GlyphIcon(AppGlyphs.refresh, size: 14, color: scheme.error),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      hint,
                      style: AppTextStyle.caption1
                          .of(context)
                          .copyWith(color: scheme.error, height: 1.35),
                    ),
                  ),
                ],
              ),
            ),
          AnimatedFold(
            open: open,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(height: 1, color: scheme.outlineVariant),
                // Sources belong to the detail view: collapsed cards show only the
                // one-line summary, so a search result never becomes a wall of links.
                if (sources.isNotEmpty)
                  _SourcesSection(sources: sources, onOpenUrl: onOpenUrl),
                if (sources.isNotEmpty)
                  Container(height: 1, color: scheme.outlineVariant),
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _MonoBlock(label: '入参', text: block.input),
                      if (output != null) ...[
                        const SizedBox(height: 10),
                        _MonoBlock(
                          label: '输出',
                          text: output,
                          isError: block.isError,
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SourcesSection extends StatefulWidget {
  const _SourcesSection({required this.sources, required this.onOpenUrl});

  final List<WebSource> sources;
  final ValueChanged<String> onOpenUrl;

  @override
  State<_SourcesSection> createState() => _SourcesSectionState();
}

class _SourcesSectionState extends State<_SourcesSection> {
  bool _showAll = false;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final visible = _showAll
        ? widget.sources
        : widget.sources.take(4).toList(growable: false);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 2, bottom: 6),
            child: Text(
              '来源 · ${widget.sources.length}',
              style: context.listCaptionStyle,
            ),
          ),
          for (final (index, source) in visible.indexed)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Semantics(
                button: true,
                label: '打开来源 ${source.title}',
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => widget.onOpenUrl(source.url),
                  child: Container(
                    constraints: const BoxConstraints(minHeight: 44),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 9,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: scheme.surfaceContainerHigh,
                      borderRadius: AppRadius.sm,
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 18,
                          height: 18,
                          decoration: BoxDecoration(
                            color: scheme.primary.withValues(alpha: 0.12),
                            shape: BoxShape.circle,
                          ),
                          child: Center(
                            child: Text(
                              '${index + 1}',
                              style: AppTextStyle.caption2
                                  .of(context)
                                  .copyWith(color: scheme.primary),
                            ),
                          ),
                        ),
                        const SizedBox(width: 9),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                source.title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: AppTextStyle.caption1.of(context),
                              ),
                              if (source.host.isNotEmpty)
                                Text(
                                  source.host,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: AppTextStyle.caption2
                                      .of(context)
                                      .copyWith(color: scheme.onSurfaceVariant),
                                ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          '↗',
                          style: AppTextStyle.caption1
                              .of(context)
                              .copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          if (widget.sources.length > 4)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => setState(() => _showAll = !_showAll),
              child: Padding(
                padding: const EdgeInsets.only(top: 6, left: 2),
                child: Text(
                  _showAll ? '收起' : '展开全部 ${widget.sources.length} 个来源',
                  style: AppTextStyle.caption1
                      .of(context)
                      .copyWith(color: scheme.primary),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _MonoBlock extends StatefulWidget {
  const _MonoBlock({
    required this.label,
    required this.text,
    this.isError = false,
  });

  final String label;
  final String text;
  final bool isError;

  @override
  State<_MonoBlock> createState() => _MonoBlockState();
}

class _MonoBlockState extends State<_MonoBlock> {
  bool _copied = false;
  Timer? _copiedTimer;

  @override
  void dispose() {
    _copiedTimer?.cancel();
    super.dispose();
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: widget.text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    setState(() => _copied = true);
    _copiedTimer?.cancel();
    _copiedTimer = Timer(const Duration(milliseconds: 1200), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                widget.label,
                style: AppTextStyle.caption2
                    .of(context)
                    .copyWith(
                      color: scheme.onSurfaceVariant,
                      letterSpacing: 0.3,
                    ),
              ),
            ),
            TextButton(
              onPressed: _copy,
              style: ButtonStyle.from(
                foregroundColor: scheme.onSurfaceVariant,
                padding: const EdgeInsets.symmetric(horizontal: 6),
                minimumSize: const Size(44, 32),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  GlyphIcon(
                    _copied ? Glyphs.check : Glyphs.copy,
                    size: 13,
                    color: scheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    _copied ? '已复制' : '复制',
                    style: AppTextStyle.caption2
                        .of(context)
                        .copyWith(color: scheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 2),
        Container(
          constraints: const BoxConstraints(maxHeight: 320),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHighest,
            borderRadius: AppRadius.sm,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            child: SelectableText(
              widget.text,
              style: AppTextStyle.caption1
                  .of(context)
                  .copyWith(
                    fontFamily: 'monospace',
                    height: 1.4,
                    color: widget.isError ? scheme.error : null,
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
        color: scheme.errorContainer.withValues(alpha: 0.35),
        borderRadius: AppRadius.md,
        border: Border.all(color: scheme.error.withValues(alpha: 0.45)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          GlyphIcon(AppGlyphs.refresh, size: 15, color: scheme.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: AppTextStyle.caption1
                  .of(context)
                  .copyWith(color: scheme.error, height: 1.45),
            ),
          ),
        ],
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
      child: PressSurface(
        onPressed: onPressed,
        fill: scheme.surfaceContainer,
        side: BorderSide(color: scheme.outlineVariant),
        shape: AppShape.full,
        padding: EdgeInsets.zero,
        child: SizedBox(
          width: 48,
          height: 48,
          child: Center(
            child: GlyphIcon(
              Glyphs.chevronDown,
              size: 20,
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

/// One cached transcript row. It stores only what decides whether the row
/// still renders the same thing — never parsed spans or element state, which
/// belong to the row's own widgets.
class _RowCacheEntry {
  const _RowCacheEntry({
    required this.widget,
    required this.message,
    required this.isLastAssistant,
    required this.running,
    required this.model,
    required this.statusLine,
    required this.epoch,
  });

  final Widget widget;
  final ChatMessage message;
  final bool isLastAssistant;
  final bool running;
  final String model;

  /// Only consulted for streaming messages; completed rows show the frozen
  /// model label instead. Stored as '' for them so status changes never
  /// invalidate an idle transcript.
  final String statusLine;

  /// Page-owned interaction state (folds, copy feedback, edit mode, search).
  final int epoch;
}

/// A [ChangeNotifier] the bar listens to; the page bumps it whenever the
/// folded chat state changes, since the bar is rebuilt outside `setState`.
class _BarTick extends ChangeNotifier {
  void bump() => notifyListeners();
}
