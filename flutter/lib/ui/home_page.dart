import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'attachments.dart';
import 'claude_dir_dialog.dart';
import 'composer.dart';
import 'fold.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'model_menu.dart';
import 'organic_halo.dart';
import 'permission_menu.dart';
import 'scaffold_bar.dart';
import 'settings/settings_destination.dart';
import 'settings/settings_labels.dart';
import 'time_format.dart';
import 'typography.dart';
import 'workspace_switcher.dart';

/// The landing page, mirroring `ui/home/HomeScreen.kt`: a floating bar with the
/// model capsule, the greeting, live AI suggestions, and one composer. Sending
/// here is handed to the shell, which creates the session and opens it.
class HomePage extends StatefulWidget {
  const HomePage({
    super.key,
    required this.bridge,
    required this.settings,
    required this.labels,
    required this.engine,
    required this.homeEpoch,
    required this.sessions,
    required this.onOpenSession,
    required this.onOpenDrawer,
    required this.onOpenSettings,
    required this.onOpenWorkspace,
    required this.onNewChat,
    required this.onSend,
    required this.onUpdateSettings,
    required this.onToast,
    this.notice,
  });

  final Bridge bridge;
  final AppSettings settings;
  final ModelLabels labels;
  final EngineState engine;

  /// Bumped whenever "new chat" is pressed; suggestions are regenerated and the
  /// draft is dropped, the same reset the Compose `resetKey` performs.
  final int homeEpoch;

  /// Most recent conversations, used for the "continue" card.
  final List<ChatSession> sessions;
  final ValueChanged<String> onOpenSession;
  final VoidCallback onOpenDrawer;
  final ValueChanged<SettingsDestination> onOpenSettings;
  final VoidCallback onOpenWorkspace;
  final VoidCallback onNewChat;

  /// Creates the session, sends [text], and switches to it. `true` means the
  /// host accepted the turn and the draft can be cleared.
  final Future<bool> Function(String text) onSend;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;
  final String? notice;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final TextEditingController _input = TextEditingController();
  final FocusNode _inputFocus = FocusNode();
  final GlobalKey<ChatComposerState> _composerKey = GlobalKey<ChatComposerState>();

  List<String> _suggestions = const [];
  bool _loadingSuggestions = true;
  bool _sending = false;
  int _suggestionGeneration = 0;

  ChatSession? get _recentSession {
    ChatSession? latest;
    for (final session in widget.sessions) {
      if (!session.hasContent) continue;
      if (latest == null || session.updatedAt > latest.updatedAt) {
        latest = session;
      }
    }
    return latest;
  }

  @override
  void initState() {
    super.initState();
    _loadSuggestions();
  }

