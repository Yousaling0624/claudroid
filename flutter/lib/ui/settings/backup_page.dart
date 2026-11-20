import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/backup.dart';
import '../dialog_motion.dart';
import '../glass_controls.dart';
import '../glyphs.dart';
import '../press_guard.dart';
import 'backup_controller.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

/// "备份与恢复": target config, cloud/local actions and live progress.
///
/// The page renders [BackupController], which the settings root owns; status
/// events keep both this page and the root row summary in sync.
class BackupPage extends StatelessWidget {
  const BackupPage({super.key, required this.controller});

  final SettingsController controller;

  BackupController get _backup => controller.backup;

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '备份与恢复',
      listenable: _backup,
      builder: (context) => [
        _statusSection(context),
        _targetSection(context),
        _autoSection(context),
        _cloudSection(context),
        _localSection(context),
        _notesSection(context),
      ],
    );
  }

  // -- status ---------------------------------------------------------------

  Widget _statusSection(BuildContext context) {
    final status = _backup.status;
    final working = status.working;
    return generateSection(
      title: '状态',
      items: [
        if (working) ...[
          DecorationListItem(
            title: Text(
              status.stage.isEmpty
                  ? '正在${backupActionLabel(status.action)}…'
                  : status.stage,
            ),
            subtitle: status.detail.isEmpty ? null : Text(status.detail),
            trailing: Text(
              '${(status.fraction * 100).round()}%',
              style: context.listCaptionStyle,
            ),
          ),
          DecorationListItem(
            title: LinearProgressIndicator(value: status.fraction),
            minVerticalPadding: 4,
          ),
          SettingsActionRow(
            label: '取消当前操作',
            glyph: Glyphs.close,
            onPressed: _backup.cancel,
          ),
        ],
        SettingsInfoRow(
          label: '上次操作',
          value: status.lastAt <= 0
              ? '还没有备份'
              : backupActionLabel(status.lastAction),
        ),
        if (status.lastAt > 0)
          SettingsInfoRow(
            label: '上次时间',
            value: backupRelativeTime(status.lastAt),
          ),
        if (status.lastBytes > 0)
          SettingsInfoRow(
            label: '大小',
            value: formatBytes(status.lastBytes),
          ),
        SettingsInfoRow(
          label: '目标',
          value: backupTargetLabel(_backup.config),
        ),
        if (status.lastAt > 0 && status.lastMessage.isNotEmpty)
          SettingsNote(
            text: status.lastMessage,
            isError: status.lastOk == false,
          ),
        if (!working && (status.error ?? '').isNotEmpty)
          SettingsNote(
            text: '${status.error}。检查目标配置后重试，或用本地导出。',
            isError: true,
          ),
      ],
    );
  }

  // -- target ---------------------------------------------------------------

  Widget _targetSection(BuildContext context) {
    final config = _backup.config;
    return generateSection(
      title: '同步目标',
      footer: '备份包含所有工作区文件、会话历史与设置；不含 API Key 与额外环境变量。',
      items: [
        SettingsPickerRow<BackupTargetKind>(
          label: '目标',
          value: config.kind.label,
          options: [
            for (final kind in BackupTargetKind.values)
              PopupMenuItem(
                label: kind.label,
                checked: config.kind == kind,
                onPressed: () => _backup.chooseKind(kind),
              ),
          ],
        ),
        ...switch (config.kind) {
          BackupTargetKind.local => const <Widget>[],
          BackupTargetKind.webdav => _webDavRows(context, config),
          BackupTargetKind.s3 => _s3Rows(context, config),
          BackupTargetKind.github => _gitHubRows(context, config),
        },
      ],
    );
  }

  List<Widget> _webDavRows(BuildContext context, BackupConfig config) {
    final webdav = config.webdav;
    return [
      SettingsValueRow(
        label: '文件地址',
        value: webdav.url.isEmpty ? '未设置' : webdav.url,
        onPressed: () => _editText(
          context,
          title: 'WebDAV 文件地址',
          current: webdav.url,
          hint: 'https://dav.example.com/…/claudroid-backup.zip',
          keyboardType: TextInputType.url,
          allowEmpty: true,
          emptyLabel: '清空',
          validator: _urlValidator,
          apply: (value) => config.copyWith(
            webdav: webdav.copyWith(url: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: '用户名',
        value: webdav.username.isEmpty ? '未设置' : webdav.username,
        onPressed: () => _editText(
          context,
          title: 'WebDAV 用户名',
          current: webdav.username,
          hint: '留空则不发送 Basic 认证',
          allowEmpty: true,
          emptyLabel: '清空',
          apply: (value) => config.copyWith(
            webdav: webdav.copyWith(username: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: '密码',
        value: webdav.password.isEmpty ? '未设置' : '已设置',
        onPressed: () => _editText(
          context,
          title: 'WebDAV 密码',
          current: webdav.password,
          hint: '只保存在本机',
          obscure: true,
          allowEmpty: true,
          emptyLabel: '清空',
          maxLength: 4096,
          apply: (value) => config.copyWith(
            webdav: webdav.copyWith(password: value),
          ),
        ),
      ),
      const SettingsNote(text: '文件地址填完整的 zip 地址，不是网盘根目录。'),
    ];
  }

  List<Widget> _s3Rows(BuildContext context, BackupConfig config) {
    final s3 = config.s3;
    return [
      SettingsValueRow(
        label: 'Endpoint',
        value: s3.endpoint.isEmpty ? 'Amazon S3' : s3.endpoint,
        onPressed: () => _editText(
          context,
          title: 'S3 Endpoint',
          current: s3.endpoint,
          hint: '留空使用 Amazon S3',
          keyboardType: TextInputType.url,
          allowEmpty: true,
          emptyLabel: '用 Amazon',
          validator: _urlValidator,
          apply: (value) => config.copyWith(s3: s3.copyWith(endpoint: value)),
        ),
      ),
      SettingsValueRow(
        label: '区域',
        value: s3.region.isEmpty ? '未设置' : s3.region,
        onPressed: () => _editText(
          context,
          title: 'S3 区域',
          current: s3.region,
          hint: 'us-east-1',
          allowEmpty: true,
          emptyLabel: '清空',
          apply: (value) => config.copyWith(s3: s3.copyWith(region: value)),
        ),
      ),
      SettingsValueRow(
        label: '存储桶',
        value: s3.bucket.isEmpty ? '未设置' : s3.bucket,
        onPressed: () => _editText(
          context,
          title: 'S3 存储桶',
          current: s3.bucket,
          hint: 'bucket-name',
          allowEmpty: true,
          emptyLabel: '清空',
          apply: (value) => config.copyWith(s3: s3.copyWith(bucket: value)),
        ),
      ),
      SettingsValueRow(
        label: '前缀',
        value: s3.prefix.isEmpty ? '根目录' : s3.prefix,
        onPressed: () => _editText(
          context,
          title: 'S3 前缀',
          current: s3.prefix,
          hint: 'claudroid',
          allowEmpty: true,
          emptyLabel: '清空',
          apply: (value) => config.copyWith(s3: s3.copyWith(prefix: value)),
        ),
      ),
      SettingsValueRow(
        label: 'Access Key',
        value: s3.accessKey.isEmpty ? '未设置' : s3.accessKey,
        onPressed: () => _editText(
          context,
          title: 'S3 Access Key',
          current: s3.accessKey,
          hint: '只保存在本机',
          allowEmpty: true,
          emptyLabel: '清空',
          maxLength: 4096,
          apply: (value) => config.copyWith(
            s3: s3.copyWith(accessKey: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: 'Secret Key',
        value: s3.secretKey.isEmpty ? '未设置' : '已设置',
        onPressed: () => _editText(
          context,
          title: 'S3 Secret Key',
          current: s3.secretKey,
          hint: '只保存在本机',
          obscure: true,
          allowEmpty: true,
          emptyLabel: '清空',
          maxLength: 4096,
          apply: (value) => config.copyWith(
            s3: s3.copyWith(secretKey: value),
          ),
        ),
      ),
      SettingsSwitchRow(
        label: '路径风格',
        value: s3.pathStyle,
        onChanged: (value) => unawaited(
          _backup.saveConfig(config.copyWith(s3: s3.copyWith(pathStyle: value))),
        ),
      ),
      const SettingsNote(text: '路径风格（path-style）适合 MinIO 等兼容服务；Amazon S3 通常关闭。'),
    ];
  }

  List<Widget> _gitHubRows(BuildContext context, BackupConfig config) {
    final github = config.github;
    return [
      SettingsValueRow(
        label: '仓库',
        value: github.repo.isEmpty ? '未设置' : github.repo,
        onPressed: () => _editText(
          context,
          title: 'GitHub 仓库',
          current: github.repo,
          hint: 'owner/name',
          allowEmpty: true,
          emptyLabel: '清空',
          validator: _repoValidator,
          apply: (value) => config.copyWith(
            github: github.copyWith(repo: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: '分支',
        value: github.branch.isEmpty ? 'main（默认）' : github.branch,
        onPressed: () => _editText(
          context,
          title: 'GitHub 分支',
          current: github.branch,
          hint: 'main',
          allowEmpty: true,
          emptyLabel: '恢复默认',
          apply: (value) => config.copyWith(
            github: github.copyWith(branch: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: '文件路径',
        value: github.path.isEmpty
            ? 'claudroid-backup.zip（默认）'
            : github.path,
        onPressed: () => _editText(
          context,
          title: '仓库内文件路径',
          current: github.path,
          hint: 'claudroid-backup.zip',
          allowEmpty: true,
          emptyLabel: '恢复默认',
          apply: (value) => config.copyWith(
            github: github.copyWith(path: value),
          ),
        ),
      ),
      SettingsValueRow(
        label: 'Token',
        value: github.token.isEmpty ? '未设置' : '已设置',
        onPressed: () => _editText(
          context,
          title: 'GitHub Token',
          current: github.token,
          hint: '需要 contents 读写权限',
          obscure: true,
          allowEmpty: true,
          emptyLabel: '清空',
          maxLength: 4096,
          apply: (value) => config.copyWith(
            github: github.copyWith(token: value),
          ),
        ),
      ),
    ];
  }

  // -- auto backup ----------------------------------------------------------

  Widget _autoSection(BuildContext context) {
    final config = _backup.config;
    final auto = config.auto;
    final warning = autoBackupWarning(config);
    final active = _backup.active;
    return generateSection(
      title: '自动备份',
      footer: '联网时会在后台自动上传；任务或终端运行时会跳过当轮，'
          '失败不弹通知，下个周期继续重试。'
          '系统省电策略可能延后执行；应用被强行停止后，要重新打开应用才会恢复。',
      items: [
        SettingsSwitchRow(
          label: '后台自动备份',
          value: auto.enabled,
          onChanged: active ? null : _backup.setAutoEnabled,
        ),
        if (auto.enabled)
          SettingsPickerRow<int>(
            label: '频率',
            value: autoIntervalLabel(auto.intervalHours),
            enabled: !active,
            options: [
              for (final hours in autoBackupIntervalHours)
                PopupMenuItem(
                  label: autoIntervalLabel(hours),
                  checked: auto.intervalHours == hours,
                  onPressed: () => _backup.setAutoIntervalHours(hours),
                ),
            ],
          ),
        if (warning.isNotEmpty) SettingsNote(text: warning),
      ],
    );
  }

  // -- cloud actions --------------------------------------------------------

  Widget _cloudSection(BuildContext context) {
    final active = _backup.active;
    final ready = _backup.cloudReady;
    final requirement = _backup.requirementText;
    return generateSection(
      title: '云端操作',
      items: [
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 8),
          child: Row(
            children: [
              Expanded(
                child: DeferredDisable(
                  onPressed: active || !ready ? null : _backup.runBackup,
                  builder: (context, onPressed) => GlassTonalButton(
                    size: TonalButtonSize.section,
                    label: '立即备份',
                    onPressed: onPressed,
                  ),
                ),
              ),
            ],
          ),
        ),
        SettingsActionRow(
          label: '从云端恢复',
          glyph: AppGlyphs.folder,
          onPressed: active || !ready
              ? null
              : () => _confirmCloudRestore(context),
        ),
        SettingsActionRow(
          label: _backup.testing ? '正在测试连接…' : '测试连接',
          glyph: AppGlyphs.plug,
          busy: _backup.testing,
          onPressed: active || _backup.testing || !ready
              ? null
              : _backup.testConnection,
        ),
        if (requirement.isNotEmpty) SettingsNote(text: requirement),
        if (_backup.testMessage.isNotEmpty)
          SettingsNote(text: _backup.testMessage, isError: !_backup.testOk),
      ],
    );
  }

  Future<void> _confirmCloudRestore(BuildContext context) async {
    final choice = await showClaudroidDialog<_RestoreChoice>(
      context: context,
      child: AlertDialog(
        title: '从云端恢复？',
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(_RestoreChoice.data),
            child: const Text('仅恢复数据'),
          ),
          TextButton(
            onPressed: () =>
                Navigator.of(context).pop(_RestoreChoice.dataAndSettings),
            child: const Text('恢复数据与设置'),
          ),
        ],
        child: const Text(
          '只补充本机缺失的内容：不删除本机数据；已有工作区文件会跳过，'
          '会话仅在备份较新时更新。',
        ),
      ),
    );
    if (choice == null) return;
    await _backup.restore(
      restoreSettings: choice == _RestoreChoice.dataAndSettings,
    );
  }

  // -- local files ----------------------------------------------------------

  Widget _localSection(BuildContext context) {
    final active = _backup.active;
    return generateSection(
      title: '本地文件',
      footer: '导出由系统选择保存位置；导入同样只合并数据，不覆盖本机已有文件。',
      items: [
        SettingsActionRow(
          label: '导出备份文件',
          glyph: AppGlyphs.backup,
          busy: _backup.pendingAction == 'export',
          onPressed: active ? null : _backup.exportFile,
        ),
        SettingsActionRow(
          label: '从文件恢复',
          glyph: AppGlyphs.file,
          busy: _backup.pendingAction == 'import',
          onPressed: active ? null : () => _confirmFileRestore(context),
        ),
      ],
    );
  }

  Future<void> _confirmFileRestore(BuildContext context) async {
    final confirmed = await showClaudroidDialog<bool>(
      context: context,
      child: AlertDialog(
        title: '从文件恢复？',
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('恢复'),
          ),
        ],
        child: const Text(
          '只补充本机缺失的内容：不删除本机数据；已有工作区文件会跳过，'
          '会话仅在备份较新时更新。本地导入只恢复数据，不会应用备份里的设置。',
        ),
      ),
    );
    if (confirmed != true) return;
    await _backup.importFile();
  }

  // -- notes ----------------------------------------------------------------

  Widget _notesSection(BuildContext context) {
    return generateSection(
      title: '说明',
      items: const [
        SettingsNote(
          text: '恢复是安全合并：备份里没有的本机会话与文件保持不动，'
              '工作区文件已存在则跳过。',
        ),
        SettingsNote(
          text: 'GitHub 仓库走 Contents API，单个文件上限 100 MB；'
              '更大的归档请改用 WebDAV 或 S3。',
        ),
        SettingsNote(text: '任务或终端正在运行时会拒绝备份与恢复，等这一轮结束后再试。'),
      ],
    );
  }

  // -- helpers --------------------------------------------------------------

  /// The settings text prompt, writing one backup config field. Unlike
  /// [editSettingText] it does not route through `SettingsController.patch`:
  /// backup config lives in its own file and is saved with `backup.saveConfig`.
  Future<void> _editText(
    BuildContext context, {
    required String title,
    required String current,
    required String hint,
    required BackupConfig Function(String value) apply,
    bool obscure = false,
    bool allowEmpty = false,
    String? emptyLabel,
    TextInputType? keyboardType,
    int? maxLength,
    FormFieldValidator<String>? validator,
  }) async {
    final value = await showClaudroidDialog<String>(
      context: context,
      child: SettingsTextPrompt(
        title: title,
        initial: current,
        hint: hint,
        obscure: obscure,
        allowEmpty: allowEmpty,
        emptyLabel: emptyLabel,
        keyboardType: keyboardType,
        maxLength: maxLength,
        singleLine: true,
        validator: validator,
      ),
    );
    if (value == null) return;
    unawaited(_backup.saveConfig(apply(value)));
  }
}

enum _RestoreChoice { data, dataAndSettings }

String? _urlValidator(String? value) {
  final text = value?.trim() ?? '';
  if (text.isEmpty) return null;
  final scheme = Uri.tryParse(text)?.scheme;
  if (scheme != 'http' && scheme != 'https') {
    return '以 http:// 或 https:// 开头';
  }
  return null;
}

String? _repoValidator(String? value) {
  final text = value?.trim() ?? '';
  if (text.isEmpty) return null;
  final parts = text.split('/');
  if (parts.length != 2 || parts.any((part) => part.trim().isEmpty)) {
    return '格式为 owner/name';
  }
  return null;
}
