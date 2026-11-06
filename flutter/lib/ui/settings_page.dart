import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'scaffold_bar.dart';

/// Settings, grouped the way FlClash groups its own: a section per topic, rows
/// that read "label — current value — chevron". A row's whole surface opens the
/// picker; the value button opens the same menu.
///
/// Enum labels are copied from `data/AppSettings.kt` because the bridge sends
/// only the serialized `name` for them (`labelsJson` carries model tiers alone).
class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.bridge,
    required this.settings,
    required this.labels,
    required this.engine,
    required this.onUpdateSettings,
    required this.onToast,
  });

  final Bridge bridge;
  final AppSettings settings;
  final ModelLabels labels;
  final EngineState engine;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late AppSettings _settings = widget.settings;
  late ModelLabels _labels = widget.labels;

  bool _busy = false;
  String _storage = '';
  String _selfTest = '';

  @override
  void didUpdateWidget(SettingsPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    // The host pushes the authoritative copy after every edit; adopt it so two
    // taps on the same picker can never disagree about what is selected.
    if (oldWidget.settings != widget.settings) _settings = widget.settings;
    if (oldWidget.labels != widget.labels) _labels = widget.labels;
  }

  void _patch(Map<String, dynamic> patch) {
    setState(() => _settings = _settings.merge(patch));
    unawaited(widget.onUpdateSettings(patch));
  }

  // -- async engine actions ------------------------------------------------

  Future<void> _run(Future<void> Function() action, String failureHint) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      widget.onToast('$failureHint（$error）', isError: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _storageUsage() => _run(() async {
        final usage = await widget.bridge.engineStorageUsage();
        if (!mounted) return;
        setState(() {
          _storage = '引擎 ${_size(usage.jail)} · 工作区 ${_size(usage.workspace)} · '
              '会话 ${_size(usage.sessions)}';
        });
      }, '读取存储占用失败，再点一次');

  Future<void> _selfTestRun() => _run(() async {
        final report = await widget.bridge.engineSelfTest();
        if (!mounted) return;
        setState(() => _selfTest = report);
      }, '自检没能完成，稍后再试');

  Future<void> _uninstall() async {
    final confirmed = await showFloatingDialog<bool>(
      context: context,
      child: AlertDialog(
        title: '卸载引擎？',
        destructive: true,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('卸载'),
          ),
        ],
        child: const Text('会删除已安装的 CLI 与容器，工作区和会话保留。'),
      ),
    );
    if (confirmed != true) return;
    await _run(
      () => widget.bridge.engineUninstall(),
      '卸载失败，先检查引擎日志',
    );
  }

  // -- text prompts --------------------------------------------------------

  Future<void> _editText({
    required String title,
    required String key,
    required String current,
    String hint = '',
    bool obscure = false,
    String? emptyLabel,
  }) async {
    final value = await showFloatingDialog<String>(
      context: context,
      child: _TextPrompt(
        title: title,
        initial: current,
        hint: hint,
        obscure: obscure,
        allowEmpty: emptyLabel != null,
        emptyLabel: emptyLabel,
      ),
    );
    if (value == null) return;
    _patch({key: value.trim()});
  }

  Future<void> _editNumber({
    required String title,
    required String key,
    required int current,
    required String hint,
  }) async {
    final value = await showFloatingDialog<String>(
      context: context,
      child: _TextPrompt(
        title: title,
        initial: '$current',
        hint: hint,
        obscure: false,
        allowEmpty: true,
        emptyLabel: '清空',
      ),
    );
    if (value == null) return;
    final trimmed = value.trim();
    _patch({key: trimmed.isEmpty ? 0 : int.tryParse(trimmed) ?? current});
  }

  // -- build ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverValue,
        builder: (context) => AppBar(
          leading: IconButton(
            onPressed: () => Navigator.of(context).maybePop(),
            icon: const GlyphIcon(Glyphs.arrowBack),
            tooltip: '返回',
          ),
          title: const Text('设置'),
        ),
      ),
      body: ListView(
        padding: EdgeInsets.only(
          top: context.contentTopPadding,
          bottom: 32 + context.bottomInset,
        ),
        children: [
          _apiSection(context),
          _tierSection(context),
          _engineSection(context),
          _labsSection(context),
          _appearanceSection(context),
          _advancedSection(context),
          _aboutSection(context),
        ],
      ),
    );
  }

  Widget _apiSection(BuildContext context) => generateSection(
        title: '接口与模型',
        items: [
          _PickerRow<String>(
            label: '协议',
            value: _enumLabel(_flavors, _settings.str('apiFlavor', 'ANTHROPIC')),
            options: [
              for (final entry in _flavors.entries)
                PopupMenuItem(
                  label: entry.value,
                  checked: _settings.str('apiFlavor') == entry.key,
                  onPressed: () => _patch({'apiFlavor': entry.key}),
                ),
            ],
          ),
          _ValueRow(
            label: 'Base URL',
            value: _settings.str('baseUrl'),
            onPressed: () => _editText(
              title: 'Base URL',
              key: 'baseUrl',
              current: _settings.str('baseUrl'),
              hint: 'https://api.anthropic.com',
            ),
          ),
          _ValueRow(
            label: 'API Key',
            value: _settings.str('apiKey').isEmpty ? '未设置' : '已设置',
            onPressed: () => _editText(
              title: 'API Key',
              key: 'apiKey',
              current: _settings.str('apiKey'),
              hint: '本地网关可以留空',
              obscure: true,
            ),
          ),
          _PickerRow<String>(
            label: '认证方式',
            value: _enumLabel(_authStyles, _settings.str('authStyle', 'API_KEY')),
            options: [
              for (final entry in _authStyles.entries)
                PopupMenuItem(
                  label: entry.value,
                  checked: _settings.str('authStyle') == entry.key,
                  onPressed: () => _patch({'authStyle': entry.key}),
                ),
            ],
          ),
          _ValueRow(
            label: '默认模型',
            value: _settings.str('model'),
            onPressed: () => _editText(
              title: '默认模型',
              key: 'model',
              current: _settings.str('model'),
              hint: 'claude-sonnet-5-5',
            ),
          ),
          _ValueRow(
            label: '小模型',
            value: _settings.str('smallModel').isEmpty
                ? 'CLI 默认'
                : _settings.str('smallModel'),
            onPressed: () => _editText(
              title: '小模型',
              key: 'smallModel',
              current: _settings.str('smallModel'),
              hint: '留空则用 CLI 默认',
              emptyLabel: '清空',
            ),
          ),
        ],
      );

  Widget _tierSection(BuildContext context) => generateSection(
        title: '模型档位',
        footer: _labels.tierSummary.isEmpty ? null : _labels.tierSummary,
        items: [
          _PickerRow<String>(
            label: '当前档位',
            value: _tierLabel(_settings.str('activeTier')),
            options: [
              PopupMenuItem(
                label: 'CLI 默认（不指定模型）',
                checked: _settings.str('activeTier').isEmpty,
                onPressed: () => _patch({'activeTier': ''}),
              ),
              PopupMenuItem(
                label: '自动（按任务路由）',
                checked: _settings.str('activeTier') == 'AUTO',
                onPressed: () => _patch({'activeTier': 'AUTO'}),
              ),
              for (final tier in _tiers.keys)
                PopupMenuItem(
                  label: _tierLabel(tier),
                  checked: _settings.str('activeTier') == tier,
                  onPressed: () => _patch({'activeTier': tier}),
                ),
            ],
          ),
          for (final MapEntry(key: tier, value: tierName) in _tiers.entries)
            _ValueRow(
              label: '$tierName · ${tier.toLowerCase()}',
              value: _labels.tierMapping[tier] ?? 'CLI 默认',
              onPressed: () => _editText(
                title: '$tierName 档位映射',
                key: _tierField(tier),
                current: _settings.str(_tierField(tier)),
                hint: '留空则用官方默认',
                emptyLabel: '清空',
              ),
            ),
        ],
      );

  Widget _engineSection(BuildContext context) {
    final engine = widget.engine;
    final working = engine.phase == 'Working';
    return generateSection(
      title: 'Claude Code 引擎',
      items: [
        if (working)
          DecorationListItem(
            title: Text(engine.stage.isEmpty ? '正在准备引擎…' : engine.stage),
            subtitle: engine.detail.isEmpty ? null : Text(engine.detail),
            trailing: Text(
              '${(engine.fraction * 100).round()}%',
              style: context.listCaptionStyle,
            ),
          ),
        if (working)
          DecorationListItem(
            title: LinearProgressIndicator(value: engine.fraction),
            minVerticalPadding: 4,
          ),
        _InfoRow(label: '已安装', value: engine.installedVersion ?? '未安装'),
        _InfoRow(label: '官方最新', value: engine.latestVersion ?? '未知'),
        _InfoRow(label: 'CPU 架构', value: engine.arch ?? '未知'),
        _InfoRow(label: '安装位置', value: engine.jailPath ?? '未设置'),
        _InfoRow(label: '运行方式', value: engine.engineLabel),
        _InfoRow(
          label: 'PRoot 组件',
          value: engine.prootAvailable ? '可用' : '缺失',
        ),
        _InfoRow(
          label: 'Root 授权',
          value: engine.rootGranted ? '已授权' : '未授权',
        ),
        _PickerRow<String>(
          label: '引擎模式',
          value: _enumLabel(_engineModes, _settings.str('engineMode', 'AUTO')),
          options: [
            for (final entry in _engineModes.entries)
              PopupMenuItem(
                label: entry.value,
                checked: _settings.str('engineMode') == entry.key,
                onPressed: () => _patch({'engineMode': entry.key}),
              ),
          ],
        ),
        _PickerRow<String>(
          label: '权限模式',
          value: _enumLabel(_permissions, _settings.str('permissionMode', 'BYPASS')),
          options: [
            for (final entry in _permissions.entries)
              PopupMenuItem(
                label: entry.value,
                checked: _settings.str('permissionMode') == entry.key,
                onPressed: () => _patch({'permissionMode': entry.key}),
              ),
          ],
        ),
        if (engine.error != null && engine.error!.isNotEmpty)
          DecorationListItem(
            invalid: true,
            title: const Text('引擎错误'),
            subtitle: Text(engine.error!),
            trailing: TextButton(
              onPressed: () => _run(
                () => widget.bridge.engineInstall(),
                '重装没能开始',
              ),
              child: const Text('重装'),
            ),
          ),
        DecorationListItem(
          title: const Text('引擎操作'),
          minVerticalPadding: 12,
          trailing: Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.end,
            children: [
              TonalButton(
                size: TonalButtonSize.compact,
                label: engine.installedVersion == null ? '安装 CLI' : '升级 · 重装',
                onPressed: _busy
                    ? null
                    : () => _run(
                          () => widget.bridge.engineInstall(
                            force: engine.installedVersion != null,
                          ),
                          '安装没能开始',
                        ),
              ),
              TonalButton(
                size: TonalButtonSize.compact,
                label: '检查更新',
                onPressed: _busy
                    ? null
                    : () => _run(
                          () => widget.bridge.engineCheckUpdate(quiet: false),
                          '检查更新失败',
                        ),
              ),
              TonalButton(
                size: TonalButtonSize.compact,
                label: '自检',
                onPressed: _busy ? null : _selfTestRun,
              ),
              TonalButton(
                size: TonalButtonSize.compact,
                label: '卸载',
                onPressed: _busy ? null : _uninstall,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _labsSection(BuildContext context) => generateSection(
        title: '功能与实验',
        items: [
          _SwitchRow(
            label: 'Agent teams（实验）',
            value: _settings.flag('agentTeams'),
            onChanged: (v) => _patch({'agentTeams': v}),
          ),
          _SwitchRow(
            label: 'Workflows',
            value: _settings.flag('workflows', true),
            onChanged: (v) => _patch({'workflows': v}),
          ),
          _SwitchRow(
            label: 'Auto memory',
            value: _settings.flag('autoMemory', true),
            onChanged: (v) => _patch({'autoMemory': v}),
          ),
          _SwitchRow(
            label: 'Background tasks',
            value: _settings.flag('backgroundTasks', true),
            onChanged: (v) => _patch({'backgroundTasks': v}),
          ),
          _SwitchRow(
            label: 'Extended thinking',
            value: _settings.flag('extendedThinking', true),
            onChanged: (v) => _patch({'extendedThinking': v}),
          ),
          _PickerRow<String>(
            label: '推理强度',
            value: _enumLabel(_efforts, _settings.str('effort', 'AUTO')),
            options: [
              for (final entry in _efforts.entries)
                PopupMenuItem(
                  label: entry.value,
                  checked: _settings.str('effort') == entry.key,
                  onPressed: () => _patch({'effort': entry.key}),
                ),
            ],
          ),
          _ValueRow(
            label: '子代理模型',
            value: _settings.str('subagentModel').isEmpty
                ? '继承主模型'
                : _settings.str('subagentModel'),
            onPressed: () => _editText(
              title: '子代理模型',
              key: 'subagentModel',
              current: _settings.str('subagentModel'),
              hint: '留空则继承主模型',
              emptyLabel: '清空',
            ),
          ),
          _ValueRow(
            label: 'Auto 权限分类器模型',
            value: _settings.str('autoModeModel').isEmpty
                ? '官方默认'
                : _settings.str('autoModeModel'),
            onPressed: () => _editText(
              title: 'Auto 权限分类器模型',
              key: 'autoModeModel',
              current: _settings.str('autoModeModel'),
              hint: '留空则用官方默认',
              emptyLabel: '清空',
            ),
          ),
        ],
      );

  Widget _appearanceSection(BuildContext context) => generateSection(
        title: '外观',
        items: [
          _PickerRow<int>(
            label: '主题模式',
            value: _enumLabel(_themes, _settings.intOr('themeMode')),
            options: [
              for (final entry in _themes.entries)
                PopupMenuItem(
                  label: entry.value,
                  checked: _settings.intOr('themeMode') == entry.key,
                  onPressed: () => _patch({'themeMode': entry.key}),
                ),
            ],
          ),
        ],
      );

  Widget _advancedSection(BuildContext context) => generateSection(
        title: '高级',
        items: [
          _SwitchRow(
            label: '启动时自动检查更新',
            value: _settings.flag('autoUpdate', true),
            onChanged: (v) => _patch({'autoUpdate': v}),
          ),
          _ValueRow(
            label: '最大轮数',
            value: _settings.intOr('maxTurns') == 0
                ? '默认'
                : '${_settings.intOr('maxTurns')}',
            onPressed: () => _editNumber(
              title: '最大轮数',
              key: 'maxTurns',
              current: _settings.intOr('maxTurns'),
              hint: '0 = 默认',
            ),
          ),
          _ValueRow(
            label: '系统提示词',
            value: _settings.str('extraSystemPrompt').isEmpty
                ? '未设置'
                : '已设置',
            onPressed: () => _editText(
              title: '系统提示词',
              key: 'extraSystemPrompt',
              current: _settings.str('extraSystemPrompt'),
              hint: '附加给 CLI 的系统提示词',
              emptyLabel: '清空',
            ),
          ),
          _ValueRow(
            label: '额外环境变量',
            value: _envSummary(),
            onPressed: () => _editText(
              title: '额外环境变量',
              key: 'extraEnv',
              current: _settings.str('extraEnv'),
              hint: '每行一条 KEY=VALUE',
              emptyLabel: '清空',
            ),
          ),
        ],
      );

  Widget _aboutSection(BuildContext context) => generateSection(
        title: '诊断',
        items: [
          _ValueRow(
            label: '存储占用',
            value: _storage.isEmpty ? '点击读取' : _storage,
            onPressed: _storageUsage,
          ),
          if (widget.engine.selfTest != null || _selfTest.isNotEmpty)
            DecorationListItem(
              title: const Text('自检报告'),
              subtitle: Text(_selfTest.isEmpty ? widget.engine.selfTest! : _selfTest),
            ),
        ],
        footer: '接口、模型与引擎都在这台设备上；改动即时保存。',
      );

  String _envSummary() {
    final raw = _settings.str('extraEnv');
    final lines = raw
        .split('\n')
        .map((line) => line.trim())
        .where((line) => line.isNotEmpty)
        .length;
    return lines == 0 ? '未设置' : '已设置 $lines 条';
  }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/// Label on the left, the live value and a chevron on the right. Tapping the
/// row or the value opens the same menu.
class _PickerRow<T> extends StatefulWidget {
  const _PickerRow({
    required this.label,
    required this.value,
    required this.options,
  });

  final String label;
  final String value;
  final List<PopupMenuItem> options;

  @override
  State<_PickerRow<T>> createState() => _PickerRowState<T>();
}

class _PickerRowState<T> extends State<_PickerRow<T>> {
  PopupOpen? _open;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(widget.label),
      onPressed: () => _open?.call(),
      trailing: PopupAnchor(
        targetBuilder: (open) {
          _open = open;
          return _ValueButton(label: widget.value, onPressed: open);
        },
        popupBuilder: (_) => PopupMenu(items: widget.options),
      ),
    );
  }
}

class _ValueRow extends StatelessWidget {
  const _ValueRow({
    required this.label,
    required this.value,
    required this.onPressed,
  });

  final String label;
  final String value;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      onPressed: onPressed,
      trailing: _ValueButton(label: value, onPressed: onPressed),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      onPressed: () => onChanged(!value),
      trailing: Switch(
        value: value,
        onChanged: onChanged,
        semanticLabel: label,
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      trailing: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 180),
        child: Text(
          value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.end,
          style: context.listSubtitleStyle,
        ),
      ),
    );
  }
}

/// The FlClash picker value: the current label, then a chevron.
class _ValueButton extends StatelessWidget {
  const _ValueButton({required this.label, required this.onPressed});

  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return TextButton(
      onPressed: onPressed,
      style: ButtonStyle.from(
        foregroundColor: context.colorScheme.onSurfaceVariant,
        textStyle: context.listTitleStyle,
        padding: const EdgeInsets.symmetric(horizontal: 8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 150),
            child: PopupValueText(
              label,
              alignment: AlignmentDirectional.centerEnd,
            ),
          ),
          const SizedBox(width: 4),
          const GlyphIcon(Glyphs.picker, size: 16),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Text prompt
// ---------------------------------------------------------------------------

class _TextPrompt extends StatefulWidget {
  const _TextPrompt({
    required this.title,
    required this.initial,
    required this.hint,
    required this.obscure,
    required this.allowEmpty,
    required this.emptyLabel,
  });

  final String title;
  final String initial;
  final String hint;
  final bool obscure;
  final bool allowEmpty;
  final String? emptyLabel;

  @override
  State<_TextPrompt> createState() => _TextPromptState();
}

class _TextPromptState extends State<_TextPrompt> {
  late final TextEditingController _controller =
      TextEditingController(text: widget.initial);
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

  @override
  Widget build(BuildContext context) {
    final canClear = widget.allowEmpty && _controller.text.isNotEmpty;
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
        TextButton(
          onPressed: () => Navigator.of(context).pop(_controller.text),
          child: const Text('保存'),
        ),
      ],
      child: TextField(
        controller: _controller,
        focusNode: _focus,
        autofocus: true,
        obscureText: widget.obscure,
        minLines: 1,
        maxLines: widget.obscure ? 1 : 4,
        keyboardType: TextInputType.multiline,
        decoration: InputDecoration(hintText: widget.hint),
        onSubmitted: (value) => Navigator.of(context).pop(value),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

const _flavors = {
  'ANTHROPIC': 'Anthropic 兼容',
  'OPENAI': 'OpenAI 兼容',
};

const _authStyles = {
  'BEARER': 'Authorization: Bearer',
  'API_KEY': 'x-api-key',
  'BOTH': '两个都发',
};

const _permissions = {
  'AUTO': '自动',
  'BYPASS': '完全放行',
  'ACCEPT_EDITS': '自动改文件',
  'MANUAL': '默认（只读）',
  'PLAN': '只读规划',
};

const _efforts = {
  'AUTO': '自动',
  'LOW': '低',
  'MEDIUM': '中',
  'HIGH': '高',
  'XHIGH': '很高',
  'MAX': '最高',
};

const _engineModes = {
  'AUTO': '自动',
  'PROOT': 'Proot',
  'ROOT': 'Root',
};

const _themes = {0: '跟随系统', 1: '浅色', 2: '深色'};

const _tiers = {
  'OPUS': 'Opus',
  'FABLE': 'Fable',
  'SONNET': 'Sonnet',
  'HAIKU': 'Haiku',
};

String _enumLabel<K>(Map<K, String> labels, K key) => labels[key] ?? '$key';

String _tierField(String tier) => switch (tier) {
      'OPUS' => 'opusModel',
      'FABLE' => 'fableModel',
      'SONNET' => 'sonnetModel',
      'HAIKU' => 'haikuModel',
      _ => 'model',
    };

String _tierLabel(String tier) => switch (tier) {
      '' => 'CLI 默认（不指定模型）',
      'AUTO' => '自动（按任务路由）',
      _ => _tiers[tier] ?? tier,
    };

String _size(int bytes) {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${value.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
}

/// The bar never changes on this page; it only needs a listenable.
/// `ChangeNotifier` has no const constructor, so this is a cached instance.
final ValueListenable<bool> _neverValue = _NeverValue();
final Listenable _neverBar = _NeverBar();

class _NeverBar extends ChangeNotifier {}

class _NeverValue implements ValueListenable<bool> {
  const _NeverValue();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