  @override
  void didUpdateWidget(covariant HomePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.homeEpoch != widget.homeEpoch) {
      _input.clear();
      _loadSuggestions();
    }
  }

  @override
  void dispose() {
    _input.dispose();
    _inputFocus.dispose();
    super.dispose();
  }

  Future<void> _loadSuggestions() async {
    final generation = ++_suggestionGeneration;
    setState(() {
      _loadingSuggestions = true;
      _suggestions = const [];
    });
    try {
      final items = await widget.bridge.suggestions(count: 3);
      if (!mounted || generation != _suggestionGeneration) return;
      setState(() {
        _suggestions = items;
        _loadingSuggestions = false;
      });
    } catch (_) {
      // Suggestions are decorative; a failed generation hides the strip.
      if (!mounted || generation != _suggestionGeneration) return;
      setState(() => _loadingSuggestions = false);
    }
  }

  Future<void> _submit() async {
    final composer = _composerKey.currentState;
    final paths = composer?.attachments ?? const <String>[];
    final text = _input.text.trim();
    if ((text.isEmpty && paths.isEmpty) || _sending) {
      return;
    }
    setState(() => _sending = true);
    final accepted = await widget.onSend(composeChatPrompt(text, paths));
    if (!mounted) return;
    setState(() => _sending = false);
    if (accepted) {
      _input.clear();
      composer?.clearAttachments();
    }
  }

  void _patch(Map<String, dynamic> patch) {
    unawaited(widget.onUpdateSettings(patch));
  }

  /// Picks the directory Claude Code runs in; applied by the host on the next
  /// turn, so no new conversation or engine restart is needed.
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

  List<SlashAction> _slashActions() => [
        SlashAction(
          command: 'new',
          label: '新对话',
          glyph: AppGlyphs.plus,
          onPressed: widget.onNewChat,
        ),
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

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverLoading,
        leading: (context) => IconButton(
          onPressed: widget.onOpenDrawer,
          icon: const GlyphIcon(AppGlyphs.menu),
          tooltip: '会话',
        ),
        title: (context) => const Text('Claudroid'),
        actions: [
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
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                // Landscape phones leave roughly 200dp between the bar and the
                // composer; shrink the hero so the continue card is not cut.
                final compact = constraints.maxHeight < 300;
                // With the suggestion strip on screen the hero gets ~150dp,
                // which only fits without the halo.
                final tight = constraints.maxHeight < 180;
                final recent = _recentSession;
                return ContentWidth(
                  maxWidth: kHomeContentWidth,
                  child: SingleChildScrollView(
                    padding: EdgeInsets.fromLTRB(
                      26,
                      context.contentTopPadding,
                      26,
                      8,
                    ),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                        minHeight: (constraints.maxHeight -
                                context.contentTopPadding -
                                8)
                            .clamp(0.0, double.infinity)
                            .toDouble(),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (!tight)
                            Center(
                              child: _Halo(
                                color: context.colorScheme.primary,
                                size: compact ? 60 : 84,
                              ),
                            ),
                          SizedBox(height: tight ? 0 : (compact ? 10 : 16)),
                          Text(
                            greetingForHour(DateTime.now().hour),
                            textAlign: TextAlign.center,
                            style: claudroidSerif(
                              AppTextStyle.custom(
                                context,
                                size: tight ? 24 : (compact ? 26 : 30),
                                leading: tight ? 30 : (compact ? 32 : 38),
                              ),
                            ).copyWith(fontWeight: FontWeight.w500),
                          ),
                          SizedBox(height: tight ? 6 : 8),
                          Text(
                            '今天想让 Claude 在这台手机上做什么？',
                            textAlign: TextAlign.center,
                            style: context.listSubtitleStyle,
                          ),
                          // Folds in on the first load instead of popping in
                          // above the composer; the lead-in gap folds with it.
                          RetainedFold(
                            open: recent != null,
                            isEmpty: recent == null,
                            slide: 6,
                            child: Padding(
                              padding: EdgeInsets.only(
                                top: compact ? 12 : 18,
                              ),
                              child: recent == null
                                  ? const SizedBox.shrink()
                                  : _RecentSessionCard(
                                      session: recent,
                                      onPressed: () =>
                                          widget.onOpenSession(recent.id),
                                    ),
                            ),
                          ),
                          if (widget.notice != null &&
                              widget.notice!.isNotEmpty) ...[
                            const SizedBox(height: 18),
                            _StartupNotice(text: widget.notice!),
                          ],
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          AnimatedFold(
            open: !widget.engine.ready,
            slide: 6,
            child: _EngineHint(
              engine: widget.engine,
              onPressed: () =>
                  widget.onOpenSettings(SettingsDestination.engine),
            ),
          ),
          if (_loadingSuggestions || _suggestions.isNotEmpty)
            _SuggestionStrip(
              loading: _loadingSuggestions,
              items: _suggestions,
              onSelect: (text) {
                _input.text = text;
                _inputFocus.requestFocus();
              },
            ),
          ContentWidth(
            maxWidth: kHomeContentWidth,
            heightFactor: 1,
            alignment: Alignment.center,
            child: ChatComposer(
              key: _composerKey,
              bridge: widget.bridge,
              controller: _input,
              focusNode: _inputFocus,
              running: false,
              busy: _sending,
              onSubmit: _submit,
              onToast: widget.onToast,
              hintText: '有什么可以帮你的？',
              actions: _slashActions(),
              footnote: permissionFootnote(
                widget.settings.str('permissionMode', 'BYPASS'),
              ),
              bottomInset: context.bottomInset,
            ),
          ),
        ],
      ),
    );
  }

}

class _RecentSessionCard extends StatelessWidget {
  const _RecentSessionCard({required this.session, required this.onPressed});

