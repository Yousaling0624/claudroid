import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import 'attachments.dart';
import 'dialog_motion.dart';
import 'fold.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'selection_menu.dart';

/// One entry in the slash palette. [command] is what the user types after `/`;
/// [label] is the human-readable action shown in the list.
class SlashAction {
  const SlashAction({
    required this.command,
    required this.label,
    required this.glyph,
    required this.onPressed,
    this.enabled = true,
    this.danger = false,
  });

  final String command;
  final String label;
  final Glyph glyph;
  final VoidCallback onPressed;
  final bool enabled;
  final bool danger;
}

/// A palette opens for a single-line `/` prefix. The sender may still send that
/// text literally later (e.g. `/` alone or an unknown command).
bool slashPaletteOpen(String text) =>
    text.startsWith('/') && !text.contains('\n');

String slashQueryOf(String text) =>
    text.startsWith('/') ? text.substring(1).trim().toLowerCase() : '';

/// Commands matching the typed text, in declaration order. A blank query keeps
/// the whole palette visible.
List<SlashAction> filterSlashActions(List<SlashAction> actions, String text) {
  if (!slashPaletteOpen(text)) return const [];
  final query = slashQueryOf(text);
  return [
    for (final action in actions)
      if (query.isEmpty ||
          action.command.toLowerCase().contains(query) ||
          action.label.toLowerCase().contains(query))
        action,
  ];
}

/// The command Enter should run for [text], or `null` when [text] is a literal
/// message (e.g. `/` or an unknown command).
///
/// An exact command name wins even when disabled, so Enter can never silently
/// turn a real command into a chat message. A unique partial match is accepted
/// too (typing `/pl` then Enter), matching what the visible palette shows.
SlashAction? slashEnterAction(List<SlashAction> actions, String text) {
  if (!slashPaletteOpen(text)) return null;
  final query = slashQueryOf(text);
  if (query.isEmpty) return null;
  final matches = filterSlashActions(actions, text);
  for (final action in matches) {
    if (action.command.toLowerCase() == query) return action;
  }
  final enabled = [
    for (final action in matches)
      if (action.enabled) action,
  ];
  return enabled.length == 1 ? enabled.single : null;
}

