import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import 'layout.dart';

/// One tappable row inside a picker dialog.
///
/// The dialogs used to set their own icon sizes, gaps and vertical padding, so
/// the host picker, the directory pickers and the workspace switcher all had
/// slightly different row heights. This keeps the leading glyph in a fixed
/// slot and the title on a single ellipsized line, which is what keeps long
/// host names from folding the row into two text heights.
class DialogListRow extends StatelessWidget {
  const DialogListRow({
    super.key,
    required this.leading,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onPressed,
    this.semanticLabel,
  });

  /// The glyph in the 28dp leading slot.
  final Widget leading;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onPressed;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final subtitle = this.subtitle;
    return Semantics(
      button: onPressed != null,
      label: semanticLabel,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onPressed,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: kRowGap),
            child: Row(
              children: [
                SizedBox(width: 28, child: Center(child: leading)),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: context.listTitleStyle,
                      ),
                      if (subtitle != null) ...[
                        const SizedBox(height: 2),
                        Text(
                          subtitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTextStyle.caption2.of(context).copyWith(
                                color: context.colorScheme.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ],
                  ),
                ),
                if (trailing != null) ...[
                  const SizedBox(width: kRowGap),
                  trailing!,
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
