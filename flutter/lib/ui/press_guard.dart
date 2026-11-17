import 'dart:async';

import 'package:flutter/widgets.dart';

/// Defers the moment a prism_ui button sees `onPressed: null` until the tap's
/// release animation has finished.
///
/// prism_ui forwards `onPressed == null` to its `PressSurface`, which cancels a
/// still-running press animation inside `didUpdateWidget` and reports the new
/// state through the button's own states controller. That `setState` lands
/// while the button is still updating its child, tripping a framework
/// assertion in debug builds (a red screen). An action that disables its own
/// button — "self test", "refresh", "check again" — hits it every time.
///
/// Holding the callback alive for [window] (longer than the 250 ms tint
/// release) lets the animation finish first. During the hold the button still
/// looks enabled but its callback is a no-op, so a stray second tap cannot
/// repeat the action.
class DeferredDisable extends StatefulWidget {
  const DeferredDisable({
    super.key,
    required this.onPressed,
    required this.builder,
  });

  /// The action; `null` when the button must be disabled.
  final VoidCallback? onPressed;

  /// Builds the button, passing the callback it should currently receive.
  final Widget Function(BuildContext context, VoidCallback? onPressed) builder;

  @override
  State<DeferredDisable> createState() => _DeferredDisableState();
}

class _DeferredDisableState extends State<DeferredDisable> {
  static const window = Duration(milliseconds: 320);

  Timer? _timer;
  bool _holding = false;
  bool _tappedWhileHolding = false;

  @override
  void didUpdateWidget(covariant DeferredDisable oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.onPressed != null) {
      _timer?.cancel();
      _timer = null;
      _holding = false;
      _tappedWhileHolding = false;
      return;
    }
    if (oldWidget.onPressed != null && !_holding) {
      _startHold();
    }
  }

  void _startHold() {
    _holding = true;
    _timer?.cancel();
    _timer = Timer(window, _release);
  }

  void _release() {
    _timer = null;
    if (!mounted) return;
    // A tap during the hold started a fresh press animation; hold again so it
    // too finishes before the button really disables.
    if (_tappedWhileHolding) {
      _tappedWhileHolding = false;
      _startHold();
      return;
    }
    setState(() => _holding = false);
  }

  void _swallow() => _tappedWhileHolding = true;

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final onPressed = widget.onPressed ?? (_holding ? _swallow : null);
    return widget.builder(context, onPressed);
  }
}
