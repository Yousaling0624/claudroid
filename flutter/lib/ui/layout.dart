import 'package:flutter/widgets.dart';

/// Reading widths for the release frontend. The FlClash bar stays full-width;
/// only the content column narrows, so wide screens get margins instead of
/// 90-character lines.
const double kChatContentWidth = 760;
const double kHomeContentWidth = 640;
const double kSettingsContentWidth = 840;
const double kWorkspaceContentWidth = 820;

/// Centres [child] at no more than [maxWidth].
///
/// By default the widget expands vertically, which is what a scroll view
/// wants. Pass `heightFactor: 1` for intrinsic-height children (the composer).
class ContentWidth extends StatelessWidget {
  const ContentWidth({
    super.key,
    required this.maxWidth,
    required this.child,
    this.alignment = Alignment.topCenter,
    this.heightFactor,
  });

  final double maxWidth;
  final Widget child;
  final AlignmentGeometry alignment;
  final double? heightFactor;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: alignment,
      heightFactor: heightFactor,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: child,
      ),
    );
  }
}
