import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// Gap between a dialog's last piece of content and its action buttons.
const double kDialogActionGap = 16;

/// Alert-style actions laid out inside the dialog content instead of prism's
/// `AlertDialog.actions` slot.
///
/// prism places the title and body at a 30dp inset but its actions at 16dp, so
/// a picker's buttons sit wider than the list above them. [AlertActions] keeps
/// the button language (last action filled, 48dp, 8dp gap) while the caller
/// puts it at the end of the content column, which makes title, body and
/// actions share one left and right edge.
class DialogActionRow extends StatelessWidget {
  const DialogActionRow({super.key, required this.actions});

  /// Buttons in reading order; the last one is the filled primary action.
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: kDialogActionGap),
      child: AlertActions(
        fill: glass ? glassFillOf(context.colorScheme) : null,
        actions: actions,
      ),
    );
  }
}
