import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/remote.dart';
import '../dialog_motion.dart';
import '../glyphs.dart';
import 'remote_files_page.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_labels.dart';
import 'settings_widgets.dart';

/// One paired host: connection health, tunnel controls, credentials and the
/// destructive actions. The list itself lives on [RemoteHostsPage].
class RemoteHostDetailPage extends StatefulWidget {
  const RemoteHostDetailPage({
    super.key,
    required this.controller,
    required this.hostId,
  });

  final SettingsController controller;
  final String hostId;

  @override
  State<RemoteHostDetailPage> createState() => _RemoteHostDetailPageState();
}

class _RemoteHostDetailPageState extends State<RemoteHostDetailPage> {
  String? _busyAction;

  SettingsController get _controller => widget.controller;
  RemoteHost? get _host => _controller.hosts.byId(widget.hostId);

  bool _busy(String action) => _busyAction == action;

  Future<void> _run(
    String action,
    Future<String?> Function() body,
  ) async {
    if (_busyAction != null) return;
    setState(() => _busyAction = action);
    try {
      final message = await body();
      await _controller.refreshHosts();
      if (!mounted) return;
      if (message != null && message.isNotEmpty) {
        _controller.onToast(message);
      }
    } catch (error) {
      if (!mounted) return;
      _controller.onToast('$error', isError: true);
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  Future<void> _test(RemoteHost host) => _run('test', () async {
        final result = await _controller.bridge.testHost(host.id);
        final version = result.health.daemonVersion.isEmpty
            ? ''
            : ' · daemon ${result.health.daemonVersion}';
        final claude = result.health.claudeVersion.isEmpty
            ? ''
            : ' · Claude ${result.health.claudeVersion}';
        return '连接成功：${result.health.hostName}$version$claude';
      });

  Future<void> _tunnel(RemoteHost host, String action) =>
      _run('tunnel', () async {
        await _controller.bridge.tunnelHost(host.id, action: action);
        return switch (action) {
          'start' => '正在开启隧道',
          'stop' => '隧道已停止',
          'restart' => '正在重启隧道',
          _ => '隧道状态已刷新',
        };
      });

  Future<void> _copyTunnelUrl(RemoteHost host) async {
    final url = host.tunnel.url ?? '';
    if (url.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: url));
    if (mounted) _controller.onToast('已复制隧道地址');
  }

  Future<void> _openTunnelUrl(RemoteHost host) async {
    final url = host.tunnel.url ?? '';
    if (url.isEmpty) return;
    try {
      await _controller.bridge.openUrl(url);
    } catch (error) {
      if (mounted) _controller.onToast('$error', isError: true);
    }
  }

  Future<void> _edit(
    String title,
    String current, {
    required Future<void> Function(String value) apply,
    String hint = '',
    bool obscure = false,
    bool allowEmpty = false,
    String? emptyLabel,
    TextInputType? keyboardType,
    int maxLength = 200,
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
    await apply(value.trim());
  }

  Future<void> _save(RemoteHost host, Map<String, String> patch) =>
      _run('save', () async {
        await _controller.bridge.updateHost(
          host.id,
          name: patch['name'],
          baseUrl: patch['baseUrl'],
          accessClientId: patch['accessClientId'],
          accessSecret: patch['accessSecret'],
          permissionMode: patch['permissionMode'],
        );
        return '已保存';
      });

  Future<void> _setDefault(RemoteHost host) {
    final isDefault = _controller.hosts.defaultHostId == host.id;
    return _run('default', () async {
      await _controller.bridge.setDefaultHost(isDefault ? null : host.id);
      return isDefault
          ? '已切回本机：会话与设置恢复为本机'
          : '已切换到「${host.displayName}」：会话与设置跟随这台主机';
    });
  }

  Future<void> _remove(RemoteHost host) async {
    final confirmed = await showClaudroidDialog<bool>(
      context: context,
      child: AlertDialog(
        title: '删除远程主机？',
        destructive: true,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
        child: Text(
          '会删除本机保存的「${host.displayName}」设备令牌，并停止正在进行的远程回合。'
          '主机上的 Claude 配置与文件不受影响。',
        ),
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busyAction = 'remove');
    try {
      await _controller.bridge.removeHost(host.id);
      await _controller.refreshHosts();
      if (!mounted) return;
      _controller.onToast('已删除「${host.displayName}」');
      Navigator.of(context).pop();
    } catch (error) {
      if (!mounted) return;
      setState(() => _busyAction = null);
      _controller.onToast('$error', isError: true);
    }
  }

  void _openFiles(RemoteHost host) {
    BaseNavigator.push<void>(
      context,
      RemoteFilesPage(
        bridge: _controller.bridge,
        host: host,
        onToast: _controller.onToast,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final host = _host;
    return SettingsScaffold(
      title: host?.displayName ?? '远程主机',
      listenable: _controller,
      builder: (context) => host == null
          ? [const SettingsNote(text: '这台主机已被删除。', isError: true)]
          : [
              _statusSection(context, host),
              _executionSection(context, host),
              _tunnelSection(context, host),
              _accessSection(context, host),
              _maintenanceSection(context, host),
            ],
    );
  }

  Widget _statusSection(BuildContext context, RemoteHost host) {
    final defaultHost = _controller.hosts.defaultHostId == host.id;
    return generateSection(
      title: '连接',
      footer: (host.error ?? '').isEmpty ? null : host.error,
      items: [
        SettingsInfoRow(label: '状态', value: host.phaseLabel),
        SettingsInfoRow(
          label: '主机',
          value: host.hostName.isEmpty ? host.displayName : host.hostName,
        ),
        if (host.platform.isNotEmpty)
          SettingsInfoRow(
            label: '系统',
            value: host.platform,
          ),
        if (host.protocol > 0)
          SettingsInfoRow(label: '协议版本', value: 'v${host.protocol}'),
        SettingsInfoRow(label: '地址', value: host.baseUrl),
        if (host.lastSeenAt > 0)
          SettingsInfoRow(
            label: '最后在线',
            value: remoteTimeLabel(host.lastSeenAt),
          ),
        SettingsActionRow(
          label: _busy('test') ? '正在测试…' : '测试连接',
          glyph: AppGlyphs.refresh,
          busy: _busy('test'),
          onPressed: _busyAction == null ? () => _test(host) : null,
        ),
        SettingsActionRow(
          label: defaultHost ? '切回本机' : '设为当前主机',
          glyph: defaultHost ? Glyphs.check : AppGlyphs.sparkle,
          busy: _busy('default'),
          onPressed: _busyAction == null ? () => _setDefault(host) : null,
        ),
      ],
    );
  }

  Widget _executionSection(BuildContext context, RemoteHost host) {
    final bypassAllowed = host.allowBypassPermissions;
    final bypassSelected = host.permissionMode == 'bypassPermissions';
    return generateSection(
      title: '执行',
      footer: '权限模式只影响这台主机的远程回合，下次回合生效。'
          '远程会话仍使用主机自己的 Claude 登录、API Key 和模型配置；'
          '手机不发送任何供应商凭据。',
      items: [
        SettingsPickerRow<String>(
          label: '权限模式',
          value: enumLabel(remotePermissionLabels, host.permissionMode),
          options: [
            for (final entry in remotePermissionLabels.entries)
              PopupMenuItem(
                label: entry.value,
                subtitle: entry.key == 'bypassPermissions' && !bypassAllowed
                    ? '需要在主机上开启 allowBypassPermissions'
                    : null,
                checked: host.permissionMode == entry.key,
                onPressed: entry.key == 'bypassPermissions' && !bypassAllowed
                    ? null
                    : () => _save(host, {'permissionMode': entry.key}),
              ),
          ],
        ),
        if (bypassSelected && !bypassAllowed)
          const SettingsNote(
            text: '主机未允许完全放行。请改选其他模式，或在主机的 claudroid-bridge '
                '配置里开启 allowBypassPermissions。',
            isError: true,
          ),
        SettingsCopyRow(
          label: '默认工作目录',
          value: host.defaultCwd.isEmpty ? '主机未报告' : host.defaultCwd,
          onPressed: () {
            if (host.defaultCwd.isEmpty) return;
            Clipboard.setData(ClipboardData(text: host.defaultCwd));
            _controller.onToast('已复制目录');
          },
        ),
        if (host.workspaceRoots.isNotEmpty)
          SettingsCopyRow(
            label: '允许的目录',
            value: host.workspaceRoots.join('\n'),
            onPressed: () {
              Clipboard.setData(
                ClipboardData(text: host.workspaceRoots.join('\n')),
              );
              _controller.onToast('已复制目录列表');
            },
          ),
        SettingsActionRow(
          label: '浏览主机文件',
          glyph: AppGlyphs.folder,
          onPressed: _busyAction == null ? () => _openFiles(host) : null,
        ),
      ],
    );
  }

  Widget _tunnelSection(BuildContext context, RemoteHost host) {
    final tunnel = host.tunnel;
    final url = tunnel.url ?? '';
    return generateSection(
      title: '隧道',
      footer: tunnel.mode == 'external'
          ? '隧道由主机上的部署服务（systemd）维护，升级守护进程不会改变公网地址。'
          : '临时隧道的公网地址每次重启都会变化；长期使用请在主机上配置固定隧道。',
      items: [
        SettingsInfoRow(label: '状态', value: tunnel.stateLabel),
        SettingsInfoRow(label: '模式', value: tunnel.modeLabel),
        if (url.isNotEmpty)
          SettingsCopyRow(
            label: '公网地址',
            value: url,
            onPressed: () => _copyTunnelUrl(host),
          ),
        if ((tunnel.error ?? '').isNotEmpty)
          SettingsNote(text: tunnel.error!, isError: true),
        if (tunnel.mode == 'external')
          const SettingsNote(
            text: '该隧道的启停由主机上的部署服务负责，App 内不提供开关。',
          ),
        if (url.isNotEmpty)
          SettingsActionRow(
            label: '打开公网地址',
            glyph: AppGlyphs.globe,
            onPressed: () => _openTunnelUrl(host),
          ),
        if (tunnel.mode != 'external') ...[
          if (tunnel.starting)
            SettingsActionRow(
              label: '正在启动隧道…',
              glyph: AppGlyphs.globe,
              busy: true,
              onPressed: null,
            )
          else if (tunnel.up)
            SettingsActionRow(
              label: _busy('tunnel') ? '正在重启…' : '重启隧道',
              glyph: AppGlyphs.refresh,
              busy: _busy('tunnel'),
              onPressed: _busyAction == null
                  ? () => _tunnel(host, 'restart')
                  : null,
            )
          else
            SettingsActionRow(
              label: _busy('tunnel') ? '正在开启…' : '开启隧道',
              glyph: AppGlyphs.globe,
              busy: _busy('tunnel'),
              onPressed: _busyAction == null
                  ? () => _tunnel(host, 'start')
                  : null,
            ),
          if (tunnel.up)
            SettingsActionRow(
              label: '停止隧道',
              glyph: Glyphs.close,
              onPressed: _busyAction == null
                  ? () => _tunnel(host, 'stop')
                  : null,
            ),
        ],
      ],
    );
  }

  Widget _accessSection(BuildContext context, RemoteHost host) {
    return generateSection(
      title: '访问保护',
      footer: '只有隧道前面加了 Cloudflare Access 时才需要填写；两项要一起配置。',
      items: [
        SettingsValueRow(
          label: 'Access Client ID',
          value: host.hasAccessToken ? '已配置' : '未配置',
          onPressed: _busyAction != null
              ? () {}
              : () => _edit(
                    'Access Client ID',
                    '',
                    hint: '留空并保存可清除',
                    allowEmpty: true,
                    emptyLabel: '清除',
                    apply: (value) => _save(host, {'accessClientId': value}),
                  ),
        ),
        SettingsValueRow(
          label: 'Access Client Secret',
          value: host.hasAccessToken ? '已配置' : '未配置',
          onPressed: _busyAction != null
              ? () {}
              : () => _edit(
                    'Access Client Secret',
                    '',
                    hint: '留空并保存可清除',
                    obscure: true,
                    allowEmpty: true,
                    emptyLabel: '清除',
                    maxLength: 4096,
                    apply: (value) => _save(host, {'accessSecret': value}),
                  ),
        ),
      ],
    );
  }

  Widget _maintenanceSection(BuildContext context, RemoteHost host) {
    return generateSection(
      title: '维护',
      items: [
        SettingsValueRow(
          label: '显示名称',
          value: host.displayName,
          onPressed: _busyAction != null
              ? () {}
              : () => _edit(
                    '显示名称',
                    host.name,
                    hint: '留空则使用主机名',
                    allowEmpty: true,
                    emptyLabel: '使用主机名',
                    maxLength: 40,
                    apply: (value) => _save(host, {'name': value}),
                  ),
        ),
        SettingsValueRow(
          label: '主机地址',
          value: host.baseUrl,
          onPressed: _busyAction != null
              ? () {}
              : () => _edit(
                    '主机地址',
                    host.baseUrl,
                    hint: 'https://claude.example.com',
                    keyboardType: TextInputType.url,
                    validator: (value) {
                      final text = value?.trim() ?? '';
                      if (text.isEmpty) return '地址不能为空';
                      final scheme = Uri.tryParse(text)?.scheme;
                      if (scheme != 'http' && scheme != 'https') {
                        return '以 http:// 或 https:// 开头';
                      }
                      return null;
                    },
                    apply: (value) => _save(host, {'baseUrl': value}),
                  ),
        ),
        SettingsActionRow(
          label: _busy('remove') ? '正在删除…' : '删除这台主机',
          glyph: AppGlyphs.trash,
          busy: _busy('remove'),
          onPressed: _busyAction == null ? () => _remove(host) : null,
        ),
      ],
    );
  }
}

String remoteTimeLabel(int millis) {
  if (millis <= 0) return '未知';
  final time = DateTime.fromMillisecondsSinceEpoch(millis);
  final now = DateTime.now();
  final diff = now.difference(time);
  if (diff.inSeconds < 60) return '刚刚';
  if (diff.inMinutes < 60) return '${diff.inMinutes} 分钟前';
  if (diff.inHours < 24) return '${diff.inHours} 小时前';
  return '${time.month} 月 ${time.day} 日';
}
