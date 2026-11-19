import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import 'attachments.dart';
import 'glyphs.dart';

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

/// The input capsule shared by the home page and the chat page.
///
/// It owns only presentation: expanding the panel for `/`, opening the system
/// file picker, and holding the picked files as preview chips. The picked paths
/// are handed to the page on submit (`composeChatPrompt` appends them to the
/// prompt); the text itself lives in the caller's [controller], so drafts and
/// "edit and resend" stay under the page's control.
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

  bool get _slashOpen =>
      widget.controller.text.startsWith('/') &&
      !widget.controller.text.contains('\n');

  String get _slashQuery =>
      widget.controller.text.substring(1).trim().toLowerCase();

  List<SlashAction> get _slashMatches {
    final query = _slashQuery;
    return [
      for (final action in widget.actions)
        if (query.isEmpty ||
            action.command.toLowerCase().contains(query) ||
            action.label.toLowerCase().contains(query))
          action,
    ];
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
    widget.onSubmit();
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

  Future<void> pickAttachments() async {
    if (_picking) return;
    setState(() => _picking = true);
    try {
      final paths = await widget.bridge.pickAttachments();
      if (!mounted || paths.isEmpty) return;
      setState(() {
        for (final path in paths) {
          if (!_attachments.contains(path)) _attachments.add(path);
        }
      });
      widget.focusNode.requestFocus();
    } catch (error) {
      widget.onToast('没能添加附件（$error）。再试一次。', isError: true);
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

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
      padding: EdgeInsets.fromLTRB(12, 8, 12, 8 + widget.bottomInset),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (widget.editing)
            Padding(
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
          if (matches.isNotEmpty) ...[
            _SlashPanel(actions: matches, onSelected: _runSlash),
            const SizedBox(height: 6),
          ],
          _GlassComposerSurface(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(6, 6, 6, 6),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (_attachments.isNotEmpty)
                    AttachmentPreviewStrip(
                      bridge: widget.bridge,
                      paths: _attachments,
                      size: 56,
                      padding: const EdgeInsets.fromLTRB(4, 2, 4, 6),
                      onRemove: (path) =>
                          setState(() => _attachments.remove(path)),
                    ),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      IconButton(
                        onPressed: pickAttachments,
                        icon: _picking
                            ? ActivityIndicator(
                                size: 16,
                                color: scheme.onSurfaceVariant,
                              )
                            : const GlyphIcon(AppGlyphs.attachment, size: 19),
                        tooltip: '添加附件',
                      ),
                      Expanded(
                        child: TextField(
                          controller: widget.controller,
                          focusNode: widget.focusNode,
                          minLines: 1,
                          maxLines: 6,
                          keyboardType: TextInputType.multiline,
                          textInputAction: TextInputAction.newline,
                          decoration: InputDecoration(
                            hintText: widget.editing
                                ? '修改这条消息…'
                                : widget.hintText,
                            border: const NoInputBorder(),
                          ),
                          onChanged: _onChanged,
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
      padding: const EdgeInsets.fromLTRB(16, 7, 16, 0),
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
