import 'package:flutter/widgets.dart';

/// Reading widths for the release frontend. The FlClash bar stays full-width;
/// only the content column narrows, so wide screens get margins instead of
/// 90-character lines.
const double kChatContentWidth = 760;
const double kHomeContentWidth = 640;
const double kSettingsContentWidth = 840;
const double kWorkspaceContentWidth = 820;

/// The app-wide horizontal gutter for page content.
///
/// Reading widths only cap the column; this keeps the transcript, composer,
/// home hero, settings cards, workspace lists and the session drawer on the
/// same left edge on phones. It is deliberately not applied to padding that
/// belongs to a prism_ui component (list rows, fields, dialog frames).
const double kPageInset = 16;

/// Text inside a pill-shaped list row sits this far in: the row is inset
/// [kRowGap] from the drawer edge and pads its label another 14dp, so group
/// labels use this to line up with the titles below them.
const double kListInset = kPageInset + 6;

/// Gap between adjacent compact rows, chips and controls in one group.
const double kRowGap = 8;

/// Vertical gap between settings groups / sections.
const double kSectionGap = 26;

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
