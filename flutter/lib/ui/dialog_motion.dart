import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// Reveals one row of a dialog list after the dialog window has landed, the
/// way a prism_ui PopupMenu settles its window first and then floats the rows
/// up in order.
///
/// Row `index` starts at 16% of the route animation and each following row 5%
/// later (capped at ten steps, so a long list still finishes inside the
/// transition), fades in on [Curves.easeOutCubic] until 30% later, and rises
/// 6dp toward its place. The route animation is only `drive`n into tweens; no
/// [CurvedAnimation] is created, so a row rebuilding cannot leave a listener
/// behind on the route.
class DialogRowReveal extends StatelessWidget {
  const DialogRowReveal({
    super.key,
    required this.index,
    required this.child,
  });

  static const _rise = 6.0;

  /// Position in the list; later rows start later.
  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final animation = ModalRoute.of(context)?.animation;
    if (animation == null || context.disableAnimations) return child;

    final start = 0.16 + 0.05 * math.min(index, 10);
    final end = math.min(start + 0.30, 1.0);
    final curve = CurveTween(
      curve: Interval(start, end, curve: Curves.easeOutCubic),
    );
    final opacity = animation.drive(
      Tween<double>(begin: 0, end: 1).chain(curve),
    );
    final offset = animation.drive(
      Tween<double>(begin: _rise, end: 0).chain(curve),
    );

    return FadeTransition(
      opacity: opacity,
      child: AnimatedBuilder(
        animation: offset,
        builder: (context, child) => Transform.translate(
          offset: Offset(0, offset.value),
          child: child,
        ),
        child: child,
      ),
    );
  }
}