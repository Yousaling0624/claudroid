import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'selection_menu.dart';

/// prism_ui's search pill with the app's selection menu.
///
/// prism 0.1.2 builds its [SearchField]'s TextField internally and never
/// exposes `contextMenuBuilder`, so select-all on a search query dropped every
/// action. This is the same field (glyph and clear insets, focus ring, pill)
/// with [appEditableTextMenu] passed through; keep it in step with the package
/// when upgrading.
class AppSearchField extends StatefulWidget {
  const AppSearchField({
    super.key,
    this.onChanged,
    this.controller,
    this.focusNode,
    this.autofocus = false,
    this.height,
    this.fill,
    this.glyph = Glyphs.search,
    this.hintText,
    this.onSubmitted,
    this.trailing,
  });

  final ValueChanged<String>? onChanged;
  final TextEditingController? controller;
  final FocusNode? focusNode;
  final bool autofocus;
  final double? height;
  final Color? fill;
  final Glyph glyph;
  final String? hintText;
  final VoidCallback? onSubmitted;
  final Widget? trailing;

  @override
  State<AppSearchField> createState() => _AppSearchFieldState();
}

// As iOS 27 sets its field: the glyph's center 22 dp in, the clear button's 24.5.
const double _glyphInset = 12;
const double _textInset = 41;
const double _clearReach = 49;
const double _clearSize = 24;
const int _maxLength = 256;

class _AppSearchFieldState extends State<AppSearchField> {
  TextEditingController? _ownedController;

  TextEditingController get _controller =>
      widget.controller ?? (_ownedController ??= TextEditingController());

  @override
  void dispose() {
    _ownedController?.dispose();
    super.dispose();
  }

  void _handleClear() {
    _controller.clear();
    widget.onChanged?.call('');
  }

  @override
  Widget build(BuildContext context) {
    final localizations = UiLocalizations.of(context);
    final colorScheme = Theme.of(context).colorScheme;
    final remote = RemoteFocusAdapter.isEnabledOf(context);
    final pill = AppShape.input.copyWith(borderRadius: AppRadius.full);
    final trailing = widget.trailing;
    return ListenableBuilder(
      listenable: Listenable.merge([_controller, FocusMovedByKey.instance]),
      builder: (_, _) {
        final empty = _controller.text.isEmpty;
        final ringed = remote || FocusMovedByKey.instance.value;
        return TextField(
          controller: _controller,
          focusNode: widget.focusNode,
          autofocus: widget.autofocus,
          maxLines: 1,
          textAlignVertical: TextAlignVertical.center,
          textInputAction: TextInputAction.search,
          style: AppTextStyle.body.of(context),
          inputFormatters: [LengthLimitingTextInputFormatter(_maxLength)],
          onChanged: widget.onChanged,
          onSubmitted: (_) => widget.onSubmitted?.call(),
          contextMenuBuilder: appEditableTextMenu,
          // Not dense: a dense decorator fits its fill to the text and ignores
          // the minimum height, which desktop's compact density left short.
          decoration: InputDecoration(
            constraints: BoxConstraints.tightFor(
              height: widget.height ?? TonalButtonSize.bar.button,
            ),
            filled: widget.fill != null,
            fillColor: widget.fill,
            hintText: widget.hintText ?? localizations.search,
            border: pill.copyWith(borderSide: BorderSide.none),
            enabledBorder: pill.copyWith(borderSide: BorderSide.none),
            focusedBorder: pill.copyWith(
              borderSide: ringed ? focusRingSide(colorScheme) : BorderSide.none,
            ),
            contentPadding: EdgeInsetsDirectional.only(
              end: empty && trailing == null ? 12 : 0,
            ),
            prefixIcon: Padding(
              padding: const EdgeInsetsDirectional.only(start: _glyphInset),
              child: GlyphIcon(widget.glyph, size: 20),
            ),
            prefixIconConstraints: const BoxConstraints.tightFor(
              width: _textInset,
            ),
            suffixIcon: empty && trailing == null
                ? null
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ?trailing,
                      if (!empty)
                        SizedBox(
                          width: _clearReach,
                          child: IconButton(
                            tooltip: localizations.clearSearch,
                            onPressed: _handleClear,
                            style: ButtonStyle.from(
                              foregroundColor: colorScheme.onSurface,
                            ),
                            icon: const GlyphIcon(
                              Glyphs.clearField,
                              size: _clearSize,
                              fill: 1,
                            ),
                          ),
                        )
                      else
                        const SizedBox(width: 8),
                    ],
                  ),
            suffixIconConstraints: const BoxConstraints(),
          ),
        );
      },
    );
  }
}
