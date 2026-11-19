import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import '../dialog_motion.dart';
import 'settings_controller.dart';

/// Text prompt used by every editable settings row.
///
/// Validation lives in a [Form]; the field types follow the value: secrets get
/// prism_ui's eye toggle, URLs and numbers their own keyboards, and only
/// multi-line values grow. The dialog's height eases between states instead of
/// jumping when an error appears.
class SettingsTextPrompt extends StatefulWidget {
  const SettingsTextPrompt({
    super.key,
    required this.title,
    required this.initial,
    required this.hint,
    this.obscure = false,
    this.allowEmpty = false,
    this.emptyLabel,
    this.keyboardType,
    this.maxLength,
    this.singleLine = false,
    this.validator,
  });

  final String title;
  final String initial;
  final String hint;
  final bool obscure;
  final bool allowEmpty;
  final String? emptyLabel;
  final TextInputType? keyboardType;
  final int? maxLength;
  final bool singleLine;
  final FormFieldValidator<String>? validator;

  @override
  State<SettingsTextPrompt> createState() => _SettingsTextPromptState();
}

class _SettingsTextPromptState extends State<SettingsTextPrompt> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _controller = TextEditingController(
    text: widget.initial,
  );
  late final FocusNode _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _submit() {
    if (_formKey.currentState?.validate() != true) return;
    Navigator.of(context).pop(_controller.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    final canClear = widget.allowEmpty && _controller.text.isNotEmpty;
    final singleLine = widget.singleLine || widget.obscure;
    final decoration = InputDecoration(
      hintText: widget.hint,
      counterText: '',
    );
    return AlertDialog(
      title: widget.title,
      hasFields: true,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        if (canClear)
          TextButton(
            onPressed: () => Navigator.of(context).pop(''),
            child: Text(widget.emptyLabel ?? '清空'),
          ),
        TextButton(onPressed: _submit, child: const Text('保存')),
      ],
      child: Form(
        key: _formKey,
        autovalidateMode: AutovalidateMode.onUserInteraction,
        child: AnimatedSize(
          duration: context.motionDuration(const Duration(milliseconds: 180)),
          curve: const Cubic(0.0, 0.0, 0.2, 1.0),
          alignment: AlignmentDirectional.topCenter,
          child: widget.obscure
              ? SecretTextFormField(
                  controller: _controller,
                  focusNode: _focus,
                  autofocus: true,
                  maxLength: widget.maxLength,
                  decoration: decoration,
                  validator: widget.validator,
                  textInputAction: TextInputAction.done,
                  onFieldSubmitted: (_) => _submit(),
                )
              : TextFormField(
                  controller: _controller,
                  focusNode: _focus,
                  autofocus: true,
                  maxLength: widget.maxLength,
                  keyboardType: widget.keyboardType ??
                      (singleLine
                          ? TextInputType.text
                          : TextInputType.multiline),
                  minLines: 1,
                  maxLines: singleLine ? 1 : 4,
                  textInputAction: singleLine
                      ? TextInputAction.done
                      : TextInputAction.newline,
                  decoration: decoration,
                  validator: widget.validator,
                  onFieldSubmitted: singleLine ? (_) => _submit() : null,
                ),
        ),
      ),
    );
  }
}

Future<void> editSettingText(
  BuildContext context,
  SettingsController controller, {
  required String title,
  required String key,
  required String current,
  String hint = '',
  bool obscure = false,
  String? emptyLabel,
  TextInputType? keyboardType,
  int? maxLength,
  bool singleLine = false,
  FormFieldValidator<String>? validator,
}) async {
  final value = await showClaudroidDialog<String>(
    context: context,
    child: SettingsTextPrompt(
      title: title,
      initial: current,
      hint: hint,
      obscure: obscure,
      allowEmpty: emptyLabel != null,
      emptyLabel: emptyLabel,
      keyboardType: keyboardType,
      maxLength: maxLength,
      singleLine: singleLine,
      validator: validator,
    ),
  );
  if (value == null) return;
  controller.patch({key: value.trim()});
}

Future<void> editSettingNumber(
  BuildContext context,
  SettingsController controller, {
  required String title,
  required String key,
  required int current,
  required String hint,
  int min = 0,
  int max = 999999,
}) async {
  final value = await showClaudroidDialog<String>(
    context: context,
    child: SettingsTextPrompt(
      title: title,
      initial: current == 0 ? '' : '$current',
      hint: hint,
      allowEmpty: true,
      emptyLabel: '清空',
      keyboardType: TextInputType.number,
      singleLine: true,
      maxLength: 7,
      validator: (value) {
        final text = value?.trim() ?? '';
        if (text.isEmpty) return null;
        final number = int.tryParse(text);
        if (number == null) return '请输入整数';
        if (number < min || number > max) return '请输入 $min–$max 之间的整数';
        return null;
      },
    ),
  );
  if (value == null) return;
  final trimmed = value.trim();
  controller.patch({
    key: trimmed.isEmpty ? 0 : int.tryParse(trimmed) ?? current,
  });
}

/// Returns `true` for "delete everything", `false` for "engine only", and
/// `null` when the user cancels.
Future<bool?> confirmEngineUninstall(BuildContext context) {
  return showClaudroidDialog<bool>(
    context: context,
    child: AlertDialog(
      title: '卸载引擎？',
      destructive: true,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('仅卸载引擎'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('全部删除（含工作区与会话）'),
        ),
      ],
      child: const Text('「仅卸载引擎」会保留工作区与会话；「全部删除」不可恢复。'),
    ),
  );
}
