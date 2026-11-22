import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../models/remote.dart';
import 'glyphs.dart';

/// An inline approval card for a tool call that the remote daemon forwarded to
/// the phone. The daemon is fail-closed: no answer (or a dropped connection)
/// denies the call, so this widget only presents the decision, never enforces
/// the deadline itself.
class PermissionCard extends StatefulWidget {
  const PermissionCard({
    super.key,
    required this.permission,
    required this.onRespond,
  });

  final PendingPermission permission;

  /// `allow == true` with `always == true` grants the tool for the rest of the
  /// session. Throwing leaves the card usable and shows the page's toast.
  final Future<void> Function({required bool allow, required bool always})
      onRespond;

  @override
  State<PermissionCard> createState() => _PermissionCardState();
}

class _PermissionCardState extends State<PermissionCard> {
  Timer? _ticker;
  bool _busy = false;
  int _remainingSeconds = 0;

  @override
  void initState() {
    super.initState();
    _syncTimer();
  }

  @override
  void didUpdateWidget(covariant PermissionCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.permission.requestId != widget.permission.requestId ||
        oldWidget.permission.expiresAt != widget.permission.expiresAt) {
      _busy = false;
      _syncTimer();
    }
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  void _syncTimer() {
    _ticker?.cancel();
    _ticker = null;
    _remainingSeconds = _computeRemaining();
    if (widget.permission.expiresAt <= 0) return;
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      final next = _computeRemaining();
      if (next != _remainingSeconds) {
        setState(() => _remainingSeconds = next);
      }
      if (next <= 0) {
        _ticker?.cancel();
        _ticker = null;
      }
    });
  }

  int _computeRemaining() {
    final millis = widget.permission.remainingMs();
    if (millis <= 0) return 0;
    return (millis / 1000).ceil();
  }

  Future<void> _respond({required bool allow, required bool always}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.onRespond(allow: allow, always: always);
    } catch (_) {
      // The page already toasted the failure; let the user try again.
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final permission = widget.permission;
    final scheme = context.colorScheme;
    final summary = permission.summary;
    final description = permission.description?.trim() ?? '';
    final expired =
        permission.expiresAt > 0 && _remainingSeconds <= 0 && !_busy;
    return Container(
      decoration: BoxDecoration(
        color: scheme.surfaceBright,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: expired
              ? scheme.error.withValues(alpha: 0.4)
              : scheme.outlineVariant.withValues(alpha: 0.7),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  color: scheme.primaryContainer,
                  shape: BoxShape.circle,
                ),
                child: Center(
                  child: GlyphIcon(
                    AppGlyphs.key,
                    size: 17,
                    color: scheme.onPrimaryContainer,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      permission.heading,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: AppTextStyle.callout
                          .of(context, emphasized: true)
                          .copyWith(color: scheme.onSurface),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _statusLine(expired),
                      style: AppTextStyle.caption2.of(context).copyWith(
                            color: expired
                                ? scheme.error
                                : scheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ),
              ),
              if (permission.toolName.isNotEmpty)
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHigh,
                    borderRadius: AppRadius.full,
                  ),
                  child: Text(
                    permission.toolName,
                    style: AppTextStyle.caption2.of(context).copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                  ),
                ),
            ],
          ),
          if (description.isNotEmpty) ...[
            const SizedBox(height: 9),
            Text(
              description,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.caption1.of(context).copyWith(
                    color: scheme.onSurfaceVariant,
                    height: 1.4,
                  ),
            ),
          ],
          if (summary.isNotEmpty) ...[
            const SizedBox(height: 9),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: AppRadius.md,
              ),
              child: Text(
                summary,
                maxLines: 4,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyle.caption1.of(context).copyWith(
                      fontFamily: 'monospace',
                      height: 1.4,
                      color: scheme.onSurface,
                    ),
              ),
            ),
          ],
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.end,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 6,
            runSpacing: 6,
            children: [
              if (_busy)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: ActivityIndicator(
                    size: 15,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              _action(
                context,
                label: '拒绝',
                foreground: scheme.error,
                onPressed: _busy
                    ? null
                    : () => _respond(allow: false, always: false),
              ),
              _action(
                context,
                label: '本会话总是允许',
                onPressed: _busy
                    ? null
                    : () => _respond(allow: true, always: true),
              ),
              _action(
                context,
                label: '允许一次',
                fill: scheme.primaryContainer,
                foreground: scheme.onPrimaryContainer,
                onPressed: _busy
                    ? null
                    : () => _respond(allow: true, always: false),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _action(
    BuildContext context, {
    required String label,
    required VoidCallback? onPressed,
    Color? fill,
    Color? foreground,
  }) {
    final scheme = context.colorScheme;
    final ink = foreground ?? scheme.onSurfaceVariant;
    return PressSurface(
      fill: fill ?? scheme.surfaceContainerHigh,
      shape: AppShape.full,
      foreground: ink,
      onPressed: onPressed,
      padding: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Text(
          label,
          style: AppTextStyle.caption1
              .of(context, emphasized: true)
              .copyWith(color: onPressed == null ? ink.withValues(alpha: 0.4) : ink),
        ),
      ),
    );
  }

  String _statusLine(bool expired) {
    if (expired) return '已超过确认时限，主机将拒绝这次调用';
    if (_busy) return '正在发送决定…';
    if (widget.permission.expiresAt <= 0) return '等待你的确认';
    final seconds = _remainingSeconds;
    final minutes = seconds ~/ 60;
    final rest = (seconds % 60).toString().padLeft(2, '0');
    return '$minutes:$rest 后自动拒绝 · 主机默认安全拒绝';
  }
}
