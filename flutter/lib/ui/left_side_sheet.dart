import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// A session drawer that really opens from the left edge.
///
/// prism_ui's `ModalSideSheetRoute` anchors its panel to the right edge even
/// on LTR layouts, so this app-level route keeps the same `SideSheet` surface,
/// elevation and durations but flips both the anchor and the slide direction.
/// Drag-to-close is intentionally omitted: Android back, the close button and
/// the barrier all dismiss it.
Future<T?> showLeftSideSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  Color? barrierColor,
}) {
  final localizations = UiLocalizations.of(context);
  return sheetNavigatorOf(context).push<T>(
    _LeftSideSheetRoute<T>(
      builder: builder,
      barrierColor: barrierColor ?? context.colorScheme.modalScrim,
      barrierLabel: localizations.close,
    ),
  );
}

class _LeftSideSheetRoute<T> extends PopupRoute<T>
    with SheetCoverRouteMixin<T> {
  _LeftSideSheetRoute({
    required this.builder,
    required this.barrierColor,
    this.barrierLabel,
  });

  final WidgetBuilder builder;

  @override
  final Color barrierColor;

  @override
  final String? barrierLabel;

  @override
  bool get barrierDismissible => true;

  @override
  Duration get transitionDuration => const Duration(milliseconds: 300);

  @override
  Duration get reverseTransitionDuration => const Duration(milliseconds: 200);

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    final width = (MediaQuery.sizeOf(context).width * 0.88).clamp(
      300.0,
      560.0,
    );
    return DisplayFeatureSubScreen(
      child: ElevatedSurfaceTheme(
        child: Semantics(
          scopesRoute: true,
          namesRoute: true,
          explicitChildNodes: true,
          child: Align(
            alignment: Alignment.centerLeft,
            child: SizedBox(
              width: math.min(width, MediaQuery.sizeOf(context).width),
              height: double.infinity,
              child: SideSheet(builder: builder),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final curved = CurvedAnimation(
      parent: animation,
      curve: Easing.standardDecelerate,
      reverseCurve: Easing.standardDecelerate.flipped,
    );
    return SlideTransition(
      position: Tween<Offset>(
        begin: const Offset(-1, 0),
        end: Offset.zero,
      ).animate(curved),
      child: child,
    );
  }
}
