import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/model_info.dart';
import '../dialog_motion.dart';
import '../glass_controls.dart';
import '../glyphs.dart';
import '../press_guard.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';

/// What the picker returned: a concrete model ID, or the "manual input"
/// action that falls back to the free-text prompt.
class ModelPick {
  const ModelPick.id(this.id) : manual = false;
  const ModelPick.manual() : id = '', manual = true;

  final String id;
  final bool manual;
}

List<ModelInfo> filterModels(List<ModelInfo> models, String query) {
  final needle = query.trim().toLowerCase();
  if (needle.isEmpty) return models;
  return [
    for (final model in models)
      if (model.title.toLowerCase().contains(needle) ||
          model.id.toLowerCase().contains(needle) ||
          model.ownedBy.toLowerCase().contains(needle) ||
          model.label.toLowerCase().contains(needle))
        model,
  ];
}

/// Groups by `owned_by`, sorting owners and their models by name. Models with
/// no owner go last under 「其他」.
Map<String, List<ModelInfo>> groupModels(List<ModelInfo> models) {
  final groups = <String, List<ModelInfo>>{};
  for (final model in models) {
    final owner = model.ownedBy.trim().isEmpty ? '其他' : model.ownedBy;
    (groups[owner] ??= []).add(model);
  }
  final owners = groups.keys.toList()
    ..sort((a, b) {
      if (a == '其他') return 1;
      if (b == '其他') return -1;
      return a.toLowerCase().compareTo(b.toLowerCase());
    });
  return {
    for (final owner in owners)
      owner: groups[owner]!..sort(
        (a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
      ),
  };
}

/// `1000000 → 1M`, `258624 → 259K`, `999 → 999`.
String tokenLabel(int? tokens) {
  if (tokens == null || tokens <= 0) return '';
  if (tokens >= 1000000) return '${_short(tokens / 1000000)}M';
  if (tokens >= 1000) return '${_short(tokens / 1000)}K';
  return '$tokens';
}

String _short(double value) {
  if (value >= 100) return value.round().toString();
  return value == value.roundToDouble()
      ? value.round().toString()
      : value.toStringAsFixed(1);
}

List<String> metaLabels(ModelInfo model) {
  final labels = <String>[
    if (tokenLabel(model.contextWindow).isNotEmpty)
      '${tokenLabel(model.contextWindow)} 上下文',
    if (tokenLabel(model.maxOutputTokens).isNotEmpty)
      '${tokenLabel(model.maxOutputTokens)} 输出',
    if (model.reasoningLevels.isNotEmpty)
      '推理 ${model.reasoningLevels.join('/')}'
    else if (model.reasoning)
      '推理',
    if (model.modalities.contains('image')) '图像输入',
  ];
  return labels;
}

/// Fetches (or reuses) the catalogue, then asks the user to pick a model.
Future<ModelPick?> showModelPicker(
  BuildContext context,
  SettingsController controller, {
  required String title,
  required String current,
  bool forceRefresh = false,
}) {
  return showClaudroidDialog<ModelPick>(
    context: context,
    child: _ModelPickerDialog(
      controller: controller,
      title: title,
      current: current,
      forceRefresh: forceRefresh,
    ),
  );
}

/// Picker-backed model field; the dialog's manual action keeps the old
/// free-text behaviour for endpoints that reject unknown IDs.
Future<void> pickModelSetting(
  BuildContext context,
  SettingsController controller, {
  required String title,
  required String key,
  required String current,
  String hint = '',
  String emptyLabel = '清空',
  bool clearActiveTier = false,
  bool forceRefresh = false,
}) async {
  final pick = await showModelPicker(
    context,
    controller,
    title: title,
    current: current,
    forceRefresh: forceRefresh,
  );
  if (pick == null || !context.mounted) return;
  if (pick.manual) {
    await editSettingText(
      context,
      controller,
      title: title,
      key: key,
      current: current,
      hint: hint,
      emptyLabel: emptyLabel,
      singleLine: true,
    );
    return;
  }
  controller.selectModel(key, pick.id, clearActiveTier: clearActiveTier);
}

class _ModelPickerDialog extends StatefulWidget {
  const _ModelPickerDialog({
    required this.controller,
    required this.title,
    required this.current,
    required this.forceRefresh,
  });

  final SettingsController controller;
  final String title;
  final String current;
  final bool forceRefresh;

  @override
  State<_ModelPickerDialog> createState() => _ModelPickerDialogState();
}

class _ModelPickerDialogState extends State<_ModelPickerDialog> {
  final TextEditingController _search = TextEditingController();
  late List<ModelInfo> _models = widget.controller.models;
  late String _endpoint = widget.controller.modelsEndpoint;
  String _query = '';
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    if (widget.forceRefresh || _models.isEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_refresh());
      });
    }
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final result = await widget.controller.fetchModels(force: true);
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (result == null) {
        _error = widget.controller.modelError ?? '拉取模型失败';
      } else {
        _models = result.models;
        _endpoint = result.endpoint;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final visible = filterModels(_models, _query);
    final groups = groupModels(visible);
    final height = (MediaQuery.sizeOf(context).height - 220).clamp(
      160.0,
      560.0,
    );
    return AlertDialog(
      title: widget.title,
      hasFields: true,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () =>
              Navigator.of(context).pop(const ModelPick.manual()),
          child: const Text('手动输入'),
        ),
      ],
      child: SizedBox(
        width: 360,
        height: height,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    [
                      if (_endpoint.isNotEmpty) _endpoint,
                      '${_models.length} 个模型',
                    ].join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: context.listCaptionStyle,
                  ),
                ),
                DeferredDisable(
                  onPressed: _loading ? null : _refresh,
                  builder: (context, onPressed) => IconButton(
                    onPressed: onPressed,
                    icon: _loading
                        ? ActivityIndicator(
                            size: 16,
                            color: scheme.onSurfaceVariant,
                          )
                        : const GlyphIcon(AppGlyphs.refresh, size: 18),
                    tooltip: '刷新模型列表',
                  ),
                ),
              ],
            ),
            SearchField(
              controller: _search,
              hintText: '搜索名称、ID 或分组',
              height: 44,
              onChanged: (value) => setState(() => _query = value),
            ),
            const SizedBox(height: 8),
            if (_error != null && _models.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  '${_error!}。可以重试，或继续选择已缓存的模型。',
                  style: context.listCaptionStyle.copyWith(
                    color: scheme.error,
                  ),
                ),
              ),
            Expanded(
              child: AnimatedSwitcher(
                duration: context.motionDuration(
                  const Duration(milliseconds: 180),
                ),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeInCubic,
                child: _body(context, scheme, visible, groups),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _body(
    BuildContext context,
    ColorScheme scheme,
    List<ModelInfo> visible,
    Map<String, List<ModelInfo>> groups,
  ) {
    if (_loading && _models.isEmpty) {
      return const _PickerState(
        key: ValueKey('loading'),
        glyph: AppGlyphs.sparkle,
        title: '拉取中…',
        message: '正在向接口请求模型列表。',
        loading: true,
      );
    }
    if (_error != null && _models.isEmpty) {
      return _PickerState(
        key: ValueKey('error:$_error'),
        glyph: AppGlyphs.refresh,
        title: '没能拉取模型',
        message: '${_error!}\n确认 Base URL、API Key 与协议后重试。',
        isError: true,
        onRetry: _refresh,
      );
    }
    if (_models.isEmpty) {
      return _PickerState(
        key: const ValueKey('empty'),
        glyph: AppGlyphs.sparkle,
        title: '接口还没有模型',
        message: '端点能连上，但列表为空。确认协议与端点匹配后重试，或手动输入模型 ID。',
        onRetry: _refresh,
      );
    }
    if (visible.isEmpty) {
      return _PickerState(
        key: const ValueKey('nomatch'),
        glyph: Glyphs.search,
        title: '没有匹配的模型',
        message: '换个关键词，或者清空搜索框。',
      );
    }
    return ListView(
      key: const ValueKey('list'),
      padding: EdgeInsets.zero,
      children: [
        for (final entry in groups.entries) ...[
          if (entry.key.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(2, 8, 2, 6),
              child: Text(
                entry.key,
                style: context.listCaptionStyle,
              ),
            ),
          for (final model in entry.value)
            _ModelCard(
              model: model,
              current: model.id == widget.current,
              onPressed: () => Navigator.of(
                context,
              ).pop(ModelPick.id(model.id)),
            ),
        ],
      ],
    );
  }
}

/// A centered state in the picker's fixed-height body: spinner, error with a
/// retry action, empty catalogue, or no search match.
class _PickerState extends StatelessWidget {
  const _PickerState({
    super.key,
    required this.glyph,
    required this.title,
    required this.message,
    this.loading = false,
    this.isError = false,
    this.onRetry,
  });

  final Glyph glyph;
  final String title;
  final String message;
  final bool loading;
  final bool isError;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final onRetry = this.onRetry;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (loading)
              ActivityIndicator(size: 20, color: scheme.primary)
            else
              GlyphIcon(
                glyph,
                size: 22,
                color: isError ? scheme.error : scheme.onSurfaceVariant,
              ),
            const SizedBox(height: 10),
            Text(
              title,
              textAlign: TextAlign.center,
              style: context.listTitleStyle,
            ),
            const SizedBox(height: 6),
            Text(
              message,
              textAlign: TextAlign.center,
              style: context.listCaptionStyle.copyWith(
                color: isError ? scheme.error : null,
                height: 1.4,
              ),
            ),
            if (onRetry != null) ...[
              const SizedBox(height: 14),
              GlassTonalButton(
                label: '重试',
                glyph: AppGlyphs.refresh,
                onPressed: onRetry,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ModelCard extends StatelessWidget {
  const _ModelCard({
    required this.model,
    required this.current,
    required this.onPressed,
  });

  final ModelInfo model;
  final bool current;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final labels = metaLabels(model);
    return Card(
      onPressed: onPressed,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  model.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: context.listTitleStyle,
                ),
                if (model.displayName.isNotEmpty &&
                    model.displayName != model.id)
                  Text(
                    model.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: context.listCaptionStyle.copyWith(
                      fontFamily: 'monospace',
                    ),
                  ),
                if (labels.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      for (final label in labels)
                        TonalChip(
                          label: label,
                          color: scheme.surfaceContainerHighest,
                          foregroundColor: scheme.onSurfaceVariant,
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
          if (current) ...[
            const SizedBox(width: 8),
            GlyphIcon(Glyphs.check, size: 18, color: scheme.primary),
          ],
        ],
      ),
    );
  }
}
