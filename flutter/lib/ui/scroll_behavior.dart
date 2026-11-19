import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// The one scroll behavior for every route: Android lists get the same
/// spring-backed bounce as the popup menus, dialogs and sheets.
///
/// Clamping stops dead at the edge, while every layer above the list settles
/// with a little give; using Bouncing on Android puts scrolling on that same
/// motion language. RangeMaintaining keeps the offset inside the new range
/// when content changes, so a streaming answer growing under the finger does
/// not jump. Other platforms keep whatever scroll behavior their widgets
/// already chose.
class ClaudroidScrollBehavior extends BaseScrollBehavior {
  const ClaudroidScrollBehavior();

  @override
  ScrollPhysics getScrollPhysics(BuildContext context) {
    if (getPlatform(context) == TargetPlatform.android) {
      return const BouncingScrollPhysics(
        parent: RangeMaintainingScrollPhysics(),
      );
    }
    return super.getScrollPhysics(context);
  }

  @override
  Widget buildOverscrollIndicator(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) {
    // BouncingScrollPhysics never sends an OverscrollNotification, so the
    // Android stretching indicator has nothing to stretch; wrapping the child
    // would only add a layer. Desktop scrollbars still come from
    // BaseScrollBehavior.buildScrollbar.
    return child;
  }
}