/// Lists every command with its trigger, for `/help`.
Future<void> showSlashHelpDialog(
  BuildContext context,
  List<SlashAction> actions,
) {
  final scheme = context.colorScheme;
  return showClaudroidDialog<void>(
    context: context,
    child: AlertDialog(
      title: '斜杠命令',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('关闭'),
        ),
      ],
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.52,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < actions.length; i++)
                DialogRowReveal(
                  index: i,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: [
                        GlyphIcon(
                          actions[i].glyph,
                          size: 18,
                          color: actions[i].enabled
                              ? scheme.onSurfaceVariant
                              : scheme.onSurfaceVariant.withValues(alpha: 0.4),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            actions[i].label,
                            style: AppTextStyle.subheadline.of(context),
                          ),
                        ),
                        Text(
                          '/${actions[i].command}',
                          style: AppTextStyle.caption1.of(context).copyWith(
                                color: scheme.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              Text(
                '输入 / 打开命令面板，回车执行。不是命令的内容（例如只发 /）会作为普通消息发送。',
                style: AppTextStyle.caption2.of(context).copyWith(
                      color: scheme.onSurfaceVariant,
                      height: 1.4,
                    ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

/// The input capsule shared by the home page and the chat page.
///
/// It owns only presentation: expanding the panel for `/`, opening the attachment
/// menu (camera or document picker), and holding the picked files as preview chips.
/// The picked paths are handed to the page on submit (`composeChatPrompt` appends
/// them to the prompt); the text itself lives in the caller's [controller], so
/// drafts and "edit and resend" stay under the page's control.
class ChatComposer extends StatefulWidget {
  const ChatComposer({
    super.key,
    required this.bridge,
    required this.controller,
    required this.focusNode,
    required this.onSubmit,
    required this.onToast,
    this.running = false,
    this.busy = false,
    this.onStop,
    this.editing = false,
    this.onCancelEdit,
    this.hintText = '输入回答…',
    this.actions = const [],
    this.onChanged,
    this.footnote,
    this.bottomInset = 0,
  });

  final Bridge bridge;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onSubmit;
  final void Function(String text, {bool isError}) onToast;
  final bool running;

  /// Disables the send button while the caller is creating a session.
  final bool busy;
  final VoidCallback? onStop;
  final bool editing;
  final VoidCallback? onCancelEdit;
  final String hintText;
  final List<SlashAction> actions;

  /// Lets the page keep a per-session draft even while it is disposed.
  final ValueChanged<String>? onChanged;

  /// A one-line caution under the capsule (tool permissions, agent safety).
  final String? footnote;

  /// Extra bottom padding; the chat page adds the navigation-bar inset itself
  /// through the scaffold, the home page passes it in.
  final double bottomInset;

  @override
  State<ChatComposer> createState() => ChatComposerState();
}

class ChatComposerState extends State<ChatComposer> {
  bool _picking = false;

  /// Workspace-relative paths picked through the host, in pick order.
  final List<String> _attachments = [];

  List<String> get attachments => List.unmodifiable(_attachments);

  /// Used by "edit and resend": the old message's attachment tokens become
  /// preview chips again.
  void setAttachments(List<String> paths) {
    setState(() {
      _attachments
        ..clear()
        ..addAll(paths.where((path) => path.trim().isNotEmpty));
    });
  }

  void clearAttachments() {
    if (_attachments.isEmpty) return;
    setState(_attachments.clear);
  }

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_handleControllerChanged);
  }

  @override
  void didUpdateWidget(covariant ChatComposer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_handleControllerChanged);
      widget.controller.addListener(_handleControllerChanged);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleControllerChanged);
    super.dispose();
  }

  // Programmatic changes (slash actions, editing prefill, draft restore) must
  // update the send button's idle/filled state too, not only typed input.
  void _handleControllerChanged() {
    if (mounted) setState(() {});
  }

  bool get _hasContent =>
      widget.controller.text.trim().isNotEmpty || _attachments.isNotEmpty;

  bool get _slashOpen => slashPaletteOpen(widget.controller.text);

  String get _slashQuery => slashQueryOf(widget.controller.text);

  List<SlashAction> get _slashMatches =>
      filterSlashActions(widget.actions, widget.controller.text);

  /// Enter (and the send button) runs the command the palette is pointing at;
  /// anything that is not a command is sent as a normal message, so a bare `/`
  /// finally has a send path.
  void _sendOrSlash() {
    final action = slashEnterAction(widget.actions, widget.controller.text);
    if (action != null) {
      if (!action.enabled) {
        widget.onToast('/${action.command} 现在不可用', isError: true);
        return;
      }
      _runSlash(action);
      return;
    }
    // Several partial matches are still a choice for the user; do not guess and
    // send an ambiguous fragment as a message.
    if (_slashOpen && _slashQuery.isNotEmpty && _slashMatches.isNotEmpty) {
      return;
    }
    widget.onSubmit();
  }

  void _handleEnter() {
    if (widget.running || widget.busy || _picking) return;
    _sendOrSlash();
  }

  void _onChanged(String value) {
    widget.onChanged?.call(value);
    setState(() {});
  }

  // prism_ui's buttons notify their states controller when `didUpdateWidget`
  // turns them disabled, and the notification reaches the button's own
  // `setState`. Doing that while the tap release is still animating trips the
  // framework's `assert(!_dirty)` (the red screen after pressing send). So the
  // composer's round buttons keep a non-null callback and refuse inside it:
  // the press animation never gets cancelled mid-update.
  void _sendPressed() {
    if (widget.running || widget.busy || _picking) return;
    unawaited(HapticFeedback.selectionClick());
    _sendOrSlash();
  }

  void _stopPressed() {
    unawaited(HapticFeedback.selectionClick());
    widget.onStop?.call();
  }

  void _runSlash(SlashAction action) {
    if (!action.enabled) return;
    widget.controller.clear();
    setState(() {});
    widget.focusNode.requestFocus();
    action.onPressed();
  }

  /// Shared pick/import flow: one request at a time, dedupe on append, then focus
  /// the field so the user can type the message. [failure] is the leading phrase
  /// of the toast, e.g. 「没能添加附件」.
  Future<void> _addAttachments(
    Future<List<String>> Function() pick,
    String failure,
  ) async {
    if (_picking) return;
    setState(() => _picking = true);
    try {
      final paths = await pick();
      if (!mounted || paths.isEmpty) return;
      setState(() {
        for (final path in paths) {
          if (!_attachments.contains(path)) _attachments.add(path);
        }
      });
      widget.focusNode.requestFocus();
    } catch (error) {
      widget.onToast('$failure（$error）。再试一次。', isError: true);
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  Future<void> pickAttachments() =>
      _addAttachments(widget.bridge.pickAttachments, '没能添加附件');

  Future<void> captureAttachment() =>
      _addAttachments(widget.bridge.captureAttachment, '没能拍照');

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final matches = _slashOpen ? _slashMatches : const <SlashAction>[];
    final active = widget.running || (_hasContent && !widget.busy);
    final buttonStyle = ButtonStyle(
      backgroundColor: WidgetStatePropertyAll(
        active ? scheme.primary : scheme.surfaceContainerHighest,
      ),
      foregroundColor: WidgetStatePropertyAll(
        active ? scheme.onPrimary : scheme.onSurfaceVariant,
      ),
    );

    return Padding(
      padding: EdgeInsets.fromLTRB(
        kPageInset,
        8,
        kPageInset,
        8 + widget.bottomInset,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedFold(
            open: widget.editing,
            slide: 6,
            child: Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Container(
                padding: const EdgeInsets.fromLTRB(12, 5, 6, 5),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHigh,
                  borderRadius: AppRadius.md,
                ),
                child: Row(
                  children: [
                    GlyphIcon(
                      AppGlyphs.edit,
                      size: 16,
                      color: scheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '正在修改这条消息，发送即保存',
                        style: context.listCaptionStyle,
                      ),
                    ),
                    TextButton(
                      onPressed: widget.onCancelEdit,
                      child: const Text('取消'),
                    ),
                  ],
                ),
              ),
            ),
          ),
          RetainedFold(
            open: matches.isNotEmpty,
            isEmpty: matches.isEmpty,
            slide: 8,
            child: Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: _SlashPanel(actions: matches, onSelected: _runSlash),
            ),
          ),
          _GlassComposerSurface(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(6, 6, 6, 6),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  RetainedFold(
                    open: _attachments.isNotEmpty,
                    isEmpty: _attachments.isEmpty,
                    slide: 6,
                    child: AttachmentPreviewStrip(
                      bridge: widget.bridge,
                      // A snapshot, so the retained strip keeps showing the
                      // removed files while the fold collapses.
                      paths: List<String>.of(_attachments),
                      size: 56,
                      padding: const EdgeInsets.fromLTRB(4, 2, 4, 6),
                      onRemove: (path) =>
                          setState(() => _attachments.remove(path)),
                    ),
                  ),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      PopupAnchor(
                        placement: PopupPlacement.overAnchorStart,
                        targetBuilder: (open) => IconButton(
                          onPressed: () {
                            if (_picking) return;
                            open();
                          },
                          icon: _picking
                              ? ActivityIndicator(
                                  size: 16,
                                  color: scheme.onSurfaceVariant,
                                )
                              : const GlyphIcon(AppGlyphs.attachment, size: 19),
                          tooltip: '添加附件',
                        ),
                        popupBuilder: (_) => PopupMenu(
                          items: [
                            PopupMenuItem(
                              label: '拍照',
                              glyph: AppGlyphs.camera,
                              onPressed: () => unawaited(captureAttachment()),
                            ),
                            PopupMenuItem(
                              label: '选择文件',
                              glyph: AppGlyphs.file,
                              onPressed: () => unawaited(pickAttachments()),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: Focus(
                          canRequestFocus: false,
                          skipTraversal: true,
                          onKeyEvent: (node, event) {
                            if (!_slashOpen || event is! KeyDownEvent) {
                              return KeyEventResult.ignored;
                            }
                            if (HardwareKeyboard.instance.isShiftPressed) {
                              return KeyEventResult.ignored;
                            }
                            if (event.logicalKey == LogicalKeyboardKey.enter ||
                                event.logicalKey ==
                                    LogicalKeyboardKey.numpadEnter) {
                              _handleEnter();
                              return KeyEventResult.handled;
                            }
                            return KeyEventResult.ignored;
                          },
                          child: TextField(
                            controller: widget.controller,
                            focusNode: widget.focusNode,
                            contextMenuBuilder: appEditableTextMenu,
                            minLines: 1,
                            maxLines: 6,
                            keyboardType: TextInputType.multiline,
                            // While the palette is open the IME action executes the
                            // command instead of inserting a newline.
                            textInputAction: _slashOpen
                                ? TextInputAction.done
                                : TextInputAction.newline,
                            decoration: InputDecoration(
                              hintText: widget.editing
                                  ? '修改这条消息…'
                                  : widget.hintText,
                              border: const NoInputBorder(),
                            ),
                            onChanged: _onChanged,
                            onSubmitted: _slashOpen
                                ? (_) => _handleEnter()
                                : null,
                          ),
                        ),
                      ),
                      const SizedBox(width: 4),
                      IconButton(
                        onPressed: widget.running ? _stopPressed : _sendPressed,
                        style: buttonStyle,
                        icon: widget.busy
                            ? ActivityIndicator(
                                size: 16,
                                color: scheme.onSurfaceVariant,
                              )
                            : GlyphIcon(
                                widget.running
                                    ? AppGlyphs.stop
                                    : AppGlyphs.send,
                                size: 20,
                              ),
                        tooltip: widget.running ? '停止' : '发送',
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          if (widget.footnote != null && widget.footnote!.isNotEmpty)
            ComposerFootnote(text: widget.footnote!),
        ],
      ),
    );
  }
}

/// The quiet caution under the capsule, matching the Compose composer's
/// permission/agent note. Kept flush with the composer so it scrolls with the
/// keyboard instead of disappearing under the navigation bar.
class ComposerFootnote extends StatelessWidget {
  const ComposerFootnote({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(kPageInset, 7, kPageInset, 0),
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: AppTextStyle.caption2.of(context).copyWith(
              color: context.colorScheme.onSurfaceVariant,
              height: 1.35,
            ),
      ),
    );
  }
}

/// One glass capsule for the whole composer: the attachment, field and send
/// button stay ordinary controls to avoid layering panes on panes.
class _GlassComposerSurface extends StatelessWidget {
  const _GlassComposerSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context)) {
      return DecoratedBox(
        decoration: ShapeDecoration(
          color: context.raisedSurfaceLevel.colorOf(context.colorScheme),
          shape: AppShape.lg,
        ),
        child: child,
      );
    }
    return LiquidGlass(
      style: LiquidGlassStyle.regular(context.colorScheme),
      borderRadius: AppRadius.lg,
      child: child,
    );
  }
}

class _SlashPanel extends StatelessWidget {
  const _SlashPanel({required this.actions, required this.onSelected});

  final List<SlashAction> actions;
  final ValueChanged<SlashAction> onSelected;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Surface(
      color: scheme.surfaceContainerHigh,
      shape: AppShape.md,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final action in actions)
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: action.enabled ? () => onSelected(action) : null,
                child: Opacity(
                  opacity: action.enabled ? 1 : 0.4,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 11,
                    ),
                    child: Row(
                      children: [
                        GlyphIcon(
                          action.glyph,
                          size: 18,
                          color: action.danger
                              ? scheme.error
                              : scheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 11),
                        Expanded(
                          child: Text(
                            action.label,
                            style: AppTextStyle.subheadline.of(context),
                          ),
                        ),
                        Text(
                          '/${action.command}',
                          style: AppTextStyle.caption1.of(context).copyWith(
                                color: scheme.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
