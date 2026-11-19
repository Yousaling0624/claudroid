import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// Opens [child] in the same floating window prism_ui builds, but moves it the
/// way this app's own layers arrive instead of growing it in as a UIKit alert.
///
/// [showFloatingDialog] scales the whole window in from 1.2x on an iOS 27
/// alert spring; on a tall input card that reads as the card inflating. The
/// prompts users edit settings and rename sessions in are exactly that shape,
/// so the window instead rises 10dp with a 4% grow, the language of the
/// composer sheet and PopupMenu, while prism_ui's glass veil, [SurfaceScope]
/// and [AlertDialog] surface stay untouched — only the motion differs.
Future<T?> showClaudroidDialog<T>({
  required BuildContext context,
  required Widget child,
  bool dismissible = true,
}) {
  return Navigator.of(context).push<T>(
    _ClaudroidDialogRoute<T>(
      // Read at push time: the navigator asks the route for its durations
      // before it has a build context inside the route.
      reduceMotion: MediaQuery.maybeDisableAnimationsOf(context) ?? false,
      barrierColor: context.colorScheme.alertScrim,
      barrierDismissible: dismissible,
      barrierLabel: dismissible ? UiLocalizations.of(context).close : null,
      builder: (context) => SurfaceScope(
        level: SurfaceLevel.floatingOf(context.colorScheme),
        child: child,
      ),
    ),
  );
}

class _ClaudroidDialogRoute<T> extends PopupRoute<T>
    with AlertMotionRouteMixin<T> {
  _ClaudroidDialogRoute({
    required this.builder,
    required this.reduceMotion,
    required this.barrierColor,
    required this.barrierDismissible,
    required this.barrierLabel,
  });

  static const _enterDuration = Duration(milliseconds: 280);
  static const _exitDuration = Duration(milliseconds: 180);

  final WidgetBuilder builder;
  final bool reduceMotion;

  @override
  final Color barrierColor;

  @override
  final bool barrierDismissible;

  @override
  final String? barrierLabel;

  @override
  Duration get transitionDuration =>
      reduceMotion ? Duration.zero : _enterDuration;

  @override
  Duration get reverseTransitionDuration =>
      reduceMotion ? Duration.zero : _exitDuration;

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    // Same page shell as showFloatingDialog; the SurfaceScope the frame needs
    // comes from the caller's builder.
    return Semantics(
      scopesRoute: true,
      explicitChildNodes: true,
      child: SafeArea(child: Builder(builder: builder)),
    );
  }

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    if (reduceMotion ||
        (MediaQuery.maybeDisableAnimationsOf(context) ?? false)) {
      return child;
    }
    return _ClaudroidDialogTransition(animation: animation, child: child);
  }
}

/// Lands the window on its place: +10dp rising to 0 and 0.96 growing to 1 on
/// one decelerating curve, while the glass fade finishes in the first 70% of
/// the entry so the window reads as settled before the motion ends.
class _ClaudroidDialogTransition extends StatelessWidget {
  const _ClaudroidDialogTransition({
    required this.animation,
    required this.child,
  });

  static const _rise = 10.0;
  static const _entryScale = 0.96;
  static const _motion = Cubic(0.0, 0.0, 0.2, 1.0);
  static const _opacity = Interval(0.0, 0.7, curve: Curves.easeOut);

  final Animation<double> animation;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final eased = animation.drive(CurveTween(curve: _motion));
    return LiquidGlassFade(
      // A fade transition would flatten the glass panes inside the window;
      // this one fades them with what they capture, as the alert route does.
      opacity: animation.drive(CurveTween(curve: _opacity)),
      child: AnimatedBuilder(
        animation: eased,
        builder: (context, child) => Transform.translate(
          offset: Offset(0, _rise * (1 - eased.value)),
          child: Transform.scale(
            scale: _entryScale + (1 - _entryScale) * eased.value,
            child: child,
          ),
        ),
        child: child,
      ),
    );
  }
}

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