  final ChatSession session;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final preview = session.preview.trim();
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Card(
          onPressed: onPressed,
          padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
          child: Row(
            children: [
              GlyphIcon(AppGlyphs.sparkle, size: 18, color: scheme.primary),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      session.title.isEmpty ? '上次对话' : session.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: context.listTitleStyle,
                    ),
                    if (preview.isNotEmpty)
                      Text(
                        preview,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTextStyle.caption1.of(context).copyWith(
                              color: scheme.onSurfaceVariant,
                            ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(
                sessionTimeLabel(session.updatedAt),
                style: AppTextStyle.caption2.of(context).copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(width: 2),
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
}

class _Halo extends StatelessWidget {
  const _Halo({required this.color, this.size = 84});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          OrganicHalo(
            size: size,
            color: color.withValues(alpha: 0.13),
          ),
          GlyphIcon(AppGlyphs.sparkle, size: size * 0.38, color: color),
        ],
      ),
    );
  }
}

class _SuggestionStrip extends StatelessWidget {
  const _SuggestionStrip({
    required this.loading,
    required this.items,
    required this.onSelect,
  });

  final bool loading;
  final List<String> items;
  final ValueChanged<String> onSelect;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return SizedBox(
      height: 52,
      child: PageTransitionSwitcher(
        // The skeleton hands off to the chips instead of being replaced on a
        // single frame; 220 ms is the fade-through rhythm used by the rest of
        // the surfaces.
        duration: context.motionDuration(const Duration(milliseconds: 220)),
        transitionBuilder: (child, primaryAnimation, secondaryAnimation) =>
            FadeThroughTransition(
              animation: primaryAnimation,
              secondaryAnimation: secondaryAnimation,
              child: child,
            ),
        // Fill the strip. The default centered Stack gives each entry loose
        // width, which would shrink the horizontal list to its content and
        // move the leading padding inward.
        layoutBuilder: (entries) => Stack(
          alignment: AlignmentDirectional.topStart,
          children: [
            for (final entry in entries) Positioned.fill(child: entry),
          ],
        ),
        child: KeyedSubtree(
          key: ValueKey(loading),
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(16, 2, 16, 8),
            child: Row(
              children: [
                if (loading)
                  for (final width in const [120.0, 100.0, 110.0])
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: SkeletonPill(width: width, height: 40),
                    )
                else
                  for (final text in items)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: Semantics(
                        button: true,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () => onSelect(text),
                          child: Container(
                            height: 40,
                            padding: const EdgeInsets.symmetric(horizontal: 13),
                            decoration: BoxDecoration(
                              color: scheme.surfaceContainerLow,
                              borderRadius: AppRadius.full,
                              border: Border.all(color: scheme.outlineVariant),
                            ),
                            child: Center(
                              child: Text(
                                text,
                                maxLines: 1,
                                style: AppTextStyle.caption1
                                    .of(context)
                                    .copyWith(
                                      color: scheme.onSurfaceVariant,
                                    ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _EngineHint extends StatelessWidget {
  const _EngineHint({required this.engine, required this.onPressed});

  final EngineState engine;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final message = switch (engine.phase) {
      'Working' =>
        '${engine.stage.isEmpty ? '正在准备引擎' : engine.stage}… ${(engine.fraction * 100).toInt()}%',
      'Failed' => '引擎初始化失败，点此查看原因',
      _ => 'Claude Code 尚未就绪',
    };
    final dot = engine.phase == 'Failed' ? scheme.error : scheme.primary;
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onPressed,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHigh,
            borderRadius: AppRadius.full,
          ),
          child: Row(
            children: [
              Container(
                width: 6,
                height: 6,
                decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  message,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTextStyle.caption1.of(context).copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '去设置',
                style: AppTextStyle.caption1.of(context).copyWith(
                      color: scheme.primary,
                      fontWeight: FontWeight.w600,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StartupNotice extends StatelessWidget {
  const _StartupNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: scheme.error.withValues(alpha: 0.10),
        borderRadius: AppRadius.md,
      ),
      child: Text(
        '启动时没能连上原生桥接：$text\n'
        '先重新打开应用；仍然失败的话，在「设置 → 关于」里看引擎日志。',
        style: AppTextStyle.caption1.of(context).copyWith(
              color: scheme.error,
              height: 1.45,
            ),
      ),
    );
  }
}

String greetingForHour(int hour) => switch (hour) {
      >= 5 && <= 10 => '早上好',
      >= 11 && <= 13 => '中午好',
      >= 14 && <= 17 => '下午好',
      >= 18 && <= 22 => '晚上好',
      _ => '夜深了',
    };

final Listenable _neverBar = _NeverNotifier();
final ValueListenable<bool> _neverLoading = _NeverValueListenable();

class _NeverNotifier extends ChangeNotifier {}

class _NeverValueListenable implements ValueListenable<bool> {
  const _NeverValueListenable();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
