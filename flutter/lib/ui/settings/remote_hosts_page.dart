import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import '../../models/remote.dart';
import '../dialog_motion.dart';
import '../glyphs.dart';
import '../palette.dart';
import 'settings_controller.dart';
import 'settings_dialogs.dart';
import 'settings_widgets.dart';
import 'remote_host_page.dart';

/// "远程主机": the paired daemons, their live phase and the default host.
class RemoteHostsPage extends StatelessWidget {
  const RemoteHostsPage({super.key, required this.controller});

  final SettingsController controller;

  Future<void> _add(BuildContext context) async {
    final host = await BaseNavigator.push<RemoteHost>(
      context,
      RemoteHostPairPage(controller: controller),
    );
    if (host == null || !context.mounted) return;
    await BaseNavigator.push<void>(
      context,
      RemoteHostDetailPage(controller: controller, hostId: host.id),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '远程主机',
      listenable: controller,
      builder: (context) => [
        generateSection(
          title: '已配对',
          footer: '主机运行 claudroid-bridge，Claude Code 与登录态都在主机上；'
              '手机只负责界面与确认。',
          items: [
            if (controller.hosts.hosts.isEmpty)
              const SettingsNote(text: '还没有远程主机。先在主机上启动守护进程并生成配对码。')
            else
              for (final host in controller.hosts.hosts)
                SettingsEntryRow(
                  key: ValueKey('remote-host-${host.id}'),
                  glyph: AppGlyphs.plug,
                  title: host.displayName,
                  value: _hostValue(controller.hosts, host),
                  tone: host.online ? SettingsTone.sage : SettingsTone.clay,
                  pageBuilder: (_) => RemoteHostDetailPage(
                    controller: controller,
                    hostId: host.id,
                  ),
                ),
            SettingsActionRow(
              label: '添加远程主机',
              glyph: AppGlyphs.plus,
              onPressed: () => _add(context),
            ),
          ],
        ),
        const SettingsNote(
          text: '主机 token 由 Android Keystore 加密保存在本机，不会进入备份、日志或界面。'
              '远程会话不使用手机上的 API Key、Base URL 或代理设置。',
        ),
      ],
    );
  }

  String _hostValue(RemoteHostsState hosts, RemoteHost host) {
    final isDefault = hosts.defaultHostId == host.id;
    final phase = host.phaseLabel;
    return isDefault ? '默认 · $phase' : phase;
  }
}

/// Pairing form. The daemon prints an 8-character one-time code; the same
/// address can be paired again and updates the existing entry.
class RemoteHostPairPage extends StatefulWidget {
  const RemoteHostPairPage({super.key, required this.controller});

  final SettingsController controller;

  @override
  State<RemoteHostPairPage> createState() => _RemoteHostPairPageState();
}

class _RemoteHostPairPageState extends State<RemoteHostPairPage> {
  String _baseUrl = '';
  String _code = '';
  String _name = '';
  bool _busy = false;

  Future<void> _edit({
    required String title,
    required String current,
    required String hint,
    required ValueChanged<String> apply,
    TextInputType? keyboardType,
    int maxLength = 200,
    FormFieldValidator<String>? validator,
    bool allowEmpty = false,
    String? emptyLabel,
  }) async {
    final value = await showClaudroidDialog<String>(
      context: context,
      child: SettingsTextPrompt(
        title: title,
        initial: current,
        hint: hint,
        keyboardType: keyboardType,
        maxLength: maxLength,
        singleLine: true,
        allowEmpty: allowEmpty,
        emptyLabel: emptyLabel,
        validator: validator,
      ),
    );
    if (value == null) return;
    apply(value.trim());
  }

  Future<void> _pair() async {
    if (_busy) return;
    final baseUrl = _baseUrl.trim();
    final code = _code.trim();
    if (baseUrl.isEmpty) {
      widget.controller.onToast('先填写主机地址', isError: true);
      return;
    }
    if (code.length != 8) {
      widget.controller.onToast('配对码是 8 位字符', isError: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final result = await widget.controller.bridge.pairHost(
        baseUrl: baseUrl,
        code: code,
        name: _name.trim(),
      );
      await widget.controller.refreshHosts();
      if (!mounted) return;
      widget.controller.onToast('已连接「${result.host.displayName}」');
      Navigator.of(context).pop(result.host);
    } catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      widget.controller.onToast('配对失败：$error', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScaffold(
      title: '添加远程主机',
      listenable: controllerListenable,
      builder: (context) => [
        generateSection(
          title: '连接信息',
          footer: '在主机上执行 claudroid-bridge setup，再按提示生成配对码；'
              '经 Cloudflare Tunnel 暴露时，地址填隧道公网 URL。',
          items: [
            SettingsValueRow(
              label: '主机地址',
              value: _baseUrl.isEmpty ? '未填写' : _baseUrl,
              onPressed: () => _edit(
                title: '主机地址',
                current: _baseUrl,
                hint: 'https://claude.example.com 或 http://192.168.1.5:8787',
                keyboardType: TextInputType.url,
                apply: (value) => setState(() => _baseUrl = value),
                validator: (value) {
                  final text = value?.trim() ?? '';
                  if (text.isEmpty) return '地址不能为空';
                  final scheme = Uri.tryParse(text)?.scheme;
                  if (scheme != 'http' && scheme != 'https') {
                    return '以 http:// 或 https:// 开头';
                  }
                  return null;
                },
              ),
            ),
            SettingsValueRow(
              label: '配对码',
              value: _code.isEmpty ? '未填写' : '已填写（${_code.length}/8）',
              onPressed: () => _edit(
                title: '配对码',
                current: _code,
                hint: '主机上显示的 8 位字符',
                maxLength: 8,
                apply: (value) => setState(() => _code = value.toUpperCase()),
                validator: (value) =>
                    (value?.trim().length ?? 0) == 8 ? null : '请输入 8 位配对码',
              ),
            ),
            SettingsValueRow(
              label: '显示名称',
              value: _name.isEmpty ? '使用主机名' : _name,
              onPressed: () => _edit(
                title: '显示名称',
                current: _name,
                hint: '例如「开发机」',
                maxLength: 40,
                allowEmpty: true,
                emptyLabel: '使用主机名',
                apply: (value) => setState(() => _name = value),
              ),
            ),
            SettingsActionRow(
              label: _busy ? '正在配对…' : '开始配对',
              glyph: AppGlyphs.plug,
              busy: _busy,
              onPressed: _busy ? null : _pair,
            ),
          ],
        ),
        const SettingsNote(
          text: '配对码 10 分钟有效且只能使用一次。成功后手机会保存一个设备令牌，'
              '可在主机上随时吊销。',
        ),
      ],
    );
  }

  Listenable get controllerListenable => widget.controller;
}
