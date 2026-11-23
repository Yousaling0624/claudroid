import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'selection_menu.dart';

/// prism_ui's secret field with the app's selection menu.
///
/// prism 0.1.2's `SecretTextFormField` builds its TextFormField internally and
/// never exposes `contextMenuBuilder`, so selecting all in an API-key field
/// closed the menu and offered no copy or paste. This mirrors that widget (eye
/// toggle, one line while obscured, visible-password keyboard, formatter-based
/// length limit) and passes [appEditableTextMenu] through; keep it in step with
/// the package when upgrading.
class AppSecretTextFormField extends StatefulWidget {
  const AppSecretTextFormField({
    super.key,
    required this.controller,
    this.focusNode,
    this.decoration = const InputDecoration(),
    this.maxLines = 1,
    this.maxLength,
    this.inputFormatters,
    this.textInputAction,
    this.onFieldSubmitted,
    this.validator,
    this.autofocus = false,
  });

  final TextEditingController controller;
  final FocusNode? focusNode;
  final InputDecoration decoration;
  final int? maxLines;
  final int? maxLength;
  final List<TextInputFormatter>? inputFormatters;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onFieldSubmitted;
  final FormFieldValidator<String>? validator;
  final bool autofocus;

  @override
  State<AppSecretTextFormField> createState() =>
      _AppSecretTextFormFieldState();
}

class _AppSecretTextFormFieldState extends State<AppSecretTextFormField> {
  bool _obscure = true;

  @override
  Widget build(BuildContext context) {
    final localizations = UiLocalizations.of(context);
    return TextFormField(
      autofocus: widget.autofocus,
      focusNode: widget.focusNode,
      controller: widget.controller,
      obscureText: _obscure,
      keyboardType: TextInputType.visiblePassword,
      maxLines: _obscure ? 1 : widget.maxLines,
      minLines: 1,
      inputFormatters: _limited(widget.inputFormatters, widget.maxLength),
      textInputAction: widget.textInputAction,
      onFieldSubmitted: widget.onFieldSubmitted,
      validator: widget.validator,
      contextMenuBuilder: appEditableTextMenu,
      decoration: widget.decoration.copyWith(
        suffixIcon: IconButton(
          tooltip: _obscure
              ? localizations.showPassword
              : localizations.hidePassword,
          icon: GlyphIcon(_obscure ? Glyphs.eye : Glyphs.eyeOff),
          onPressed: () {
            setState(() {
              _obscure = !_obscure;
            });
          },
        ),
      ),
    );
  }
}

// A limit holds as iOS and macOS hold one, without a counter under the field.
List<TextInputFormatter>? _limited(
  List<TextInputFormatter>? formatters,
  int? maxLength,
) {
  if (maxLength == null) {
    return formatters;
  }
  return [...?formatters, LengthLimitingTextInputFormatter(maxLength)];
}
