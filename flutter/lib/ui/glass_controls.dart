import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

/// A primary action button on FlClash-style liquid glass. Destructive actions
/// keep prism_ui's plain [TonalButton] so their red affordance is unchanged.
class GlassTonalButton extends StatelessWidget {
  const GlassTonalButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.glyph,
    this.size = TonalButtonSize.compact,
  });

  final String label;
  final VoidCallback? onPressed;
  final Glyph? glyph;
  final TonalButtonSize size;

  @override
  Widget build(BuildContext context) {
    return TonalButtonTheme(
      glass: true,
      grouped: false,
      size: size,
      child: glyph == null
          ? FilledButton(onPressed: onPressed, child: Text(label))
          : FilledButton.icon(
              onPressed: onPressed,
              icon: GlyphIcon(glyph!),
              label: Text(label),
            ),
    );
  }
}
